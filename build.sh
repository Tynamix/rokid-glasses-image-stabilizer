#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT="$SDK/build-tools/36.1.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
BUILD="$ROOT/build"
NATIVE_DIR=${ROKID_NATIVE_DIR:-$ROOT/native/arm64-v8a}

set -- "$NATIVE_DIR"/*.so
if [ ! -f "$1" ]; then
    echo "No ARM64 native libraries found in $NATIVE_DIR" >&2
    echo "Set ROKID_NATIVE_DIR or follow README.md to extract them." >&2
    exit 1
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/gen" "$BUILD/dex" "$BUILD/apk" "$BUILD/lib/arm64-v8a"
cp "$NATIVE_DIR/"*.so "$BUILD/lib/arm64-v8a/"

"$BT/aapt2" compile -o "$BUILD/resources.zip" --dir "$ROOT/res"
"$BT/aapt2" link --manifest "$ROOT/AndroidManifest.xml" \
  -I "$PLATFORM" -o "$BUILD/apk/resources.apk" "$BUILD/resources.zip" --java "$BUILD/gen"
javac --release 8 -classpath "$PLATFORM" -d "$BUILD/classes" \
  "$BUILD/gen/com/roman/rokidarcsoft/R.java" \
  "$ROOT/src/com/rokid/media/process/MediaManager.java" \
  "$ROOT/src/com/roman/rokidarcsoft/SynchronizationService.java" \
  "$ROOT/src/com/roman/rokidarcsoft/MainActivity.java"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .

"$BT/d8" --lib "$PLATFORM" --output "$BUILD/dex" "$BUILD/classes.jar"

cp "$BUILD/apk/resources.apk" "$BUILD/apk/unsigned.apk"
(cd "$BUILD" && zip -q -j "$BUILD/apk/unsigned.apk" "$BUILD/dex/classes.dex" && zip -q -r "$BUILD/apk/unsigned.apk" lib)
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
  --out "$ROOT/Rokid-Arcsoft-Converter.apk" "$BUILD/apk/unsigned.apk"
"$BT/apksigner" verify "$ROOT/Rokid-Arcsoft-Converter.apk"
echo "Created $ROOT/Rokid-Arcsoft-Converter.apk"
