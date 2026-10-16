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
#define LED_PIN               6      // Chân xuất dữ liệu DIN cho LED (GPIO 6 trên ESP32-C3)

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
// 3. CẤU HÌNH CẢM BIẾN CHẠM (TOUCH SENSOR) - GPIO 4
// ============================================================================
#define TOUCH_PIN             4      // Chân tín hiệu SIG module TTP223 (GPIO 4 trên ESP32-C3)
#define TOUCH_MODE_INTERNAL   0      // 0: Module Digital TTP223
#define TOUCH_THRESHOLD       35     // Ngưỡng chạm điện dung (nếu dùng touchRead)
#define HOLD_THRESHOLD_MS     400    // Giữ nút >= 400ms để vào chế độ chỉnh sáng MPU

// ============================================================================
// 4. CẤU HÌNH CẢM BIẾN GIA TỐC MPU-6050 (I2C)
// ============================================================================
#define MPU_SDA_PIN           0      // Chân I2C SDA (GPIO 0 trên ESP32-C3)
#define MPU_SCL_PIN           1      // Chân I2C SCL (GPIO 1 trên ESP32-C3)
#define MPU_I2C_ADDR          0x68   // Địa chỉ I2C mặc định

#define MIN_BRIGHTNESS        5      // Độ sáng tối thiểu (5/255)
#define MAX_BRIGHTNESS        255    // Độ sáng tối đa toàn dải (255/255) giữ trọn vẹn độ phân giải màu RGB
#define MPU_TILT_AXIS         0      // 0: Trục Pitch (gật tới/lui), 1: Trục Roll (lắc trái/phải)
#define INVERT_TILT_DIR       false  // true: Đảo chiều nghiêng tăng/giảm sáng

// ============================================================================
// 5. CẤU HÌNH DANH SÁCH CHẾ ĐỘ HIỂN THỊ (TUYỂN CHỌN TINH GỌN 10 CHẾ ĐỘ)
// ============================================================================
// Mode 0: Tự động đổi cảnh Lãng Mạn (Romantic Cycle)
// Mode 1: Nhịp đập trái tim (Heartbeat Pulse - MẶC ĐỊNH ĐÈN LÃNG MẠN)
// Mode 2: Pha Lê Thạch Anh Tím (Amethyst Crystal Dream - Cột đá quý lung linh 360°)
// Mode 3: Ánh nến hẹn hò lung linh (Candlelight Romance - Ấm cúng hai người)
// Mode 4: Cử chỉ yêu thương MPU (Love Tilt & Caress - Ánh sáng âu yếm)
// Mode 5: Lửa ấm đêm đông (Cozy Fireplace Romance - Lò sưởi ấm cúng 360°)
// Mode 6: Biển Hoa Oải Hương Tím (Lavender Twilight Breeze - Cánh đồng tím hoàng hôn)
// Mode 7: Cực quang tình nhân (Lover's Aurora - Huyền ảo tím & hồng)
// Mode 8: Bầu trời ngàn sao hẹn ước (Starry Night Romance - Sao băng ước nguyện)
// Mode 9: Tắt đèn (Standby / Off)
// Mode 10: Màu tĩnh lãng mạn tùy chọn theo App (Romantic Mood Color)
// Mode 11: 4 Khung hình kỷ niệm tình yêu (4-Slot Love Memories)
// Mode 12: Đóa hồng hé nở (Blooming Rose of Love - Hoa hồng tình yêu nở rộ)
// Mode 13: Chạy chữ 360° lãng mạn (Romantic Cylinder Marquee)
// Mode 14: Nắng đẹp & bầu trời 360° (Clear Sky & Sunbeams / Moon & Stars)
// Mode 15: Mưa rơi & vũng nước bắn sóng 360° (Rain & Puddle Ripples)
// Mode 16: Mây trôi đa tầng 360° (Volumetric Clouds)
// Mode 17: Giông bão sấm chớp 360° (Thunderstorm & Lightning)
#define DEFAULT_MODE          1      // Khởi động mặc định vào Mode 1: Nhịp đập trái tim
#define TOTAL_MODES           18     // Mode 0..17 (18 chế độ hiển thị & thời tiết 360°)

// ============================================================================
// 6. CẤU HÌNH QUẢN LÝ NGUỒN & SẠC PIN (MODULE IP2312)
// ============================================================================
#define ENABLE_BATTERY_MONITOR true  // Bật giám sát trạng thái sạc pin
#define PIN_VBUS              2      // Tuyến phát hiện cắm nguồn 5V USB (GPIO 2 - ADC1_CH2 trên ESP32-C3)
#define PIN_CHG_STAT          3      // Tuyến phát hiện LED đỏ IP2312 (GPIO 3 - ADC1_CH3 trên ESP32-C3)
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
// 9. CẤU HÌNH TÍNH NĂNG VẬT LÝ ESP32 (ĐÈN NẰM NGANG & ĐÈN PIN KHẨN CẤP)
// ============================================================================
#define HORIZONTAL_TILT_DEG       60.0f              // Góc nghiêng tối thiểu để nhận diện đèn xoay ngang (>= 60 độ)
#define HORIZONTAL_UPRIGHT_DEG    30.0f              // Góc để coi là đã dựng đứng lại (<= 30 độ)
#define HORIZONTAL_HOLD_TRIGGER_MS 350UL             // Giữ nằm ngang ổn định liên tục >= 350ms để kích hoạt ánh sáng vàng
#define EMERGENCY_TIMEOUT_MS      60000UL            // 60 giây đèn pin khẩn cấp
#define SHAKE_JERK_THRESHOLD      2.4f               // Ngưỡng gia tốc lắc mạnh (g)

// ============================================================================
// 10. CẤU HÌNH OTA BLUETOOTH BLE
// ============================================================================
#define OTA_HOLD_TRIGGER_MS       3000   // Giữ nút không di chuyển >= 3000ms để vào OTA
#define OTA_STILL_ACCEL_DELTA     1800   // Ngưỡng gia tốc MPU tối đa coi là đứng yên (~0.11g)
