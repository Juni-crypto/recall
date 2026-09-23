#!/usr/bin/env bash
# Builds llama.xcframework (iPhone + simulator, Metal) from the same llama.cpp the Android app uses.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LLAMA="$ROOT/app/src/main/cpp/llama.cpp"
OUT="$ROOT/ios/Frameworks"
if [[ -d "$OUT/llama.xcframework" && "${1:-}" != "--force" ]]; then
  echo "llama.xcframework already built (pass --force to rebuild)"; exit 0
fi
cd "$LLAMA"
./build-xcframework.sh ios-device ios-sim
mkdir -p "$OUT"
rm -rf "$OUT/llama.xcframework"
cp -R build-apple/llama.xcframework "$OUT/"
echo "Built $OUT/llama.xcframework"
