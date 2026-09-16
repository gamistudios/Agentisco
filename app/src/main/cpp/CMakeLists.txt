cmake_minimum_required(VERSION 3.22)
project(termux C)

add_library(termux SHARED termux.c)
target_link_libraries(termux log)

# 16 KB page-size support: NDK r27 links 4 KB-aligned LOAD segments by default,
# which Android flags as "LOAD segment not aligned" on 16 KB devices.
target_link_options(termux PRIVATE
  "-Wl,-z,max-page-size=16384"
  "-Wl,-z,common-page-size=16384"
)
