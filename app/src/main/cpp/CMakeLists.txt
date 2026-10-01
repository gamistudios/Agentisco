cmake_minimum_required(VERSION 3.22)
project(agentisco_native C CXX)

add_library(termux SHARED termux.c)
target_link_libraries(termux log)

# 16 KB page-size support: NDK r27 links 4 KB-aligned LOAD segments by default,
# which Android flags as "LOAD segment not aligned" on 16 KB devices.
target_link_options(termux PRIVATE
  "-Wl,-z,max-page-size=16384"
  "-Wl,-z,common-page-size=16384"
)

# ---- Local GGUF inference engine -----------------------------------------------
#
# llama.cpp is pinned as a submodule and compiled as static libraries that are
# linked into a single JNI shared library. Static (BUILD_SHARED_LIBS=OFF) is
# deliberate: one libagentisco-llm.so carries the whole engine, so the APK gains
# one file per ABI instead of four, and the STL is embedded rather than shipped
# as a separate libc++_shared.so.
#
# Only ggml + llama + the chat-template layer are built. tools/, examples/,
# tests/ and the llama-server binary stay off — the OpenAI-compatible endpoint
# Agentisco exposes for local models is implemented in Kotlin.
#
# common/ is the one exception, and only because it is the thing that must not be
# reimplemented: it holds llama.cpp's Jinja renderer and its per-architecture
# tool-call parsers. Every GGUF file publishes its own chat template, and a custom
# model from an arbitrary URL carries whichever dialect its author chose. Applying
# those with the engine's own code is the difference between supporting one model
# and supporting the format.

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
if (NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
  message(FATAL_ERROR
    "llama.cpp is missing from ${LLAMA_DIR}. Run 'git submodule update --init --recursive' "
    "before building — local AI inference cannot compile without it.")
endif ()

set(BUILD_SHARED_LIBS          OFF CACHE BOOL "" FORCE)
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
set(GGML_OPENMP                OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE                OFF CACHE BOOL "" FORCE)
# Cross-compiling must never probe the *build host* for CPU features; NDK clang
# enables ARM extensions from the ABI's feature flags instead.
set(GGML_NATIVE                OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS           OFF CACHE BOOL "" FORCE)
# llamafile's tiled SGEMM loads fp16 blocks with vld1q_f16/vld1_f16, which exist
# only on ARMv8.3+; on armeabi-v7a the file guards them behind __ARM_NEON alone
# and fails to compile (upstream FIXME at sgemm.cpp:309). ggml then uses its
# portable gemm path, which is what llamafile itself falls back to.
if (CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64)")
  set(GGML_LLAMAFILE           ON  CACHE BOOL "" FORCE)
else ()
  set(GGML_LLAMAFILE           OFF CACHE BOOL "" FORCE)
endif ()

add_subdirectory("${LLAMA_DIR}" llama_build)

add_library(agentisco-llm SHARED
  llama_jni.cpp
  chat_jni.cpp
)
target_compile_features(agentisco-llm PRIVATE cxx_std_17)
target_include_directories(agentisco-llm PRIVATE
  "${LLAMA_DIR}/include"
  "${LLAMA_DIR}/ggml/include"
)
target_link_libraries(agentisco-llm PRIVATE llama llama-common ggml log android)
target_link_options(agentisco-llm PRIVATE
  "-Wl,-z,max-page-size=16384"
  "-Wl,-z,common-page-size=16384"
)
