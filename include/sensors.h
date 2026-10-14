#pragma once
#include <Arduino.h>
#include "config.h"
#include "display.h"

// Biến trạng thái toàn cục của cảm biến
extern bool mpuFound;
extern uint8_t currentBrightness;
extern uint8_t currentSpeed;
extern volatile uint8_t currentMode;

// ============================================================================
// TRẠNG THÁI SẠC PIN IP2312 (VBUS GPIO 32 + CATHODE LED ĐỎ GPIO 33)
// ============================================================================
enum ChargeState {
  DISCHARGING = 0, // Đang dùng pin (rút cáp sạc 5V)
  CHARGING    = 1, // Đang sạc (VBUS HIGH, Cathode LED đỏ LOW)
  FULL        = 2  // Pin đầy (VBUS HIGH, Cathode LED đỏ HIGH)
};

extern ChargeState currentChargeState;
extern bool userOverrideCharging; // true: Người dùng bấm bật đèn khi đang cắm sạc
extern bool chgStatActiveLow;     // true: Active LOW, false: Active HIGH

// Đọc và cập nhật trạng thái sạc pin IP2312
ChargeState updateChargeState();
uint16_t getStatPinMilliVolts();
bool isStatPinHigh();
bool isVbusPlugged();

// Khởi tạo toàn bộ cảm biến (Touch, MPU-6050, Flash NVS)
void initSensors();

// Khởi tạo MPU-6050
void initMPU6050();

// Đọc thô gia tốc 3 trục từ MPU-6050
bool readMpuAccel(int16_t &ax, int16_t &ay, int16_t &az);

// Đọc gia tốc MPU-6050 và cập nhật độ sáng (chỉ khi đang giữ nút)
void updateMPUBrightness();

// Quét nút bấm: Nhấp nhanh đổi mode, Giữ nút chỉnh sáng MPU, Thả nút lưu Flash
bool checkButtonAndMPU();

// Hàm chờ delay có quét nút bấm ngắt tức thì (< 5ms)
bool delayWithTouch(uint16_t ms);

// Lưu cấu hình vào Flash NVS (ngay lập tức hoặc hoãn lại có debounce)
void saveSettingsToFlash();
void markSettingsChanged();
void processDeferredSettingsSave();

// Đọc cấu hình từ Flash NVS khi khởi động
void loadSettingsFromFlash();

// Lấy tên mô tả của chế độ
const char* getModeName(uint8_t mode);

// ============================================================================
// CỬ CHỈ VẬT LÝ MPU-6050 (POMODORO & ĐÈN PIN KHẨN CẤP)
// ============================================================================
extern bool isPomodoroActive;
extern bool isEmergencyActive;
extern bool pendingEmergencyFlashlight;
extern bool pendingPomodoroTrigger;

bool checkEmergencyShake();       // Nhấc hộp lên lắc mạnh 3 lần -> Đèn pin khẩn cấp
bool checkPomodoroOrientation();   // Kiểm tra hộp đang nằm nghiêng (> 60 độ)
bool checkPomodoroTrigger();       // Phát hiện cạnh lật hộp từ đứng sang nghiêng
bool isLampStationaryOnTable();   // Đặt đèn nằm yên ổn định trên bàn

// ============================================================================
// CHẾ ĐỘ OTA FIRMWARE QUA BLUETOOTH BLE
// ============================================================================
extern bool isOtaModeActive;
void startBleOtaMode();
void exitBleOtaMode();
