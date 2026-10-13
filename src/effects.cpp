#include "effects.h"
#include "display.h"
#include "sensors.h"
#include "uart_debug.h"
#include <Wire.h>

/**
 * ============================================================================
 * HIỆU ỨNG DÒ HƯỚNG ĐI DÂY LED 0 -> 255 (LED ORDER CALIBRATION TEST)
 * ============================================================================
 * Hiệu ứng này chạy tuần tự qua từng bóng LED theo chỉ số vật lý thực (index):
 * - Tấm 1: LED 0 -> 63    (Vệt màu Đỏ)
 * - Tấm 2: LED 64 -> 127   (Vệt màu Xanh Lá)
 * - Tấm 3: LED 128 -> 191  (Vệt màu Xanh Dương)
 * - Tấm 4: LED 192 -> 255  (Vệt màu Vàng)
 * 
 * Bóng LED hiện tại luôn sáng trắng rực rỡ để bạn nhìn rõ:
 * 1. Chân DIN bắt đầu từ góc nào của tấm 1?
 * 2. LED 1, 2, 3 đi ngang (Row) hay đi dọc (Col)?
 * 3. Hàng thứ 2 có quay đầu zic-zac (Serpentine) hay nhảy lại đầu hàng?
 */
void effectLedOrderTest(uint16_t stepDelayMs) {
  Serial.println("\n========================================================");
  Serial.println("  [CHẾ ĐỘ DÒ HƯỚNG ĐI DÂY LED VẬT LÝ 0 -> 255]");
  Serial.println("  Quan sát chấm sáng trắng để xác định hướng đi dây:");
  Serial.println("  - Tấm 1: LED 0   -> 63   (Đỏ)");
  Serial.println("  - Tấm 2: LED 64  -> 127  (Xanh Lá)");
  Serial.println("  - Tấm 3: LED 128 -> 191  (Xanh Dương)");
  Serial.println("  - Tấm 4: LED 192 -> 255  (Vàng)");
  Serial.println("========================================================");

  strip.clear();
  strip.show();

  for (int i = 0; i < NUM_LEDS; i++) {
    uint8_t panel = i / 64;
    uint8_t localIndex = i % 64;

    // Khi chuyển sang tấm mới: in thông báo Serial
    if (localIndex == 0) {
      Serial.printf(">>> [TẤM %d] Bắt đầu từ LED index %d -> %d <<<\n", panel + 1, i, i + 63);
    }

    // Đặt lại màu cho bóng vừa đi qua (chính xác theo màu của tấm chứa bóng đó)
    if (i > 0) {
      uint8_t prevPanel = (i - 1) / 64;
      uint32_t prevColor;
      if (prevPanel == 0)      prevColor = strip.Color(90, 0, 0);     // Đỏ (Tấm 1: 0..63)
      else if (prevPanel == 1) prevColor = strip.Color(0, 90, 0);     // Xanh lá (Tấm 2: 64..127)
      else if (prevPanel == 2) prevColor = strip.Color(0, 0, 90);     // Xanh dương (Tấm 3: 128..191)
      else                     prevColor = strip.Color(90, 90, 0);    // Vàng (Tấm 4: 192..255)
      strip.setPixelColor(i - 1, prevColor);
    }

    // Bóng LED hiện tại sáng trắng rực rỡ để quan sát
    strip.setPixelColor(i, strip.Color(255, 255, 255));
    strip.show();

    if (delayWithTouch(stepDelayMs)) return;
  }

  // Đặt lại màu cho bóng cuối cùng 255 của Tấm 4
  strip.setPixelColor(NUM_LEDS - 1, strip.Color(90, 90, 0));
  strip.show();

  // Kết thúc 1 chu kỳ: giữ sáng toàn bộ một lúc rồi chớp nhẹ
  if (delayWithTouch(600)) return;
  strip.clear();
  strip.show();
  if (delayWithTouch(200)) return;
}

// ============================================================================
// CÁC HÀM BỔ TRỢ TOÁN HỌC & NOISE (LIỀN MẠCH 360 ĐỘ QUANH HỘP VUÔNG)
// ============================================================================
static inline float smooth01(float t) {
  t = constrain(t, 0.0f, 1.0f);
  return t * t * (3.0f - 2.0f * t);
}

static uint8_t hash3(int x, int y, int z) {
  uint32_t h = (uint32_t)x * 374761393u + (uint32_t)y * 668265263u + (uint32_t)z * 2147483647u;
  h = (h ^ (h >> 13)) * 1274126177u;
  return (h ^ (h >> 16)) & 0xFF;
}

// period = số ô lưới theo X (để wrap liền mạch 4 mặt). x, y, t tính theo đơn vị ô lưới.
static float noise3(float x, float y, float t, int period) {
  int xi = floorf(x), yi = floorf(y), ti = floorf(t);
  float fx = smooth01(x - xi), fy = smooth01(y - yi), ft = smooth01(t - ti);

  auto h = [&](int a, int b, int c) {
    return hash3(((a % period) + period) % period, b, c) / 255.0f;
  };
  auto lerpf = [](float a, float b, float k) { return a + (b - a) * k; };

  float c00 = lerpf(h(xi, yi, ti),         h(xi + 1, yi, ti),         fx);
  float c10 = lerpf(h(xi, yi + 1, ti),     h(xi + 1, yi + 1, ti),     fx);
  float c01 = lerpf(h(xi, yi, ti + 1),     h(xi + 1, yi + 1, ti + 1), fx);
  float c11 = lerpf(h(xi, yi + 1, ti + 1), h(xi + 1, yi + 1, ti + 1), fx);

  return lerpf(lerpf(c00, c10, fy), lerpf(c01, c11, fy), ft);
}

// Palette 3 điểm: tối -> giữa -> sáng
static uint32_t palette3(float v, uint32_t c0, uint32_t c1, uint32_t c2) {
  v = constrain(v, 0.0f, 1.0f);
  return (v < 0.5f) ? lerpColor(c0, c1, v * 2.0f)
                    : lerpColor(c1, c2, (v - 0.5f) * 2.0f);
}

// ============================================================================
// 1. SÓNG NHỊP 4 MẶT HỘP (FACE WAVE - MỖI MẶT 1 NHỊP LỆCH PHA)
// ============================================================================
void effectFaceWave(uint32_t durationMs) {
  unsigned long start = millis();
  float t = 0.0f;
  const int FACES = 4;
  const float FACE_W = (float)MATRIX_WIDTH / FACES;

  const uint32_t pal[FACES] = {
    strip.Color(0, 200, 255),     // cyan
    strip.Color(120, 60, 255),    // tím
    strip.Color(255, 60, 160),    // hồng
    strip.Color(255, 150, 50)     // cam
  };

  while (millis() - start < durationMs) {
    for (int x = 0; x < MATRIX_WIDTH; x++) {
      float pos = (x + 0.5f) / FACE_W;            // 0..4, phần nguyên = số mặt
      int f0 = (int)pos % FACES;
      int f1 = (f0 + 1) % FACES;
      float k = smooth01(pos - floorf(pos));      // trộn màu mượt giữa 2 mặt kề nhau
      uint32_t base = lerpColor(pal[f0], pal[f1], k);

      // nhịp sáng lệch pha theo từng mặt (mỗi mặt chậm hơn mặt trước 1/4 chu kỳ)
      float wave = 0.5f + 0.5f * sinf(t - pos * (PI / 2.0f));
      float bri = 0.12f + 0.88f * wave * wave;

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        // làm sáng nhẹ ở giữa theo chiều dọc, tối dần mép trên/dưới
        float v = 1.0f - fabsf((y - (MATRIX_HEIGHT - 1) / 2.0f) / (MATRIX_HEIGHT / 2.0f)) * 0.35f;
        setPixelXY(x, y, strip.gamma32(scaleColor(base, bri * v)));
      }
    }
    strip.show();
    t += 0.045f;
    if (delayWithTouch(25)) return;
  }
}

// ============================================================================
// 2. ĐÈN VIỀN 4 CẠNH GÓC HỘP (EDGE LIGHT - TỐI GIẢN CAO CẤP)
// ============================================================================
void effectEdgeLight(uint32_t durationMs) {
  unsigned long start = millis();
  const uint32_t edgeCol = strip.Color(160, 200, 255);   // trắng lạnh
  const uint32_t fillCol = strip.Color(10, 0, 40);       // nền tím rất tối

  while (millis() - start < durationMs) {
    float t = (millis() - start) / 1000.0f;
    float breath = 0.5f + 0.5f * sinf(t * 1.1f);
    breath = 0.25f + 0.75f * breath * breath;

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      // khoảng cách tới cạnh gần nhất (cạnh nằm ở x = 0, 8, 16, 24; wrap quanh hộp)
      float local = fmodf(x + 0.5f, MATRIX_WIDTH / 4.0f);              // 0..8 trong một mặt
      float d = fminf(local, (MATRIX_WIDTH / 4.0f) - local);           // 0 tại cạnh
      float glow = expf(-d * 0.9f);                                    // vệt sáng tắt dần vào trong mặt

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        // nhịp chạy dọc theo cạnh, lệch pha theo từng cạnh
        int edgeIdx = (int)((x + 0.5f) / (MATRIX_WIDTH / 4.0f + 0.0001f) + 0.5f) % 4;
        float run = 0.65f + 0.35f * sinf(t * 2.0f + y * 0.5f + edgeIdx * 1.57f);
        uint32_t c = lerpColor(fillCol, scaleColor(edgeCol, breath * run), glow);
        setPixelXY(x, y, strip.gamma32(c));
      }
    }
    strip.show();
    if (delayWithTouch(25)) return;
  }
}

// ============================================================================
// 3. ĐÈN DUNG NHAM 3D NOISE LIỀN MẠCH (LAVA NOISE - 4 BLOB TRÊN 4 MẶT)
// ============================================================================
void effectLavaNoise(uint32_t durationMs) {
  unsigned long start = millis();
  float t = 0.0f;
  const int CELLS = 4;  // 4 ô tương ứng 4 mặt của hộp vuông

  const uint32_t c0 = strip.Color(5, 0, 25);       // gần đen tím
  const uint32_t c1 = strip.Color(150, 20, 140);   // magenta
  const uint32_t c2 = strip.Color(255, 140, 60);   // cam ấm

  while (millis() - start < durationMs) {
    for (int y = 0; y < MATRIX_HEIGHT; y++) {
      for (int x = 0; x < MATRIX_WIDTH; x++) {
        float nx = (float)x * CELLS / MATRIX_WIDTH;
        float n1 = noise3(nx,        y * 0.35f,        t,        CELLS);
        float n2 = noise3(nx * 2.0f, y * 0.7f + 5.0f,  t * 1.6f, CELLS * 2);
        float n  = n1 * 0.7f + n2 * 0.3f;

        // đẩy tương phản để tạo "blob" rõ, nền tối rộng
        n = smooth01(constrain((n - 0.30f) * 1.9f, 0.0f, 1.0f));
        setPixelXY(x, y, strip.gamma32(palette3(n, c0, c1, c2)));
      }
    }
    strip.show();
    t += 0.018f;               // chậm = sang; nhanh = rối
    if (delayWithTouch(25)) return;
  }
}

// ============================================================================
// 4. ĐÈN NGHIÊNG DÍNH CHIỀU NGHIÊNG & BỪNG SÁNG KHI LẮC (TILT GLOW MPU)
// ============================================================================
void effectTiltGlow(uint32_t durationMs) {
  unsigned long start = millis();
  float fx = 0.0f, fy = 0.0f;      // gia tốc đã lọc (đơn vị g)
  float flash = 0.0f;              // độ sáng bùng khi lắc
  float prevMag = 1.0f;
  float t = 0.0f;

  const uint32_t cA = strip.Color(0, 200, 255);
  const uint32_t cB = strip.Color(160, 60, 255);

  while (millis() - start < durationMs) {
    int16_t ax, ay, az;
    if (readMpuAccel(ax, ay, az)) {
      float gx = ax / 16384.0f, gy = ay / 16384.0f, gz = az / 16384.0f;
      fx += (gx - fx) * 0.12f;     // low-pass: lọc mượt chống rung giật
      fy += (gy - fy) * 0.12f;

      float mag = sqrtf(gx * gx + gy * gy + gz * gz);
      if (fabsf(mag - prevMag) > 0.25f) flash = 1.0f;   // phát hiện lắc
      prevMag = mag;
    }
    flash *= 0.90f;

    float angle = atan2f(fy, fx);
    float tilt = constrain(sqrtf(fx * fx + fy * fy), 0.0f, 1.0f);

    for (int y = 0; y < MATRIX_HEIGHT; y++) {
      for (int x = 0; x < MATRIX_WIDTH; x++) {
        float a = (x * 2.0f * PI) / MATRIX_WIDTH;
        float facing = 0.5f + 0.5f * cosf(a - angle);            // 1 = hướng đang nghiêng tới
        float glow = powf(facing, 2.0f) * (0.25f + 0.75f * tilt); // powf 2.0f tối ưu cho 32 cột
        glow += flash * 0.6f;
        glow += 0.05f + 0.04f * sinf(t + x * 0.3f);                // nền "thở" rất nhẹ

        float mix = 0.5f + 0.5f * sinf(a * 0.5f - angle + y * 0.25f);
        setPixelXY(x, y, strip.gamma32(scaleColor(lerpColor(cA, cB, mix), constrain(glow, 0.0f, 1.0f))));
      }
    }
    strip.show();
    t += 0.05f;
    if (delayWithTouch(20)) return;
  }
}

// ============================================================================
// 5. DẢI CỰC QUANG UỐN LƯỢN (AURORA FLOW - TÍM & CYAN)
// ============================================================================
void effectAurora(uint32_t durationMs) {
  unsigned long start = millis();
  float t = 0.0f;
  const uint32_t cA = strip.Color(120, 40, 255);   // Tím sâu
  const uint32_t cB = strip.Color(0, 220, 200);    // Cyan ngọc bích

  while (millis() - start < durationMs) {
    for (int y = 0; y < MATRIX_HEIGHT; y++) {
      for (int x = 0; x < MATRIX_WIDTH; x++) {
        float a = (x * 2.0f * PI) / MATRIX_WIDTH;
        float mix = 0.5f + 0.5f * sin(a * 2.0f + t + y * 0.35f);
        float bri = 0.25f + 0.75f * (0.5f + 0.5f * sin(a * 3.0f - t * 1.3f + y * 0.6f));
        bri = bri * bri;  // Tăng tương phản, tạo nhiều khoảng đen sang trọng
        setPixelXY(x, y, strip.gamma32(scaleColor(lerpColor(cA, cB, mix), bri)));
      }
    }
    strip.show();
    t += 0.035f;
    if (delayWithTouch(25)) return;
  }
}

// ============================================================================
// 6. NHỊP THỞ TỐI GIẢN (BREATHING GRADIENT - CAM SAN HÔ & HỒNG TÍM)
// ============================================================================
void effectBreathingGradient(uint32_t durationMs) {
  unsigned long start = millis();
  const uint32_t cA = strip.Color(255, 90, 60);    // Cam san hô
  const uint32_t cB = strip.Color(200, 40, 150);   // Hồng tím pastel

  while (millis() - start < durationMs) {
    float phase = (millis() - start) / 1000.0f;
    float breath = 0.5f + 0.5f * sin(phase * 1.2f); // Chu kỳ thở ~5s
    breath = 0.15f + 0.85f * breath * breath;

    for (int y = 0; y < MATRIX_HEIGHT; y++) {
      for (int x = 0; x < MATRIX_WIDTH; x++) {
        float a = (x * 2.0f * PI) / MATRIX_WIDTH;
        float g = 0.5f + 0.5f * sin(a + phase * 0.4f);
        setPixelXY(x, y, strip.gamma32(scaleColor(lerpColor(cA, cB, g), breath)));
      }
    }
    strip.show();
    if (delayWithTouch(20)) return;
  }
}

// ============================================================================
// CHẠY CHỮ 360° QUANH TRỤ: KHUNG CHỮ 6x8 (BỎ 2 HÀNG BIÊN TRÊN/DƯỚI)
// ============================================================================
void effectCylinderScrollText(const char* text, uint32_t color, uint16_t speedDelay) {
  if (text == NULL || strlen(text) == 0) return;
  size_t len = strlen(text);
  const uint8_t CHAR_WIDTH = 6; // Khung 6x8: 5 cột chữ + 1 cột khoảng cách
  int16_t totalPixelWidth = (int16_t)(len * CHAR_WIDTH);

  for (int16_t offset = MATRIX_WIDTH; offset > -totalPixelWidth; offset--) {
    strip.clear();
    for (size_t i = 0; i < len; i++) {
      int16_t charX = offset + (i * CHAR_WIDTH);
      if (charX > -CHAR_WIDTH && charX < MATRIX_WIDTH) {
        drawCharCylinder(charX, text[i], color);
      }
    }
    strip.show();
    if (delayWithTouch(speedDelay)) return;
  }
}

void effectWaterPhysics(uint32_t durationMs) {
  Serial.println("[Effect] Nước chảy & Vật lý sóng xô lệch MPU");
  unsigned long start = millis();

  float surfaceY[MATRIX_WIDTH];
  float velocity[MATRIX_WIDTH];
  for (int x = 0; x < MATRIX_WIDTH; x++) {
    surfaceY[x] = 4.0f;
    velocity[x] = 0.0f;
  }

  struct Droplet {
    bool active;
    float x;
    float y;
    float vy;
  };
  const int MAX_DROPLETS = 8;
  Droplet droplets[MAX_DROPLETS];
  for (int d = 0; d < MAX_DROPLETS; d++) droplets[d].active = false;

  int16_t prevAx = 0, prevAy = 0, prevAz = 16384;

  while (millis() - start < durationMs) {
    int16_t ax = 0, ay = 0, az = 16384;
    float jerk = 0.0f;

    if (readMpuAccel(ax, ay, az)) {
      jerk = (abs(ax - prevAx) + abs(ay - prevAy) + abs(az - prevAz)) / 16384.0f;
      prevAx = ax;
      prevAy = ay;
      prevAz = az;
    }

    float tiltAngle = atan2((float)ay, (float)ax);
    float tiltMag = constrain(sqrt((float)ax * ax + (float)ay * ay) / 16384.0f, 0.0f, 1.2f);

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      int left = (x - 1 + MATRIX_WIDTH) % MATRIX_WIDTH;
      int right = (x + 1) % MATRIX_WIDTH;

      float waveTension = 0.22f * (surfaceY[left] + surfaceY[right] - 2.0f * surfaceY[x]);
      float gravityRestore = 0.035f * (4.0f - surfaceY[x]);

      float colAngle = x * (2.0f * PI / MATRIX_WIDTH);
      float tiltForce = -sin(colAngle - tiltAngle) * tiltMag * 1.6f;

      velocity[x] += waveTension + gravityRestore + tiltForce;

      if (jerk > 0.25f) {
        float splashImpulse = min(jerk * 0.9f, 2.2f);
        velocity[x] += -sin(colAngle - tiltAngle) * splashImpulse + ((float)random(-40, 40) / 100.0f);
      }

      velocity[x] *= 0.93f;
    }

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      surfaceY[x] += velocity[x];
      surfaceY[x] = constrain(surfaceY[x], 0.6f, 6.8f);
    }

    if (jerk > 0.45f) {
      for (int d = 0; d < MAX_DROPLETS; d++) {
        if (!droplets[d].active && random(0, 10) < 4) {
          int splashCol = random(0, MATRIX_WIDTH);
          droplets[d].active = true;
          droplets[d].x = splashCol;
          droplets[d].y = surfaceY[splashCol] - 0.5f;
          droplets[d].vy = -((float)random(30, 80) / 100.0f);
          break;
        }
      }
    }

    strip.clear();

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      int waterTop = (int)round(surfaceY[x]);

      for (int y = MATRIX_HEIGHT - 1; y >= waterTop; y--) {
        uint32_t waterColor;

        if (y == waterTop) {
          if (abs(velocity[x]) > 0.15f || jerk > 0.3f) {
            waterColor = strip.Color(230, 255, 255);
          } else {
            waterColor = strip.Color(100, 220, 255);
          }
        } else if (y == waterTop + 1) {
          waterColor = strip.Color(0, 160, 255);
        } else {
          waterColor = strip.Color(0, 45, 220);
        }

        setPixelXY(x, y, waterColor);
      }
    }

    for (int d = 0; d < MAX_DROPLETS; d++) {
      if (droplets[d].active) {
        droplets[d].y += droplets[d].vy;
        droplets[d].vy += 0.08f;

        int dx = (int)round(droplets[d].x);
        int dy = (int)round(droplets[d].y);

        if (dy >= 0 && dy < MATRIX_HEIGHT && dy < (int)surfaceY[dx]) {
          setPixelXY(dx, dy, strip.Color(240, 255, 255));
        } else if (dy >= (int)surfaceY[dx]) {
          droplets[d].active = false;
        }
      }
    }

    strip.show();
    if (delayWithTouch(28)) return;
  }
}

// ============================================================================
// CHỚP BÁO THÔNG BÁO TỪ ĐIỆN THOẠI (ZALO, MESSENGER, CUỘC GỌI) - FRAME 0x03
// ============================================================================
void triggerNotificationFlash(uint8_t r, uint8_t g, uint8_t b, uint8_t count, uint16_t delayMs) {
  dbgLog(TAG_FX, "Báo thông báo App: Chớp màu RGB(%d, %d, %d) x %d lần", r, g, b, count);
  uint32_t col = strip.Color(r, g, b);
  for (uint8_t i = 0; i < count; i++) {
    for (int p = 0; p < NUM_LEDS; p++) strip.setPixelColor(p, col);
    strip.show();
    delay(delayMs);
    strip.clear();
    strip.show();
    delay(delayMs);
  }
}

// ============================================================================
// ĐỒNG HỒ POMODORO VẬT LÝ (25 PHÚT TẬP TRUNG, DẢI SÁNG HẠ DẦN TỪ ĐỈNH XUỐNG ĐÁY)
// ============================================================================
void effectPomodoro(uint32_t workDurationMs) {
  dbgLog(TAG_FX, "BẮT ĐẦU POMODORO 25 PHÚT: Lật hộp sang mặt làm việc!");
  unsigned long start = millis();
  const uint32_t cWork = strip.Color(255, 120, 20); // Cam ấm tập trung
  const uint32_t cTop  = strip.Color(255, 220, 100);

  while (millis() - start < workDurationMs) {
    // Nếu người dùng dựng đứng hộp lại hoặc đổi mặt -> Hủy Pomodoro
    if (!checkPomodoroOrientation()) {
      dbgLog(TAG_FX, "Hủy Pomodoro: Hộp đã được dựng đứng lại!");
      return;
    }

    float progress = (float)(millis() - start) / (float)workDurationMs;
    int remainingRows = (int)round((1.0f - progress) * (float)MATRIX_HEIGHT);

    strip.clear();
    for (int x = 0; x < MATRIX_WIDTH; x++) {
      for (int y = MATRIX_HEIGHT - 1; y >= MATRIX_HEIGHT - remainingRows; y--) {
        if (y == MATRIX_HEIGHT - remainingRows) {
          setPixelXY(x, y, cTop);
        } else {
          setPixelXY(x, y, cWork);
        }
      }
    }
    strip.show();
    if (delayWithTouch(200)) return;
  }

  // HẾT GIỜ 25 PHÚT: Chớp đỏ nhẹ báo hiệu nghỉ 5 phút
  dbgLog(TAG_FX, "HẾT GIỜ POMODORO: Bắt đầu 5 phút nghỉ ngơi thư giãn!");
  unsigned long breakStart = millis();
  const uint32_t cBreak = strip.Color(230, 40, 40);

  while (millis() - breakStart < POMODORO_BREAK_MS) {
    if (!checkPomodoroOrientation()) return;

    float t = (millis() - breakStart) * 0.003f;
    float breath = 0.15f + 0.85f * (0.5f + 0.5f * sinf(t));

    for (int i = 0; i < NUM_LEDS; i++) {
      strip.setPixelColor(i, strip.gamma32(scaleColor(cBreak, breath)));
    }
    strip.show();
    if (delayWithTouch(35)) return;
  }
}

// ============================================================================
// ĐÈN PIN KHẨN CẤP (LẮC MẠNH 3 LẦN -> SÁNG TRẮNG 100% 60S, ĐẶT XUỐNG BÀN TỰ TẮT)
// ============================================================================
void effectEmergencyFlashlight(uint32_t durationMs) {
  dbgLog(TAG_FX, "KÍCH HOẠT ĐÈN PIN KHẨN CẤP (Sáng trắng 100%% trong 60 giây)!");
  uint8_t savedBrightness = currentBrightness;
  strip.setBrightness(255);

  for (int i = 0; i < NUM_LEDS; i++) {
    strip.setPixelColor(i, strip.Color(255, 255, 255));
  }
  strip.show();

  unsigned long start = millis();
  unsigned long stationaryStart = 0;

  while (millis() - start < durationMs) {
    // Nếu người dùng đặt đèn yên trên bàn trong 2 giây liên tục -> tự khôi phục đèn ngủ
    if (isLampStationaryOnTable()) {
      if (stationaryStart == 0) {
        stationaryStart = millis();
      } else if (millis() - stationaryStart > 2000) {
        dbgLog(TAG_FX, "Đã đặt đèn nằm yên trên bàn -> Tự động tắt đèn pin khẩn cấp!");
        break;
      }
    } else {
      stationaryStart = 0;
    }

    if (delayWithTouch(60)) break;
  }

  // Khôi phục lại độ sáng ban đầu
  strip.setBrightness(savedBrightness);
  strip.show();
}

// ============================================================================
// CHẾ ĐỘ MÀU TĨNH TÙY CHỌN THEO APP (MODE 10)
// ============================================================================
void effectStaticColor() {
  uint8_t r, g, b;
  getCustomStaticColor(r, g, b);
  uint32_t col = strip.Color(r, g, b);
  for (int i = 0; i < NUM_LEDS; i++) {
    strip.setPixelColor(i, col);
  }
  strip.show();
  delayWithTouch(60);
}

// ============================================================================
// HIỆU ỨNG THỜI TIẾT TƯƠNG TÁC TỪ APP (FRAME 0x04)
// ============================================================================
void triggerWeatherUpdate(int8_t temp, uint8_t weatherId) {
  const char* wLabel = "NANG";
  uint32_t wCol = strip.Color(255, 180, 20); // Vàng nắng
  uint8_t targetMode = 1;

  switch (weatherId) {
    case 1: // Nắng
      wLabel = "NANG";
      wCol = strip.Color(255, 180, 20);
      targetMode = 1; // Lava Noise (Ấm áp)
      break;
    case 2: // Mây / Sương mù
      wLabel = "MAY";
      wCol = strip.Color(160, 200, 255);
      targetMode = 2; // Edge Light (Dịu mát)
      break;
    case 3: // Mưa
      wLabel = "MUA";
      wCol = strip.Color(0, 160, 255);
      targetMode = 5; // Water Physics (Sóng nước)
      break;
    case 4: // Sấm chớp
      wLabel = "SAM";
      wCol = strip.Color(220, 100, 255);
      targetMode = 4; // Tilt Glow (Chớp giật)
      break;
    default:
      wLabel = "TROIDEP";
      wCol = strip.Color(0, 220, 255);
      targetMode = 3;
      break;
  }

  // Chớp nhẹ 2 nhịp màu thời tiết để báo hiệu
  for (int f = 0; f < 2; f++) {
    for (int i = 0; i < NUM_LEDS; i++) strip.setPixelColor(i, wCol);
    strip.show();
    delay(100);
    strip.clear();
    strip.show();
    delay(80);
  }

  // Cuộn thông báo nhiệt độ & trạng thái thời tiết: Ví dụ "28C NANG" (font 6x8, 65ms)
  char banner[24];
  snprintf(banner, sizeof(banner), "%dC %s", (int)temp, wLabel);
  dbgLog(TAG_FX, "Cập nhật thời tiết: %s -> Chuyển sang Mode [%d]: %s",
         banner, targetMode, getModeName(targetMode));

  effectCylinderScrollText(banner, wCol, 65);

  // Chuyển sang chế độ thời tiết tương ứng và lưu trạng thái
  currentMode = targetMode;
  markSettingsChanged();
}

