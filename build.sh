#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build"
CLASSES="$BUILD/classes"
rm -rf "$BUILD"
mkdir -p "$CLASSES"
javac --release 17 -d "$CLASSES" "$ROOT/src/cupthenthecnt/CupTheCnt.java"
jar --create --file "$BUILD/CupTheCnt.jar" --main-class cupthenthecnt.CupTheCnt -C "$CLASSES" .
echo "Built $BUILD/CupTheCnt.jar"
