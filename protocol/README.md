# Pico GPS BLE protocol v1

The Pico advertises the service UUID and the device name `Pico GPS`. It shares position only with authorized, encrypted clients. Each client discovers the service, reads metadata, and subscribes to notifications from the fix characteristic. The client must read metadata again after every connection.

- Service `c7a10001-8a6b-4c0f-9e12-5197b5e62d11`
- Metadata `c7a10002-8a6b-4c0f-9e12-5197b5e62d11`, read
- Fix `c7a10003-8a6b-4c0f-9e12-5197b5e62d11`, notify

## Metadata

Read 17 bytes. Byte 0 is the protocol version, currently `1`. Bytes 1–8 contain a stable, nonzero unsigned receiver ID. Bytes 9–16 contain a nonzero unsigned session ID generated on boot and renewed before sequence wrap. Both IDs are little-endian. Clients can store them as fixed-width, 16-character lowercase hexadecimal identifiers.

## Fix notification

Each notification contains 20 bytes. All multibyte fields are little-endian.

| Offset | Type | Field |
| --- | --- | --- |
| 0 | uint16 | Epoch sequence, beginning at zero in each session |
| 2 | uint32 | UTC Unix seconds |
| 6 | int32 | Latitude in degrees × 10⁷ |
| 10 | int32 | Longitude in degrees × 10⁷ |
| 14 | uint16 | Ground speed in centimetres per second |
| 16 | int16 | Altitude in whole metres above mean sea level |
| 18 | uint8 | Validity flags |
| 19 | uint8 | Satellites used, with `255` for unknown |

Flag bits 0–3 indicate valid position, time, speed, and altitude respectively. Both coordinate fields use the position flag. Set every invalid field to zero and leave bits 4–7 clear. A cleared position flag indicates no fix. Coordinates of zero with the position flag set are valid. The sequence advances for every published epoch, including no-fix epochs. To renew a session, the Pico disconnects its clients before sending a notification from the new session. Clients reconnect and read the new metadata before receiving samples.

Use `(receiver ID, session ID, sequence)` as source identity. Keep reception time separate from GPS time. A sequence gap, disconnect, session change, or no-fix sample marks a break in the recorded route.

## Test vectors

`v1-vectors.tsv` contains a header and five columns. The first two are the name and hex payload, followed by the expected receiver ID or sequence, session ID or a comma-separated list of decoded values, and a note. A single dash represents an absent field. Decoded fix values are UTC seconds, latitude E7, longitude E7, speed cm/s, altitude m MSL, and satellites. The coordinates are synthetic.
