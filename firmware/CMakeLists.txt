cmake_minimum_required(VERSION 3.13...3.31)

if(NOT DEFINED PICO_SDK_PATH AND DEFINED ENV{PICO_SDK_PATH})
    set(PICO_SDK_PATH "$ENV{PICO_SDK_PATH}")
endif()
if(NOT PICO_SDK_PATH)
    set(PICO_SDK_PATH "${CMAKE_CURRENT_LIST_DIR}/../pico-sdk")
endif()
get_filename_component(PICO_SDK_PATH "${PICO_SDK_PATH}" ABSOLUTE)
if(NOT EXISTS "${PICO_SDK_PATH}/external/pico_sdk_import.cmake")
    message(FATAL_ERROR "Set PICO_SDK_PATH to a Raspberry Pi Pico SDK checkout with submodules.")
endif()

set(PICO_BOARD pico2_w CACHE STRING "Pico GPS target board")
include("${PICO_SDK_PATH}/external/pico_sdk_import.cmake")

project(pico_gps C CXX ASM)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
pico_sdk_init()

add_executable(pico_gps main.cpp nmea.cpp fix.cpp)
target_include_directories(pico_gps PRIVATE "${CMAKE_CURRENT_LIST_DIR}")
target_compile_definitions(pico_gps PRIVATE
    CYW43_LWIP=0
    PICO_BTSTACK_CYW43_MAX_HCI_PROCESS_LOOP_COUNT=8
    PICO_STDIO_USB_STDOUT_TIMEOUT_US=1000
)
target_link_libraries(pico_gps PRIVATE
    hardware_uart
    pico_btstack_ble
    pico_btstack_cyw43
    pico_cyw43_arch_poll
    pico_rand
    pico_stdlib
    pico_unique_id
)
pico_btstack_make_gatt_header(pico_gps PRIVATE "${CMAKE_CURRENT_LIST_DIR}/tracker.gatt")
pico_enable_stdio_usb(pico_gps 1)
pico_enable_stdio_uart(pico_gps 0)
pico_add_extra_outputs(pico_gps)
