#!/usr/bin/env bash
# Скачивает бинарник cloudflared (linux/arm64 — он же подходит для Android arm64,
# это статический Go-бинарник) и кладёт его как native lib, чтобы встроить в APK.
set -euo pipefail

VERSION="${1:-latest}"
DEST_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
DEST="$DEST_DIR/libcloudflared.so"

mkdir -p "$DEST_DIR"

if [ "$VERSION" = "latest" ]; then
  URL="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
else
  URL="https://github.com/cloudflare/cloudflared/releases/download/${VERSION}/cloudflared-linux-arm64"
fi

echo "Качаю: $URL"
curl -fL --retry 4 --retry-delay 2 -o "$DEST" "$URL"
chmod +x "$DEST"

echo "Готово: $DEST"
ls -lh "$DEST"
echo
echo "Теперь собери APK:  ./gradlew assembleDebug"
