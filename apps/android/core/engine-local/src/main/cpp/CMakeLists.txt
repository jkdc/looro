# Mural local conversation engine: llama.cpp (LLM) + whisper.cpp (STT) via a
# minimal JNI surface. Port of the proven local-speaking-poc native layer.
cmake_minimum_required(VERSION 3.22)
project(mural_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/llama.cpp)
add_subdirectory(third_party/whisper.cpp)

find_library(log-lib log)

add_library(mural_llama SHARED llama_jni.cpp whisper_jni.cpp)

target_include_directories(mural_llama
    PRIVATE
    third_party/llama.cpp/include
    third_party/llama.cpp/common
    third_party/whisper.cpp/include
)

target_link_libraries(mural_llama
    PRIVATE
    llama
    llama-common
    llama-common-base
    whisper
    ${log-lib}
)
