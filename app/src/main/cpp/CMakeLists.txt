cmake_minimum_required(VERSION 3.22.1)
project(devson_player CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

#  FFmpeg prebuilt .so files 
# ffmpeg-kit-full AAR .so files are extracted by Gradle. The variable
# FFMPEG_JNI_DIR is injected by build.gradle.kts via cmake arguments.
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
        ${log-lib}
        ${android-lib}
        ${gles2-lib}
        ${egl-lib}
        ${media-lib}
)

target_compile_options(devson_player PRIVATE
    -Wall -Wextra -ffast-math -O2
)
