cmake_minimum_required(VERSION 3.22)
project(awaki_native C CXX)

# 16 KB page-size support: NDK r27 links 4 KB-aligned LOAD segments by default,
# which Android flags as "LOAD segment not aligned" on 16 KB devices. Set once
# here so every shared library built below carries it, llama.cpp's included.
set(CMAKE_SHARED_LINKER_FLAGS
  "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
)

add_library(termux SHARED termux.c)
target_link_libraries(termux log)

# ---- Local GGUF inference engine ----------------------------------------------
#
# llama.cpp is pinned as a submodule at third_party/llama.cpp and compiled with
# this app's JNI sources into the libraries libawaki-llm.so loads at runtime.
#
# The engine is arm64-only. Building the same tree for armeabi-v7a buys nothing:
# there is no 32-bit variant set to choose from, and a device that cannot run a
# model gets a clear "not on this ABI" answer from Kotlin instead of a library
# that would SIGILL on it.
if (NOT ANDROID OR NOT CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
  message(STATUS "awaki-llm: not built for '${CMAKE_ANDROID_ARCH_ABI}' - on-device models are arm64-only")
  return()
endif ()

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
if (NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
  message(FATAL_ERROR
    "llama.cpp is missing from ${LLAMA_DIR}. Run 'git submodule update --init --recursive' "
    "before building - local AI inference cannot compile without it.")
endif ()

# Shared, with the CPU backend loaded as a dynamic library, because that is the
# only way a phone gets the right kernels for its own cores.
#
# A single static build has to pick one ARM feature set at compile time. Picked
# conservatively it answers a modern SoC with baseline NEON kernels - measured on
# this engine, several times slower on the prefill that dominates an agent turn.
# Picked aggressively it answers an older phone with SIGILL. GGML_CPU_ALL_VARIANTS
# builds one CPU backend per feature set (armv8.0 baseline through armv9.2) and
# GGML_BACKEND_DL makes them modules, so ggml scores them against the running CPU
# and takes the best one the device actually has. The baseline variant stays in
# the set, so an exotic core still runs.
#
# The cost is a handful of .so files in place of one, which is the trade the size
# budget was written for: the Python runtime this replaces was 60 MB in the APK and
# 177 MB unpacked, and it could only ever use baseline kernels.
set(BUILD_SHARED_LIBS          ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL            ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS      ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE                OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP                OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE                OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS           OFF CACHE BOOL "" FORCE)
# Tiled SGEMM kernels: the prefill path an agent turn is dominated by. The 32-bit
# build is the one that cannot compile them, and there is no 32-bit build here.
set(GGML_LLAMAFILE             ON  CACHE BOOL "" FORCE)
# KleidiAI needs its own measurement: it is a second set of matmul kernels, and
# whether it earns its bytes on the phones this ships on is not known yet.
set(GGML_CPU_KLEIDIAI          OFF CACHE BOOL "" FORCE)

# Only ggml + llama + the chat-template layer are built. tools/, examples/, tests/
# and the llama-server binary stay off: the OpenAI-compatible endpoint the app
# exposes for local models is implemented in Kotlin.
set(LLAMA_BUILD_COMMON         ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP            OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI             OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL                 OFF CACHE BOOL "" FORCE)
# common/ spawns helper processes with fork/exec, which Android's app sandbox has
# no business doing; nothing in the chat-template path uses it.
set(LLAMA_SUBPROCESS           OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" llama_build)

# common/ is the one part of llama.cpp that must not be reimplemented. Every GGUF
# file publishes its own chat template, and a custom model from an arbitrary URL
# carries whichever dialect its author chose. Applying those with the engine's own
# Jinja renderer and its generated answer parser is the difference between
# supporting one model and supporting the format.
add_library(awaki-llm SHARED
  llm/llama_jni.cpp
  llm/chat_jni.cpp
)
target_compile_features(awaki-llm PRIVATE cxx_std_17)
# Nothing but the exported JNI functions is part of this library's interface, and a
# native symbol that leaks is a symbol another app's library could bind to.
target_compile_options(awaki-llm PRIVATE -fvisibility=hidden)
target_include_directories(awaki-llm PRIVATE
  "${LLAMA_DIR}/include"
  "${LLAMA_DIR}/ggml/include"
  "${LLAMA_DIR}/common"
)
target_link_libraries(awaki-llm PRIVATE llama llama-common ggml-base log android)
