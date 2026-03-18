#!/bin/bash

# ==========================================================
# 1. SETUP YOUR PATHS
# ==========================================================
# Replace this with the actual path to your Android NDK
NDK_PATH="C:\Users\DEVENDRA\AppData\Local\Android\Sdk\ndk\27.0.12077973"

# Common settings
HOST_OS="darwin-x86_64" # Use "linux-x86_64" if on Linux, "windows-x86_64" on Windows
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/$HOST_OS"
API_MIN=21

# Output directory for the compiled .so files
OUTPUT_DIR="$(pwd)/android_build/arm64-v8a"

# ==========================================================
# 2. CONFIGURE TARGET (arm64-v8a)
# Note: ARM64 is the most important target for HEVC performance
# ==========================================================
ARCH="aarch64"
CPU="armv8-a"
CC="$TOOLCHAIN/bin/aarch64-linux-android$API_MIN-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android$API_MIN-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
NM="$TOOLCHAIN/bin/llvm-nm"
STRIP="$TOOLCHAIN/bin/llvm-strip"

# ==========================================================
# 3. BUILD COMMAND
# ==========================================================
echo "Configuring FFmpeg for Android $ARCH..."

./configure \
    --prefix=$OUTPUT_DIR \
    --enable-cross-compile \
    --target-os=android \
    --arch=$ARCH \
    --cpu=$CPU \
    --cc=$CC \
    --cxx=$CXX \
    --ar=$AR \
    --nm=$NM \
    --strip=$STRIP \
    --sysroot=$TOOLCHAIN/sysroot \
    --extra-cflags="-O3 -fPIC -DANDROID -Wfatal-errors -Wno-deprecated" \
    --extra-ldflags="-lc -lm -ldl -llog" \
    \
    --enable-shared \
    --disable-static \
    \
    --disable-doc \
    --disable-programs \
    --disable-avdevice \
    --disable-postproc \
    --disable-avfilter \
    --disable-network \
    \
    `# =====================================================` \
    `# CRITICAL: Enable Assembly & NEON for HEVC Performance` \
    `# =====================================================` \
    --enable-asm \
    --enable-neon \
    --enable-pthreads \
    \
    `# Disable everything first to keep the APK size tiny` \
    --disable-everything \
    \
    `# Enable exact Decoders needed (Add others if necessary)` \
    --enable-decoder=hevc \
    --enable-decoder=h264 \
    --enable-decoder=vp9 \
    --enable-decoder=aac \
    --enable-decoder=mp3 \
    --enable-decoder=flac \
    \
    `# Enable exact Demuxers (MKV/WebM, MP4, HEVC raw)` \
    --enable-demuxer=matroska \
    --enable-demuxer=mov \
    --enable-demuxer=mp4 \
    --enable-demuxer=hevc \
    \
    `# Enable exact Parsers` \
    --enable-parser=hevc \
    --enable-parser=h264 \
    --enable-parser=aac \
    \
    `# Enable File Protocol to read local storage` \
    --enable-protocol=file

# ==========================================================
# 4. COMPILE AND INSTALL
# ==========================================================
echo "Compiling FFmpeg (this might take a few minutes)..."
make clean
make -j$(getconf _NPROCESSORS_ONLN) # Uses all CPU cores
make install

echo "Build completed! Check the '$OUTPUT_DIR' folder."