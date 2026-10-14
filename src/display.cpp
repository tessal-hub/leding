#include "display.h"
#include "uart_debug.h"

// Khởi tạo đối tượng NeoPixel
Adafruit_NeoPixel strip(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);

// Bảng tra cứu trước tọa độ 32x8 Look-Up Table (LUT) tối ưu hóa tối đa cho nhân 240MHz
static uint16_t xyTable[MATRIX_WIDTH][MATRIX_HEIGHT];
static bool xyTableReady = false;

static void precomputeXyTable() {
  for (int16_t x = 0; x < MATRIX_WIDTH; x++) {
    for (int16_t y = 0; y < MATRIX_HEIGHT; y++) {
      uint8_t panel = x / PANEL_WIDTH;      // Tấm 0, 1, 2 hoặc 3
      uint8_t localX = x % PANEL_WIDTH;     // Cột 0..7 trong tấm
      uint8_t localY = y;                   // Hàng 0..7

#if INVERT_PANEL_X
      localX = (PANEL_WIDTH - 1) - localX;
#endif

#if INVERT_PANEL_Y
      localY = (PANEL_HEIGHT - 1) - localY;
#endif

#if SWAP_ROW_COL
      uint8_t px = localY;
      uint8_t py = localX;
#else
      uint8_t px = localX;
      uint8_t py = localY;
#endif

      uint16_t localIndex;
#if MATRIX_SERPENTINE
      if (py % 2 == 1) {
        localIndex = (py * PANEL_WIDTH) + (PANEL_WIDTH - 1 - px);
      } else {
        localIndex = (py * PANEL_WIDTH) + px;
      }
#else
      localIndex = (py * PANEL_WIDTH) + px;
#endif
      xyTable[x][y] = (panel * 64) + localIndex;
    }
  }
  xyTableReady = true;
}

void initDisplay(uint8_t brightness) {
  precomputeXyTable();
  strip.begin();
  strip.setBrightness(toHardwareBrightness(brightness));
  strip.clear();
  strip.show();
  dbgLog(TAG_LED, "Khởi tạo Adafruit_NeoPixel: %d bóng (DIN: GPIO %d, Độ sáng: %d/255, HW: %d) [Look-Up Table: 1 CHU KỲ ĐỌC RAM]",
         NUM_LEDS, LED_PIN, brightness, toHardwareBrightness(brightness));
}

/**
 * Chuyển đổi tọa độ trụ (x, y) sang chỉ số bóng LED (0 -> 255) siêu tốc (1 chu kỳ đọc RAM)
 */
uint16_t XY(int16_t x, int16_t y) {
  if (y < 0 || y >= MATRIX_HEIGHT) return 0;
  if (!xyTableReady) precomputeXyTable();
  // Quấn chu vi 32 cột khép kín 360 độ (dùng bitwise mask & 31 vì 32 = 2^5)
  uint8_t wx = (uint8_t)(x & (MATRIX_WIDTH - 1));
  return xyTable[wx][y];
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

// Bảng tra cứu trước hàm Sin8 (256 bytes) cho 1 CPU Cycle (0..255 -> 0..255)
static const uint8_t sin8Table[256] PROGMEM = {
  128, 131, 134, 137, 140, 143, 146, 149, 152, 155, 158, 162, 165, 167, 170, 173,
  176, 179, 182, 185, 188, 190, 193, 196, 198, 201, 203, 206, 208, 211, 213, 215,
  218, 220, 222, 224, 226, 228, 230, 232, 234, 235, 237, 238, 240, 241, 243, 244,
  245, 246, 248, 249, 250, 250, 251, 252, 253, 253, 254, 254, 254, 255, 255, 255,
  255, 255, 255, 255, 254, 254, 254, 253, 253, 252, 251, 250, 250, 249, 248, 246,
  245, 244, 243, 241, 240, 238, 237, 235, 234, 232, 230, 228, 226, 224, 222, 220,
  218, 215, 213, 211, 208, 206, 203, 201, 198, 196, 193, 190, 188, 185, 182, 179,
  176, 173, 170, 167, 165, 162, 158, 155, 152, 149, 146, 143, 140, 137, 134, 131,
  128, 124, 121, 118, 115, 112, 109, 106, 103, 100,  97,  93,  90,  88,  85,  82,
   79,  76,  73,  70,  67,  65,  62,  59,  57,  54,  52,  49,  47,  44,  42,  40,
   37,  35,  33,  31,  29,  27,  25,  23,  21,  20,  18,  17,  15,  14,  12,  11,
   10,   9,   7,   6,   5,   5,   4,   3,   2,   2,   1,   1,   1,   0,   0,   0,
    0,   0,   0,   0,   1,   1,   1,   2,   2,   3,   4,   5,   5,   6,   7,   9,
   10,  11,  12,  14,  15,  17,  18,  20,  21,  23,  25,  27,  29,  31,  33,  35,
   37,  40,  42,  44,  47,  49,  52,  54,  57,  59,  62,  65,  67,  70,  73,  76,
   79,  82,  85,  88,  90,  93,  97, 100, 103, 106, 109, 112, 115, 118, 121, 124
};

uint8_t fastSin8(uint8_t angle) {
  return pgm_read_byte(&sin8Table[angle]);
}

uint32_t lerpColor8(uint32_t a, uint32_t b, uint8_t t8) {
  uint16_t inv = 255 - t8;
  uint8_t ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
  uint8_t br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
  uint8_t r = (uint8_t)((ar * inv + br * t8) >> 8);
  uint8_t g = (uint8_t)((ag * inv + bg * t8) >> 8);
  uint8_t b_out = (uint8_t)((ab * inv + bb * t8) >> 8);
  return ((uint32_t)r << 16) | ((uint32_t)g << 8) | b_out;
}

uint32_t scaleColor8(uint32_t c, uint8_t k8) {
  uint8_t r = (uint8_t)(((c >> 16) & 0xFF) * k8 >> 8);
  uint8_t g = (uint8_t)(((c >> 8) & 0xFF) * k8 >> 8);
  uint8_t b = (uint8_t)((c & 0xFF) * k8 >> 8);
  return ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
}

uint32_t lerpColor(uint32_t a, uint32_t b, float t) {
  if (t <= 0.0f) return a;
  if (t >= 1.0f) return b;
  return lerpColor8(a, b, (uint8_t)(t * 255.0f));
}

uint32_t scaleColor(uint32_t c, float k) {
  if (k <= 0.0f) return 0;
  if (k >= 1.0f) return c;
  return scaleColor8(c, (uint8_t)(k * 255.0f));
}

void fadeTransition(uint16_t durationMs) {
  uint8_t hwBri = strip.getBrightness(); // Giá trị này ĐÃ là độ sáng phần cứng (5..MAX_BRIGHTNESS)
  if (hwBri < 2) return;
  uint16_t stepDelay = durationMs / 12;
  if (stepDelay < 4) stepDelay = 4;

  // Snapshot RAM pixel: setBrightness() rescale mất mát (lossy) trực tiếp trên
  // buffer, nên mỗi bước fade phải dựng lại từ bản gốc thay vì chồng lên nhau.
  static uint8_t fadeSnap[NUM_LEDS * 3];
  uint8_t* pix = strip.getPixels();
  uint16_t nBytes = strip.numPixels() * 3; // GRB, không kênh trắng
  if (pix == NULL || nBytes > sizeof(fadeSnap)) return;
  memcpy(fadeSnap, pix, nBytes);

  for (int step = 6; step >= 0; step--) {
    memcpy(pix, fadeSnap, nBytes);
    strip.setBrightness((hwBri * step) / 6);
    strip.show();
    delay(stepDelay);
  }
  memcpy(pix, fadeSnap, nBytes); // Trả buffer gốc trước khi clear để lần sau không fade từ data rác
  strip.clear();
  strip.show();
  strip.setBrightness(hwBri); // Khôi phục đúng độ sáng phần cứng ban đầu
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

// ============================================================================
// QUẢN LÝ 4 Ô BITMAP / PIXEL ART ĐỘC LẬP (MỖI Ô 8x8 = 64 PIXELS)
// ============================================================================
#include <Preferences.h>
static Preferences slotPrefs;
static bool slotsDirty = false;
static unsigned long lastSlotsChangeTime = 0;

static uint32_t slotPixels[NUM_SLOTS][SLOT_PIXELS_COUNT];

// Bộ Icon Preset 8x8 nhúng sẵn
static const uint8_t PRESET_NUM1[8] PROGMEM = { 0x18, 0x38, 0x18, 0x18, 0x18, 0x18, 0x7E, 0x00 };
static const uint8_t PRESET_NUM2[8] PROGMEM = { 0x3C, 0x66, 0x06, 0x0C, 0x18, 0x30, 0x7E, 0x00 };
static const uint8_t PRESET_NUM3[8] PROGMEM = { 0x3C, 0x66, 0x06, 0x1C, 0x06, 0x66, 0x3C, 0x00 };
static const uint8_t PRESET_NUM4[8] PROGMEM = { 0x0C, 0x1C, 0x2C, 0x4C, 0x7E, 0x0C, 0x0C, 0x00 };
static const uint8_t PRESET_HEART[8] PROGMEM = { 0x00, 0x66, 0xFF, 0xFF, 0xFF, 0x7E, 0x3C, 0x18 };
static const uint8_t PRESET_SMILE[8] PROGMEM = { 0x3C, 0x42, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C };
static const uint8_t PRESET_STAR[8] PROGMEM = { 0x18, 0x18, 0xFF, 0x7E, 0x3C, 0x66, 0xC3, 0x00 };
static const uint8_t PRESET_BOLT[8] PROGMEM = { 0x0C, 0x18, 0x30, 0x7E, 0x0C, 0x18, 0x20, 0x40 };
static const uint8_t PRESET_FIRE[8] PROGMEM = { 0x10, 0x30, 0x78, 0x7C, 0xFE, 0xFE, 0x7C, 0x38 };
static const uint8_t PRESET_MUSIC[8] PROGMEM = { 0x0E, 0x0A, 0x0A, 0x0A, 0x3A, 0x7A, 0x72, 0x00 };
static const uint8_t PRESET_GHOST[8] PROGMEM = { 0x3C, 0x7E, 0xAB, 0xFF, 0xFF, 0xFF, 0xB5, 0x00 };
static const uint8_t PRESET_PACMAN[8] PROGMEM = { 0x3C, 0x7E, 0xFF, 0xF8, 0xF0, 0xF8, 0x7E, 0x3C };
// Frame 2 cho Animation đa khung hình (Heart beat, Pacman chomp, Fire flame, Star twinkle, Ghost legs)
static const uint8_t PRESET_HEART_F2[8] PROGMEM  = { 0x00, 0x00, 0x24, 0x7E, 0x7E, 0x3C, 0x18, 0x00 }; // Tim thu nhỏ (nhịp đập)
static const uint8_t PRESET_PACMAN_F2[8] PROGMEM = { 0x3C, 0x7E, 0xFF, 0xFF, 0xFF, 0xFF, 0x7E, 0x3C }; // Pacman khép miệng
static const uint8_t PRESET_FIRE_F2[8] PROGMEM   = { 0x08, 0x18, 0x3C, 0x7E, 0xFF, 0xFE, 0x78, 0x30 }; // Ngọn lửa bập bùng
static const uint8_t PRESET_STAR_F2[8] PROGMEM   = { 0x00, 0x18, 0x7E, 0x3C, 0x3C, 0x7E, 0x18, 0x00 }; // Sao tỏa sáng / co lại
static const uint8_t PRESET_GHOST_F2[8] PROGMEM  = { 0x3C, 0x7E, 0xAB, 0xFF, 0xFF, 0xFF, 0x5B, 0x00 }; // Chân ma lượn sóng

static uint8_t slotPresetType[NUM_SLOTS] = { 1, 2, 3, 4 };
static uint32_t slotPresetColor[NUM_SLOTS] = { 0, 0, 0, 0 };

static void initDefaultSlots() {
  // Ô 1: Số 1 (Cyan)
  setSlotBitmap1Bit(0, 0, 220, 255, PRESET_NUM1);
  slotPresetType[0] = 1; slotPresetColor[0] = strip.Color(0, 220, 255);
  // Ô 2: Số 2 (Vàng ấm)
  setSlotBitmap1Bit(1, 255, 180, 20, PRESET_NUM2);
  slotPresetType[1] = 2; slotPresetColor[1] = strip.Color(255, 180, 20);
  // Ô 3: Số 3 (Xanh lá)
  setSlotBitmap1Bit(2, 20, 240, 100, PRESET_NUM3);
  slotPresetType[2] = 3; slotPresetColor[2] = strip.Color(20, 240, 100);
  // Ô 4: Số 4 (Hồng tím)
  setSlotBitmap1Bit(3, 255, 60, 180, PRESET_NUM4);
  slotPresetType[3] = 4; slotPresetColor[3] = strip.Color(255, 60, 180);
}

void markSlotsDirty() {
  slotsDirty = true;
  lastSlotsChangeTime = millis();
}

void processDeferredSlotsSave() {
  if (slotsDirty && (millis() - lastSlotsChangeTime >= 1500)) {
    saveSlotsToFlash();
  }
}

void saveSlotsToFlash() {
  slotsDirty = false;
  slotPrefs.begin("matrix_slots", false);
  slotPrefs.putBytes("pixels", slotPixels, sizeof(slotPixels));
  slotPrefs.putBytes("ptype", slotPresetType, sizeof(slotPresetType));
  slotPrefs.putBytes("pcol", slotPresetColor, sizeof(slotPresetColor));
  slotPrefs.end();
  dbgLog(TAG_FLASH, "Đã lưu 4 Ô Bitmap (1024 bytes) + preset type/color vào Flash NVS!");
}

void loadSlotsFromFlash() {
  slotPrefs.begin("matrix_slots", true);
  size_t readLen = slotPrefs.getBytes("pixels", slotPixels, sizeof(slotPixels));
  // Thiết bị đã nâng cấp từ firmware cũ (chưa có ptype/pcol): giữ type mặc định, màu 0
  if (slotPrefs.getBytes("ptype", slotPresetType, sizeof(slotPresetType)) != sizeof(slotPresetType)) {
    slotPresetType[0] = 1; slotPresetType[1] = 2; slotPresetType[2] = 3; slotPresetType[3] = 4;
  }
  if (slotPrefs.getBytes("pcol", slotPresetColor, sizeof(slotPresetColor)) != sizeof(slotPresetColor)) {
    memset(slotPresetColor, 0, sizeof(slotPresetColor));
  }
  slotPrefs.end();
  if (readLen == sizeof(slotPixels)) {
    dbgLog(TAG_FLASH, "Đã khôi phục 4 Ô Bitmap tùy biến từ Flash NVS!");
  } else {
    dbgLog(TAG_FLASH, "Chưa có dữ liệu 4 Ô trong Flash, nạp mặc định [1, 2, 3, 4]...");
    initDefaultSlots();
    saveSlotsToFlash();
  }
}

void initSlots() {
  loadSlotsFromFlash();
}

void setSlotBitmap1Bit(uint8_t slot, uint8_t r, uint8_t g, uint8_t b, const uint8_t* rows) {
  if (slot >= NUM_SLOTS || rows == NULL) return;
  slotPresetType[slot] = 0;
  slotPresetColor[slot] = strip.Color(r, g, b);
  uint32_t color = slotPresetColor[slot];

  // Nhận diện đầy đủ cả 12 preset (tĩnh + động) để lưu đúng typeId và tránh nhấp nháy shimmer sai
  struct PresetMap { const uint8_t* bmp; uint8_t typeId; };
  static const PresetMap presetMap[] = {
    { PRESET_NUM1,   1  },
    { PRESET_NUM2,   2  },
    { PRESET_NUM3,   3  },
    { PRESET_NUM4,   4  },
    { PRESET_HEART,  5  },
    { PRESET_SMILE,  6  },
    { PRESET_STAR,   7  },
    { PRESET_BOLT,   8  },
    { PRESET_FIRE,   9  },
    { PRESET_MUSIC,  10 },
    { PRESET_GHOST,  11 },
    { PRESET_PACMAN, 12 }
  };
  for (const auto& item : presetMap) {
    if (memcmp_P(rows, item.bmp, 8) == 0) {
      slotPresetType[slot] = item.typeId;
      break;
    }
  }

  for (uint8_t y = 0; y < 8; y++) {
    uint8_t rowVal = rows[y];
    for (uint8_t x = 0; x < 8; x++) {
      bool on = (rowVal >> (7 - x)) & 0x01;
      slotPixels[slot][y * 8 + x] = on ? color : 0;
    }
  }
}

void setSlotPixel(uint8_t slot, uint8_t x, uint8_t y, uint32_t color) {
  if (slot >= NUM_SLOTS || x >= 8 || y >= 8) return;
  slotPixels[slot][y * 8 + x] = color;
  slotPresetType[slot] = 0; // Đánh dấu là pixel tùy chỉnh
}

uint32_t getSlotPixel(uint8_t slot, uint8_t x, uint8_t y) {
  if (slot >= NUM_SLOTS || x >= 8 || y >= 8) return 0;
  return slotPixels[slot][y * 8 + x];
}

void setSlotPixelsRGB565(uint8_t slot, uint8_t chunkIdx, const uint8_t* data, size_t len) {
  if (slot >= NUM_SLOTS || data == NULL) return;
  size_t startPx = chunkIdx * 16;
  size_t numPx = len / 2;
  slotPresetType[slot] = 0; // Đánh dấu là pixel tùy chỉnh
  for (size_t i = 0; i < numPx && (startPx + i) < SLOT_PIXELS_COUNT; i++) {
    uint16_t c565 = (uint16_t)data[i * 2] | ((uint16_t)data[i * 2 + 1] << 8);
    uint8_t r = ((c565 >> 11) & 0x1F) * 255 / 31;
    uint8_t g = ((c565 >> 5) & 0x3F) * 255 / 63;
    uint8_t b = (c565 & 0x1F) * 255 / 31;
    slotPixels[slot][startPx + i] = strip.Color(r, g, b);
  }
}

void clearSlot(uint8_t slot) {
  if (slot >= NUM_SLOTS) return;
  for (int i = 0; i < SLOT_PIXELS_COUNT; i++) slotPixels[slot][i] = 0;
  slotPresetType[slot] = 0;
}

void clearAllSlots() {
  for (int s = 0; s < NUM_SLOTS; s++) clearSlot(s);
}

void renderFourSlotsToMatrix() {
  for (uint8_t s = 0; s < NUM_SLOTS; s++) {
    int16_t startX = s * PANEL_WIDTH;
    for (uint8_t y = 0; y < PANEL_HEIGHT; y++) {
      for (uint8_t x = 0; x < PANEL_WIDTH; x++) {
        setPixelXY(startX + x, y, slotPixels[s][y * PANEL_WIDTH + x]);
      }
    }
  }
}

void renderFourSlotsAnimated(uint8_t frameIdx) {
  for (uint8_t s = 0; s < NUM_SLOTS; s++) {
    int16_t startX = s * PANEL_WIDTH;
    uint8_t pType = slotPresetType[s];
    const uint8_t* f2Bmp = NULL;

    if (frameIdx % 2 == 1) {
      if (pType == 5) f2Bmp = PRESET_HEART_F2;
      else if (pType == 12) f2Bmp = PRESET_PACMAN_F2;
      else if (pType == 9) f2Bmp = PRESET_FIRE_F2;
      else if (pType == 7) f2Bmp = PRESET_STAR_F2;
      else if (pType == 11) f2Bmp = PRESET_GHOST_F2;
    }

    if (f2Bmp != NULL) {
      uint32_t col = slotPresetColor[s];
      for (uint8_t y = 0; y < 8; y++) {
        uint8_t rowVal = pgm_read_byte(&f2Bmp[y]);
        for (uint8_t x = 0; x < 8; x++) {
          bool on = (rowVal >> (7 - x)) & 0x01;
          setPixelXY(startX + x, y, on ? col : 0);
        }
      }
    } else {
      float shimmer = (frameIdx % 2 == 1) ? 0.82f : 1.0f;
      for (uint8_t y = 0; y < PANEL_HEIGHT; y++) {
        for (uint8_t x = 0; x < PANEL_WIDTH; x++) {
          uint32_t c = slotPixels[s][y * PANEL_WIDTH + x];
          if (c != 0 && shimmer < 1.0f && pType == 0) {
            c = scaleColor(c, shimmer);
          }
          setPixelXY(startX + x, y, c);
        }
      }
    }
  }
}

bool applySlotPreset(uint8_t slot, const char* presetName, uint8_t r, uint8_t g, uint8_t b) {
  if (slot >= NUM_SLOTS || presetName == NULL) return false;
  const uint8_t* bmp = NULL;
  uint8_t defR = 0, defG = 220, defB = 255;
  uint8_t typeId = 0;

  if (strcmp(presetName, "1") == 0 || strcasecmp(presetName, "num1") == 0) {
    bmp = PRESET_NUM1; defR = 0; defG = 220; defB = 255; typeId = 1;
  } else if (strcmp(presetName, "2") == 0 || strcasecmp(presetName, "num2") == 0) {
    bmp = PRESET_NUM2; defR = 255; defG = 180; defB = 20; typeId = 2;
  } else if (strcmp(presetName, "3") == 0 || strcasecmp(presetName, "num3") == 0) {
    bmp = PRESET_NUM3; defR = 20; defG = 240; defB = 100; typeId = 3;
  } else if (strcmp(presetName, "4") == 0 || strcasecmp(presetName, "num4") == 0) {
    bmp = PRESET_NUM4; defR = 255; defG = 60; defB = 180; typeId = 4;
  } else if (strcasecmp(presetName, "heart") == 0 || strcasecmp(presetName, "tim") == 0) {
    bmp = PRESET_HEART; defR = 255; defG = 20; defB = 60; typeId = 5;
  } else if (strcasecmp(presetName, "smile") == 0 || strcasecmp(presetName, "cuoi") == 0) {
    bmp = PRESET_SMILE; defR = 255; defG = 215; defB = 0; typeId = 6;
  } else if (strcasecmp(presetName, "star") == 0 || strcasecmp(presetName, "sao") == 0) {
    bmp = PRESET_STAR; defR = 255; defG = 165; defB = 0; typeId = 7;
  } else if (strcasecmp(presetName, "bolt") == 0 || strcasecmp(presetName, "sam") == 0) {
    bmp = PRESET_BOLT; defR = 255; defG = 255; defB = 0; typeId = 8;
  } else if (strcasecmp(presetName, "fire") == 0 || strcasecmp(presetName, "lua") == 0) {
    bmp = PRESET_FIRE; defR = 255; defG = 70; defB = 0; typeId = 9;
  } else if (strcasecmp(presetName, "music") == 0 || strcasecmp(presetName, "nhac") == 0) {
    bmp = PRESET_MUSIC; defR = 155; defG = 50; defB = 255; typeId = 10;
  } else if (strcasecmp(presetName, "ghost") == 0 || strcasecmp(presetName, "ma") == 0) {
    bmp = PRESET_GHOST; defR = 0; defG = 255; defB = 255; typeId = 11;
  } else if (strcasecmp(presetName, "pacman") == 0) {
    bmp = PRESET_PACMAN; defR = 255; defG = 230; defB = 0; typeId = 12;
  }

  if (bmp != NULL) {
    if (r == 0 && g == 0 && b == 0) {
      r = defR; g = defG; b = defB;
    }
    setSlotBitmap1Bit(slot, r, g, b, bmp);
    slotPresetType[slot] = typeId;
    slotPresetColor[slot] = strip.Color(r, g, b);
    return true;
  }
  return false;
}

// ============================================================================
// ĐỒNG HỒ ĐỂ BÀN 360° KỸ THUẬT SỐ & THỜI TIẾT (DESK CLOCK)
// ============================================================================
static const uint16_t DIGIT_3X5[10] = {
  0b111101101101111, // 0
  0b010110010010111, // 1
  0b111001111100111, // 2
  0b111001111001111, // 3
  0b101101111001001, // 4
  0b111100111001111, // 5
  0b111100111101111, // 6
  0b111001001010010, // 7
  0b111101111101111, // 8
  0b111101111001111  // 9
};

static void drawDigit3x5(int16_t startX, int16_t startY, uint8_t digit, uint32_t color) {
  if (digit > 9) digit = 0;
  uint16_t bits = DIGIT_3X5[digit];
  for (uint8_t r = 0; r < 5; r++) {
    for (uint8_t c = 0; c < 3; c++) {
      uint8_t shift = 14 - (r * 3 + c);
      if ((bits >> shift) & 0x01) {
        setPixelXY(startX + c, startY + r, color);
      }
    }
  }
}

void renderDeskClock(uint8_t hour, uint8_t minute, bool colonBlink, int8_t temp, bool showTemp) {
  strip.clear();
  uint8_t h1 = (hour / 10) % 10;
  uint8_t h2 = hour % 10;
  uint8_t m1 = (minute / 10) % 10;
  uint8_t m2 = minute % 10;

  const uint32_t colHour  = strip.Color(0, 220, 255);   // Cyan băng mát mắt
  const uint32_t colColon = strip.Color(255, 180, 20); // Vàng nắng nhấp nháy 1Hz
  const uint32_t colMin   = strip.Color(245, 245, 255);  // Trắng tuyết tinh khôi
  const uint32_t colTemp  = strip.Color(255, 140, 20); // Cam hổ phách cho nhiệt độ

  // Chuẩn bị ký tự nhiệt độ ("-5C".."99C", font 5x6 sẵn có)
  int t = constrain((int)temp, -9, 99);
  char tc1 = 0, tc2 = '0';
  bool twoChar = false;
  if (t < 0) { tc1 = '-'; tc2 = (char)('0' + (-t)); twoChar = true; }
  else if (t >= 10) { tc1 = (char)('0' + t / 10); tc2 = (char)('0' + t % 10); twoChar = true; }
  else { tc2 = (char)('0' + t); }

  // Hiển thị 2 mặt đối xứng 360° (Mặt trước: offset 0, Mặt sau: offset 16)
  for (int face = 0; face < 2; face++) {
    int16_t base = face * 16;

    if (showTemp) {
      if (twoChar) drawCharCylinder(base + 0, tc1, colTemp);
      drawCharCylinder(base + 5, tc2, colTemp);
      drawCharCylinder(base + 10, 'C', colTemp);
    } else {
      // Giờ (H1, H2)
      drawDigit3x5(base + 1, 1, h1, colHour);
      drawDigit3x5(base + 5, 1, h2, colHour);

      // Dấu hai chấm (Colon)
      if (colonBlink) {
        setPixelXY(base + 8, 2, colColon);
        setPixelXY(base + 8, 4, colColon);
      }

      // Phút (M1, M2)
      drawDigit3x5(base + 9, 1, m1, colMin);
      drawDigit3x5(base + 13, 1, m2, colMin);
    }

    // Dải đèn đế trang trí hàng 7
    for (int x = 0; x < 16; x++) {
      setPixelXY(base + x, 7, strip.Color(2, 8, 18));
    }
    // Điểm nhấn công nghệ góc trên
    setPixelXY(base + 0, 0, strip.Color(0, 40, 80));
    setPixelXY(base + 15, 0, strip.Color(0, 40, 80));
  }
}

