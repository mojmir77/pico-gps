#include "fix.h"

#include <cstddef>
#include <type_traits>

namespace gps {
namespace {

template<typename T>
void write_le(uint8_t *destination, T value) {
    auto bits = static_cast<std::make_unsigned_t<T>>(value);
    for (size_t i = 0; i < sizeof(T); ++i) {
        destination[i] = static_cast<uint8_t>(bits >> (8 * i));
    }
}

}  // namespace

MetadataPacket encode_metadata(uint64_t receiver_id, uint64_t session_id) {
    MetadataPacket packet{};
    packet[0] = 1;
    write_le(packet.data() + 1, receiver_id);
    write_le(packet.data() + 9, session_id);
    return packet;
}

FixPacket encode_fix(uint16_t sequence, const Fix &fix) {
    FixPacket packet{};
    write_le(packet.data(), sequence);
    if (fix.position) {
        write_le(packet.data() + 6, fix.position->latitude_e7);
        write_le(packet.data() + 10, fix.position->longitude_e7);
        packet[18] |= 0x01;
    }
    if (fix.utc_seconds) {
        write_le(packet.data() + 2, *fix.utc_seconds);
        packet[18] |= 0x02;
    }
    if (fix.speed_cm_s) {
        write_le(packet.data() + 14, *fix.speed_cm_s);
        packet[18] |= 0x04;
    }
    if (fix.altitude_m) {
        write_le(packet.data() + 16, *fix.altitude_m);
        packet[18] |= 0x08;
    }
    packet[19] = fix.satellites.value_or(0xff);
    return packet;
}

}  // namespace gps
