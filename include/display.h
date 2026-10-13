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

// Vẽ 1 ký tự font 5x7 lên vòng trụ 360 độ
void drawCharCylinder(int16_t startX, char c, uint32_t color);

// Quản lý màu tĩnh người dùng chọn từ App (Custom Static Color)
void setCustomStaticColor(uint8_t r, uint8_t g, uint8_t b);
void getCustomStaticColor(uint8_t &r, uint8_t &g, uint8_t &b);

// Hiển thị thanh tiến trình nạp firmware OTA qua Bluetooth
void displayOtaProgress(uint8_t percent, bool error = false);
