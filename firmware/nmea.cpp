#include "nmea.h"

#include <limits>

namespace gps {
namespace {

constexpr uint64_t kGgaWaitMs = 350;
constexpr uint64_t kEpochMatchMs = 500;
constexpr uint64_t kHeartbeatMs = 1250;
constexpr uint64_t kHeartbeatRepeatMs = 1000;
constexpr int64_t kDecimalScale = 10'000'000;
constexpr uint32_t kSecondsPerDay = 86'400;

bool is_digit(char value) {
    return value >= '0' && value <= '9';
}

int hex_digit(char value) {
    if (is_digit(value)) return value - '0';
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    return -1;
}

std::optional<uint32_t> unsigned_decimal(std::string_view field) {
    if (field.empty() || field.size() > 6) return std::nullopt;
    uint32_t value = 0;
    for (const char digit : field) {
        if (!is_digit(digit)) return std::nullopt;
        value = value * 10 + static_cast<uint32_t>(digit - '0');
    }
    return value;
}

std::optional<int64_t> scaled_decimal(std::string_view field, bool signed_field = false) {
    const bool negative = !field.empty() && field.front() == '-';
    if (signed_field && !field.empty() && (negative || field.front() == '+')) {
        field.remove_prefix(1);
    }
    const auto point = field.find('.');
    const auto whole = unsigned_decimal(field.substr(0, point));
    if (!whole) return std::nullopt;
    int64_t value = *whole * kDecimalScale;
    if (point != field.npos) {
        const auto fraction = field.substr(point + 1);
        if (fraction.empty()) return std::nullopt;
        int64_t weight = kDecimalScale / 10;
        for (const char digit : fraction) {
            if (!is_digit(digit)) return std::nullopt;
            value += (digit - '0') * weight;
            weight /= 10;
        }
    }
    return negative ? -value : value;
}

std::optional<uint64_t> parse_time(std::string_view field) {
    if (field.size() < 6 || (field.size() > 6 && field[6] != '.')) return std::nullopt;
    const auto value = scaled_decimal(field);
    if (!value) return std::nullopt;
    const auto hhmmss = *value / kDecimalScale;
    const auto hours = hhmmss / 10'000;
    const auto minutes = (hhmmss / 100) % 100;
    const auto seconds = hhmmss % 100;
    if (hours >= 24 || minutes >= 60 || seconds >= 60) return std::nullopt;
    return (hours * 3600 + minutes * 60 + seconds) * kDecimalScale + (*value % kDecimalScale);
}

bool leap_year(uint32_t year) {
    return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
}

uint32_t days_in_month(uint32_t year, uint32_t month) {
    constexpr std::array<uint32_t, 12> days{31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    return days[month - 1] + (month == 2 && leap_year(year));
}

std::optional<uint32_t> parse_utc(std::string_view date, uint64_t time_of_day) {
    if (date.size() != 6) return std::nullopt;
    const auto value = unsigned_decimal(date);
    if (!value) return std::nullopt;
    const uint32_t day = *value / 10'000;
    const uint32_t month = (*value / 100) % 100;
    const uint32_t year_suffix = *value % 100;
    const uint32_t year = year_suffix >= 80 ? 1900 + year_suffix : 2000 + year_suffix;
    if (month == 0 || month > 12 || day == 0 || day > days_in_month(year, month)) {
        return std::nullopt;
    }
    uint32_t days = day - 1;
    for (uint32_t y = 1970; y < year; ++y) days += 365u + leap_year(y);
    for (uint32_t m = 1; m < month; ++m) days += days_in_month(year, m);
    return days * kSecondsPerDay + static_cast<uint32_t>(time_of_day / kDecimalScale);
}

std::optional<int32_t> coordinate(std::string_view field, std::string_view hemisphere,
                                  size_t degree_digits, uint32_t max_degrees,
                                  char positive, char negative) {
    if (field.size() < degree_digits + 2 || hemisphere.size() != 1 ||
        (hemisphere[0] != positive && hemisphere[0] != negative)) return std::nullopt;
    if (field.size() > degree_digits + 2 && field[degree_digits + 2] != '.') return std::nullopt;
    const auto degrees = unsigned_decimal(field.substr(0, degree_digits));
    const auto minutes = scaled_decimal(field.substr(degree_digits));
    if (!degrees || !minutes || *degrees > max_degrees || *minutes >= 60 * kDecimalScale ||
        (*degrees == max_degrees && *minutes != 0)) return std::nullopt;
    const auto e7 = static_cast<int32_t>(*degrees * kDecimalScale + (*minutes + 30) / 60);
    return hemisphere[0] == negative ? -e7 : e7;
}

std::optional<uint16_t> speed_cm_s(std::string_view field) {
    const auto knots = scaled_decimal(field);
    if (!knots) return std::nullopt;
    constexpr int64_t divisor = 36 * kDecimalScale;
    const auto value = (*knots * 1852 + divisor / 2) / divisor;
    if (value > std::numeric_limits<uint16_t>::max()) return std::nullopt;
    return static_cast<uint16_t>(value);
}

std::optional<int16_t> altitude_m(std::string_view field) {
    const auto metres = scaled_decimal(field, true);
    if (!metres) return std::nullopt;
    const auto sign = *metres < 0 ? -1 : 1;
    const auto value = (*metres + sign * (kDecimalScale / 2)) / kDecimalScale;
    if (value < std::numeric_limits<int16_t>::min() || value > std::numeric_limits<int16_t>::max()) {
        return std::nullopt;
    }
    return static_cast<int16_t>(value);
}

bool checksum_valid(std::string_view line) {
    if (line.size() < 9 || line[line.size() - 3] != '*') return false;
    const auto body = line.substr(0, line.size() - 3);
    uint8_t actual = 0;
    for (const char value : body) {
        if (value < ' ' || value > '~' || value == '*') return false;
        actual ^= static_cast<uint8_t>(value);
    }
    const auto high = hex_digit(line[line.size() - 2]);
    const auto low = hex_digit(line[line.size() - 1]);
    return high >= 0 && low >= 0 && actual == (high * 16 + low);
}

}  // namespace

std::optional<Fix> NmeaFixParser::ingest(char byte, uint64_t now_ms) {
    if (byte == '$') {
        line_size_ = 0;
        receiving_ = true;
        return std::nullopt;
    }
    if (!receiving_) return std::nullopt;
    if (byte == '\n' || byte == '\r') {
        receiving_ = false;
        return parse_sentence(std::string_view(line_.data(), line_size_), now_ms);
    }
    if (line_size_ == line_.size()) {
        receiving_ = false;
        return std::nullopt;
    }
    line_[line_size_++] = byte;
    return std::nullopt;
}

std::optional<Fix> NmeaFixParser::parse_sentence(std::string_view line, uint64_t now_ms) {
    if (line.size() < 9 || line[5] != ',') return std::nullopt;
    if (line[0] < 'A' || line[0] > 'Z' || line[1] < 'A' || line[1] > 'Z') return std::nullopt;
    const auto type = line.substr(2, 3);
    if ((type != "RMC" && type != "GGA") || !checksum_valid(line)) return std::nullopt;

    std::array<std::string_view, 20> fields{};
    size_t count = 0;
    auto rest = line.substr(6, line.size() - 9);
    for (;;) {
        if (count == fields.size()) return std::nullopt;
        const auto comma = rest.find(',');
        fields[count++] = rest.substr(0, comma);
        if (comma == rest.npos) break;
        rest.remove_prefix(comma + 1);
    }

    const auto time_of_day = parse_time(fields[0]);
    if (!time_of_day) return std::nullopt;

    if (type == "GGA") {
        if (count < 9) return std::nullopt;
        const auto quality = unsigned_decimal(fields[5]);
        if (!quality || *quality > 8) return std::nullopt;
        Gga gga{*time_of_day, now_ms, *quality >= 1 && *quality <= 5, {}, {}};
        const auto satellites = unsigned_decimal(fields[6]);
        if (satellites && *satellites < 0xff) gga.satellites = static_cast<uint8_t>(*satellites);
        if (gga.has_fix && fields[9] == "M") gga.altitude_m = altitude_m(fields[8]);
        pending_gga_ = gga;
        if (gga_matches_rmc()) return finish_rmc(now_ms);
        return std::nullopt;
    }

    if (count < 9 || (fields[1] != "A" && fields[1] != "V")) return std::nullopt;
    Fix fix;
    fix.utc_seconds = parse_utc(fields[8], *time_of_day);
    if (last_time_of_day_ == time_of_day && last_utc_seconds_ == fix.utc_seconds) return std::nullopt;
    const auto mode = fields[11];
    if (fields[1] == "A" && mode != "N" && mode != "E" && mode != "M" && mode != "S") {
        const auto latitude = coordinate(fields[2], fields[3], 2, 90, 'N', 'S');
        const auto longitude = coordinate(fields[4], fields[5], 3, 180, 'E', 'W');
        if (latitude && longitude) {
            fix.position = Position{*latitude, *longitude};
            fix.speed_cm_s = speed_cm_s(fields[6]);
        }
    }

    std::optional<Fix> prior_fix;
    if (pending_rmc_) {
        if (pending_rmc_->time_of_day == *time_of_day && pending_rmc_->fix.utc_seconds == fix.utc_seconds) {
            return std::nullopt;
        }
        prior_fix = finish_rmc(now_ms);
    }
    pending_rmc_ = Rmc{*time_of_day, now_ms, fix};
    if (!prior_fix && gga_matches_rmc()) return finish_rmc(now_ms);
    return prior_fix;
}

bool NmeaFixParser::gga_matches_rmc() const {
    if (!pending_rmc_ || !pending_gga_ || pending_rmc_->time_of_day != pending_gga_->time_of_day) {
        return false;
    }
    const auto rmc_ms = pending_rmc_->received_ms;
    const auto gga_ms = pending_gga_->received_ms;
    return (rmc_ms >= gga_ms ? rmc_ms - gga_ms : gga_ms - rmc_ms) <= kEpochMatchMs;
}

Fix NmeaFixParser::finish_rmc(uint64_t now_ms) {
    Fix fix = pending_rmc_->fix;
    if (gga_matches_rmc()) {
        fix.satellites = pending_gga_->satellites;
        if (!pending_gga_->has_fix) {
            fix.position.reset();
            fix.speed_cm_s.reset();
        } else if (fix.position) {
            fix.altitude_m = pending_gga_->altitude_m;
        }
        pending_gga_.reset();
    }
    last_time_of_day_ = pending_rmc_->time_of_day;
    last_utc_seconds_ = fix.utc_seconds;
    pending_rmc_.reset();
    last_emission_ms_ = now_ms;
    last_emission_was_rmc_ = true;
    return fix;
}

std::optional<Fix> NmeaFixParser::poll(uint64_t now_ms) {
    if (pending_rmc_) {
        if (gga_matches_rmc() || now_ms - pending_rmc_->received_ms >= kGgaWaitMs) {
            return finish_rmc(now_ms);
        }
        return std::nullopt;
    }
    const auto heartbeat_ms = last_emission_was_rmc_ ? kHeartbeatMs : kHeartbeatRepeatMs;
    if (now_ms - last_emission_ms_ < heartbeat_ms) return std::nullopt;
    last_emission_ms_ = now_ms;
    last_emission_was_rmc_ = false;
    return Fix{};
}

void NmeaFixParser::discard_partial_input() {
    receiving_ = false;
    pending_rmc_.reset();
    pending_gga_.reset();
}

}  // namespace gps
