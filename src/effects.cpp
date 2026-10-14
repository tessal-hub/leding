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
  strip.clear();
  for (int i = 0; i < NUM_LEDS; i++) {
    uint8_t panel = i / 64;
    uint32_t c = (panel == 0) ? strip.Color(90, 0, 0) : (panel == 1) ? strip.Color(0, 90, 0) : (panel == 2) ? strip.Color(0, 0, 90) : strip.Color(90, 90, 0);
    if (i > 0) strip.setPixelColor(i - 1, c);
    strip.setPixelColor(i, strip.Color(255, 255, 255));
    showDisplay();
    if (delayWithTouch(stepDelayMs)) return;
  }
  delayWithTouch(400);
}

// ============================================================================
// CÁC HÀM BỔ TRỢ TOÁN HỌC & NOISE (LIỀN MẠCH 360 ĐỘ QUANH HỘP VUÔNG)
// ============================================================================
static inline float smooth01(float t) {
  t = constrain(t, 0.0f, 1.0f);
  return t * t * (3.0f - 2.0f * t);
}

// ESP32-C3 (RISC-V) không có FPU: sinf/cosf chạy giả lập phần mềm tốn hàng trăm
// chu kỳ. Các dao động trên từng pixel dùng LUT số nguyên fastSin8 (đúng tới 1/255).
// lerpColor/scaleColor đã chạy lõi lerpColor8/scaleColor8 nên giữ nguyên.
static inline uint8_t sin8rad(float rad) {
  return fastSin8((uint8_t)((int32_t)(rad * 40.7436638f) & 0xFF)); // 256/(2π)
}
static inline float sin01(float rad) { return sin8rad(rad) * (1.0f / 255.0f); }                // 0..1
static inline float sinsf(float rad) { return ((int16_t)sin8rad(rad) - 128) * (1.0f / 128.0f); } // -1..1
static inline float cos01(float rad) { return sin01(rad + 1.5707963f); }                         // 0..1
static inline float cossf(float rad) { return sinsf(rad + 1.5707963f); }                         // -1..1

// Khép kín vòng trụ 32 cột (Panel 1 -> 4 -> 1): mọi hiệu ứng quét ngang phải
// dùng bộ này để không đứt pha tại biên cột 31 <-> 0.
static inline float wrapCylinderX(float x) {
  float w = fmodf(x, (float)MATRIX_WIDTH);
  if (w < 0) w += MATRIX_WIDTH;
  return w;
}
static inline float cylinderDeltaX(float a, float b) { // Hiệu có dấu ngắn nhất a - b trên vòng trụ
  float d = fmodf(a - b, (float)MATRIX_WIDTH);
  if (d > MATRIX_WIDTH * 0.5f) d -= MATRIX_WIDTH;
  else if (d <= -MATRIX_WIDTH * 0.5f) d += MATRIX_WIDTH;
  return d;
}
static inline float cylinderDistX(float a, float b) { // Khoảng cách ngắn nhất qua biên 31 <-> 0
  return fabsf(cylinderDeltaX(a, b));
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
  float c01 = lerpf(h(xi, yi, ti + 1),     h(xi + 1, yi, ti + 1),     fx);
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
      int f2 = (f1 + 1) % FACES;
      float k = smooth01(pos - floorf(pos));      // trộn màu mượt giữa 2 mặt kề nhau
      uint32_t base = lerpColor(pal[f0], pal[f1], k);

      // Hòa sắc loang màu tinh tế giữa các mặt kế cận (inter-face bleeding)
      float bleed = 0.16f * sin01(pos * PI * 2.0f + t * 0.6f);
      base = lerpColor(base, pal[f2], bleed);

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        // Nhịp sóng kết hợp điều chế dọc uốn lượn mềm mại (2D vertical wave modulation)
        float vertWave = 0.35f * sinsf(y * 0.55f - t * 0.75f + pos * 0.8f);
        float wave = sin01(t - pos * (PI / 2.0f) + vertWave);
        float bri = 0.12f + 0.88f * wave * wave;

        // Độ sáng vòm trung tâm theo chiều dọc với dao động gợn sóng nhẹ
        float v = 1.0f - fabsf((y - (MATRIX_HEIGHT - 1) / 2.0f) / (MATRIX_HEIGHT / 2.0f)) * 0.30f;
        v += 0.10f * sinsf(y * 0.75f + t * 1.1f);
        setPixelXY(x, y, strip.gamma32(scaleColor(base, constrain(bri * v, 0.0f, 1.0f))));
      }
    }
    showDisplay();
    t += 0.045f;
    if (delayWithTouch(25)) return;
  }
}

// ============================================================================
// 2. ĐÈN VIỀN 4 CẠNH GÓC HỘP (EDGE LIGHT - TỐI GIẢN CAO CẤP)
// ============================================================================
void effectEdgeLight(uint32_t durationMs) {
  unsigned long start = millis();
  const uint32_t coolWhite = strip.Color(150, 195, 255);  // Trắng lạnh băng thanh
  const uint32_t warmWhite = strip.Color(255, 215, 160);  // Trắng ấm sang trọng
  const uint32_t fillCol   = strip.Color(10, 0, 40);      // Nền tím rất tối

  // Tần số và độ lệch pha nhịp thở riêng biệt cho từng cạnh của hộp
  const float edgeFreq[4]  = { 1.05f, 1.25f, 0.95f, 1.18f };
  const float edgePhase[4] = { 0.00f, 1.80f, 3.40f, 5.10f };

  while (millis() - start < durationMs) {
    float t = (millis() - start) / 1000.0f;

    // Chuyển dịch nhiệt độ màu êm dịu theo thời gian (Cool White <-> Warm White)
    float tempShift = sin01(t * 0.30f);
    uint32_t curEdgeCol = lerpColor(coolWhite, warmWhite, tempShift);

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      // Khoảng cách tới cạnh gần nhất (cạnh nằm ở x = 0, 8, 16, 24; wrap quanh hộp)
      float local = fmodf(x + 0.5f, MATRIX_WIDTH / 4.0f);              // 0..8 trong một mặt
      float d = fminf(local, (MATRIX_WIDTH / 4.0f) - local);           // 0 tại cạnh
      float glow = expf(-d * 0.9f);                                    // Vệt sáng tắt dần vào trong mặt
      int edgeIdx = (int)((x + 0.5f) / (MATRIX_WIDTH / 4.0f + 0.0001f) + 0.5f) % 4;

      // Nhịp thở hữu cơ riêng cho từng cạnh góc
      float b = sin01(t * edgeFreq[edgeIdx] + edgePhase[edgeIdx]);
      float edgeBreath = 0.25f + 0.75f * b * b;

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        // Nhịp chạy dọc theo cạnh với chu kỳ sóng mượt
        float run = 0.65f + 0.35f * sinsf(t * 2.1f + y * 0.55f + edgeIdx * 1.57f);
        uint32_t c = lerpColor(fillCol, scaleColor(curEdgeCol, edgeBreath * run), glow);
        setPixelXY(x, y, strip.gamma32(c));
      }
    }
    showDisplay();
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

  const uint32_t c0 = strip.Color(5, 0, 25);       // Gần đen tím obsidian

  while (millis() - start < durationMs) {
    // Xoay chuyển dải màu nhẹ nhàng theo thời gian (rotating palette hue)
    float hueRot = t * 0.12f;
    float h1 = sin01(hueRot);
    float h2 = cos01(hueRot * 0.85f);
    uint32_t curC1 = lerpColor(strip.Color(160, 20, 130), strip.Color(210, 30, 65), h1);  // Magenta sâu <-> Đỏ rực
    uint32_t curC2 = lerpColor(strip.Color(255, 130, 45), strip.Color(255, 195, 65), h2); // Cam nham thạch <-> Vàng hổ phách

    for (int y = 0; y < MATRIX_HEIGHT; y++) {
      for (int x = 0; x < MATRIX_WIDTH; x++) {
        float nx = (float)x * CELLS / MATRIX_WIDTH;
        float n1 = noise3(nx,        y * 0.35f,        t,        CELLS);
        float n2 = noise3(nx * 2.0f, y * 0.7f + 5.0f,  t * 1.6f, CELLS * 2);
        float n  = n1 * 0.7f + n2 * 0.3f;

        // Đẩy tương phản để tạo "blob" dung nham rõ nét, nền tối sâu
        n = smooth01(constrain((n - 0.30f) * 1.9f, 0.0f, 1.0f));
        uint32_t pixelCol = palette3(n, c0, curC1, curC2);

        // Đốm lấp lánh (sparkle overlay) tinh tế trên các đỉnh dung nham nóng sáng rực
        if (n > 0.72f && (random(100) < 18)) {
          float sparkleBri = ((float)random(30, 80) / 100.0f) * (n - 0.72f) * 3.5f;
          pixelCol = lerpColor(pixelCol, strip.Color(255, 250, 230), constrain(sparkleBri, 0.0f, 0.75f));
        }

        setPixelXY(x, y, strip.gamma32(pixelCol));
      }
    }
    showDisplay();
    t += 0.018f;               // Chậm = sang; nhanh = rối
    if (delayWithTouch(25)) return;
  }
}

// ============================================================================
// 4. ĐÈN NGHIÊNG DÍNH CHIỀU NGHIÊNG & BỪNG SÁNG KHI LẮC (TILT GLOW MPU)
// ============================================================================
void effectTiltGlow(uint32_t durationMs) {
  unsigned long start = millis();
  float fx = 0.0f, fy = 0.0f;      // Gia tốc đã lọc (đơn vị g)
  float flash = 0.0f;              // Độ sáng bùng khi lắc
  float prevMag = 1.0f;
  float t = 0.0f;

  // Bộ đệm lưu vệt sáng dư quang (trailing afterglow buffer)
  float trail[MATRIX_WIDTH][MATRIX_HEIGHT] = { {0.0f} };

  const uint32_t cA = strip.Color(0, 200, 255);
  const uint32_t cB = strip.Color(160, 60, 255);
  const uint32_t cWhiteBurst = strip.Color(255, 255, 255);

  while (millis() - start < durationMs) {
    int16_t ax, ay, az;
    if (readMpuAccel(ax, ay, az)) {
      float gx = ax / 16384.0f, gy = ay / 16384.0f, gz = az / 16384.0f;
      fx += (gx - fx) * 0.12f;     // Low-pass: lọc mượt chống rung giật
      fy += (gy - fy) * 0.12f;

      float mag = sqrtf(gx * gx + gy * gy + gz * gz);
      if (fabsf(mag - prevMag) > 0.22f) {
        flash = 1.8f;   // Lắc mạnh -> Kích hoạt chớp trắng bùng nổ dramatic burst
      }
      prevMag = mag;
    }
    flash *= 0.86f;

    float angle = atan2f(fy, fx);
    float tilt = constrain(sqrtf(fx * fx + fy * fy), 0.0f, 1.0f);

    for (int y = 0; y < MATRIX_HEIGHT; y++) {
      for (int x = 0; x < MATRIX_WIDTH; x++) {
        float a = (x * 2.0f * PI) / MATRIX_WIDTH;
        float facing = cos01(a - angle);            // 1 = hướng đang nghiêng tới
        float targetGlow = (facing * facing) * (0.25f + 0.75f * tilt); // Bình phương số nguyên, rẻ cho 32 cột

        // Cập nhật vệt dư quang mượt mà (trailing afterglow effect)
        trail[x][y] = trail[x][y] * 0.78f + targetGlow * 0.26f;
        float glow = fmaxf(targetGlow, trail[x][y]);

        glow += 0.05f + 0.04f * sinsf(t + x * 0.3f);                // Nền "thở" rất nhẹ

        float mix = sin01(a * 0.5f - angle + y * 0.25f);
        uint32_t col = scaleColor(lerpColor(cA, cB, mix), constrain(glow, 0.0f, 1.0f));

        // Hiệu ứng chớp trắng rực rỡ khi lắc (dramatic white burst on shake)
        if (flash > 0.02f) {
          float burstBri = constrain(flash * 0.90f, 0.0f, 1.0f);
          col = lerpColor(col, cWhiteBurst, burstBri);
        }

        setPixelXY(x, y, strip.gamma32(col));
      }
    }
    showDisplay();
    t += 0.05f;
    if (delayWithTouch(20)) return;
  }
}

// ============================================================================
// 5. DẢI CỰC QUANG UỐN LƯỢN (AURORA FLOW - TÍM, CYAN, XANH LỤC & HỒNG)
// ============================================================================
static inline uint32_t auroraMultiPalette(float v) {
  v = fmodf(v, 1.0f);
  if (v < 0.0f) v += 1.0f;
  if (v < 0.30f) {
    return lerpColor(strip.Color(110, 25, 245), strip.Color(0, 220, 210), v / 0.30f); // Tím sâu -> Cyan ngọc
  } else if (v < 0.65f) {
    return lerpColor(strip.Color(0, 220, 210), strip.Color(25, 255, 115), (v - 0.30f) / 0.35f); // Cyan -> Xanh lục cực quang
  } else {
    return lerpColor(strip.Color(25, 255, 115), strip.Color(255, 70, 160), (v - 0.65f) / 0.35f); // Xanh lục -> Hồng cực quang
  }
}

void effectAurora(uint32_t durationMs) {
  unsigned long start = millis();
  float t = 0.0f;

  while (millis() - start < durationMs) {
    for (int y = 0; y < MATRIX_HEIGHT; y++) {
      for (int x = 0; x < MATRIX_WIDTH; x++) {
        float a = (x * 2.0f * PI) / MATRIX_WIDTH;

        // Độ rộng dải lụa cực quang co giãn hữu cơ (varying width modulation)
        float widthMod = 0.75f + 0.30f * sinsf(a * 2.0f - t * 0.7f);

        // Sóng uốn lượn đa hài tạo các nếp gấp rèm cực quang (organic undulation)
        float curtain1 = sinsf(a * 2.0f + t * 0.95f + sinsf(y * 0.55f + t * 0.45f) * 1.6f);
        float curtain2 = sinsf(a * 3.0f - t * 1.35f + y * 0.40f);
        float mix = 0.5f + 0.5f * (curtain1 * 0.65f + curtain2 * 0.35f);
        mix = mix * widthMod;

        // Độ sáng tương phản sắc nét mô phỏng ánh sáng dải lụa đêm bắc cực
        float bri = sin01(a * 2.0f - t * 1.1f + y * 0.7f + curtain1 * 0.85f);
        bri = 0.15f + 0.85f * powf(constrain(bri, 0.0f, 1.0f), 2.2f);

        uint32_t col = auroraMultiPalette(mix + t * 0.045f);
        setPixelXY(x, y, strip.gamma32(scaleColor(col, bri)));
      }
    }
    showDisplay();
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
  const float CYCLE_SEC = 5.2f;                    // Chu kỳ thở tự nhiên ~5.2s

  while (millis() - start < durationMs) {
    float phase = (millis() - start) / 1000.0f;
    float normCycle = fmodf(phase, CYCLE_SEC) / CYCLE_SEC; // 0.0 -> 1.0

    // Nhịp thở tự nhiên bất đối xứng: Hít vào nhanh hơn (~36%), Thở ra chậm rãi (~64%)
    float breath;
    if (normCycle < 0.36f) {
      // Hít vào (Inhale): dâng lên êm ái
      breath = smooth01(normCycle / 0.36f);
    } else {
      // Thở ra (Exhale): hạ dần thư thái
      breath = 1.0f - smooth01((normCycle - 0.36f) / 0.64f);
    }
    breath = 0.14f + 0.86f * powf(breath, 1.35f);

    for (int y = 0; y < MATRIX_HEIGHT; y++) {
      for (int x = 0; x < MATRIX_WIDTH; x++) {
        float a = (x * 2.0f * PI) / MATRIX_WIDTH;
        float g = sin01(a + phase * 0.4f);

        // Thêm hạt texture noise vi mô phá vỡ dải sọc gradient đồng đều
        float nx = (float)x / 8.0f;
        float texNoise = noise3(nx, y * 0.4f, phase * 0.25f, 4);
        float texMod = 1.0f + (texNoise - 0.5f) * 0.12f; // Thay đổi +/- 6% tinh tế

        float finalBri = constrain(breath * texMod, 0.0f, 1.0f);
        setPixelXY(x, y, strip.gamma32(scaleColor(lerpColor(cA, cB, g), finalBri)));
      }
    }
    showDisplay();
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
    showDisplay();
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
    unsigned long nowMs = millis();

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      int left = (x - 1 + MATRIX_WIDTH) % MATRIX_WIDTH;
      int right = (x + 1) % MATRIX_WIDTH;

      float waveTension = 0.22f * (surfaceY[left] + surfaceY[right] - 2.0f * surfaceY[x]);
      float gravityRestore = 0.035f * (4.0f - surfaceY[x]);

      float colAngle = x * (2.0f * PI / MATRIX_WIDTH);
      float tiltForce = -sinsf(colAngle - tiltAngle) * tiltMag * 1.6f;

      // Gợn sóng vi mô xung quanh kể cả khi đèn đứng yên (ambient ripple)
      float ambientRipple = 0.012f * sinsf(nowMs * 0.0035f + x * 0.65f)
                          + 0.007f * cossf(nowMs * 0.0062f - x * 1.10f);

      velocity[x] += waveTension + gravityRestore + tiltForce + ambientRipple;

      if (jerk > 0.25f) {
        float splashImpulse = min(jerk * 0.9f, 2.2f);
        velocity[x] += -sinsf(colAngle - tiltAngle) * splashImpulse + ((float)random(-40, 40) / 100.0f);
      }

      velocity[x] *= 0.93f;
      velocity[x] = constrain(velocity[x], -2.0f, 2.0f);
    }

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      surfaceY[x] += velocity[x];
      surfaceY[x] = constrain(surfaceY[x], 0.6f, 6.8f);
      if ((surfaceY[x] <= 0.65f && velocity[x] < 0) || (surfaceY[x] >= 6.75f && velocity[x] > 0)) {
        velocity[x] = -velocity[x] * 0.25f;
      }
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
      if (waterTop < 0) waterTop = 0;
      if (waterTop >= MATRIX_HEIGHT) waterTop = MATRIX_HEIGHT - 1;

      int left = (x - 1 + MATRIX_WIDTH) % MATRIX_WIDTH;
      int right = (x + 1) % MATRIX_WIDTH;
      float slope = surfaceY[right] - surfaceY[left];
      float specular = sin01(slope * 1.8f + (float)(nowMs & 0xFFFF) * 0.004f);

      for (int y = MATRIX_HEIGHT - 1; y >= waterTop; y--) {
        uint32_t waterColor;
        float depth = (float)(y - waterTop) / (float)(MATRIX_HEIGHT - waterTop + 0.01f);
        depth = constrain(depth, 0.0f, 1.0f);

        if (y == waterTop) {
          if (fabsf(velocity[x]) > 0.15f || jerk > 0.3f) {
            waterColor = strip.Color(240, 255, 255); // Bọt sóng trắng xóa
          } else {
            // Ánh phản chiếu lấp lánh trên mặt nước (reflective specular surface highlight)
            waterColor = lerpColor(strip.Color(90, 225, 255), strip.Color(225, 250, 255), specular * 0.75f);
          }
        } else if (y == waterTop + 1) {
          // Gần mặt nước: sắc xanh ngọc lam chuyển tiếp
          waterColor = lerpColor(strip.Color(10, 185, 255), strip.Color(0, 140, 240), depth);
        } else {
          // Tầng nước sâu: gradient xanh biển thẫm lắng đọng
          waterColor = lerpColor(strip.Color(0, 115, 230), strip.Color(0, 30, 160), depth);
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

        if (dx >= 0 && dx < MATRIX_WIDTH) {
          if (dy >= 0 && dy < MATRIX_HEIGHT && dy < (int)surfaceY[dx]) {
            setPixelXY(dx, dy, strip.Color(240, 255, 255));
          } else if (dy >= (int)surfaceY[dx]) {
            droplets[d].active = false;
          }
        } else {
          droplets[d].active = false;
        }
      }
    }

    showDisplay();
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
    showDisplay();
    delay(delayMs);
    strip.clear();
    showDisplay();
    delay(delayMs);
  }
}

// ============================================================================
// ĐỒNG HỒ POMODORO VẬT LÝ (25 PHÚT TẬP TRUNG, DẢI SÁNG HẠ DẦN TỪ ĐỈNH XUỐNG ĐÁY)
// ============================================================================
void effectPomodoro(uint32_t workDurationMs) {
  bool startedByTilt = checkPomodoroOrientation();
  dbgLog(TAG_FX, "BẮT ĐẦU POMODORO 25 PHÚT (%s)!", startedByTilt ? "Lật hộp vật lý" : "Điều khiển từ App/CLI");
  unsigned long start = millis();
  const uint32_t cWork = strip.Color(255, 120, 20); // Cam ấm tập trung
  const uint32_t cTop  = strip.Color(255, 220, 100);

  while (millis() - start < workDurationMs) {
    // Chỉ tự hủy khi dựng đứng hộp nếu ban đầu Pomodoro được kích hoạt bằng cử chỉ lật hộp
    if (startedByTilt && !checkPomodoroOrientation()) {
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
    showDisplay();
    if (delayWithTouch(200)) return;
  }

  // HẾT GIỜ 25 PHÚT: Chớp đỏ nhẹ báo hiệu nghỉ 5 phút
  dbgLog(TAG_FX, "HẾT GIỜ POMODORO: Bắt đầu 5 phút nghỉ ngơi thư giãn!");
  unsigned long breakStart = millis();
  const uint32_t cBreak = strip.Color(230, 40, 40);

  while (millis() - breakStart < POMODORO_BREAK_MS) {
    if (startedByTilt && !checkPomodoroOrientation()) return;

    float t = (millis() - breakStart) * 0.003f;
    float breath = 0.15f + 0.85f * sin01(t);

    for (int i = 0; i < NUM_LEDS; i++) {
      strip.setPixelColor(i, strip.gamma32(scaleColor(cBreak, breath)));
    }
    showDisplay();
    if (delayWithTouch(35)) return;
  }
}

// ============================================================================
// ĐÈN PIN KHẨN CẤP (LẮC MẠNH 3 LẦN -> SÁNG TRẮNG 100% 60S, ĐẶT XUỐNG BÀN TỰ TẮT)
// ============================================================================
void effectEmergencyFlashlight(uint32_t durationMs) {
  dbgLog(TAG_FX, "KÍCH HOẠT ĐÈN PIN KHẨN CẤP (Sáng trắng 100%% trong 60 giây)!");
  strip.setBrightness(255);

  for (int i = 0; i < NUM_LEDS; i++) {
    strip.setPixelColor(i, strip.Color(255, 255, 255));
  }
  showDisplay();

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

  // Khôi phục độ sáng hiện hành (currentBrightness đã cập nhật nếu user đổi sáng giữa chừng)
  strip.setBrightness(toHardwareBrightness(currentBrightness));
  showDisplay();
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
  showDisplay();
  delayWithTouch(60);
}

// ============================================================================
// MODE 11: HIỂN THỊ 4 Ô BITMAP TÙY BIẾN ĐỘC LẬP THEO 4 MẶT 1, 2, 3, 4 (CÓ ANIMATION)
// ============================================================================
void effectFourSlotsCustom(uint32_t durationMs) {
  unsigned long start = millis();
  while (millis() - start < durationMs) {
    uint8_t frame = (millis() / 350) % 2;
    renderFourSlotsAnimated(frame);
    showDisplay();
    if (delayWithTouch(60)) return;
  }
}

// ============================================================================
// MODE 12: ĐỒNG HỒ ĐỂ BÀN 360° KỸ THUẬT SỐ & ĐỒNG BỘ GIỜ RTC
// ============================================================================
static uint32_t currentSecOfDay = 12 * 3600; // Mặc định 12:00:00
static unsigned long lastSecTickMillis = 0;
static bool timeIsSynced = false;
static int8_t  currentWeatherTemp = 26;
static uint8_t currentWeatherId   = 1; // 1: Nắng/Quang, 2: Mây, 3: Mưa, 4: Giông bão (hiện luân phiên trên Desk Clock)

void syncDeviceTime(uint8_t hour, uint8_t minute, uint8_t second) {
  currentSecOfDay = ((uint32_t)hour % 24) * 3600 + ((uint32_t)minute % 60) * 60 + ((uint32_t)second % 60);
  lastSecTickMillis = millis();
  timeIsSynced = true;
  dbgLog(TAG_FX, "Đồng bộ giờ hệ thống RTC: %02d:%02d:%02d", hour, minute, second);
}

void getDeviceTime(uint8_t &hour, uint8_t &minute, uint8_t &second) {
  unsigned long now = millis();
  unsigned long elapsedSec = (now - lastSecTickMillis) / 1000UL;
  if (elapsedSec > 0) {
    currentSecOfDay = (currentSecOfDay + elapsedSec) % 86400UL;
    lastSecTickMillis += elapsedSec * 1000UL;
  }
  hour = (currentSecOfDay / 3600) % 24;
  minute = (currentSecOfDay % 3600) / 60;
  second = currentSecOfDay % 60;
}

void effectDeskClock(uint32_t durationMs) {
  unsigned long start = millis();
  while (millis() - start < durationMs) {
    uint8_t h, m, s;
    getDeviceTime(h, m, s);
    bool colonBlink = (millis() % 1000) < 500;
    // Luân phiên: 8s hiện giờ, 2s hiện nhiệt độ đồng bộ từ App
    bool showTemp = ((millis() - start) % 10000) >= 8000;
    renderDeskClock(h, m, colonBlink, currentWeatherTemp, showTemp);
    showDisplay();
    if (delayWithTouch(80)) return;
  }
}


// ============================================================================
// HỆ THỐNG THỜI TIẾT SỐNG ĐỘNG 360° THEO THỜI GIAN THỰC SÁNG / TỐI TRONG NGÀY
// ============================================================================
struct SkyTimePalette {
  float dayFactor;      // 0.0 = Đêm khuya, 1.0 = Ban ngày
  float twilightFactor; // 0.0 -> 1.0 khi vào khung giờ Bình minh (5h-7h) hoặc Hoàng hôn (16h45-18h45)
  float nightBriScale;  // Hệ số giảm chói ban đêm (0.62 ban đêm -> 1.0 ban ngày)
  uint32_t skyTop;      // Màu bầu trời đỉnh trụ (y = 0)
  uint32_t skyHorizon;  // Màu chân trời đáy trụ (y = 7)
  uint32_t cloudBase;   // Màu thân đám mây theo giờ
  uint32_t cloudEdge;   // Màu viền sáng của mây (Silver lining / Sunset glow)
  uint32_t orbColor;    // Màu Mặt trời (ngày) hoặc Mặt trăng (đêm)
  uint32_t rainColor;   // Màu hạt mưa theo ngày/đêm
};

// Tự động tính bảng màu bầu trời theo giờ hệ thống (RTC đồng bộ qua BLE 0x05 hoặc CLI)
static SkyTimePalette computeSkyPalette() {
  uint8_t h, m, s;
  getDeviceTime(h, m, s);
  float hourF = (float)h + ((float)m / 60.0f) + ((float)s / 3600.0f);

  SkyTimePalette p;
  p.dayFactor = 0.0f;
  p.twilightFactor = 0.0f;

  // Nội suy mượt 4 pha trong ngày:
  // Đêm (18:45-05:00) -> Bình minh (05:00-07:00) -> Ngày (07:00-16:45) -> Hoàng hôn (16:45-18:45)
  if (hourF >= 5.0f && hourF < 7.0f) {
    float k = (hourF - 5.0f) / 2.0f;
    p.dayFactor = smooth01(k);
    p.twilightFactor = sinsf(k * PI); // Đỉnh bình minh lúc 06:00
  } else if (hourF >= 7.0f && hourF < 16.75f) {
    p.dayFactor = 1.0f;
    p.twilightFactor = 0.0f;
  } else if (hourF >= 16.75f && hourF < 18.75f) {
    float k = (hourF - 16.75f) / 2.0f;
    p.dayFactor = 1.0f - smooth01(k);
    p.twilightFactor = sinsf(k * PI); // Đỉnh hoàng hôn lúc 17:45
  } else {
    p.dayFactor = 0.0f;
    p.twilightFactor = 0.0f;
  }

  // Ban đêm tự động dịu độ sáng xuống 62% để không gây chói mắt trong phòng ngủ
  p.nightBriScale = 0.62f + 0.38f * p.dayFactor;

  uint32_t nightTop     = strip.Color(2, 4, 22);
  uint32_t nightHorizon = strip.Color(8, 18, 52);
  uint32_t dayTop       = strip.Color(10, 115, 245);
  uint32_t dayHorizon   = strip.Color(75, 195, 255);
  uint32_t duskTop      = strip.Color(55, 20, 115);
  uint32_t duskHorizon  = strip.Color(255, 90, 40);

  p.skyTop     = lerpColor(lerpColor(nightTop, dayTop, p.dayFactor), duskTop, p.twilightFactor * 0.85f);
  p.skyHorizon = lerpColor(lerpColor(nightHorizon, dayHorizon, p.dayFactor), duskHorizon, p.twilightFactor * 0.90f);

  uint32_t cloudNight = strip.Color(28, 38, 78);
  uint32_t cloudDay   = strip.Color(175, 215, 245);
  uint32_t cloudDusk  = strip.Color(225, 110, 95);
  p.cloudBase = lerpColor(lerpColor(cloudNight, cloudDay, p.dayFactor), cloudDusk, p.twilightFactor * 0.80f);

  uint32_t edgeNight = strip.Color(90, 120, 185);
  uint32_t edgeDay   = strip.Color(255, 255, 255);
  uint32_t edgeDusk  = strip.Color(255, 190, 80);
  p.cloudEdge = lerpColor(lerpColor(edgeNight, edgeDay, p.dayFactor), edgeDusk, p.twilightFactor);

  // Mặt trời (Vàng rực ban ngày / Cam đỏ hoàng hôn) vs Mặt trăng (Trắng bạc ban đêm)
  uint32_t sunCol  = lerpColor(strip.Color(255, 215, 40), strip.Color(255, 95, 25), p.twilightFactor);
  uint32_t moonCol = strip.Color(195, 225, 255);
  p.orbColor  = (p.dayFactor >= 0.35f) ? sunCol : moonCol;

  p.rainColor = lerpColor(strip.Color(70, 140, 235), strip.Color(145, 225, 255), p.dayFactor);
  return p;
}

// ----------------------------------------------------------------------------
// 1. TRỜI QUANG MÂY TẠNH (NGÀY: MẶT TRỜI & TIA NẮNG XOAY 360° | ĐÊM: TRĂNG & SAO)
// ----------------------------------------------------------------------------
void effectWeatherClearSky(uint32_t durationMs) {
  unsigned long start = millis();
  float t = 0.0f;

  while (millis() - start < durationMs) {
    SkyTimePalette sky = computeSkyPalette();
    // Tâm Mặt trời / Mặt trăng trôi vòng tròn vô hạn qua 4 panel (1 -> 2 -> 3 -> 4 -> 1)
    float orbX = wrapCylinderX(t * 2.4f);
    float orbY = 2.0f + 0.45f * sinsf(t * 0.8f);

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      float theta = (x * 2.0f * PI) / (float)MATRIX_WIDTH;
      // Khoảng cách có dấu trên vòng trụ để tia nắng không bị đứt ở biên cột 31 <-> 0
      float signedDxOrb = cylinderDeltaX((float)x, orbX);
      float dxOrb = fabsf(signedDxOrb);

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        // 1. Nền bầu trời chuyển sắc theo độ cao y
        float vGrad = (float)y / (float)(MATRIX_HEIGHT - 1);
        uint32_t col = lerpColor(sky.skyTop, sky.skyHorizon, smooth01(vGrad));

        // 2. Ban đêm / Chập tối: vẽ các ngôi sao lấp lánh trên bầu trời (y = 0..5)
        if (sky.dayFactor < 0.65f && y <= 5) {
          uint8_t starHash = hash3(x, y, 7);
          if (starHash > 222) { // ~13% mật độ sao
            float twinklePhase = t * (1.8f + (starHash & 7) * 0.35f) + starHash * 0.1f;
            float starBri = (1.0f - sky.dayFactor) * (0.25f + 0.75f * sin01(twinklePhase));
            col = lerpColor(col, strip.Color(240, 248, 255), constrain(starBri, 0.0f, 0.90f));
          }
        }

        // 3. Vẽ Mặt trời (Ban ngày/Hoàng hôn) hoặc Mặt trăng (Ban đêm)
        float dyOrb = (float)y - orbY;
        float distOrb = sqrtf(dxOrb * dxOrb + dyOrb * dyOrb);

        if (sky.dayFactor >= 0.35f) {
          // BAN NGÀY / HOÀNG HÔN: Mặt trời tỏa 6 cánh tia nắng xoay tròn (Sunbeams)
          float rayAngle = atan2f(dyOrb, signedDxOrb);
          float rays = sin01(rayAngle * 6.0f - t * 2.2f);
          float sunHalo = expf(-distOrb * 0.42f) * (0.55f + 0.45f * rays);
          col = lerpColor(col, sky.orbColor, constrain(sunHalo, 0.0f, 1.0f));
          if (distOrb < 1.65f) {
            float core = smooth01(1.0f - distOrb / 1.65f);
            col = lerpColor(col, strip.Color(255, 255, 220), core * 0.90f);
          }
          // Ánh nắng lấp lánh dưới chân trời (y = 6..7)
          if (y >= 6) {
            float shimmer = sin01(theta * 3.0f - t * 2.5f + y);
            col = lerpColor(col, sky.orbColor, shimmer * 0.25f);
          }
        } else {
          // BAN ĐÊM: Mặt trăng tròn dịu êm & quầng sáng bạc
          float moonHalo = expf(-distOrb * 0.65f) * 0.55f;
          col = lerpColor(col, sky.orbColor, constrain(moonHalo, 0.0f, 0.75f));
          if (distOrb < 1.35f) {
            float core = smooth01(1.0f - distOrb / 1.35f);
            col = lerpColor(col, strip.Color(245, 250, 255), core * 0.85f);
          }
        }

        setPixelXY(x, y, strip.gamma32(scaleColor(col, sky.nightBriScale)));
      }
    }
    showDisplay();
    t += 0.035f;
    if (delayWithTouch(25)) return;
  }
}

// ----------------------------------------------------------------------------
// 2. MÂY TRÔI ĐA TẦNG 360° (VOLUMETRIC CLOUDS - ĐỔI MÀU NGÀY / HOÀNG HÔN / ĐÊM)
// ----------------------------------------------------------------------------
void effectWeatherCloudy(uint32_t durationMs) {
  unsigned long start = millis();
  float t = 0.0f;
  const int CELLS = 4; // Khép kín chu vi 4 panel không vết nối

  while (millis() - start < durationMs) {
    SkyTimePalette sky = computeSkyPalette();

    for (int y = 0; y < MATRIX_HEIGHT; y++) {
      float vGrad = (float)y / (float)(MATRIX_HEIGHT - 1);
      uint32_t skyCol = lerpColor(sky.skyTop, sky.skyHorizon, smooth01(vGrad));

      for (int x = 0; x < MATRIX_WIDTH; x++) {
        // 2 tầng mây trôi lệch vận tốc (Parallax) xuyên từ Panel 1 -> 2 -> 3 -> 4 -> 1
        float nx1 = ((float)x * CELLS / (float)MATRIX_WIDTH) - t * 0.45f;
        float nx2 = ((float)x * (CELLS * 2) / (float)MATRIX_WIDTH) - t * 0.82f;

        float c1 = noise3(nx1, y * 0.38f, t * 0.35f, CELLS);
        float c2 = noise3(nx2, y * 0.65f + 4.0f, t * 0.60f, CELLS * 2);
        float cloudDensity = c1 * 0.65f + c2 * 0.35f;

        // Mây dày hơn ở tầng cao và tầng giữa (y = 0..5)
        float heightMask = 1.0f - smooth01(constrain((vGrad - 0.55f) * 2.2f, 0.0f, 1.0f)) * 0.35f;
        float alpha = smooth01(constrain((cloudDensity * heightMask - 0.32f) * 2.1f, 0.0f, 1.0f));

        // Viền mây phát sáng (Silver lining ban ngày / Ánh vàng hồng lúc hoàng hôn)
        float rim = smooth01(constrain((cloudDensity - 0.58f) * 3.2f, 0.0f, 1.0f));
        uint32_t cloudCol = lerpColor(sky.cloudBase, sky.cloudEdge, rim);

        uint32_t finalCol = lerpColor(skyCol, cloudCol, alpha);
        setPixelXY(x, y, strip.gamma32(scaleColor(finalCol, sky.nightBriScale)));
      }
    }
    showDisplay();
    t += 0.022f;
    if (delayWithTouch(25)) return;
  }
}

// ----------------------------------------------------------------------------
// 3. MƯA RƠI SUB-PIXEL & VŨNG NƯỚC BẮN SÓNG (RAIN & PUDDLE RIPPLES)
// ----------------------------------------------------------------------------
void effectWeatherRain(uint32_t durationMs) {
  unsigned long start = millis();
  float t = 0.0f;

  struct RainDrop {
    float x;
    float y;
    float vy;
    float vx;
  };
  const int NUM_DROPS = 14;
  RainDrop drops[NUM_DROPS];
  for (int i = 0; i < NUM_DROPS; i++) {
    drops[i].x  = (float)random(0, MATRIX_WIDTH);
    drops[i].y  = (float)random(-8, MATRIX_HEIGHT);
    drops[i].vy = 0.28f + ((float)random(0, 18) / 100.0f);
    drops[i].vx = 0.06f; // Gió nhẹ đưa hạt mưa trôi vòng qua các panel
  }

  // Độ sáng gợn nước bắn tóe tại hàng đáy y = 7
  float puddleGlow[MATRIX_WIDTH] = { 0.0f };

  while (millis() - start < durationMs) {
    SkyTimePalette sky = computeSkyPalette();
    uint32_t rainSkyTop  = scaleColor(sky.skyTop, 0.45f);
    uint32_t rainSkyBot  = scaleColor(sky.skyHorizon, 0.40f);
    uint32_t cloudCanopy = scaleColor(sky.cloudBase, 0.55f);

    // 1. Vẽ nền trời mưa & tầng mây vũ tích ở hàng y = 0..1
    for (int x = 0; x < MATRIX_WIDTH; x++) {
      float nx = ((float)x * 4.0f / (float)MATRIX_WIDTH) - t * 0.35f;
      float cn = noise3(nx, 0.5f, t * 0.4f, 4);

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        float vGrad = (float)y / (float)(MATRIX_HEIGHT - 1);
        uint32_t bg = lerpColor(rainSkyTop, rainSkyBot, vGrad);
        if (y <= 1) {
          float cloudMix = (y == 0 ? 0.75f : 0.40f) * (0.5f + 0.5f * cn);
          bg = lerpColor(bg, cloudCanopy, cloudMix);
        }
        setPixelXY(x, y, strip.gamma32(scaleColor(bg, sky.nightBriScale)));
      }
    }

    // 2. Cập nhật và vẽ từng hạt mưa rơi Sub-pixel có đuôi mờ
    for (int i = 0; i < NUM_DROPS; i++) {
      drops[i].x = wrapCylinderX(drops[i].x + drops[i].vx);
      drops[i].y += drops[i].vy;

      int col = (int)floorf(drops[i].x) & (MATRIX_WIDTH - 1);

      // Khi hạt mưa chạm mặt nước ở đáy (y >= 6.8) -> Bắn tóe sóng sang 2 cột bên cạnh
      if (drops[i].y >= 6.8f) {
        puddleGlow[col] = 1.0f;
        int left  = (col + MATRIX_WIDTH - 1) & (MATRIX_WIDTH - 1);
        int right = (col + 1) & (MATRIX_WIDTH - 1);
        puddleGlow[left]  = fmaxf(puddleGlow[left], 0.55f);
        puddleGlow[right] = fmaxf(puddleGlow[right], 0.55f);

        drops[i].y  = -((float)random(5, 25) / 10.0f);
        drops[i].x  = (float)random(0, MATRIX_WIDTH);
        drops[i].vy = 0.28f + ((float)random(0, 18) / 100.0f);
      } else if (drops[i].y >= 0.0f) {
        int headY = (int)floorf(drops[i].y);
        float frac = drops[i].y - (float)headY;

        if (headY >= 0 && headY < MATRIX_HEIGHT) {
          uint32_t cHead = scaleColor(sky.rainColor, (0.45f + 0.55f * frac) * sky.nightBriScale);
          setPixelXY(col, headY, strip.gamma32(cHead));
        }
        if (headY - 1 >= 0 && headY - 1 < MATRIX_HEIGHT) {
          uint32_t cTail = scaleColor(sky.rainColor, (1.0f - frac) * 0.65f * sky.nightBriScale);
          setPixelXY(col, headY - 1, strip.gamma32(cTail));
        }
      }
    }

    // 3. Vẽ mặt nước đọng & gợn sóng bắn tóe ở hàng y = 7
    for (int x = 0; x < MATRIX_WIDTH; x++) {
      puddleGlow[x] *= 0.82f;
      float baseWater = 0.22f + 0.08f * sinsf((x * 2.0f * PI / MATRIX_WIDTH) * 2.0f - t * 3.0f);
      float bri = constrain(baseWater + puddleGlow[x] * 0.78f, 0.0f, 1.0f) * sky.nightBriScale;
      uint32_t puddleCol = lerpColor(sky.rainColor, strip.Color(240, 252, 255), puddleGlow[x] * 0.7f);
      setPixelXY(x, MATRIX_HEIGHT - 1, strip.gamma32(scaleColor(puddleCol, bri)));
    }

    showDisplay();
    t += 0.035f;
    if (delayWithTouch(24)) return;
  }
}

// ----------------------------------------------------------------------------
// 4. GIÔNG BÃO & SẤM CHỚP PHÂN NHÁNH 360° (THUNDERSTORM & LIGHTNING BOLTS)
// ----------------------------------------------------------------------------
void effectWeatherStorm(uint32_t durationMs) {
  unsigned long start = millis();
  float t = 0.0f;

  struct StormDrop {
    float x;
    float y;
    float vy;
    float vx;
  };
  const int NUM_DROPS = 18;
  StormDrop drops[NUM_DROPS];
  for (int i = 0; i < NUM_DROPS; i++) {
    drops[i].x  = (float)random(0, MATRIX_WIDTH);
    drops[i].y  = (float)random(-8, MATRIX_HEIGHT);
    drops[i].vy = 0.38f + ((float)random(0, 20) / 100.0f);
    drops[i].vx = 0.22f; // Gió giật đẩy mưa bay xiên góc xuyên qua 4 panel
  }

  float lightningFlash = 0.0f;
  int   boltCols[MATRIX_HEIGHT] = { 0 };
  uint8_t restrikeCount = 0;
  unsigned long nextStrikeTime = millis() + random(1200, 3200);
  unsigned long restrikeTime   = 0;

  while (millis() - start < durationMs) {
    SkyTimePalette sky = computeSkyPalette();
    unsigned long now = millis();

    // 1. Phóng tia sét mới hoặc chớp bồi lần 2 (Double-strike restrike)
    if (now >= nextStrikeTime) {
      lightningFlash = 1.0f;
      int curX = random(0, MATRIX_WIDTH);
      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        boltCols[y] = curX;
        curX = (curX + (int)random(-1, 2) + MATRIX_WIDTH) & (MATRIX_WIDTH - 1);
      }
      restrikeCount  = (random(0, 10) < 6) ? 1 : 0; // 60% xác suất có chớp bồi
      restrikeTime   = now + random(90, 160);
      nextStrikeTime = now + random(2400, 5200);
    } else if (restrikeCount > 0 && now >= restrikeTime) {
      lightningFlash = 0.95f;
      restrikeCount = 0;
    }

    lightningFlash *= 0.78f; // Dư quang sấm chớp tắt dần trong tầng mây

    // 2. Vẽ nền mây bão đen tím và ánh chớp lan tỏa 360° (Sheet Lightning)
    uint32_t stormBgTop     = strip.Color(8, 6, 24);
    uint32_t stormBgBot     = strip.Color(4, 10, 28);
    uint32_t thunderGlowCol = strip.Color(190, 175, 255);

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      float distBolt = cylinderDistX((float)x, (float)boltCols[0]);
      float sheetGlow = lightningFlash * expf(-distBolt * 0.22f);

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        uint32_t bg = (y <= 2) ? stormBgTop : stormBgBot;
        if (sheetGlow > 0.02f) {
          float vertWeight = (y <= 2) ? 0.85f : 0.45f;
          bg = lerpColor(bg, thunderGlowCol, constrain(sheetGlow * vertWeight, 0.0f, 0.90f));
        }
        setPixelXY(x, y, strip.gamma32(scaleColor(bg, sky.nightBriScale)));
      }
    }

    // 3. Vẽ mưa giông quất xiên qua 4 panel
    uint32_t rainCol = strip.Color(95, 165, 245);
    for (int i = 0; i < NUM_DROPS; i++) {
      drops[i].x = wrapCylinderX(drops[i].x + drops[i].vx);
      drops[i].y += drops[i].vy;

      if (drops[i].y >= (float)MATRIX_HEIGHT) {
        drops[i].y = -((float)random(2, 20) / 10.0f);
        drops[i].x = (float)random(0, MATRIX_WIDTH);
      } else if (drops[i].y >= 0.0f) {
        int col = (int)floorf(drops[i].x) & (MATRIX_WIDTH - 1);
        int row = (int)floorf(drops[i].y);
        if (row >= 0 && row < MATRIX_HEIGHT) {
          setPixelXY(col, row, strip.gamma32(scaleColor(rainCol, 0.75f * sky.nightBriScale)));
        }
      }
    }

    // 4. Vẽ lõi tia sét zig-zac trắng xanh rực rỡ khi đang phóng điện
    if (lightningFlash > 0.28f) {
      uint32_t boltCore = strip.Color(255, 255, 255);
      uint32_t boltHalo = strip.Color(150, 210, 255);
      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        int bx = boltCols[y];
        setPixelXY(bx, y, strip.gamma32(scaleColor(boltCore, lightningFlash)));
        setPixelXY((bx + 1) & (MATRIX_WIDTH - 1), y, strip.gamma32(scaleColor(boltHalo, lightningFlash * 0.55f)));
      }
    }

    showDisplay();
    t += 0.04f;
    if (delayWithTouch(22)) return;
  }
}

// ----------------------------------------------------------------------------
// 5. TRÌNH ĐIỀU PHỐI THỜI TIẾT TRỰC TIẾP (LIVE WEATHER ENGINE - MODE 15)
// ----------------------------------------------------------------------------
void effectWeatherLive(uint32_t durationMs) {
  switch (currentWeatherId) {
    case 1:  effectWeatherClearSky(durationMs); break;
    case 2:  effectWeatherCloudy(durationMs);   break;
    case 3:  effectWeatherRain(durationMs);     break;
    case 4:  effectWeatherStorm(durationMs);    break;
    default: effectWeatherClearSky(durationMs); break;
  }
}

// ----------------------------------------------------------------------------
// 6. CẬP NHẬT THỜI TIẾT TỪ APP / CLI & CHUYỂN VÀO CHẾ ĐỘ THỜI TIẾT SỐNG ĐỘNG
// ----------------------------------------------------------------------------
void triggerWeatherUpdate(int8_t temp, uint8_t weatherId, bool showBannerAndSwitchMode) {
  currentWeatherTemp = temp;
  currentWeatherId   = (weatherId >= 1 && weatherId <= 4) ? weatherId : 1;

  if (!showBannerAndSwitchMode) {
    dbgLog(TAG_FX, "Cập nhật dữ liệu thời tiết ngầm: %d°C (ID: %d)", temp, currentWeatherId);
    return;
  }

  SkyTimePalette sky = computeSkyPalette();
  bool isNight = (sky.dayFactor < 0.35f);

  const char* wLabel = "NANG";
  uint32_t wCol = sky.orbColor;

  switch (currentWeatherId) {
    case 1: // Quang đãng: Ban ngày báo "NANG", Ban đêm báo "QUANG"
      wLabel = isNight ? "QUANG" : "NANG";
      wCol   = sky.orbColor;
      break;
    case 2: // Nhiều mây
      wLabel = "MAY";
      wCol   = sky.cloudEdge;
      break;
    case 3: // Mưa
      wLabel = "MUA";
      wCol   = sky.rainColor;
      break;
    case 4: // Giông bão / Sấm chớp
      wLabel = "BAO";
      wCol   = strip.Color(210, 120, 255);
      break;
  }

  char banner[24];
  snprintf(banner, sizeof(banner), "%dC %s", (int)temp, wLabel);
  dbgLog(TAG_FX, "Đồng bộ thời tiết (%s): %s -> Chạy hiệu ứng Thời tiết 360° (Mode 15)",
         isNight ? "BAN ĐÊM" : "BAN NGÀY", banner);

  currentMode = 15; // Đặt Mode 15 trước khi cuộn chữ để nếu user bấm đổi mode giữa chừng sẽ không bị ghi đè
  markSettingsChanged();

  effectCylinderScrollText(banner, wCol, 55);
}

// ============================================================================
// MODE 13: NHỊP ĐẬP TRÁI TIM LÃNG MẠN (HEARTBEAT PULSE - TÌNH YÊU NỒNG NÀN)
// ============================================================================
void effectHeartbeatPulse(uint32_t durationMs) {
  unsigned long start = millis();

  // Bitmap trái tim 8x8 chuẩn pixel art căn giữa trên mỗi mặt 8x8
  static const uint8_t HEART_MAP[8] = {
    0b00000000,
    0b01100110,
    0b11111111,
    0b11111111,
    0b11111111,
    0b01111110,
    0b00111100,
    0b00011000
  };

  // Vùng tâm trái tim sáng điểm nhấn (inner highlight)
  static const uint8_t HEART_CORE[8] = {
    0b00000000,
    0b00000000,
    0b01000010,
    0b01100110,
    0b00111100,
    0b00011000,
    0b00000000,
    0b00000000
  };

  // Hạt bụi sao / đốm sáng lãng mạn bay bổng lên trên như tàn lửa tình yêu
  struct FloatingEmber {
    float x;
    float y;
    float vy;
    float phase;
  };
  const int NUM_EMBERS = 8;
  FloatingEmber embers[NUM_EMBERS];
  for (int i = 0; i < NUM_EMBERS; i++) {
    embers[i].x = (float)random(0, MATRIX_WIDTH);
    embers[i].y = (float)random(0, MATRIX_HEIGHT);
    embers[i].vy = 0.04f + ((float)random(20, 60) / 1000.0f);
    embers[i].phase = ((float)random(0, 628) / 100.0f);
  }

  const uint32_t cDeepRose   = strip.Color(180, 8, 45);    // Đỏ hoa hồng nhung
  const uint32_t cWarmPink   = strip.Color(255, 65, 130);  // Hồng ấm rực rỡ
  const uint32_t cSoftWhite  = strip.Color(255, 235, 245); // Ánh trắng ấm tại đỉnh nhịp đập
  const uint32_t cGlowPink   = strip.Color(210, 25, 95);   // Ánh hào quang hồng lan tỏa
  const uint32_t cBgBurgundy = strip.Color(20, 2, 8);      // Nền đỏ rượu vang sâu
  const uint32_t cBgWarmRose = strip.Color(38, 4, 18);     // Nền hồng cánh sen trầm ấm

  const uint32_t CYCLE_MS = 1150; // Chu kỳ nhịp đập ~52 bpm (êm đềm, tình cảm)

  while (millis() - start < durationMs) {
    uint32_t elapsed = millis() - start;
    uint32_t tCycle = elapsed % CYCLE_MS;
    float tSec = elapsed / 1000.0f;

    // 1. Nhịp đập đôi tự nhiên sinh học (lub-dub)
    float beatPulse = 0.0f;
    if (tCycle >= 50 && tCycle <= 280) {
      // Nhịp đập 1 (Lub) - Đột ngột bùng nở
      float p = (float)(tCycle - 50) / 230.0f;
      float s = sinsf(p * PI);
      beatPulse = s * s;
    } else if (tCycle >= 340 && tCycle <= 560) {
      // Nhịp đập 2 (Dub) - Âm vang dội lại
      float p = (float)(tCycle - 340) / 220.0f;
      float s = sinsf(p * PI);
      beatPulse = 0.80f * s * s;
    }

    // 2. Nền chuyển dịch chậm giữa các tông màu đỏ rượu ấm
    float bgShift = sin01(tSec * 0.35f);
    uint32_t bgCurrent = lerpColor(cBgBurgundy, cBgWarmRose, bgShift);

    // 3. Cập nhật hạt đốm sáng lơ lửng bay lên
    for (int i = 0; i < NUM_EMBERS; i++) {
      embers[i].y -= embers[i].vy;
      embers[i].x += 0.02f * sinsf(tSec * 1.5f + embers[i].phase);
      if (embers[i].y < -1.0f) {
        embers[i].y = (float)MATRIX_HEIGHT;
        embers[i].x = (float)random(0, MATRIX_WIDTH);
      }
    }

    // 4. Vẽ từng pixel lên ma trận 32x8
    for (int x = 0; x < MATRIX_WIDTH; x++) {
      int face = x / 8;        // 0, 1, 2, 3
      int lx = x % 8;          // 0..7 trên mặt hiện tại
      float faceCenterX = face * 8.0f + 3.5f;
      float faceCenterY = 3.5f;

      // Khoảng cách hình học tới tâm mặt (để tạo vầng hào quang tỏa tròn)
      float dx = (float)x - faceCenterX;

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        float dy = (float)y - faceCenterY;
        float dist = sqrtf(dx * dx + dy * dy);

        // Vầng hào quang hồng mềm mại tỏa lan giữa các nhịp đập
        float glowRadius = 2.4f + 2.2f * beatPulse + 0.35f * sinsf(tSec * 2.2f);
        float glowVal = expf(-dist / glowRadius) * (0.22f + 0.65f * beatPulse);
        uint32_t pixelCol = lerpColor(bgCurrent, cGlowPink, constrain(glowVal, 0.0f, 1.0f));

        // Kiểm tra hình vẽ trái tim 8x8
        bool isHeartPixel = (HEART_MAP[y] >> (7 - lx)) & 0x01;
        bool isCorePixel = (HEART_CORE[y] >> (7 - lx)) & 0x01;

        if (isHeartPixel) {
          // Màu thân trái tim: đỏ nhung -> hồng ấm theo nhịp đập
          uint32_t heartColor = lerpColor(cDeepRose, cWarmPink, beatPulse);

          if (isCorePixel && beatPulse > 0.45f) {
            // Điểm nhấn bừng sáng trắng ngọc ở đỉnh nhịp đập
            float coreBright = (beatPulse - 0.45f) / 0.55f;
            heartColor = lerpColor(heartColor, cSoftWhite, coreBright * 0.85f);
          }

          // Trái tim đập sáng bừng rực rỡ
          float heartBri = 0.70f + 0.30f * beatPulse;
          pixelCol = scaleColor(heartColor, heartBri);
        }

        // 5. Hòa trộn hạt sparkle/ember lãng mạn bay bổng
        for (int i = 0; i < NUM_EMBERS; i++) {
          float ex = embers[i].x;
          float ey = embers[i].y;
          float edx = fabsf((float)x - ex);
          if (edx > MATRIX_WIDTH * 0.5f) edx = MATRIX_WIDTH - edx;
          float edy = fabsf((float)y - ey);
          if (edx < 1.0f && edy < 1.0f) {
            float sparkBri = (1.0f - edx) * (1.0f - edy) * (0.4f + 0.6f * sinsf(tSec * 4.0f + embers[i].phase));
            pixelCol = lerpColor(pixelCol, strip.Color(255, 215, 230), constrain(sparkBri, 0.0f, 0.85f));
          }
        }

        setPixelXY(x, y, strip.gamma32(pixelCol));
      }
    }

    showDisplay();
    if (delayWithTouch(28)) return;
  }
}

// ============================================================================
// MODE 14: SÓNG TÌNH YÊU LAN TỎA (LOVE RIPPLE - GỢN SÓNG LÃNG MẠN MƠ MỘNG)
// ============================================================================
static inline uint32_t loveRipplePalette(float v) {
  v = fmodf(v, 1.0f);
  if (v < 0.0f) v += 1.0f;
  if (v < 0.25f) {
    return lerpColor(strip.Color(255, 75, 145), strip.Color(225, 25, 165), v * 4.0f); // Rose pink -> Magenta
  } else if (v < 0.50f) {
    return lerpColor(strip.Color(225, 25, 165), strip.Color(170, 90, 245), (v - 0.25f) * 4.0f); // Magenta -> Lavender
  } else if (v < 0.75f) {
    return lerpColor(strip.Color(170, 90, 245), strip.Color(120, 55, 225), (v - 0.50f) * 4.0f); // Lavender -> Soft violet
  } else {
    return lerpColor(strip.Color(120, 55, 225), strip.Color(255, 75, 145), (v - 0.75f) * 4.0f); // Soft violet -> Rose pink
  }
}

void effectLoveRipple(uint32_t durationMs) {
  unsigned long start = millis();
  const int NUM_SOURCES = 4;

  while (millis() - start < durationMs) {
    float t = (millis() - start) / 1000.0f;

    // Nhịp thở độ sáng tổng thể dịu êm
    float breath = 0.65f + 0.35f * sin01(t * 0.9f);

    // 4 tâm sóng tình yêu trôi dạt hữu cơ quanh 4 mặt của hình trụ
    float srcX[NUM_SOURCES];
    float srcY[NUM_SOURCES];
    for (int i = 0; i < NUM_SOURCES; i++) {
      float baseCenter = i * 8.0f + 3.5f;
      // Trôi dạt chậm theo quỹ đạo Lissajous mềm mại
      srcX[i] = baseCenter + 1.8f * sinsf(t * 0.55f + i * 1.57f);
      srcY[i] = 3.5f + 1.6f * cossf(t * 0.70f + i * 2.09f);
    }

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        float waveSum = 0.0f;
        float attenSum = 0.0f;

        // Tính giao thoa sóng từ các tâm nguồn (liền mạch 360 độ quanh trụ)
        for (int i = 0; i < NUM_SOURCES; i++) {
          float dx = fabsf((float)x - srcX[i]);
          if (dx > MATRIX_WIDTH * 0.5f) dx = MATRIX_WIDTH - dx; // Quấn vòng trụ
          float dy = (float)y - srcY[i];
          float dist = sqrtf(dx * dx + dy * dy);

          // Vòng sóng đồng tâm lan tỏa ra ngoài
          float w = sinsf(dist * 1.6f - t * 2.8f);
          float atten = 1.0f / (1.0f + dist * 0.32f);

          waveSum += w * atten;
          attenSum += atten;
        }

        float interference = waveSum / (attenSum + 0.001f); // Chuẩn hóa -1.0 .. 1.0
        float normVal = 0.5f + 0.5f * interference;

        // Chu kỳ màu sắc luân chuyển chậm
        float colorPhase = normVal * 0.45f + t * 0.05f;
        uint32_t baseCol = loveRipplePalette(colorPhase);

        // Độ sáng với độ tương phản gợn sóng đẹp mắt
        float bri = 0.20f + 0.80f * powf(normVal, 1.4f);
        uint32_t col = scaleColor(baseCol, bri * breath);

        // Lớp shimmer / hạt sao pastel lấp lánh như bụi tiên
        float shimmerNoise = sinsf(x * 12.9898f + y * 78.233f + t * 4.5f);
        if (shimmerNoise > 0.82f) {
          float shimmerIntensity = (shimmerNoise - 0.82f) / 0.18f;
          col = lerpColor(col, strip.Color(255, 235, 250), shimmerIntensity * 0.70f);
        }

        setPixelXY(x, y, strip.gamma32(col));
      }
    }

    showDisplay();
    if (delayWithTouch(26)) return;
  }
}

// ============================================================================
// HIỆU ỨNG TRẠNG THÁI NGUỒN SẠC IP2312
// ============================================================================
void effectChargingAmber(uint32_t durationMs) {
  unsigned long start = millis();
  const uint32_t cAmber = strip.Color(255, 130, 15);
  int step = 0;

  while ((durationMs == 0) ? true : (millis() - start < durationMs)) {
    if (userOverrideCharging || updateChargeState() != CHARGING) break;

    strip.clear();
    float breath = 0.35f + 0.65f * sin01(step * 0.12f);
    int maxRow = 1 + ((step / 4) % MATRIX_HEIGHT);

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      for (int y = 0; y < maxRow; y++) {
        setPixelXY(x, y, strip.gamma32(scaleColor(cAmber, breath)));
      }
    }
    showDisplay();
    step++;
    if (delayWithTouch(40)) break;
  }
}

void effectChargeFullGreen(uint32_t durationMs) {
  unsigned long start = millis();
  const uint32_t cGreen = strip.Color(20, 200, 40);

  while ((durationMs == 0) ? true : (millis() - start < durationMs)) {
    if (userOverrideCharging || updateChargeState() != FULL) break;

    for (int i = 0; i < NUM_LEDS; i++) {
      strip.setPixelColor(i, cGreen);
    }
    showDisplay();
    if (delayWithTouch(60)) break;
  }
}
