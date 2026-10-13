#pragma once
#include <Arduino.h>
#include "config.h"

/**
 * ============================================================================
 * MODULE BLUETOOTH LOW ENERGY (BLE) NORDIC UART SERVICE (NUS) CHO ESP32
 * ============================================================================
 * - Chuẩn UUID tương thích 100% với nRF Connect, LightBlue và App Flutter
 * - Nhận lệnh điều khiển (Đổi mode, độ sáng, màu sắc, chạy chữ 360°)
 * - Gửi telemetry JSON thời gian thực (Pin, Đang sạc, Góc nghiêng MPU, Độ sáng)
 */

// Khởi tạo BLE Server & Nordic UART Service
void initBleService();

// Xử lý gói tin nhận từ BLE và gửi telemetry định kỳ (Non-blocking)
// Trả về true nếu có lệnh yêu cầu ngắt hiệu ứng hiện tại (đổi mode, chạy chữ...)
bool handleBleService();

// Gửi chuỗi phản hồi / JSON lên điện thoại qua BLE TX (Notify)
void bleNotify(const char* data);

// Gửi gói tin telemetry tình trạng đèn (JSON)
void bleSendTelemetry(bool force = false);

// Kiểm tra điện thoại có đang kết nối BLE hay không
bool isBleConnected();
