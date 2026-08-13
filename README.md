# Rokid Arcsoft Converter

A small Android application for recovering stabilization of Rokid AI Glasses
videos when the Rokid mobile app fails to apply it automatically.

The app lets the user:

1. Select an MP4 video.
2. Select the matching Rokid gyro TXT file, or let the app find a TXT file with
   the same base name in the same folder.
3. Start conversion and watch native progress updates.
4. Open the converted video or open the `Movies/Rokid Arcsoft` folder.

The converter does not use Gyroflow. It calls the native Rokid media-processing
pipeline, which in turn calls Arcsoft Video Stabilizer. Processing is local and
does not require a network connection.

## Important Licensing Note

This repository contains the Android application code and the JNI bridge only.
It does **not** contain Rokid or Arcsoft native libraries, APKs, or other
proprietary assets.

The native libraries are proprietary third-party software. You must obtain
them from a Rokid APK that you are legally entitled to use, and you must check
the applicable Rokid and Arcsoft licenses before redistributing the libraries,
an APK containing them, or a public build service.

Do not commit `.so` files, extracted APKs, or a built APK to a public Git
repository. The included `.gitignore` protects the usual locations, but verify
the files before publishing.

This project is not affiliated with or endorsed by Rokid or Arcsoft.

## Requirements

- Android ARM64 device, API 29 or newer
- A device with a compatible hardware video encoder
- macOS or Linux for building
- Android SDK platform 36
- Android Build Tools 36.1.0
- Java with Java 8 source compatibility

The native pipeline was verified on a Google Pixel 9. Other Android devices
may have different codec support and are not guaranteed to work.

## Providing the Native Libraries

The build expects all ARM64 native libraries in:

```text
native/arm64-v8a/
```

You need the complete set of `.so` files, not only the three Arcsoft-related
files, because `libmedia_process.so` has dependencies on other Rokid and
third-party libraries.

### From APK files

If you have legally obtained the Rokid APK set, usually consisting of
`base.apk` and `split_config.arm64_v8a.apk`, extract every ARM64 library:

```bash
mkdir -p native/arm64-v8a
unzip -o -j /path/to/base.apk 'lib/arm64-v8a/*.so' -d native/arm64-v8a
unzip -o -j /path/to/split_config.arm64_v8a.apk 'lib/arm64-v8a/*.so' -d native/arm64-v8a
```

The directory should contain at least these files:

```text
libmedia_process.so
libarcsoft_videostabilizer.so
libnighthawk.arcsoft.so
libmpbase.so
libc++_shared.so
```

It will normally contain additional `.so` dependencies. Keep all of them in
the directory.

### Using another directory

The native library directory can be kept outside the repository:

```bash
ROKID_NATIVE_DIR=/path/to/rokid-native-arm64 sh build.sh
```

This is the recommended approach for a public checkout because proprietary
files never need to be copied into the repository at all.

## Building

Set `ANDROID_HOME` if the SDK is not in the default location, then run:

```bash
sh build.sh
```

The script creates:

```text
Rokid-Arcsoft-Converter.apk
```

The APK is debug-signed for local installation. For public distribution, use
your own release keystore and follow Android's signing requirements.

## Installing

With a connected Android device:

```bash
adb install -r Rokid-Arcsoft-Converter.apk
```

If Android blocks installation from a file manager, enable installation from
that source in Android settings or install with `adb`.

## Output

Completed videos are written to:

```text
Movies/Rokid Arcsoft/
```

The input MP4 and TXT file are copied into the app's private working directory
while processing. They are not uploaded anywhere.

## Project Layout

```text
AndroidManifest.xml
build.sh
README.md
res/                         Android resources
src/com/roman/rokidarcsoft/  Application UI and file handling
src/com/rokid/media/process/  JNI-compatible Rokid MediaManager bridge
native/arm64-v8a/             User-provided proprietary libraries, ignored
```
