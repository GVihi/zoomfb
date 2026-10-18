#!/usr/bin/env bash
# Builds zoomfb.jar. Needs a JDK 24 or newer (uses the built-in java.lang.classfile API).
set -euo pipefail
cd "$(dirname "$0")"

rm -rf build
mkdir -p build/classes
javac --release 25 -d build/classes $(find src -name '*.java')
jar --create --file zoomfb.jar --manifest manifest.mf -C build/classes .
echo "built $(pwd)/zoomfb.jar"
