#pragma once
#include <Arduino.h>
#include "config.h"

// ============================================================================
// CÁC CHẾ ĐỘ HIỂN THỊ CHÍNH (BỘ SƯU TẬP HIỆU ỨNG TÌNH YÊU & LÃNG MẠN 360°)
// ============================================================================
// Mode 1: Nhịp đập trái tim lãng mạn (Heartbeat Pulse - Mặc định)
void effectHeartbeatPulse(uint32_t durationMs = 60000);

// Mode 2: Pha Lê Thạch Anh Tím (Amethyst Crystal Dream - Cột đá quý lung linh 360°)
void effectAmethystCrystal(uint32_t durationMs = 60000);

// Mode 3: Ánh nến hẹn hò lung linh (Candlelight Romance - Ngọn lửa ấm áp)
void effectCandlelight(uint32_t durationMs = 60000);

// Mode 4: Cử chỉ yêu thương MPU (Love Tilt & Caress - Ánh sáng âu yếm theo góc nghiêng)
void effectTiltGlow(uint32_t durationMs = 60000);

// Mode 5: Lửa ấm đêm đông (Cozy Fireplace Romance - Lò sưởi ấm áp tình nồng)
void effectCozyFireplace(uint32_t durationMs = 60000);

// Mode 6: Biển Hoa Oải Hương Tím (Lavender Twilight Breeze - Cánh đồng tím hoàng hôn)
void effectLavenderBreeze(uint32_t durationMs = 60000);

// Mode 7: Cực quang tình nhân (Lover's Aurora - Tím mộng mơ & hồng cánh sen)
void effectAurora(uint32_t durationMs = 60000);

// Mode 8: Bầu trời ngàn sao hẹn ước (Starry Night Romance - Sao băng ước nguyện)
void effectStarryNight(uint32_t durationMs = 60000);

// Mode 10: Chế độ màu tĩnh tùy chọn theo App (Static Color - phản ứng chuyển động MPU)
void effectStaticColor(uint32_t durationMs = 60000);

// Mode 11: 4 Khung hình kỷ niệm tình yêu (4-Slot Love Memories)
void effectFourSlotsCustom(uint32_t durationMs = 60000);

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

// Chạy chữ 360 độ quanh trụ ma trận LED (mặc định 125ms mượt mà)
void effectCylinderScrollText(const char* text, uint32_t color, uint16_t speedDelay = 125);

// Mode 13: Chạy chữ 360° lãng mạn liên tục quanh thân đèn (Romantic Cylinder Marquee)
void effectMarqueeMode(uint32_t durationMs = 60000);

// Cài đặt và truy xuất trạng thái chữ chạy 360°
void setMarqueeState(const char* text, uint32_t color = 0, uint16_t speedDelay = 125);
void getMarqueeState(char* text, size_t maxLen, uint32_t &color, uint16_t &speedDelay);

// Chớp báo thông báo hệ thống (Zalo, Messenger, Call) - Frame 0x03
void triggerNotificationFlash(uint8_t r, uint8_t g, uint8_t b, uint8_t count = 3, uint16_t delayMs = 150);

// Ánh sáng vàng thân thiện mắt khi đèn xoay ngang (dựng đứng tự thoát)
void effectHorizontalWarmLight(uint32_t maxDurationMs = 0);

// Đèn pin khẩn cấp: Lắc 3 lần -> Sáng trắng 100% soi đường 60s, đặt xuống bàn tự tắt
void effectEmergencyFlashlight(uint32_t durationMs = EMERGENCY_TIMEOUT_MS);

// Cập nhật thông báo thời tiết từ App (Frame 0x04)
void triggerWeatherUpdate(int8_t temp, uint8_t weatherId, bool switchMode = false);

// Mode 12: Đóa hồng hé nở (Blooming Rose of Love - Hoa hồng tình yêu nở rộ)
void effectBloomingRose(uint32_t durationMs = 60000);

// ============================================================================
// CÁC CHẾ ĐỘ THỜI TIẾT SỐNG ĐỘNG 360° (WEATHER SIMULATION MODES)
// ============================================================================
// Mode 14: Nắng đẹp & bầu trời 360° (Clear Sky & Sunbeams / Moon & Stars)
void effectWeatherClearSky(uint32_t durationMs = 60000);

// Mode 15: Mưa rơi & vũng nước bắn sóng 360° (Rain & Puddle Ripples)
void effectWeatherRain(uint32_t durationMs = 60000);

// Mode 16: Mây trôi đa tầng 360° (Volumetric Clouds)
void effectWeatherCloudy(uint32_t durationMs = 60000);

// Mode 17: Giông bão sấm chớp 360° (Thunderstorm & Lightning)
void effectWeatherStorm(uint32_t durationMs = 60000);

// Chạy animation thời tiết tương ứng theo currentWeatherId (cho Mode 0 tuần hoàn)
void effectWeatherLive(uint32_t durationMs = 60000);
