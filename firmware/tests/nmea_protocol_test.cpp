#include "fix.h"
#include "nmea.h"

#include <cassert>
#include <fstream>
#include <iostream>
#include <string>
#include <string_view>
#include <vector>

namespace {

std::vector<std::string> split(std::string_view line, char delimiter) {
    std::vector<std::string> fields;
    for (;;) {
        const auto end = line.find(delimiter);
        fields.emplace_back(line.substr(0, end));
        if (end == line.npos) return fields;
        line.remove_prefix(end + 1);
    }
}

template<typename Packet>
std::string hex(const Packet &packet) {
    constexpr std::string_view digits = "0123456789abcdef";
    std::string result;
    for (uint8_t byte : packet) {
        result += digits[byte / 16];
        result += digits[byte % 16];
    }
    return result;
}

void verify_wire_vectors(const char *path) {
    std::ifstream fixtures(path);
    assert(fixtures.good());
    std::string line;
    assert(std::getline(fixtures, line));
    unsigned rows = 0;
    while (std::getline(fixtures, line)) {
        const auto columns = split(line, '\t');
        assert(columns.size() == 5);
        if (columns[0] == "metadata") {
            assert(hex(gps::encode_metadata(std::stoull(columns[2], nullptr, 16),
                                            std::stoull(columns[3], nullptr, 16))) == columns[1]);
        } else {
            const auto values = split(columns[3], ',');
            assert(values.size() == 6);
            gps::Fix fix;
            if (values[0] != "-") fix.utc_seconds = static_cast<uint32_t>(std::stoul(values[0]));
            if (values[1] != "-") {
                assert(values[2] != "-");
                fix.position = gps::Position{std::stoi(values[1]), std::stoi(values[2])};
            }
            if (values[3] != "-") fix.speed_cm_s = static_cast<uint16_t>(std::stoi(values[3]));
            if (values[4] != "-") fix.altitude_m = static_cast<int16_t>(std::stoi(values[4]));
            if (values[5] != "-") fix.satellites = static_cast<uint8_t>(std::stoi(values[5]));
            const auto sequence = static_cast<uint16_t>(std::stoi(columns[2]));
            assert(hex(gps::encode_fix(sequence, fix)) == columns[1]);
        }
        ++rows;
    }
    assert(rows >= 4);
    assert(hex(gps::encode_fix(0, gps::Fix{})) == "00000000000000000000000000000000000000ff");
}

std::optional<gps::Fix> feed(gps::NmeaFixParser &parser, std::string_view input, uint64_t now_ms) {
    std::optional<gps::Fix> result;
    for (const char c : input) {
        if (auto fix = parser.ingest(c, now_ms)) {
            assert(!result);
            result = *fix;
        }
    }
    return result;
}

std::optional<gps::Fix> sentence(gps::NmeaFixParser &parser, std::string_view body, uint64_t now_ms) {
    uint8_t checksum = 0;
    for (const char c : body) checksum ^= static_cast<uint8_t>(c);
    return feed(parser, "$" + std::string(body) + "*" + hex(std::array{checksum}) + "\r\n", now_ms);
}

void verify_reception() {
    gps::NmeaFixParser parser;
    assert(!feed(parser, "$GNRMC,010555.000,A,2232.4682,N,11404.6748,E,0.00,125.29,230822,,,D*71\r\n", 100));
    auto fix = sentence(parser, "BDGGA,010555.0,2232.4682,N,11404.6748,E,2,09,1.0,-23.6,M,0.0,M,,", 160);
    assert(fix && fix->position);
    assert(fix->utc_seconds == 1661216755 && fix->speed_cm_s == 0);
    assert(fix->position->latitude_e7 == 225411367 && fix->position->longitude_e7 == 1140779133);
    assert(fix->altitude_m == -24 && fix->satellites == 9);

    // Repeated UTC, including a stalled receiver, must never refresh an old position.
    assert(!sentence(parser, "GPRMC,010555,A,2232.4682,N,11404.6748,E,0.00,0,230822,,,A", 1100));
    assert(!parser.poll(1409));
    fix = parser.poll(1410);
    assert(fix && !fix->position && !fix->utc_seconds && !fix->satellites);
    assert(!sentence(parser, "GPRMC,010555,A,2232.4682,N,11404.6748,E,0.00,0,230822,,,A", 2100));

    assert(!sentence(parser, "GNGGA,010556.1,2232.4682,N,11404.6748,E,1,10,1,300.0,M,0,M,,", 2200));
    assert(!sentence(parser, "GNRMC,010556.2,A,2232.4682,N,11404.6748,E,6.34,0,230822,,,A", 2250));
    assert(!parser.poll(2599));
    fix = parser.poll(2600);
    assert(fix && fix->position && fix->speed_cm_s == 326 && !fix->satellites && !fix->altitude_m);

    assert(!sentence(parser, "GNGGA,010557,,,,,0,00,99.99,,M,,M,,", 3000));
    fix = sentence(parser, "GNRMC,010557,A,2232.4682,N,11404.6748,E,0.00,0,230822,,,A", 3100);
    assert(fix && !fix->position && !fix->speed_cm_s && !fix->altitude_m && fix->satellites == 0);
    assert(fix->utc_seconds == 1661216757);

    assert(!sentence(parser, "GNGGA,010558,,,,,0,00,99.99,,M,,M,,", 4000));
    fix = sentence(parser, "GNRMC,010558,V,,,,,,,230822,,,N", 4100);
    assert(fix && !fix->position && fix->utc_seconds == 1661216758 && fix->satellites == 0);
}

void verify_midnight_and_recovery() {
    gps::NmeaFixParser parser;
    assert(!feed(parser, "$GPRMC,235959,A,3351.912,S,15112.558,E,1.00,0,290224,,,A*00\r\n", 20));
    assert(!parser.poll(500));
    assert(!feed(parser, "$" + std::string(140, 'A') + "\r\n", 505));
    assert(!sentence(parser, "GPRMC,235959.000,A,3351.912,S,15112.558,E,1.00,0,290224,,,A", 510));
    auto fix = sentence(parser, "GNGGA,235959.000,3351.912,S,15112.558,E,1,07,1,-23.5,M,0,M,,", 550);
    assert(fix && fix->utc_seconds == 1709251199 && fix->altitude_m == -24 && fix->satellites == 7);
    assert(fix->position && fix->position->latitude_e7 == -338652000 && fix->position->longitude_e7 == 1512093000);

    assert(!sentence(parser, "GNGGA,000000.000,,,,,1,08,1,999.0,M,0,M,,", 1100));
    parser.discard_partial_input();
    assert(!sentence(parser, "GNRMC,000000.000,A,0000.0,N,00000.0,E,2.00,0,010324,,,A", 1130));
    fix = parser.poll(1480);
    assert(fix && fix->utc_seconds == 1709251200 && !fix->altitude_m && !fix->satellites);
    assert(fix->position && fix->position->latitude_e7 == 0 && fix->position->longitude_e7 == 0);

    assert(!sentence(parser, "GNRMC,000001.000,A,9000.1,N,18000.0,E,2000,0,300224,,,A", 2150));
    fix = parser.poll(2500);
    assert(fix && !fix->utc_seconds && !fix->position && !fix->speed_cm_s);
}

}  // namespace

int main(int argc, char **argv) {
    assert(argc == 2);
    verify_wire_vectors(argv[1]);
    verify_reception();
    verify_midnight_and_recovery();
    std::cout << "NMEA tests and shared v1 wire vectors passed\n";
}
