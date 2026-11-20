#include "nmea.h"

#include <algorithm>
#include <array>
#include <cinttypes>
#include <cstdio>
#include <string_view>

#include "btstack.h"
#include "hardware/irq.h"
#include "hardware/sync.h"
#include "hardware/uart.h"
#include "pico/binary_info.h"
#include "pico/cyw43_arch.h"
#include "pico/rand.h"
#include "pico/stdio_usb.h"
#include "pico/stdlib.h"
#include "pico/unique_id.h"
#include "tracker.h"

namespace {

constexpr uint kGpsRxPin = 1;
constexpr uint kGpsBaud = 9600;
constexpr size_t kClientCount = 2;
constexpr uint64_t kPairingWindowMs = 60'000;
constexpr uint64_t kUnencryptedTimeoutMs = 15'000;
constexpr size_t kUartCapacity = 1024;

constexpr uint16_t kMetadataHandle =
    ATT_CHARACTERISTIC_C7A10002_8A6B_4C0F_9E12_5197B5E62D11_01_VALUE_HANDLE;
constexpr uint16_t kFixHandle =
    ATT_CHARACTERISTIC_C7A10003_8A6B_4C0F_9E12_5197B5E62D11_01_VALUE_HANDLE;
constexpr uint16_t kCccHandle =
    ATT_CHARACTERISTIC_C7A10003_8A6B_4C0F_9E12_5197B5E62D11_01_CLIENT_CONFIGURATION_HANDLE;

uint8_t advertisement[] = {
    2, BLUETOOTH_DATA_TYPE_FLAGS, 0x06,
    17, BLUETOOTH_DATA_TYPE_COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
    0x11, 0x2d, 0xe6, 0xb5, 0x97, 0x51, 0x12, 0x9e,
    0x0f, 0x4c, 0x6b, 0x8a, 0x01, 0x00, 0xa1, 0xc7,
    9, BLUETOOTH_DATA_TYPE_COMPLETE_LOCAL_NAME, 'P', 'i', 'c', 'o', ' ', 'G', 'P', 'S',
};
static_assert(sizeof(advertisement) == 31);

struct Client {
    hci_con_handle_t handle = HCI_CON_HANDLE_INVALID;
    uint64_t connected_ms = 0;
    bool closing = false;
    bool authorized = false;
    bool passkey_attempt = false;
    bool metadata_read = false;
    uint16_t ccc = 0;
    std::optional<gps::FixPacket> pending;
    bool send_requested = false;
    btstack_context_callback_registration_t send_callback{};
};

std::array<Client, kClientCount> clients;
btstack_packet_callback_registration_t hci_registration{};
btstack_packet_callback_registration_t sm_registration{};
bool bluetooth_ready = false;
bool rotating_session = false;
uint64_t pair_until_ms = 0;
hci_con_handle_t pairing_client = HCI_CON_HANDLE_INVALID;
uint64_t receiver_id = 0;
uint64_t session_id = 0;
uint32_t next_sequence = 0;
gps::MetadataPacket metadata;

volatile uint8_t uart_buffer[kUartCapacity];
volatile size_t uart_head = 0;
volatile size_t uart_tail = 0;
volatile bool uart_lost_data = false;

uint64_t now_ms() {
    return to_ms_since_boot(get_absolute_time());
}

void on_uart_rx() {
    auto *hardware = uart_get_hw(uart0);
    constexpr uint32_t error_bits = UART_UARTDR_OE_BITS | UART_UARTDR_BE_BITS |
                                    UART_UARTDR_PE_BITS | UART_UARTDR_FE_BITS;
    while (uart_is_readable(uart0)) {
        const uint32_t word = hardware->dr;
        const size_t next = (uart_head + 1) % kUartCapacity;
        if ((word & error_bits) || next == uart_tail) {
            uart_head = uart_tail;
            uart_lost_data = true;
            hardware->rsr = 0;
            continue;
        }
        uart_buffer[uart_head] = static_cast<uint8_t>(word & UART_UARTDR_DATA_BITS);
        uart_head = next;
    }
}

void init_uart() {
    bi_decl(bi_1pin_with_name(kGpsRxPin, "L76K UART0 RX (9600 8N1)"));
    uart_init(uart0, kGpsBaud);
    uart_set_format(uart0, 8, 1, UART_PARITY_NONE);
    uart_set_hw_flow(uart0, false, false);
    gpio_set_function(kGpsRxPin, GPIO_FUNC_UART);
    irq_set_exclusive_handler(UART0_IRQ, on_uart_rx);
    irq_set_enabled(UART0_IRQ, true);
    uart_set_irqs_enabled(uart0, true, false);
}

Client *find_client(hci_con_handle_t handle) {
    if (handle == HCI_CON_HANDLE_INVALID) return nullptr;
    const auto it = std::find_if(clients.begin(), clients.end(), [handle](const Client &client) {
        return client.handle == handle;
    });
    return it == clients.end() ? nullptr : &*it;
}

bool has_bond_security(hci_con_handle_t handle) {
    return gap_bonded(handle) && gap_encryption_key_size(handle) == 16 &&
           gap_authenticated(handle) && gap_secure_connection(handle);
}

bool can_access_tracker(const Client &client) {
    return !client.closing && client.authorized && has_bond_security(client.handle);
}

void disconnect(Client &client) {
    if (client.closing) return;
    client.closing = true;
    client.pending.reset();
    gap_disconnect(client.handle);
}

bool pairing_window_open() {
    return pair_until_ms != 0 && now_ms() < pair_until_ms && stdio_usb_connected();
}

void close_pairing_window() {
    pair_until_ms = 0;
    sm_set_accepted_stk_generation_methods(0);
    if (auto *client = find_client(pairing_client); client && !client->authorized) {
        disconnect(*client);
        // Preserve the claimant until disconnect so a late successful pairing can be revoked.
    } else {
        pairing_client = HCI_CON_HANDLE_INVALID;
    }
}

void open_pairing_window() {
    if (!bluetooth_ready) {
        std::puts("Bluetooth is starting. Try pair again in a moment.");
    } else if (pair_until_ms != 0 || pairing_client != HCI_CON_HANDLE_INVALID) {
        std::puts("Pairing is already in progress.");
    } else {
        pair_until_ms = now_ms() + kPairingWindowMs;
        sm_set_accepted_stk_generation_methods(SM_STK_GENERATION_METHOD_PASSKEY);
        std::puts("Pairing is open for 60 seconds. Connect from the Android app.");
    }
}

void poll_usb_console() {
    static std::array<char, 16> line;
    static size_t length = 0;
    static bool overflow = false;
    if (!stdio_usb_connected()) {
        length = 0;
        overflow = false;
        return;
    }
    for (size_t i = 0; i < line.size(); ++i) {
        const int byte = getchar_timeout_us(0);
        if (byte == PICO_ERROR_TIMEOUT) break;
        if (byte == '\n' || byte == '\r') {
            if (!overflow && std::string_view(line.data(), length) == "pair") {
                open_pairing_window();
            } else if (length != 0 || overflow) {
                std::puts("Type pair to enroll one Android phone.");
            }
            length = 0;
            overflow = false;
        } else if (length < line.size()) {
            line[length++] = static_cast<char>(byte);
        } else {
            overflow = true;
        }
    }
}

void poll_connections() {
    if (pair_until_ms != 0 && !pairing_window_open()) {
        close_pairing_window();
        std::puts("Pairing window closed.");
    }
    for (auto &client : clients) {
        if (client.handle == HCI_CON_HANDLE_INVALID || client.closing) continue;
        if (client.authorized) {
            if (!has_bond_security(client.handle)) disconnect(client);
            continue;
        }
        if (!pairing_window_open() && now_ms() - client.connected_ms >= kUnencryptedTimeoutMs) {
            disconnect(client);
        }
    }
}

void rotate_session() {
    const auto previous = session_id;
    do {
        session_id = get_rand_64();
    } while (session_id == 0 || session_id == previous);
    metadata = gps::encode_metadata(receiver_id, session_id);
    next_sequence = 0;
}

bool all_slots_empty() {
    return std::all_of(clients.begin(), clients.end(), [](const Client &client) {
        return client.handle == HCI_CON_HANDLE_INVALID;
    });
}

void finish_session_rotation() {
    if (rotating_session && all_slots_empty()) {
        rotating_session = false;
        gap_advertisements_enable(1);
    }
}

void send_notification(void *context) {
    auto &client = *static_cast<Client *>(context);
    client.send_requested = false;
    if (client.pending && can_access_tracker(client) && client.metadata_read && client.ccc != 0) {
        att_server_notify(client.handle, kFixHandle, client.pending->data(), client.pending->size());
    }
    client.pending.reset();
}

void publish(const gps::Fix &fix) {
    if (next_sequence > UINT16_MAX) {
        rotating_session = true;
        gap_advertisements_enable(0);
        for (auto &client : clients) {
            if (client.handle != HCI_CON_HANDLE_INVALID) disconnect(client);
        }
        rotate_session();
        finish_session_rotation();
    }

    const auto packet = gps::encode_fix(static_cast<uint16_t>(next_sequence++), fix);
    for (auto &client : clients) {
        if (!can_access_tracker(client) || !client.metadata_read || client.ccc == 0) continue;
        // Replacing a queued sample bounds memory and preserves the sequence gap for the phone.
        client.pending = packet;
        if (client.send_requested) continue;
        client.send_requested = true;
        const auto status = att_server_request_to_send_notification(&client.send_callback, client.handle);
        if (status != ERROR_CODE_SUCCESS) {
            client.send_requested = false;
            client.pending.reset();
        }
    }
}

void poll_gps(gps::NmeaFixParser &parser) {
    std::array<uint8_t, 128> bytes;
    size_t count = 0;
    const auto irq_state = save_and_disable_interrupts();
    const bool discontinuity = uart_lost_data;
    uart_lost_data = false;
    while (uart_tail != uart_head && count < bytes.size()) {
        bytes[count++] = uart_buffer[uart_tail];
        uart_tail = (uart_tail + 1) % kUartCapacity;
    }
    const bool further_input = uart_tail != uart_head;
    restore_interrupts(irq_state);

    if (discontinuity) parser.discard_partial_input();
    const auto received_ms = now_ms();
    for (size_t i = 0; i < count; ++i) {
        if (const auto fix = parser.ingest(static_cast<char>(bytes[i]), received_ms)) publish(*fix);
    }
    if (!further_input) {
        if (const auto fix = parser.poll(received_ms)) publish(*fix);
    }
}

uint16_t on_att_read(hci_con_handle_t handle, uint16_t attribute, uint16_t offset,
                     uint8_t *buffer, uint16_t buffer_size) {
    auto *client = find_client(handle);
    if (attribute == kMetadataHandle) {
        if (!client || !can_access_tracker(*client)) {
            return ATT_READ_ERROR_CODE_OFFSET + ATT_ERROR_INSUFFICIENT_AUTHENTICATION;
        }
        const uint16_t count = att_read_callback_handle_blob(metadata.data(), metadata.size(),
                                                            offset, buffer, buffer_size);
        if (buffer && offset == 0 && count == metadata.size()) client->metadata_read = true;
        return count;
    }
    if (attribute == kCccHandle && client) {
        return att_read_callback_handle_little_endian_16(client->ccc, offset, buffer, buffer_size);
    }
    return 0;
}

int on_att_write(hci_con_handle_t handle, uint16_t attribute, uint16_t transaction_mode,
                 uint16_t offset, uint8_t *buffer, uint16_t buffer_size) {
    if (attribute != kCccHandle) return ATT_ERROR_WRITE_NOT_PERMITTED;
    auto *client = find_client(handle);
    if (!client || !can_access_tracker(*client)) return ATT_ERROR_INSUFFICIENT_AUTHENTICATION;
    if (transaction_mode != ATT_TRANSACTION_MODE_NONE) return ATT_ERROR_PREPARE_QUEUE_FULL;
    if (offset != 0) return ATT_ERROR_INVALID_OFFSET;
    if (buffer_size != 2) return ATT_ERROR_INVALID_ATTRIBUTE_VALUE_LENGTH;
    const auto ccc = little_endian_read_16(buffer, 0);
    if (ccc != 0 && ccc != GATT_CLIENT_CHARACTERISTICS_CONFIGURATION_NOTIFICATION) {
        return ATT_ERROR_VALUE_NOT_ALLOWED;
    }
    client->ccc = ccc;
    if (ccc == 0) client->pending.reset();
    return 0;
}

void on_connection(const uint8_t *packet) {
    if (gap_subevent_le_connection_complete_get_status(packet) != ERROR_CODE_SUCCESS ||
        gap_subevent_le_connection_complete_get_role(packet) != HCI_ROLE_SLAVE) return;

    const auto handle = gap_subevent_le_connection_complete_get_connection_handle(packet);
    for (auto &client : clients) {
        if (client.handle != HCI_CON_HANDLE_INVALID) continue;
        client.handle = handle;
        client.connected_ms = now_ms();
        client.send_callback.callback = send_notification;
        client.send_callback.context = &client;
        if (rotating_session) disconnect(client);
        return;
    }
    gap_disconnect(handle);
}

void on_hci_event(uint8_t packet_type, uint16_t, uint8_t *packet, uint16_t) {
    if (packet_type != HCI_EVENT_PACKET) return;
    switch (hci_event_packet_get_type(packet)) {
        case BTSTACK_EVENT_STATE:
            bluetooth_ready = btstack_event_state_get_state(packet) == HCI_STATE_WORKING;
            if (bluetooth_ready) {
                gap_advertisements_enable(1);
                std::puts("Pico GPS ready. Type pair to enroll one Android phone.");
            }
            break;
        case HCI_EVENT_META_GAP:
            if (hci_event_gap_meta_get_subevent_code(packet) == GAP_SUBEVENT_LE_CONNECTION_COMPLETE) {
                on_connection(packet);
            }
            break;
        case HCI_EVENT_DISCONNECTION_COMPLETE: {
            const auto handle = hci_event_disconnection_complete_get_connection_handle(packet);
            if (handle == pairing_client) pairing_client = HCI_CON_HANDLE_INVALID;
            if (auto *client = find_client(handle)) *client = {};
            finish_session_rotation();
            break;
        }
        default:
            break;
    }
}

void on_passkey_display(const uint8_t *packet) {
    const auto handle = sm_event_passkey_display_number_get_handle(packet);
    auto *client = find_client(handle);
    if (!client) return;
    client->passkey_attempt = true;
    if (!pairing_window_open() || client->closing ||
        (pairing_client != HCI_CON_HANDLE_INVALID && pairing_client != handle)) {
        disconnect(*client);
        return;
    }
    pairing_client = handle;
    const auto passkey = sm_event_passkey_display_number_get_passkey(packet);
    std::printf("Enter this passkey on Android: %06" PRIu32 "\n", passkey);
}

void revoke_bond(hci_con_handle_t handle) {
    const int index = sm_le_device_index(handle);
    if (index < 0) return;
    int type;
    bd_addr_t address;
    le_device_db_info(index, &type, address, nullptr);
    gap_delete_bonding(static_cast<bd_addr_type_t>(type), address);
}

void on_pairing_complete(const uint8_t *packet) {
    const auto handle = sm_event_pairing_complete_get_handle(packet);
    auto *client = find_client(handle);
    if (!client) return;
    const bool success = sm_event_pairing_complete_get_status(packet) == ERROR_CODE_SUCCESS;
    if (client->authorized && success && !client->passkey_attempt) return;

    const bool claimed = pairing_client == handle;
    if (success && claimed && pairing_window_open() && !client->closing && has_bond_security(handle)) {
        client->authorized = true;
        client->passkey_attempt = false;
        close_pairing_window();
        std::puts("Phone bonded. Pairing window closed.");
    } else {
        if (success && client->passkey_attempt) revoke_bond(handle);
        if (claimed) {
            pairing_client = HCI_CON_HANDLE_INVALID;
            std::puts("Pairing failed. Reconnect to try again during the pairing window.");
        }
        disconnect(*client);
    }
}

void on_sm_event(uint8_t packet_type, uint16_t, uint8_t *packet, uint16_t) {
    if (packet_type != HCI_EVENT_PACKET) return;
    switch (hci_event_packet_get_type(packet)) {
        case SM_EVENT_PAIRING_STARTED: {
            const auto handle = sm_event_pairing_started_get_handle(packet);
            if (auto *client = find_client(handle); client &&
                (!pairing_window_open() || (pairing_client != HCI_CON_HANDLE_INVALID && pairing_client != handle))) {
                disconnect(*client);
            }
            break;
        }
        case SM_EVENT_PASSKEY_DISPLAY_NUMBER:
            on_passkey_display(packet);
            break;
        case SM_EVENT_PAIRING_COMPLETE:
            on_pairing_complete(packet);
            break;
        case SM_EVENT_REENCRYPTION_COMPLETE: {
            const auto handle = sm_event_reencryption_complete_get_handle(packet);
            const auto status = sm_event_reencryption_complete_get_status(packet);
            if (auto *client = find_client(handle); client && !client->closing) {
                client->authorized = status == ERROR_CODE_SUCCESS && has_bond_security(handle);
                if (!client->authorized && !pairing_window_open()) disconnect(*client);
            }
            break;
        }
        case SM_EVENT_JUST_WORKS_REQUEST:
            sm_bonding_decline(sm_event_just_works_request_get_handle(packet));
            break;
        default:
            break;
    }
}

void init_bluetooth() {
    l2cap_init();
    sm_init();
    sm_set_io_capabilities(IO_CAPABILITY_DISPLAY_ONLY);
    sm_set_authentication_requirements(SM_AUTHREQ_BONDING | SM_AUTHREQ_MITM_PROTECTION |
                                       SM_AUTHREQ_SECURE_CONNECTION);
    sm_set_secure_connections_only_mode(true);
    sm_set_encryption_key_size_range(16, 16);
    sm_set_accepted_stk_generation_methods(0);
    sm_allow_ltk_reconstruction_without_le_device_db_entry(0);

    att_server_init(profile_data, on_att_read, on_att_write);
    hci_registration.callback = on_hci_event;
    hci_add_event_handler(&hci_registration);
    sm_registration.callback = on_sm_event;
    sm_add_event_handler(&sm_registration);

    bd_addr_t unused_address{};
    gap_set_max_number_peripheral_connections(kClientCount);
    gap_advertisements_set_params(0x00a0, 0x00f0, 0, 0, unused_address, 0x07, 0x00);
    gap_advertisements_set_data(sizeof(advertisement), advertisement);
    hci_power_control(HCI_POWER_ON);
}

}  // namespace

int main() {
    stdio_init_all();
    pico_unique_board_id_t board_id;
    pico_get_unique_board_id(&board_id);
    static_assert(sizeof(board_id.id) == sizeof(receiver_id));
    for (const uint8_t byte : board_id.id) receiver_id = (receiver_id << 8) | byte;
    if (receiver_id == 0) panic("Missing Pico receiver ID");

    if (cyw43_arch_init() != 0) panic("Could not initialize the Pico 2 W radio");
    rotate_session();
    init_uart();
    init_bluetooth();

    gps::NmeaFixParser parser(now_ms());
    for (;;) {
        poll_usb_console();
        poll_connections();
        cyw43_arch_poll();
        poll_gps(parser);
        cyw43_arch_wait_for_work_until(make_timeout_time_ms(2));
    }
}
