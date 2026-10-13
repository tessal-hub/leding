#pragma once
#include <Arduino.h>
#include "config.h"

// Mode 8: Hiệu ứng kiểm tra chiều đi dây từng bóng từ LED 0 -> 255 (Calibration Test)
void effectLedOrderTest(uint16_t stepDelayMs = 35);

// ============================================================================
// CÁC CHẾ ĐỘ HIỂN THỊ CHÍNH (TUYỂN CHỌN CHO HỘP VUÔNG 4 MẶT 360°)
// ============================================================================
// Mode 1: Đèn dung nham 3D Noise mượt mà bao quanh 4 mặt (Lava Noise - Mặc định)
void effectLavaNoise(uint32_t durationMs);

// Mode 2: Đèn viền 4 cạnh góc hộp (Edge Light: 4 cạnh sáng thở nhẹ, nền tối cao cấp)
void effectEdgeLight(uint32_t durationMs);

// Mode 3: Sóng nhịp 4 mặt hộp (Face Wave: mỗi mặt 1 nhịp lệch pha, chuyển màu mượt)
void effectFaceWave(uint32_t durationMs);

// Mode 4: Đèn nghiêng dính theo chiều nghiêng & bừng sáng khi lắc MPU (Tilt Glow)
void effectTiltGlow(uint32_t durationMs);

// Mode 5: Mô phỏng nước chảy & vật lý sóng xô lệch MPU-6050 (Water Physics)
void effectWaterPhysics(uint32_t durationMs);

// Mode 6: Gradient hai màu thở chậm tối giản cam san hô - hồng tím (Night Breathing)
void effectBreathingGradient(uint32_t durationMs);

// Mode 7: Dải màu cực quang uốn lượn tím - cyan (Aurora Flow)
void effectAurora(uint32_t durationMs);

// Mode 10: Chế độ màu tĩnh tùy chọn theo App (Static Color)
void effectStaticColor();

// Mode 11: Hiển thị 4 ô Bitmap tùy biến độc lập theo 4 mặt (4-Slot Custom Display)
void effectFourSlotsCustom(uint32_t durationMs = 60000);

// Mode 12: Đồng hồ để bàn 360 độ kỹ thuật số & thời tiết (Desk Clock)
void effectDeskClock(uint32_t durationMs = 60000);

// Mode 13: Nhịp đập trái tim lãng mạn (Heartbeat Pulse)
void effectHeartbeatPulse(uint32_t durationMs);

// Mode 14: Sóng tình yêu lan tỏa (Love Ripple)
void effectLoveRipple(uint32_t durationMs);

// Đồng bộ và đọc thời gian hệ thống RTC (Frame 0x05)
void syncDeviceTime(uint8_t hour, uint8_t minute, uint8_t second);
void getDeviceTime(uint8_t &hour, uint8_t &minute, uint8_t &second);

// ============================================================================
// HIỆU ỨNG TRẠNG THÁI NGUỒN SẠC & TIỆN ÍCH HỆ THỐNG
// ============================================================================
// Đang sạc: Dâng mực màu & nhịp thở cam ấm báo nạp năng lượng (IP2312)
void effectChargingAmber(uint32_t durationMs = 0);

// Sạc đầy: Đèn xanh lá tĩnh dịu mắt báo pin đầy 100%
void effectChargeFullGreen(uint32_t durationMs = 0);

// Chạy chữ 360 độ quanh trụ ma trận LED (mặc định 75ms)
void effectCylinderScrollText(const char* text, uint32_t color, uint16_t speedDelay = 75);

// Chớp báo thông báo hệ thống (Zalo, Messenger, Call) - Frame 0x03
void triggerNotificationFlash(uint8_t r, uint8_t g, uint8_t b, uint8_t count = 3, uint16_t delayMs = 150);

// Đồng hồ Pomodoro vật lý: Lật hộp -> Đếm lùi 25p, dải LED hạ dần
void effectPomodoro(uint32_t workDurationMs = POMODORO_WORK_MS);

// Đèn pin khẩn cấp: Lắc 3 lần -> Sáng trắng 100% soi đường 60s, đặt xuống bàn tự tắt
void effectEmergencyFlashlight(uint32_t durationMs = EMERGENCY_TIMEOUT_MS);

// Cập nhật thông báo thời tiết từ App (Frame 0x04)
void triggerWeatherUpdate(int8_t temp, uint8_t weatherId);
