#!/bin/sh
# 用 NDK 重编 Termux terminal-emulator 的 JNI（上游 termux.c，未修改），16KB 页对齐。
set -eu
cd "$(dirname "$0")"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$HOME"/Library/Android/sdk/ndk/* | tail -1)}"
BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
"$BIN/aarch64-linux-android26-clang" -shared -fPIC -O2 -std=c11 -Wall \
  -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=4096 -Wl,--build-id=none \
  termux.c -o ../../app/src/main/jniLibs/arm64-v8a/libtermux.so
"$BIN/llvm-strip" --strip-unneeded ../../app/src/main/jniLibs/arm64-v8a/libtermux.so
echo built libtermux.so
