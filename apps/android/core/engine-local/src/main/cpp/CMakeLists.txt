# Palique local voice native layer: whisper.cpp (STT) via a minimal JNI surface.
# The on-device llama.cpp (LLM) engine was removed on 2026-09-17 (too slow for
# voice turns); the whisper STT leg remains because the remote conversation
# engines (deepseek/glm) still run speech-to-text on device.
cmake_minimum_required(VERSION 3.22)
project(app_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/whisper.cpp)

find_library(log-lib log)

add_library(app_whisper SHARED whisper_jni.cpp)

target_include_directories(app_whisper
    PRIVATE
    third_party/whisper.cpp/include
)

target_link_libraries(app_whisper
    PRIVATE
    whisper
    ${log-lib}
)
