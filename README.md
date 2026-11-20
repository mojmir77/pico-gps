# Pico GPS

Pico GPS reads positions from a Waveshare L76K wired to a Raspberry Pi Pico 2 W and sends them to an Android phone over Bluetooth Low Energy. The phone records trips locally and can display them on an imported offline map.

The [prototype release](https://github.com/mojmir77/pico-gps/releases/tag/v0.1.0) provides the Android APK, flashable UF2, and Moravskoslezský kraj offline map pack.

## Build

The Pico firmware uses C++ and Raspberry Pi Pico SDK 2.3.1, pinned as a submodule. After cloning the repository, initialize the SDK and its nested dependencies.

```sh
git submodule update --init --recursive
```

Install CMake, Ninja, Python 3, and the Arm bare-metal C and C++ toolchain with its Newlib support. The SDK can fetch Picotool when it creates the UF2.

```sh
cmake -S firmware -B firmware/build -G Ninja \
  -DPICO_BOARD=pico2_w -DPICO_SDK_PATH="$PWD/pico-sdk"
cmake --build firmware/build
```

The Android app uses Kotlin, builds with Android SDK 36, and runs on Android 8 or later. From the `android/` directory, build a debug APK and run the host-side unit tests with the Gradle wrapper.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The resulting app is `android/app/build/outputs/apk/debug/app-debug.apk`. With an authorized test phone connected, `./gradlew :app:connectedDebugAndroidTest` runs the in-memory Room tests for trip recovery, duplicates, and route gaps.

The [BLE contract and packet fixtures](protocol/README.md) are shared across both builds.

## First trip

1. With the Pico powered off, connect the cabled L76K's labelled 3.3 V and ground pins to the Pico and its TX to GP1 (UART0 RX). Check the receiver's labels and board variant before wiring.
2. Hold BOOTSEL while plugging the Pico into USB. Copy `firmware/build/pico_gps.uf2` to the mounted boot drive. After the board resets, open its USB serial port and send `pair` followed by a newline. Keep the serial port open while connecting from the Android app. When the Pico prints a six-digit passkey, enter it in Android's pairing prompt.
3. Install the APK, grant Nearby devices permission on Android 12 or later, scan for **Pico GPS**, and connect. Earlier supported Android versions use location permission for BLE scanning.
4. Copy `maps/moravskoslezsky-kraj.zip` to the phone and select it in the app's map import screen. Keep the ZIP intact. The map pack is optional for recording.
5. With the receiver outdoors, wait for a fix and start a trip. After stopping, open the saved route in History. To verify background recording on your phone, take a short trip with the screen locked and check that the saved route has points from that interval.

## Offline map

The [Moravskoslezský kraj starter pack](https://github.com/mojmir77/pico-gps/releases/download/v0.1.0/moravskoslezsky-kraj.zip) contains a Protomaps v4 cutout from the November 18, 2026 snapshot, buffered about 2 km around the OpenStreetMap region boundary. It contains vector tiles through zoom 15, a local style, Noto Sans glyphs, sprites, and their licenses. The archive manifest lists coverage and SHA-256 checksums for import. At the edge, rectangular tiles can extend beyond the buffered boundary.

Map data is © OpenStreetMap contributors, available under the Open Database License. [OpenStreetMap copyright](https://www.openstreetmap.org/copyright) · [Protomaps basemap downloads](https://docs.protomaps.com/basemaps/downloads)
