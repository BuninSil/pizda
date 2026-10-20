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

# --- DNS patch ------------------------------------------------------------
# Android has no /etc/resolv.conf, so Go's resolver hits [::1]:53 and every
# lookup fails — including the SRV records cloudflared needs to find the edge
# (that failure makes the quick tunnel exit and churn its URL). Inject a
# pure-Go resolver that talks to public DNS directly; this works through a VPN
# tunnel too, so the tunnel stays up.
cat > cmd/cloudflared/zz_android_resolver.go <<'PATCH'
//go:build android

package main

import (
	"context"
	"net"
	"time"
)

func init() {
	servers := []string{"1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53"}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{Timeout: 8 * time.Second}
			var lastErr error
			for _, s := range servers {
				if c, err := d.DialContext(ctx, network, s); err == nil {
					return c, nil
				} else {
					lastErr = err
				}
			}
			return nil, lastErr
		},
	}
}
PATCH

echo "Building (android/arm64, CGO via $CC)..."
CGO_ENABLED=1 GOOS=android GOARCH=arm64 GOTOOLCHAIN=auto GOFLAGS=-mod=mod CC="$CC" \
  go build -ldflags "-X main.Version=$VERSION -s -w" -o "$OUT" ./cmd/cloudflared

echo "Done: $OUT"
ls -lh "$OUT"
file "$OUT" || true
