#!/bin/sh
# 下载 Ubuntu Base（官方最小 rootfs）并放进 APK assets。
set -eu
VERSION="${UBUNTU_BASE_VERSION:-24.04.5}"
SHA256="${UBUNTU_BASE_SHA256:-a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2}"
NAME="ubuntu-base-$VERSION-base-arm64.tar.gz"
cd "$(dirname "$0")/.."
OUT=app/src/main/assets/rootfs.tar.gz.bin
CACHE=build/downloads
mkdir -p "$CACHE" app/src/main/assets
if [ ! -f "$CACHE/$NAME" ]; then
  for base in https://cdimage.ubuntu.com/ubuntu-base/releases \
              https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases; do
    curl -fL --retry 3 -o "$CACHE/$NAME.part" "$base/${VERSION%.*}/release/$NAME" && break
  done
  mv "$CACHE/$NAME.part" "$CACHE/$NAME"
fi
echo "$SHA256  $CACHE/$NAME" | shasum -a 256 -c -
cp "$CACHE/$NAME" "$OUT"
echo "$VERSION" > app/src/main/assets/rootfs.version
echo "rootfs -> $OUT"
