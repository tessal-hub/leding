#pragma once
#include <Arduino.h>
#include <Adafruit_NeoPixel.h>
#include "config.h"

// Đối tượng dải LED toàn cục
extern Adafruit_NeoPixel strip;

// Khởi tạo hiển thị
void initDisplay(uint8_t brightness);

// Chuyển đổi tọa độ 2D hình trụ (x, y) sang chỉ số LED trong chuỗi 256 bóng
uint16_t XY(int16_t x, int16_t y);

// Đặt màu tại tọa độ (x, y)
void setPixelXY(int16_t x, int16_t y, uint32_t color);

// Xóa toàn bộ ma trận (tắt hết LED)
void clearMatrix();

// Helper trộn màu (Linear Interpolation) và điều chỉnh độ sáng kênh RGB
uint32_t lerpColor(uint32_t a, uint32_t b, float t);
uint32_t scaleColor(uint32_t c, float k);

// Hàm toán học và hòa sắc số nguyên siêu tốc (1 CPU Cycle)
uint8_t fastSin8(uint8_t angle);
uint32_t lerpColor8(uint32_t a, uint32_t b, uint8_t t8);
uint32_t scaleColor8(uint32_t c, uint8_t k8);

// Vẽ 1 ký tự font 5x7 lên vòng trụ 360 độ
void drawCharCylinder(int16_t startX, char c, uint32_t color);

// Quản lý màu tĩnh người dùng chọn từ App (Custom Static Color)
void setCustomStaticColor(uint8_t r, uint8_t g, uint8_t b);
void getCustomStaticColor(uint8_t &r, uint8_t &g, uint8_t &b);

// Hiển thị thanh tiến trình nạp firmware OTA qua Bluetooth
void displayOtaProgress(uint8_t percent, bool error = false);

// ============================================================================
// QUẢN LÝ 4 Ô BITMAP / PIXEL ART ĐỘC LẬP (MỖI Ô 8x8, TƯƠNG ỨNG 4 MẶT 1, 2, 3, 4)
// ============================================================================
#define NUM_SLOTS 4
#define SLOT_PIXELS_COUNT 64

void initSlots();
void loadSlotsFromFlash();
void saveSlotsToFlash();
void markSlotsDirty();
void processDeferredSlotsSave();

// Thiết lập dữ liệu ô (slot: 0..3)
void setSlotBitmap1Bit(uint8_t slot, uint8_t r, uint8_t g, uint8_t b, const uint8_t* rows);
void setSlotPixel(uint8_t slot, uint8_t x, uint8_t y, uint32_t color);
void setSlotPixelsRGB565(uint8_t slot, uint8_t chunkIdx, const uint8_t* data, size_t len);
void clearSlot(uint8_t slot);
void clearAllSlots();
uint32_t getSlotPixel(uint8_t slot, uint8_t x, uint8_t y);

// Chuyển cảnh mượt mà giữa các mode (Cross-fade Transition)
void fadeTransition(uint16_t durationMs = 240);

// Hiển thị đồng hồ để bàn 360 độ (Desk Clock)
void renderDeskClock(uint8_t hour, uint8_t minute, bool colonBlink, int8_t temp = 25);

// Vẽ dữ liệu 4 ô lên ma trận LED (có hỗ trợ Animation đa khung hình)
void renderFourSlotsToMatrix();
void renderFourSlotsAnimated(uint8_t frameIdx);

// Áp dụng Preset icon sẵn có vào ô (heart, smile, star, bolt, fire, music, ghost, pacman, 1, 2, 3, 4)
bool applySlotPreset(uint8_t slot, const char* presetName, uint8_t r = 0, uint8_t g = 0, uint8_t b = 0);
