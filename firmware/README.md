# Pico GPS firmware

The Pico 2 W receives NMEA from a Waveshare L76K over UART and sends fixes to up to two bonded BLE centrals. The radio uses the [Pico GPS BLE v1 protocol](../protocol/README.md).

## Build for Pico 2 W

Use Pico SDK 2.3.1 with its submodules, an Arm `arm-none-eabi` C/C++ toolchain with newlib, CMake, Ninja, and Python 3. SDK setup and the first build can download dependencies. An existing picotool 2.3.1 CMake package can be selected with `-Dpicotool_DIR=/path/to/lib/cmake/picotool`.

With the SDK checked out as `pico-sdk` next to `firmware`, run from the project root. Set `PICO_SDK_PATH` or pass `-DPICO_SDK_PATH=/path/to/pico-sdk` for another SDK directory.

```sh
cmake -S firmware -B /tmp/pico-gps-firmware -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/pico-gps-firmware
```

To install, plug the Pico 2 W into USB while holding BOOTSEL, then copy `pico_gps.uf2` from the build directory to the mounted drive. [Raspberry Pi C/C++ SDK documentation](https://www.raspberrypi.com/documentation/microcontrollers/c_sdk.html)

## Wire the GPS

Use the labels on the cabled Waveshare L76K connector. Connect VCC to Pico `3V3(OUT)` (pin 36), GND to Pico GND (for example, pin 38), and GPS TX to Pico GP1/UART0 RX (pin 2). Leave GPS RX and PPS disconnected. The firmware expects the factory settings of 9600 baud, 8N1 and 1 Hz fixes. Check the vendor pinout for a header-mounted board. [Cabled L76K module](https://www.waveshare.com/wiki/L76K_GPS_Module) · [Header-mounted L76K variant](https://www.waveshare.com/wiki/Pico-GPS-L76K)

## Pair each phone

1. Plug the running Pico into a computer with a USB data cable. Open its USB CDC serial port in a terminal, such as `/dev/ttyACM0` on Linux. The terminal can use 115200 baud because USB CDC carries the console independently of the GPS UART.
2. Type `pair` and press Enter. Keep the terminal open. The Pico accepts one new bond within the next 60 seconds.
3. Select **Pico GPS** in the Android app. Enter the six-digit code displayed on the USB terminal in Android's pairing prompt.
4. Wait for the terminal to report that the phone bonded. Repeat from step 2 for the second phone.

The Pico stores bonds in flash and accepts them on later encrypted reconnects. It serves metadata and notifications only after authenticated LE Secure Connections encryption, and each connection reads the metadata and explicitly enables the notification descriptor. A slow central keeps at most one pending fix and can detect skipped epochs from the sequence. On sequence rollover, each central reconnects and reads the new session ID.

## Replay the parser tests

Run from the project root using a host C++17 compiler.

```sh
c++ -std=c++17 -Wall -Wextra -Ifirmware firmware/tests/nmea_protocol_test.cpp \
  firmware/nmea.cpp firmware/fix.cpp -o /tmp/pico-gps-nmea-tests
/tmp/pico-gps-nmea-tests protocol/v1-vectors.tsv
```

The suite exercises checksums, long and corrupt inputs, multiple talker IDs, UTC midnight rollover, absent or mismatched GGA, loss of fix, UART data loss recovery, and the shared wire vectors. The parser waits up to 350 ms for GGA following RMC and matches against GGA received up to 500 ms before RMC. If GPS data stops, the firmware emits a no-fix sample within about 1.25 seconds and repeats it once per second. UART capture, pairing, flash-backed reconnects, and simultaneous BLE streaming require a physical Pico 2 W and phones for verification.
