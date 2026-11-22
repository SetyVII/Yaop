#!/usr/bin/env bash
# Runs every test suite against the shared fixtures in spec/fixtures.
# Usage: tools/check.sh [server|core|desktop|mobile|extension]...   (no arguments: all)
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
targets=("$@"); [ ${#targets[@]} -eq 0 ] && targets=(server core desktop mobile extension)
fail=0
run() { local name="$1"; shift; echo "── $name"; if "$@"; then echo "   ✓ $name"; else echo "   ✗ $name"; fail=1; fi; }

for t in "${targets[@]}"; do
  case "$t" in
    server)    run server    bash -c "cd '$ROOT/server' && go vet ./... && go test ./..." ;;
    core)      run core      bash -c "cd '$ROOT/pkmcore' && cmake -S . -B build -G Ninja >/dev/null && cmake --build build && QT_QPA_PLATFORM=offscreen ./build/pkmcore_tests" ;;
    desktop)   run desktop   bash -c "cd '$ROOT/desktop' && cmake -S . -B build -G Ninja >/dev/null && cmake --build build && QT_QPA_PLATFORM=offscreen ./build/yaop --selftest" ;;
    mobile)    run mobile    bash -c "cd '$ROOT/mobile' && ./gradlew testStressUnitTest --console=plain -q" ;;
    extension) run extension bash -c "node --test '$ROOT/extension/test.mjs'" ;;
    *) echo "unknown target: $t"; fail=1 ;;
  esac
done
[ $fail -eq 0 ] && echo "ALL OK" || echo "FAILURES"
exit $fail
