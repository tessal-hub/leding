#include "display.h"
#include "uart_debug.h"

// Khởi tạo đối tượng NeoPixel
Adafruit_NeoPixel strip(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);

void initDisplay(uint8_t brightness) {
  strip.begin();
  strip.setBrightness(brightness);
  strip.clear();
  strip.show();
  dbgLog(TAG_LED, "Khởi tạo Adafruit_NeoPixel: %d bóng (DIN: GPIO %d, Độ sáng: %d/255)",
         NUM_LEDS, LED_PIN, brightness);
}

/**
 * Chuyển đổi tọa độ trụ (x, y) sang chỉ số bóng LED (0 -> 255)
 * - Tự động quấn vòng 360 độ quanh chu vi 32 cột (cột 31 nối liền cột 0)
 * - Áp dụng cấu hình SWAP_ROW_COL và đảo chiều trục
 */
uint16_t XY(int16_t x, int16_t y) {
  if (y < 0 || y >= MATRIX_HEIGHT) return 0;

  // Quấn vòng tròn chu vi 32 cột khép kín 360 độ
  x = (x % MATRIX_WIDTH + MATRIX_WIDTH) % MATRIX_WIDTH;

  uint8_t panel = x / PANEL_WIDTH;      // Tấm 0, 1, 2 hoặc 3
  uint8_t localX = x % PANEL_WIDTH;     // Cột 0..7 trong tấm
  uint8_t localY = y;                   // Hàng 0..7

  // Đảo chiều trục ngang (Trái <-> Phải) hoặc trục dọc (Trên <-> Dưới) nếu cần
#if INVERT_PANEL_X
  localX = (PANEL_WIDTH - 1) - localX;
#endif

#if INVERT_PANEL_Y
  localY = (PANEL_HEIGHT - 1) - localY;
#endif

  // Áp dụng hoán đổi hàng thành cột nếu SWAP_ROW_COL = true
#if SWAP_ROW_COL
  uint8_t px = localY;
  uint8_t py = localX;
#else
  uint8_t px = localX;
  uint8_t py = localY;
#endif

  uint16_t localIndex;
#if MATRIX_SERPENTINE
  // Hàng chẵn: trái sang phải; Hàng lẻ: phải sang trái
  if (py % 2 == 1) {
    localIndex = (py * PANEL_WIDTH) + (PANEL_WIDTH - 1 - px);
  } else {
    localIndex = (py * PANEL_WIDTH) + px;
  }
#else
  localIndex = (py * PANEL_WIDTH) + px;
#endif

  return (panel * 64) + localIndex;
}

void setPixelXY(int16_t x, int16_t y, uint32_t color) {
  if (y >= 0 && y < MATRIX_HEIGHT) {
    strip.setPixelColor(XY(x, y), color);
  }
}

void clearMatrix() {
  strip.clear();
  strip.show();
}

uint32_t lerpColor(uint32_t a, uint32_t b, float t) {
  t = constrain(t, 0.0f, 1.0f);
  uint8_t ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
  uint8_t br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
  return strip.Color(ar + (br - ar) * t, ag + (bg - ag) * t, ab + (bb - ab) * t);
}

uint32_t scaleColor(uint32_t c, float k) {
  return lerpColor(0, c, k);
}

// ============================================================================
// BỘ FONT CHỮ 5x7 ASCII HỖ TRỢ CHỮ CHẠY VÒNG QUANH TRỤ 360°
// ============================================================================

struct FontChar {
  char c;
  uint8_t cols[5];
};

// Bảng font 5x6: Chiều cao 6 pixel, dành riêng cho khung 6x8 (bỏ trống hàng 0 và 7)
const FontChar PROGMEM FONT_5X6[] = {
  {' ', {0x00, 0x00, 0x00, 0x00, 0x00}},
  {'!', {0x00, 0x00, 0x2F, 0x00, 0x00}},
  {'-', {0x00, 0x08, 0x08, 0x08, 0x00}},
  {':', {0x00, 0x14, 0x14, 0x00, 0x00}},
  {'.', {0x00, 0x20, 0x20, 0x00, 0x00}},
  {'0', {0x1E, 0x21, 0x21, 0x21, 0x1E}},
  {'1', {0x00, 0x22, 0x3F, 0x20, 0x00}},
  {'2', {0x22, 0x31, 0x29, 0x25, 0x22}},
  {'3', {0x12, 0x21, 0x25, 0x25, 0x1A}},
  {'4', {0x0C, 0x0A, 0x09, 0x3F, 0x08}},
  {'5', {0x17, 0x25, 0x25, 0x25, 0x19}},
  {'6', {0x1E, 0x25, 0x25, 0x25, 0x18}},
  {'7', {0x01, 0x31, 0x09, 0x05, 0x03}},
  {'8', {0x1A, 0x25, 0x25, 0x25, 0x1A}},
  {'9', {0x06, 0x29, 0x29, 0x29, 0x1E}},
  {'A', {0x3E, 0x09, 0x09, 0x09, 0x3E}},
  {'B', {0x3F, 0x25, 0x25, 0x25, 0x1A}},
  {'C', {0x1E, 0x21, 0x21, 0x21, 0x12}},
  {'D', {0x3F, 0x21, 0x21, 0x12, 0x0C}},
  {'E', {0x3F, 0x25, 0x25, 0x25, 0x21}},
  {'F', {0x3F, 0x05, 0x05, 0x05, 0x01}},
  {'G', {0x1E, 0x21, 0x25, 0x25, 0x3A}},
  {'H', {0x3F, 0x04, 0x04, 0x04, 0x3F}},
  {'I', {0x00, 0x21, 0x3F, 0x21, 0x00}},
  {'J', {0x10, 0x20, 0x21, 0x1F, 0x01}},
  {'K', {0x3F, 0x04, 0x0A, 0x11, 0x20}},
  {'L', {0x3F, 0x20, 0x20, 0x20, 0x20}},
  {'M', {0x3F, 0x02, 0x0C, 0x02, 0x3F}},
  {'N', {0x3F, 0x02, 0x04, 0x08, 0x3F}},
  {'O', {0x1E, 0x21, 0x21, 0x21, 0x1E}},
  {'P', {0x3F, 0x09, 0x09, 0x09, 0x06}},
  {'Q', {0x1E, 0x21, 0x29, 0x11, 0x2E}},
  {'R', {0x3F, 0x09, 0x19, 0x29, 0x06}},
  {'S', {0x12, 0x25, 0x25, 0x25, 0x18}},
  {'T', {0x01, 0x01, 0x3F, 0x01, 0x01}},
  {'U', {0x1F, 0x20, 0x20, 0x20, 0x1F}},
  {'V', {0x0F, 0x10, 0x20, 0x10, 0x0F}},
  {'W', {0x3F, 0x10, 0x0C, 0x10, 0x3F}},
  {'X', {0x33, 0x0C, 0x0C, 0x33, 0x00}},
  {'Y', {0x07, 0x08, 0x30, 0x08, 0x07}},
  {'Z', {0x31, 0x29, 0x25, 0x23, 0x21}}
};
#define FONT_CHAR_COUNT (sizeof(FONT_5X6) / sizeof(FontChar))

static const uint8_t* getCharCols(char c) {
  if (c >= 'a' && c <= 'z') c -= 32;
  for (size_t i = 0; i < FONT_CHAR_COUNT; i++) {
    if (pgm_read_byte(&FONT_5X6[i].c) == c) {
      return FONT_5X6[i].cols;
    }
  }
  return FONT_5X6[0].cols;
}

void drawCharCylinder(int16_t startX, char c, uint32_t color) {
  const uint8_t* cols = getCharCols(c);
  for (uint8_t col = 0; col < 5; col++) {
    uint8_t colBits = pgm_read_byte(&cols[col]);
    for (uint8_t r = 0; r < 6; r++) {
      if ((colBits >> r) & 0x01) {
        // Hàng 0 và Hàng 7 để trống (bỏ 2 hàng trên và dưới cùng), vẽ ở hàng 1..6
        setPixelXY(startX + col, 1 + r, color);
      }
    }
  }
}

// Quản lý màu tĩnh được chọn từ App
static uint8_t customStaticR = 255;
static uint8_t customStaticG = 160;
static uint8_t customStaticB = 60;

void setCustomStaticColor(uint8_t r, uint8_t g, uint8_t b) {
  customStaticR = r;
  customStaticG = g;
  customStaticB = b;
}

void getCustomStaticColor(uint8_t &r, uint8_t &g, uint8_t &b) {
  r = customStaticR;
  g = customStaticG;
  b = customStaticB;
}

void displayOtaProgress(uint8_t percent, bool error) {
  strip.clear();
  if (error) {
    for (int i = 0; i < NUM_LEDS; i++) {
      strip.setPixelColor(i, strip.Color(255, 0, 0));
    }
  } else if (percent == 0) {
    // Chờ nhận OTA: 2 vòng trên và dưới phát sáng xanh dương dịu
    for (int x = 0; x < MATRIX_WIDTH; x++) {
      strip.setPixelColor(XY(x, 0), strip.Color(0, 120, 255));
      strip.setPixelColor(XY(x, MATRIX_HEIGHT - 1), strip.Color(0, 120, 255));
    }
  } else if (percent >= 100) {
    for (int i = 0; i < NUM_LEDS; i++) {
      strip.setPixelColor(i, strip.Color(0, 255, 0));
    }
  } else {
    // Đang nạp OTA: thanh tiến trình chạy theo 32 cột ma trận
    uint8_t activeCols = (percent * MATRIX_WIDTH) / 100;
    for (int x = 0; x < MATRIX_WIDTH; x++) {
      uint32_t c = (x <= activeCols) ? strip.Color(0, 220, 255) : strip.Color(10, 10, 20);
      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        strip.setPixelColor(XY(x, y), c);
      }
    }
  }
  strip.show();
}
