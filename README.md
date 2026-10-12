# Unprocess Camera

An open source Android camera for capturing sensor RAW images and making JPEGs from those RAW captures. It keeps the shooting screen clear, offers direct control when your phone supports it, and saves photos where the phone's gallery can find them.

**[Download the latest APK](../../releases/latest)** · [How to install](#install) · [Build from source](#build-from-source)

## What it does

- Choose **RAW** to save a DNG, or **JPG** to convert the captured RAW data to a JPEG. The JPG path uses a basic Bayer demosaic and limits output to 8 megapixels to keep memory use manageable. Its colors may differ from the phone's stock camera.
- Set flash to off, auto, or on when supported by the camera.
- Tap the viewfinder to focus and meter at that spot. Adjust exposure with the translucent slider that appears beside the focus indicator; it fades away after a few seconds.
- Open **Pro** for manual ISO, shutter speed, focus, white balance presets, and a rule of thirds grid. Controls depend on the Camera2 capabilities reported by the phone. Manual exposure disables flash and EV compensation. The viewfinder approximates long shutter settings; the saved shot uses the selected shutter time.
- Shoot without an automatic post capture preview. A thumbnail beside the shutter opens recent photos.
- Browse photos saved by this app in its in-app gallery, or open one in the phone's image viewer. Images are saved to `DCIM/Unprocess` and indexed by Android MediaStore. Newly captured DNGs include a small embedded thumbnail where supported.

## Install

1. Open the [latest release](../../releases/latest) and download its `.apk` asset on your Android phone.
2. Open the downloaded APK and allow installation from your browser or file manager if Android asks.
3. Grant the camera permission when the app starts. On older Android versions, grant storage access when requested.

The app requires **Android 5.0 (API 21) or newer** and a rear camera that exposes RAW capture through Camera2. Available manual controls vary by device. An existing installation can be updated only with an APK signed by the same key; uninstalling first may be necessary when switching from another build.

## Build from source

Install **JDK 17** and **Android SDK 34**, then run:

```sh
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat :app:assembleDebug`. The APK is written to `app/build/outputs/apk/debug/`. For a non-debuggable APK, use `:app:assembleRelease`; the current Gradle configuration signs that build with the local Android debug keystore. Keep the signing key safe if you intend to publish compatible updates. Never commit a keystore.

## Project layout

- `app/` — Android UI, Camera2 capture, RAW conversion, and gallery.
- `utils/` — shared camera and image utilities inherited from the Android Camera2Basic sample.

## Origins and license

This project continues [Reiland Eubank's unprocess](https://github.com/reilandeubank/unprocess), which was built on Google's [Camera2Basic sample](https://github.com/android/camera-samples/tree/main/Camera2Basic). The original Git history and copyright notices are preserved. See [NOTICE](NOTICE) for attribution.

Licensed under the [Apache License, Version 2.0](LICENSE). This is an independent continuation and is not an official release from either original project.
