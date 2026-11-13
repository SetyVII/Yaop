#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -x build/yaop ]; then
    cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
    cmake --build build
fi

exec ./build/yaop "$@"
