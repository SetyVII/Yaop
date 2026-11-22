#!/usr/bin/env bash
# Builds unpacked & zipped Chromium and Firefox packages for the YAOP browser extension.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
DIST="$DIR/dist"
rm -rf "$DIST"
mkdir -p "$DIST/chromium" "$DIST/firefox"

copy_common() {
  local target="$1"
  cp -r "$DIR/_locales" "$DIR/icons" "$DIR/src" "$target/"
}

copy_common "$DIST/chromium"
cp "$DIR/manifest.json" "$DIST/chromium/manifest.json"

copy_common "$DIST/firefox"
cp "$DIR/manifest.firefox.json" "$DIST/firefox/manifest.json"

if command -v zip >/dev/null 2>&1; then
  (cd "$DIST/chromium" && zip -qr "$DIST/yaop-extension-chromium.zip" .)
  (cd "$DIST/firefox" && zip -qr "$DIST/yaop-extension-firefox.xpi" .)
  echo "Built:"
  echo "  $DIST/yaop-extension-chromium.zip"
  echo "  $DIST/yaop-extension-firefox.xpi"
else
  echo "Built unpacked directories under $DIST/chromium and $DIST/firefox"
fi
