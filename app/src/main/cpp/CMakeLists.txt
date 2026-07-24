cmake_minimum_required(VERSION 3.22.1)
project("whisper-jni")

# --- whisper.cpp ---
set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

if(NOT EXISTS ${WHISPER_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "whisper.cpp 未找到。请在 ${CMAKE_CURRENT_SOURCE_DIR} 目录下运行：\n"
        "  git clone https://github.com/ggerganov/whisper.cpp.git\n"
        "或从 https://github.com/ggerganov/whisper.cpp 下载 ZIP 解压到此目录。"
    )
endif()

# Build whisper as a static library
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_NO_AVX ON CACHE BOOL "" FORCE)
set(WHISPER_NO_AVX2 ON CACHE BOOL "" FORCE)
set(WHISPER_NO_FMA ON CACHE BOOL "" FORCE)
set(WHISPER_NO_F16C ON CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_CPP_DIR} whisper_build)

# --- JNI shared library ---
add_library(whisper-jni SHARED
    whisper_jni.cpp
)

target_include_directories(whisper-jni PRIVATE
    ${WHISPER_CPP_DIR}/include
)

target_link_libraries(whisper-jni
    whisper
    android
    log
)
