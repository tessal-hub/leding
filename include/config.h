#pragma once
#include <Arduino.h>

/**
 * ============================================================================
 * CẤU HÌNH TOÀN BỘ DỰ ÁN VÒNG TRỤ LED RGB 360° (ESP32 + WS2812B + TOUCH + MPU)
 * ============================================================================
 */

// ============================================================================
// 1. CẤU HÌNH PHẦN CỨNG MA TRẬN LED WS2812B
// ============================================================================
#define LED_PIN               13     // Chân xuất dữ liệu DIN cho LED

#define NUM_PANELS            4      // Số lượng tấm ma trận 8x8 nối tiếp
#define PANEL_WIDTH           8      // Chiều rộng mỗi tấm (pixel)
#define PANEL_HEIGHT          8      // Chiều cao mỗi tấm (pixel)

#define MATRIX_WIDTH          (PANEL_WIDTH * NUM_PANELS) // Chu vi vòng trụ = 32 cột
#define MATRIX_HEIGHT         PANEL_HEIGHT               // Chiều cao hình trụ = 8 hàng
#define NUM_LEDS              (MATRIX_WIDTH * MATRIX_HEIGHT) // Tổng số: 256 LEDs

#define DEFAULT_BRIGHTNESS    25     // Độ sáng mặc định ban đầu (0 - 255)

// ============================================================================
// 2. CẤU HÌNH HƯỚNG VÀ TỌA ĐỘ MA TRẬN (HIỆU CHỈNH ĐI DÂY)
// ============================================================================
// Kiểu đi dây trong từng tấm 8x8:
// true  : Zic-zac (Serpentine - Hàng chẵn xuôi, hàng lẻ ngược hoặc ngược lại)
// false : Thẳng hàng (Progressive - Tất cả các cột/hàng đều đi cùng một chiều)
#define MATRIX_SERPENTINE     false

// Hoán đổi hàng và cột:
// true  : Đổi 1..8 thành hàng thay vì cột (hoán đổi X và Y của từng tấm)
// false : Giữ nguyên (1..8 là cột)
#define SWAP_ROW_COL          true

// Đảo chiều các trục:
#define INVERT_PANEL_X        true   // true: Lật ngược trái <-> phải (khắc phục chữ bị mirror)
#define INVERT_PANEL_Y        false  // true: Lật ngược trên <-> dưới

// ============================================================================
// 3. CẤU HÌNH CẢM BIẾN CHẠM (TOUCH SENSOR) - GPIO 5
// ============================================================================
#define TOUCH_PIN             5
#define TOUCH_MODE_INTERNAL   0      // 0: Module Digital TTP223 qua GPIO 5
#define TOUCH_THRESHOLD       35     // Ngưỡng chạm điện dung (nếu dùng touchRead)
#define HOLD_THRESHOLD_MS     400    // Giữ nút >= 400ms để vào chế độ chỉnh sáng MPU

// ============================================================================
// 4. CẤU HÌNH CẢM BIẾN GIA TỐC MPU-6050 (I2C)
// ============================================================================
#define MPU_SDA_PIN           21     // Chân I2C SDA
#define MPU_SCL_PIN           22     // Chân I2C SCL
#define MPU_I2C_ADDR          0x68   // Địa chỉ I2C mặc định

#define MIN_BRIGHTNESS        5      // Độ sáng tối thiểu khi nghiêng
#define MAX_BRIGHTNESS        75     // Độ sáng tối đa khi nghiêng (an toàn nguồn USB)
#define MPU_TILT_AXIS         0      // 0: Trục Pitch (gật tới/lui), 1: Trục Roll (lắc trái/phải)
#define INVERT_TILT_DIR       false  // true: Đảo chiều nghiêng tăng/giảm sáng

// ============================================================================
// 5. CẤU HÌNH DANH SÁCH CHẾ ĐỘ HIỂN THỊ (TUYỂN CHỌN TINH GỌN 10 CHẾ ĐỘ)
// ============================================================================
// Mode 0: Tự động chạy tuần hoàn 5 mode đỉnh cao + nhịp thở ban đêm (Auto-Cycle)
// Mode 1: Đèn dung nham 3D Noise (Lava Noise - MẶC ĐỊNH ĐÈN TRANG TRÍ)
// Mode 2: Đèn viền 4 cạnh góc hộp (Edge Light - Tối giản kiểu thiết bị cao cấp)
// Mode 3: Sóng nhịp 4 mặt hộp (Face Wave - Đậm màu, chuyển sắc mượt khi xoay)
// Mode 4: Đèn nghiêng & lắc bừng sáng (Tilt Glow MPU - Tương tác cảm biến)
// Mode 5: Nước chảy & sóng xô lệch (Water Physics MPU - Showpiece vật lý nước)
// Mode 6: Nhịp thở ban đêm (Night Breathing - Đèn ngủ êm dịu, ít chói)
// Mode 7: Dải cực quang Bắc Cực (Aurora Flow - Chế độ dự bị)
// Mode 8: DÒ HƯỚNG ĐI DÂY LED 0 -> 255 (Calibration Test - Công cụ Debug)
// Mode 9: Tắt đèn (Standby / Off)
// Mode 10: Màu tĩnh tùy chọn theo App (Static Color)
// Mode 11: Hiển thị 4 ô tùy biến độc lập (4-Slot Custom Bitmap Display)
// Mode 12: Đồng hồ để bàn 360° kỹ thuật số & thời tiết (Desk Clock 360°)
// Mode 13: Nhịp đập trái tim (Heartbeat Pulse - Tình yêu lãng mạn)
// Mode 14: Sóng tình yêu lan tỏa (Love Ripple - Dreamy romantic ripple)
#define DEFAULT_MODE          1      // Khởi động mặc định vào Mode 1: Lava Noise
#define TOTAL_MODES           15     // 15 chế độ (bao gồm 2 hiệu ứng tình yêu mới)

// ============================================================================
// 6. CẤU HÌNH QUẢN LÝ NGUỒN & SẠC PIN (MODULE IP2312)
// ============================================================================
#define PIN_VBUS              32     // Tuyến phát hiện cắm nguồn 5V USB (qua cầu phân áp 10k/20k)
#define PIN_CHG_STAT          33     // Tuyến phát hiện LED đỏ IP2312 (qua cầu phân áp)
#define CHG_STAT_ACTIVE_LOW   false  // false: Active HIGH (Đèn tắt = 0V, Đèn sáng = >700mV)
#define CHG_STAT_THRESHOLD_MV 700    // Ngưỡng điện áp phân định sạc/đầy (mV)
#define CHG_SOFT_START_HOLD_MS 1500  // Khoảng đệm chờ IP2312 khởi động mềm (1.5s), triệt tiêu hoàn toàn nháy xanh khi cắm sạc
#define CHG_PULSE_HOLD_MS      1500  // Giữ trạng thái LED đỏ sáng trong 1.5s sau xung cuối (lọc PWM/xung ngắt quãng)

// ============================================================================
// 7. CẤU HÌNH DEBUG UART & SERIAL CLI INTERFACE (TOÀN DIỆN MỌI THỨ)
// ============================================================================
#define UART_BAUD_RATE        115200 // Tốc độ truyền Serial UART (Baud Rate)
#define UART_DEBUG_ENABLED    true   // true: Tự động in log chi tiết ra UART
#define UART_HEARTBEAT_MS     4000   // Chu kỳ in nhịp tim Telemetry (ms) (0 = tắt)

// ============================================================================
// 8. CẤU HÌNH BLUETOOTH LOW ENERGY (BLE) NORDIC UART SERVICE (NUS)
// ============================================================================
#define BLE_DEVICE_NAME           "LED-MATRIX-360" // Tên thiết bị hiển thị khi dò BLE
#define BLE_TELEMETRY_INTERVAL_MS 2000             // Chu kỳ gửi thông số trạng thái về App (ms)
#define BLE_NUS_SERVICE_UUID      "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define BLE_NUS_RX_UUID           "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // Write (App -> Đèn)
#define BLE_NUS_TX_UUID           "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // Notify (Đèn -> App)

// ============================================================================
// 9. CẤU HÌNH TÍNH NĂNG VẬT LÝ ESP32 (POMODORO & ĐÈN PIN KHẨN CẤP)
// ============================================================================
#define POMODORO_WORK_MS          (25 * 60 * 1000UL) // 25 phút đếm lùi
#define POMODORO_BREAK_MS         (5 * 60 * 1000UL)  // 5 phút nghỉ
#define EMERGENCY_TIMEOUT_MS      60000UL            // 60 giây đèn pin khẩn cấp
#define SHAKE_JERK_THRESHOLD      2.4f               // Ngưỡng gia tốc lắc mạnh (g)

// ============================================================================
// 10. CẤU HÌNH OTA BLUETOOTH BLE
// ============================================================================
#define OTA_HOLD_TRIGGER_MS       3000   // Giữ nút không di chuyển >= 3000ms để vào OTA
#define OTA_STILL_ACCEL_DELTA     1800   // Ngưỡng gia tốc MPU tối đa coi là đứng yên (~0.11g)
