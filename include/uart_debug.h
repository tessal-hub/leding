#pragma once
#include <Arduino.h>
#include "config.h"

/**
 * ============================================================================
 * MODULE UART DEBUG & SERIAL CLI INTERFACE TOÀN DIỆN CHO ESP32
 * ============================================================================
 * - In telemetry chi tiết có timestamp cho tất cả sự kiện: Power, Touch, MPU, LED, Flash...
 * - Console CLI tương tác 2 chiều qua cổng Serial: điều khiển và test mọi thứ
 * - Hỗ trợ stream dữ liệu thời gian thực (MPU, Touch, Battery) để quan sát
 */

// Các tiền tố phân loại Log
#define TAG_SYS    "SYS"
#define TAG_PWR    "PWR"
#define TAG_TOUCH  "TOUCH"
#define TAG_MPU    "MPU"
#define TAG_FX     "EFFECT"
#define TAG_FLASH  "FLASH"
#define TAG_LED    "LED"
#define TAG_CLI    "CLI"
#define TAG_BLE    "BLE"

// Cờ cấu hình debug thời gian thực
extern bool uartLoggingEnabled;
extern uint32_t uartHeartbeatIntervalMs;

// Khởi tạo UART Debug & CLI
void initUartDebug();

// In log định dạng chuẩn: [hh:mm:ss.mmm] [TAG] nội dung
void dbgLog(const char* tag, const char* format, ...);

// In báo cáo sức khỏe & trạng thái hệ thống toàn diện
void printSystemStatusReport();

// In hướng dẫn sử dụng các lệnh qua UART CLI
void printUartHelp();

// Thực thi lệnh điều khiển CLI (dùng chung cho cả UART và BLE)
// Trả về true nếu lệnh yêu cầu ngắt hiệu ứng hiện tại (đổi mode, chạy chữ...)
bool executeCliCommand(const char* cmdLine);

// Xử lý luồng ký tự nhận từ UART (Non-blocking)
// Trả về true nếu có lệnh yêu cầu ngắt hiệu ứng hiện tại (đổi mode, test led...)
bool handleUartDebug();
