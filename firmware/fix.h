#pragma once

#include <array>
#include <cstdint>
#include <optional>

namespace gps {

struct Position {
    int32_t latitude_e7;
    int32_t longitude_e7;
};

struct Fix {
    std::optional<Position> position;
    std::optional<uint32_t> utc_seconds;
    std::optional<uint16_t> speed_cm_s;
    std::optional<int16_t> altitude_m;
    std::optional<uint8_t> satellites;
};

using MetadataPacket = std::array<uint8_t, 17>;
using FixPacket = std::array<uint8_t, 20>;

MetadataPacket encode_metadata(uint64_t receiver_id, uint64_t session_id);
FixPacket encode_fix(uint16_t sequence, const Fix &fix);

}  // namespace gps
