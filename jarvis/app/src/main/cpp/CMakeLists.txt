cmake_minimum_required(VERSION 3.24)
project("jarvis" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is downloaded at build time (pinned release), nothing to copy by hand
include(FetchContent)
FetchContent_Declare(llama
        URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/v1.0.0.tar.gz
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# one CPU backend per ARM generation; the best one for the phone is picked at runtime
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(llama)

add_library(jarvis SHARED engine.cpp jni_bridge.cpp)
target_link_libraries(jarvis llama ggml android log)
