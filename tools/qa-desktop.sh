#!/usr/bin/env bash
# Visual QA of the desktop at several window sizes (offscreen, isolated profile).
# Usage: tools/qa-desktop.sh <out-dir> [WxH ...]   (default: 1440x900 1100x700 800x600)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?out dir}"; shift || true
SIZES=("${@:-1440x900 1100x700 800x600}")
[ "${#SIZES[@]}" -eq 1 ] && read -r -a SIZES <<< "${SIZES[0]}"
mkdir -p "$OUT"
for size in "${SIZES[@]}"; do
  for page in editor home journal library tasks whiteboard palette capture; do
    P=$(mktemp -d)
    args=(--profile qa --size "$size" --showcase --screenshot "$OUT/${page}_${size}.png")
    case "$page" in
      editor) ;;
      palette|capture) args+=(--page home --overlay "$page") ;;
      *) args+=(--page "$page") ;;
    esac
    YAOP_VAULT_PASSPHRASE=qa XDG_CONFIG_HOME="$P/c" XDG_DATA_HOME="$P/d" QT_QPA_PLATFORM=offscreen \
      timeout 60 "$ROOT/desktop/build/yaop" "${args[@]}" >/dev/null 2>&1 || echo "FAIL $page $size"
    rm -rf "$P"
  done
done
ls "$OUT"
