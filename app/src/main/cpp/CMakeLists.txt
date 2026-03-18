cmake_minimum_required(VERSION 3.22.1)
project(devson_player CXX)

find_package(oboe REQUIRED CONFIG)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# cmake_policy(SET CMP0063 NEW)
# Suppress the CMake visibility warnings for external libraries
# -----------------------------------------------------------------------------
# Fetch and build libebml v1.4.5 and libmatroska v1.7.1
# -----------------------------------------------------------------------------
include(FetchContent)

# 1. Fetch libebml
FetchContent_Declare(
    libebml
    GIT_REPOSITORY https://github.com/Matroska-Org/libebml.git
    GIT_TAG release-1.4.5
)
FetchContent_MakeAvailable(libebml)

# --- CRITICAL FIX 1: Wrap the binary dir in BUILD_INTERFACE ---
# This prevents the "prefixed in the build directory" CMake Export error
target_include_directories(ebml PUBLIC "$<BUILD_INTERFACE:${libebml_BINARY_DIR}>")

# --- Mock find_package(EBML) for libmatroska ---
file(WRITE "${CMAKE_BINARY_DIR}/FindEBML.cmake" "
set(EBML_FOUND TRUE)
set(EBML_VERSION \"1.4.5\")
if(TARGET ebml AND NOT TARGET EBML::ebml)
    add_library(EBML::ebml ALIAS ebml)
endif()
")
list(APPEND CMAKE_MODULE_PATH "${CMAKE_BINARY_DIR}")
# --------------------------------------------------------

# 2. Fetch libmatroska (depends on libebml)
FetchContent_Declare(
    libmatroska
    GIT_REPOSITORY https://github.com/Matroska-Org/libmatroska.git
    GIT_TAG release-1.7.1
)
FetchContent_MakeAvailable(libmatroska)

# --- CRITICAL FIX 2: Same generator expression wrap for matroska ---
target_include_directories(matroska PUBLIC "$<BUILD_INTERFACE:${libmatroska_BINARY_DIR}>")
# -----------------------------------------------------------------------------

#  FFmpeg prebuilt .so files 
# ffmpeg-kit-full AAR .so files are extracted by Gradle.
if(DEFINED FFMPEG_JNI_DIR)
    set(FFMPEG_SO_DIR "${FFMPEG_JNI_DIR}/${ANDROID_ABI}")
elseif(NOT DEFINED FFMPEG_SO_DIR)
    set(FFMPEG_SO_DIR
        "${CMAKE_SOURCE_DIR}/../../../build/intermediates/stripped_native_libs/debug/out/lib/${ANDROID_ABI}"
    )
endif()

message(STATUS "FFmpeg .so dir: ${FFMPEG_SO_DIR}")

macro(add_ffmpeg_lib name)
    add_library(${name} SHARED IMPORTED)
    set_target_properties(${name} PROPERTIES
        IMPORTED_LOCATION "${FFMPEG_SO_DIR}/lib${name}.so"
    )
endmacro()

add_ffmpeg_lib(avcodec)
add_ffmpeg_lib(avformat)
add_ffmpeg_lib(avutil)
add_ffmpeg_lib(avfilter)
add_ffmpeg_lib(swscale)
add_ffmpeg_lib(swresample)

# FFmpeg C headers placed in app/src/main/cpp/include/
include_directories(${CMAKE_SOURCE_DIR}/include)

#  Our native player library 
add_library(
    devson_player
    SHARED
    ffmpeg_decoder.cpp
    video_renderer.cpp
    native_player.cpp
    audio_renderer.cpp
)

#  Android system libraries 
find_library(log-lib    log)
find_library(android-lib android)
find_library(gles2-lib  GLESv2)
find_library(egl-lib    EGL)
find_library(media-lib  mediandk)

target_link_libraries(
    devson_player
    PRIVATE
    avcodec
    avformat
    avutil
    avfilter
    swscale
    swresample
    ebml           # <--- Link libebml
    matroska       # <--- Link libmatroska
    ${log-lib}
    ${android-lib}
    ${gles2-lib}
    ${egl-lib}
    ${media-lib}
    oboe::oboe
)

target_compile_options(devson_player PRIVATE
    -Wall -Wextra -ffast-math -O2
)