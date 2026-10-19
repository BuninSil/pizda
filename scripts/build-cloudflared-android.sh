#!/usr/bin/env bash
# Builds cloudflared for Android (arm64) with CGO so it resolves DNS through the
# Android system resolver (bionic). The upstream linux/arm64 binary does NOT work
# on Android: there's no /etc/resolv.conf, so Go's pure resolver tries [::1]:53
# and fails ("dial tcp: lookup api.trycloudflare.com on [::1]:53").
#
# Output: app/src/main/jniLibs/arm64-v8a/libcloudflared.so  (committed to the repo)
#
# Requirements:
#   - Go (the build auto-fetches the toolchain pinned in cloudflared's go.mod)
#   - Android NDK (set ANDROID_NDK_HOME, or pass the path as $2)
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/android-ndk-r27c ./scripts/build-cloudflared-android.sh [version] [ndk_path]
set -euo pipefail

VERSION="${1:-2026.10.1}"
NDK="${2:-${ANDROID_NDK_HOME:-}}"
API=24

if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "error: set ANDROID_NDK_HOME to your Android NDK (e.g. android-ndk-r27c)" >&2
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libcloudflared.so"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${API}-clang"

if [ ! -x "$CC" ]; then
  echo "error: clang not found at $CC (check NDK path / host OS)" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
echo "Cloning cloudflared $VERSION..."
git clone --depth 1 --branch "$VERSION" https://github.com/cloudflare/cloudflared.git "$WORK/src"

cd "$WORK/src"
echo "Building (android/arm64, CGO via $CC)..."
CGO_ENABLED=1 GOOS=android GOARCH=arm64 GOTOOLCHAIN=auto GOFLAGS=-mod=mod CC="$CC" \
  go build -ldflags "-X main.Version=$VERSION -s -w" -o "$OUT" ./cmd/cloudflared

echo "Done: $OUT"
ls -lh "$OUT"
file "$OUT" || true
