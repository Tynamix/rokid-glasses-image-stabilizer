# Agent Instructions

## Project Overview

This repository contains a small Android application that converts Rokid AI
Glasses videos using the native Rokid media-processing pipeline and Arcsoft
Video Stabilizer.

The project is intentionally not a Gradle project. It uses `build.sh` and the
Android SDK command-line tools directly.

## Repository Structure

- `src/com/roman/rokidarcsoft/`: application UI and file handling
- `src/com/rokid/media/process/`: JNI-compatible media manager bridge
- `res/`: Android resources
- `native/arm64-v8a/`: locally provided proprietary ARM64 native libraries
- `build.sh`: complete local build, packaging, signing, and verification
- `README.md`: user-facing setup and licensing documentation

## Build and Verification

Run the official build from the repository root:

```sh
sh build.sh
```

The build requires:

- Android SDK platform 36
- Android Build Tools 36.1.0
- Java with Java 8 source compatibility
- ARM64 `.so` libraries in `native/arm64-v8a/`, or a directory supplied via
  `ROKID_NATIVE_DIR`
- A local debug keystore at `$HOME/.android/debug.keystore`

The script compiles the Java sources, packages resources and native libraries,
creates `Rokid-Arcsoft-Converter.apk`, signs it with the debug keystore, and
runs `apksigner verify`.

Do not use Gradle commands unless the project is explicitly migrated to Gradle.

## Development Guidelines

- Keep changes small and compatible with the existing command-line build.
- Preserve the current minimum Android API level and ARM64 target unless the
  task explicitly requires changing them.
- Keep Java source compatibility at version 8.
- Do not commit generated build output, APKs, signing files, extracted APKs, or
  proprietary native libraries.
- Do not add or redistribute Rokid or Arcsoft binaries without confirming the
  applicable licenses.
- Follow the existing package layout and avoid introducing dependencies that
  are not available through the Android SDK or the checked-in sources.

## Testing

At minimum, run `sh build.sh` after source or resource changes. A successful
build includes APK signing and signature verification.

For device testing, use an ARM64 Android device running API 29 or newer:

```sh
adb install -r Rokid-Arcsoft-Converter.apk
```

The native video pipeline may require a compatible hardware video encoder and
is not guaranteed to work on every Android device.
