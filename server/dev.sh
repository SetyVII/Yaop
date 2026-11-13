#!/usr/bin/env bash
# Local test server. Usage: server/dev.sh [port]   (default 8765)
# Data in ~/.yaop-dev (delete that folder to start from scratch).
set -euo pipefail
cd "$(dirname "$0")"
PORT="${1:-8765}"
D="${YAOP_DEV_DATA:-$HOME/.yaop-dev}"
mkdir -p "$D"
[ -f "$D/bearer.token" ] || go run ./cmd/yaop-server -data-dir "$D" -init-secrets
echo "URL:   http://127.0.0.1:$PORT"
echo "TOKEN: $(cat "$D/bearer.token")"
echo "Android device: adb reverse tcp:$PORT tcp:$PORT  (and use the URL above)"
exec go run ./cmd/yaop-server -listen "127.0.0.1:$PORT" -data-dir "$D" -mdns=false
