# Offline GPS tracker with Raspberry Pi Pico 2 W

**First usable trip.** Power the Pico and GPS receiver outdoors, open the app and start recording. With Wi-Fi and mobile data off, the app shows the receiver's position on an installed map, saves the route while the screen is locked and reopens the trip with distance and speed statistics. A second authorized subscriber can independently watch or record the same stream.

## Design choices for the first prototype

- **Hardware and radio.** Use the external GPS as the position source, UART into the Pico and Bluetooth Low Energy (BLE) notifications out. One shared fix per second is the starting rate. Target two simultaneous subscribers and measure the limit on the actual board. Classic Bluetooth adds a second protocol to maintain with little benefit at this data rate.
- **App.** Build for Android phones using Kotlin, a user-started recording service, Room/SQLite and MapLibre Native. Verify behavior on at least two physical Android phones, including simultaneous recording with both displays off. Firmware, packet fixtures and the SQLite data model can be reused for other clients.
- **Recording ownership.** The first prototype records on each connected app. No internet is needed. If every client disconnects, the Pico continues acquiring fixes but a reconnecting app has a gap. If uninterrupted history without a phone is required, move a durable on-device log and a sequence-based backfill protocol into the first release. Size storage from sample size × fix rate × maximum unattended duration, with margin for indexes and power-loss recovery.
- **Map and receiver.** Use the Waveshare L76K and ship the first offline pack for Moravskoslezský kraj, Czech Republic. Clip an OSM extract to the regional boundary with a small buffer, retain Czech place names, and check Ostrava streets and rural trails at the supported zoom levels. Measure pack size before choosing the highest zoom. USB power is enough to prove the first link.

## Android implementation

Start scanning and connecting after the user selects the receiver. Request the Bluetooth permissions applicable to the OS version. Start a connected-device foreground service from the visible app when the user starts recording, display a persistent recording notification with a Stop action, and let that service own the BLE connection and writes. Test screen lock and process termination on the target handset. Android documents the permission split and supported background options. [Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) · [Background BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/background)

Load a local PMTiles archive through MapLibre Native using the device-file or asset source. Start with a map pack copied to the phone so even first use can work without internet. Render the live marker and recorded polyline as separate layers over the local basemap. [MapLibre local PMTiles example](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/)

## From the antenna to a saved trip

1. **Read the receiver.** Start with USB power and an outdoor view of the sky. For the cabled Waveshare L76K GPS Module, use the labelled VCC and GND pins, a 3.3 V supply, and start at 9600 baud, 8 data bits, no parity, 1 stop bit (8N1), with the default 1 Hz update rate. Choose Pico UART0 on GP1 (RX) and optionally GP0 (TX). Leave PPS disconnected. Check the connector labels because Waveshare uses nonstandard wire colours. If the board is the header-mounted Pico-GPS-L76K variant, use its own board pinout and supply instructions. [Cabled L76K guide](https://www.waveshare.com/wiki/L76K_GPS_Module) · [Header-mounted variant](https://www.waveshare.com/wiki/Pico-GPS-L76K) Join grounds and connect receiver TX to Pico UART RX. Add Pico TX to receiver RX only if configuration commands are needed. Check that the signal reaching the Pico is 3.3 V compatible. Save a short serial capture as a repeatable test input.
2. **Build one fix per receiver epoch.** Start with NMEA RMC for UTC date/time, position and ground speed, and GGA for fix quality, satellite count and altitude. Accept the module's talker IDs, validate checksums and combine only matching epochs. Missing altitude or speed stays unknown. Emit an explicit no-fix state during acquisition. Test corrupted sentences, midnight rollover and loss of antenna reception before adding Bluetooth.
3. **Publish over BLE.** Use the Pico C/C++ SDK with BTstack. The Pico advertises a custom GATT service UUID. Each app connects as a central, discovers the service and subscribes to fix notifications. Keep coordinates out of advertisements. Provide a read-only metadata characteristic with protocol version, stable receiver ID and a new session ID on every boot. Require an authorized encrypted connection before sharing location. Open pairing deliberately during setup.
4. **Record on receipt.** Each recording app owns its local trip database. Commit each accepted point before publishing it to the map and statistics views. Use a unique key of receiver ID, boot session and sequence number for the source sample. Keep trip membership separate. A retry can then deliver the same sample twice without adding distance twice.

The Pico 2 W supports the BLE peripheral role, and the SDK exposes BTstack for BLE. The simultaneous subscriber count is a hardware test target. [Pico 2 W datasheet](https://datasheets.raspberrypi.com/picow/pico-2-w-datasheet.pdf) · [Pico SDK networking libraries](https://www.raspberrypi.com/documentation/pico-sdk/networking.html)

## A small, testable radio protocol

Start at one fix per second. A proposed 20-byte, little-endian v1 notification fits the default BLE notification payload. Freeze field meanings in one shared specification and give firmware and app the same encoded sample to test.

- Sequence number (2 bytes), UTC Unix seconds (4), signed latitude and longitude in ten-millionths of a degree (4 each), ground speed in cm/s (2), signed whole-metre altitude above mean sea level (2), flags (1) and satellites used (1).
- Use validity bits for position, time, speed and altitude. Never encode “no fix” as a real point at zero latitude and longitude. Increment sequence on every published epoch, including no-fix. Renew the stream session before the sequence wraps and have clients re-read metadata on a session change.
- Track notification subscriptions and send readiness separately per connection. Keep advertising while there is a free connection slot. Bound each client's queue. A slow subscriber may miss epochs, but cannot stall UART parsing or the other clients. Surface missed sequences and reconnect with backoff.
- Distinguish a receiver reporting no satellite fix from a phone losing BLE. A stale marker shows the last valid point and its age. Start a new line segment after either kind of gap. Never draw a straight line or add distance across an outage.

## Trips, maps and statistics

**What the user sees.** Open the map, select the Pico and watch the fix age and satellite state. Start a trip explicitly. Show a live marker, breadcrumb trail and recording indicator. Panning releases follow mode, and a recenter button restores it. Stop saves a trip summary. History reopens every trip with its original segments and statistics. Map coordinates always come from the external receiver.

**Offline map pack.** Ship a small licensed starter region or provide a pack that can be copied over USB before departure. Include the tile archive, style, fonts, icons, attribution, coverage bounds and zoom range. Verify their versions and checksum before activating a pack. Display coverage and installed size before import. Outside coverage, continue recording and show the trail and coordinates with a clear map-coverage message. Do a clean-install test with an empty cache and networking disabled. Public OpenStreetMap raster servers prohibit offline prefetching. Use tiles built from an OSM extract or a provider that explicitly permits offline redistribution. [OSM tile policy](https://operations.osmfoundation.org/policies/tiles/)

**Local record.** Store trips, source samples, trip segments and reasons for gaps in SQLite. Keep GPS time separate from local receive time and monotonic elapsed time. Save enough quality information to recompute summaries when filters change. On restart, recover committed points and present an interrupted trip with an explicit resume or finish action.

**First statistics.** Show elapsed duration, valid recorded distance, current speed and average moving speed. Sum geodesic distances only between accepted adjacent points in one segment. Suppress stationary jitter and flag impossible jumps using limits calibrated to the activity. Calculate average moving speed as accepted moving distance divided by moving time, and display it as unavailable when moving time is zero. Use a stationary trace, a known route, a dropout and a replay of duplicate packets as reference cases.

## Optional server support later

Give each trip and sample a stable identity in v1. Keep storage behind a repository with a read path for completed trips. Later, an explicitly enabled uploader on the app can read committed data, persist an upload cursor or outbox, and send authenticated HTTPS batches when connectivity returns. The server deduplicates stable IDs and acknowledges batches before the cursor advances. A timeout, rejected credentials or disabled upload leaves the local trip intact. Day one needs local persistence and versioned records. Server hosting, accounts and retry jobs can wait.

## Build order and acceptance tests

### 1. Prove the receiver outdoors
Capture ten minutes of UART traffic and decode one timestamped point per epoch. Replay it on a development machine. Corrupt a checksum, remove GGA and inject a no-fix sentence. The decoder must reject corruption, keep altitude optional and stop publishing a live position during loss of fix.

### 2. Prove the radio with two subscribers
Stream the same trace to two authorized clients for 30 minutes at 1 Hz. Compare sequence numbers and coordinates. Disconnect the first client, reconnect it, then reboot the Pico. The other client must keep receiving throughout the first client's disconnect. Both must recognize the new session after reboot. Record any packet losses and the highest verified subscriber count.

### 3. Save a trip with the screen locked
Start a trip on both Android phones, lock both screens and carry them together for 30 minutes. Unlock, stop and reopen each trip. Compare their sequence coverage against the run with the screens on. Verify point counts, timestamps and segments. Repeat with Bluetooth toggled, app termination, denied permissions and low storage. Distinguish recording, interrupted and saved states. Simulate duplicate samples and verify unchanged distance.

### 4. Take a fully offline field trip
Disable Wi-Fi and cellular data, enable Bluetooth and start from a clean app installation with a local map pack. Acquire an outdoor fix, watch the marker move, pan, recenter, leave coverage and return. Stop and reopen the trip after restarting the device. Verify local labels, visible attribution, retained history and no required network access.

### 5. Calibrate and set release targets
Use a stationary outdoor test and a route of known length to measure false distance, distance error, fix latency, BLE gaps and battery runtime. Record receiver, antenna placement, client OS, map region and test conditions. Pick release tolerances from the intended activity. Fix the worst observed failure before expanding map coverage or adding sync.
