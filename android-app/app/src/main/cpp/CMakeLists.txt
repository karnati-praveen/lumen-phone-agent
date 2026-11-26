cmake_minimum_required(VERSION 3.22.1)
project("aegis_brain" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Static minimal llama.cpp build (no network FetchContent, no tools/server/examples)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

if(DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
elseif(DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "x86_64")
    set(GGML_SYSTEM_ARCH "x86" CACHE STRING "" FORCE)
    set(GGML_SSE42 ON CACHE BOOL "" FORCE)
    set(GGML_AVX ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)
    set(GGML_FMA ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
    set(GGML_BMI2 ON CACHE BOOL "" FORCE)
endif()

# Ensure ggml C/C++ kernels compile with -O3 even in Android Debug APK builds
add_compile_options(-O3)

set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp")
add_subdirectory("${LLAMA_SRC}" build-llama EXCLUDE_FROM_ALL)

add_library(aegis_brain SHARED
    brain_engine.cpp
    guard_2m.cpp
    aegis_jni.cpp
)

target_include_directories(aegis_brain PRIVATE
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/ggml/include"
    "${LLAMA_SRC}/tools/mtmd"
)

target_compile_options(aegis_brain PRIVATE -O2 -Wall -Wextra -Wno-deprecated-declarations -fvisibility=hidden)
find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(aegis_brain PRIVATE llama mtmd ${log-lib} ${android-lib})
