#pragma once

#include "fix.h"

#include <array>
#include <cstddef>
#include <string_view>

namespace gps {

class NmeaFixParser {
public:
    explicit NmeaFixParser(uint64_t now_ms = 0) : last_emission_ms_(now_ms) {}

    std::optional<Fix> ingest(char byte, uint64_t now_ms);
    std::optional<Fix> poll(uint64_t now_ms);
    void discard_partial_input();

private:
    struct Rmc {
        uint64_t time_of_day;
        uint64_t received_ms;
        Fix fix;
    };

    struct Gga {
        uint64_t time_of_day;
        uint64_t received_ms;
        bool has_fix;
        std::optional<int16_t> altitude_m;
        std::optional<uint8_t> satellites;
    };

    std::optional<Fix> parse_sentence(std::string_view line, uint64_t now_ms);
    bool gga_matches_rmc() const;
    Fix finish_rmc(uint64_t now_ms);

    std::array<char, 128> line_{};
    size_t line_size_ = 0;
    bool receiving_ = false;
    std::optional<Rmc> pending_rmc_;
    std::optional<Gga> pending_gga_;
    std::optional<uint64_t> last_time_of_day_;
    std::optional<uint32_t> last_utc_seconds_;
    uint64_t last_emission_ms_;
    bool last_emission_was_rmc_ = false;
};

}  // namespace gps
