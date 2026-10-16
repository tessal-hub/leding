#include "effects.h"
#include "display.h"
#include "sensors.h"
#include "uart_debug.h"
#include <Wire.h>

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
// MODE 8: BẦU TRỜI NGÀN SAO HẸN ƯỚC (COSMIC STARRY NIGHT ROMANCE)
// Tinh vân tím vũ trụ trôi lững lờ 360°, ngàn sao đa cấp độ lấp lánh &
// sao băng ước nguyện rực rỡ lướt qua để lại vệt đuôi ánh sáng lãng mạn.
// ============================================================================
void effectStarryNight(uint32_t durationMs) {
  unsigned long start = millis();
  const int NUM_STARS = 32;
  struct Star {
    uint8_t x;
    uint8_t y;
    float phase;
    float speed;
    uint32_t color;
    bool isMajor;
  };
  Star stars[NUM_STARS];
  for (int i = 0; i < NUM_STARS; i++) {
    stars[i].x = random(0, MATRIX_WIDTH);
    stars[i].y = random(0, MATRIX_HEIGHT);
    stars[i].phase = (float)random(0, 628) / 100.0f;
    stars[i].speed = 1.0f + (float)random(8, 28) / 10.0f;
    uint8_t type = random(5);
    // Tông màu sao lãng mạn: Trăng vàng mật ong, Hồng ngọc, Bạch kim pha lê, Tím pastel, Vàng Champagne
    stars[i].color = (type == 0) ? strip.Color(255, 230, 150)
                   : (type == 1) ? strip.Color(255, 155, 205)
                   : (type == 2) ? strip.Color(240, 240, 255)
                   : (type == 3) ? strip.Color(215, 175, 255)
                                 : strip.Color(255, 215, 175);
    stars[i].isMajor = (i < 4); // 4 ngôi sao sáng rực rỡ nhất vòm trời có hào quang
  }

  // Sao băng ước nguyện (Shooting star / comet) lướt qua vòm trời 360°
  float shootX = -10.0f;
  float shootY = 0.0f;
  bool shootActive = false;
  unsigned long nextShootingTime = millis() + random(2500, 5000);

  // Bảng màu tinh vân vũ trụ (Cosmic Violet Nebula): không bao giờ để nền đen chết chóc
  const uint32_t cNebulaDark   = strip.Color(16, 5, 36);  // Tím than vũ trụ sâu thẳm
  const uint32_t cNebulaViolet = strip.Color(42, 12, 68); // Tím phong lan huyền bí
  const uint32_t cNebulaPlum   = strip.Color(62, 16, 56); // Bụi khí hồng rượu vang ấm áp

  while (millis() - start < durationMs) {
    float t = (millis() - start) / 1000.0f;

    // 1. Vẽ nền trời đêm tinh vân vũ trụ huyền ảo, trôi êm ái liền mạch 360°
    for (int y = 0; y < MATRIX_HEIGHT; y++) {
      for (int x = 0; x < MATRIX_WIDTH; x++) {
        // Dùng noise 3D tuần hoàn quanh chu vi trụ (period = 4)
        float n = noise3((float)x * 0.125f, (float)y * 0.25f, t * 0.09f, 4);
        uint32_t skyCol = palette3(n, cNebulaDark, cNebulaViolet, cNebulaPlum);
        setPixelXY(x, y, strip.gamma32(skyCol));
      }
    }

    // 2. Các vì sao tình yêu lấp lánh lung linh
    for (int i = 0; i < NUM_STARS; i++) {
      float b = sin01(t * stars[i].speed + stars[i].phase);
      float bri = b * b * b; // Đường cong phi tuyến giúp sao nhấp nháy tự nhiên
      if (bri > 0.03f) {
        uint32_t starCol = strip.gamma32(scaleColor(stars[i].color, bri));
        setPixelXY(stars[i].x, stars[i].y, starCol);

        // Với các ngôi sao lớn (Major stars), khi đạt độ sáng cao -> tỏa hào quang chữ thập 4 hướng
        if (stars[i].isMajor && bri > 0.45f) {
          float haloBri = (bri - 0.45f) * 0.45f;
          uint32_t haloCol = strip.gamma32(scaleColor(stars[i].color, haloBri));
          setPixelXY((uint8_t)wrapCylinderX((float)stars[i].x - 1), stars[i].y, haloCol);
          setPixelXY((uint8_t)wrapCylinderX((float)stars[i].x + 1), stars[i].y, haloCol);
          if (stars[i].y > 0) setPixelXY(stars[i].x, stars[i].y - 1, haloCol);
          if (stars[i].y < MATRIX_HEIGHT - 1) setPixelXY(stars[i].x, stars[i].y + 1, haloCol);
        }
      }
    }

    // 3. Sao băng ước nguyện (Comet) lướt nhẹ qua vòm trời 360°
    if (!shootActive && millis() > nextShootingTime) {
      shootActive = true;
      shootX = (float)random(0, MATRIX_WIDTH);
      shootY = 0.0f;
    }

    if (shootActive) {
      // Vệt đuôi sao băng lộng lẫy (6 pixel kéo dài)
      for (int trail = 5; trail >= 0; trail--) {
        float tx = shootX - trail * 0.95f;
        int ty = (int)round(shootY - trail * 0.40f);
        if (ty >= 0 && ty < MATRIX_HEIGHT) {
          uint8_t px = (uint8_t)wrapCylinderX(tx);
          float tailFactor = 1.0f - ((float)trail / 6.0f);
          // Đầu sao băng: Trắng kim cương rực rỡ -> Thân: Vàng mật ong -> Đuôi: Tím hồng bụi tiên
          uint32_t cometCol = (trail == 0) ? strip.Color(255, 255, 245)
                            : (trail <= 2) ? strip.Color(255, 220, 170)
                                           : strip.Color(215, 140, 255);
          setPixelXY(px, ty, strip.gamma32(scaleColor(cometCol, tailFactor * 0.95f)));
        }
      }
      shootX += 0.95f;
      shootY += 0.40f;
      if (shootY >= MATRIX_HEIGHT + 3) {
        shootActive = false;
        nextShootingTime = millis() + random(3500, 7500);
      }
    }

    showDisplay();
    if (delayWithTouch(28)) return;
  }
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
    strip.Color(255, 45, 95),     // Đỏ hoa hồng nhung (Crimson Rose)
    strip.Color(135, 45, 230),    // Tím mộng mơ (Romantic Violet)
    strip.Color(255, 60, 160),    // Hồng cánh sen (Blush Pink)
    strip.Color(255, 140, 50)     // Cam đào hoàng hôn (Sunset Peach)
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
// MODE 3: ÁNH NẾN HẸN HÒ LUNG LINH (CANDLELIGHT ROMANCE - LÃNG MẠN ẤM ÁP)
// ============================================================================
void effectCandlelight(uint32_t durationMs) {
  unsigned long start = millis();
  const uint32_t cCandleGlow = strip.Color(255, 110, 15); // Cam lửa mật ong ấm áp
  const uint32_t cCandleCore = strip.Color(255, 210, 90); // Vàng sáng tâm ngọn nến
  const uint32_t cBaseAmber  = strip.Color(160, 40, 5);   // Đỏ cam hổ phách chân nến
  const uint32_t cBgWarm     = strip.Color(15, 2, 4);     // Không gian tối ấm áp quanh nến

  while (millis() - start < durationMs) {
    float t = (millis() - start) / 1000.0f;

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      // 4 ngọn nến tại 4 tâm mặt (x = 3.5, 11.5, 19.5, 27.5)
      float faceX = fmodf((float)x, 8.0f);
      float distToFlame = fabsf(faceX - 3.5f);
      float flameShape = expf(-distToFlame * 0.65f);

      // Bập bùng ngọn lửa hữu cơ bằng sóng sin đa tần
      float flicker = 0.72f + 0.28f * sin01(t * 8.5f + x * 1.7f);
      flicker += 0.12f * (sin01(t * 19.0f + x * 3.1f) - 0.5f);
      flicker = constrain(flicker, 0.45f, 1.15f);

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        // Ngọn lửa nến từ đáy (hàng 7) vươn lên đỉnh (hàng 0)
        float heightFactor = (float)(7 - y) / 7.0f;
        float flameGlow = flameShape * (0.35f + 0.65f * (1.0f - heightFactor * 0.45f));
        flameGlow *= flicker;

        uint32_t col;
        if (flameGlow > 0.75f) {
          col = lerpColor(cCandleGlow, cCandleCore, (flameGlow - 0.75f) / 0.25f);
        } else {
          col = lerpColor(cBaseAmber, cCandleGlow, flameGlow / 0.75f);
        }
        col = lerpColor(cBgWarm, col, constrain(flameGlow, 0.0f, 1.0f));

        setPixelXY(x, y, strip.gamma32(col));
      }
    }

    showDisplay();
    if (delayWithTouch(30)) return;
  }
}

// ============================================================================
// 4. CỬ CHỈ YÊU THƯƠNG MPU (LOVE TILT & CARESS - ÁNH SÁNG ÂU YẾM)
// ============================================================================
void effectTiltGlow(uint32_t durationMs) {
  unsigned long start = millis();
  float fx = 0.0f, fy = 0.0f;      // Gia tốc đã lọc (đơn vị g)
  float flash = 0.0f;              // Độ sáng bùng khi lắc
  float prevMag = 1.0f;
  float t = 0.0f;

  // Bộ đệm lưu vệt sáng dư quang (trailing afterglow buffer)
  float trail[MATRIX_WIDTH][MATRIX_HEIGHT] = { {0.0f} };

  const uint32_t cA = strip.Color(255, 50, 120);       // Hồng cánh sen rực rỡ
  const uint32_t cB = strip.Color(255, 140, 50);       // Cam đào mật ong ấm áp
  const uint32_t cWhiteBurst = strip.Color(255, 230, 240); // Ánh hào quang hồng ngọc khi lắc

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
        float tiltBoost = (facing * facing) * (0.35f + 0.65f * tilt);
        // Nền thở ấm áp tự nhiên (38% - 50% độ sáng) khi đặt đèn nằm yên trên bàn
        float baseAmbient = 0.38f + 0.12f * sin01(t * 1.4f + x * 0.25f);
        float targetGlow = fmaxf(baseAmbient, tiltBoost);

        // Cập nhật vệt dư quang mượt mà (trailing afterglow effect)
        trail[x][y] = trail[x][y] * 0.78f + targetGlow * 0.26f;
        float glow = fmaxf(targetGlow, trail[x][y]);

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
// 7. CỰC QUANG TÌNH NHÂN (LOVER'S AURORA - TÍM MỘNG MƠ & HỒNG CÁNH SEN)
// ============================================================================
static inline uint32_t auroraMultiPalette(float v) {
  v = fmodf(v, 1.0f);
  if (v < 0.0f) v += 1.0f;
  if (v < 0.35f) {
    return lerpColor(strip.Color(130, 15, 210), strip.Color(245, 40, 130), v / 0.35f); // Tím mộng mơ -> Hồng cánh sen
  } else if (v < 0.70f) {
    return lerpColor(strip.Color(245, 40, 130), strip.Color(255, 130, 80), (v - 0.35f) / 0.35f); // Hồng cánh sen -> Cam đào hoàng hôn
  } else {
    return lerpColor(strip.Color(255, 130, 80), strip.Color(180, 25, 160), (v - 0.70f) / 0.30f); // Cam đào -> Tím hồng hoàng hôn
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
// MODE 6: BIỂN HOA OẢI HƯƠNG TÍM (LAVENDER TWILIGHT BREEZE)
// Cánh đồng oải hương tím ngát dập dờn trong làn gió hoàng hôn dịu êm,
// từng nhịp sóng hoa tím biếc đung đưa cùng những chú đom đóm tím lơ lửng.
// ============================================================================
void effectLavenderBreeze(uint32_t durationMs) {
  unsigned long start = millis();

  // Bảng màu cánh đồng oải hương hoàng hôn
  const uint32_t cTwilightSky    = strip.Color(26, 12, 54);   // Bầu trời chạng vạng tím chàm
  const uint32_t cLavenderShadow = strip.Color(48, 14, 76);   // Tím mận sâu lắng dưới gốc hoa
  const uint32_t cLavenderBloom  = strip.Color(162, 62, 235); // Hoa oải hương tím biếc nở rộ
  const uint32_t cPastelLilac    = strip.Color(225, 145, 255);// Sắc tím phấn pastel đón gió
  const uint32_t cFireflyGlow    = strip.Color(255, 225, 255);// Đom đóm tím bạch kim ấm áp

  // 4 chú đom đóm tím mộng mơ bay lượn trên cánh đồng
  struct Firefly {
    float baseX;
    float phase;
    float speedX;
    float speedY;
    float pulseSpeed;
  };
  Firefly fireflies[4] = {
    { 4.0f,  0.5f, 0.45f, 0.65f, 2.2f },
    { 12.0f, 2.1f, -0.38f, 0.75f, 1.8f },
    { 20.0f, 4.3f, 0.50f, 0.55f, 2.5f },
    { 28.0f, 5.8f, -0.42f, 0.80f, 2.0f }
  };

  while (millis() - start < durationMs) {
    float t = (millis() - start) / 1000.0f;

    // Gió thổi tuần hoàn quanh vòng trụ 360°
    float windOffset = t * 1.5f;

    for (int y = 0; y < MATRIX_HEIGHT; y++) {
      // Tỷ lệ độ cao từ gốc (y=0) lên ngọn/trời (y=7)
      float normY = (float)y / (MATRIX_HEIGHT - 1.0f);

      for (int x = 0; x < MATRIX_WIDTH; x++) {
        float theta = (x * 2.0f * PI) / MATRIX_WIDTH;

        // 1. Làn gió lay động sóng hoa oải hương (3 cụm sóng lướt quanh chu vi)
        float windSway = sin01(theta * 3.0f - windOffset + sinsf((float)y * 0.7f + t * 0.8f) * 1.1f);

        // 2. Độ nở rộ hoa oải hương: đậm đặc ở tầng trung và thấp (y = 1..5)
        float bloomLayer = 1.0f - fabsf(normY - 0.42f) / 0.55f;
        bloomLayer = constrain(bloomLayer, 0.0f, 1.0f);
        float bloomIntensity = powf(windSway, 1.5f) * bloomLayer;

        // Nội suy màu sắc: Nền chạng vạng -> Gốc tím mận -> Hoa nở rộ -> Ngọn tím phấn
        uint32_t baseCol = lerpColor(cTwilightSky, cLavenderShadow, (1.0f - normY) * 0.7f);
        uint32_t flowerCol = lerpColor(cLavenderBloom, cPastelLilac, windSway);
        uint32_t col = lerpColor(baseCol, flowerCol, bloomIntensity * 0.88f);

        // Thêm chuyển động nhịp thở nhẹ nhàng của buổi chiều tà
        float ambientBreath = 0.82f + 0.18f * sin01(t * 0.8f + (float)x * 0.1f);
        col = scaleColor(col, ambientBreath);

        setPixelXY(x, y, strip.gamma32(col));
      }
    }

    // 3. Đom đóm tím lơ lửng dạo chơi trên biển hoa
    for (int i = 0; i < 4; i++) {
      float fx = wrapCylinderX(fireflies[i].baseX + fireflies[i].speedX * t * 4.0f + 2.5f * sinsf(t * fireflies[i].speedY + fireflies[i].phase));
      float fy = 2.5f + 2.0f * cossf(t * fireflies[i].speedY * 1.2f + fireflies[i].phase);
      float pulse = powf(sin01(t * fireflies[i].pulseSpeed + fireflies[i].phase), 2.5f);

      if (pulse > 0.08f) {
        int ix = (int)round(fx);
        int iy = (int)round(fy);
        if (iy >= 0 && iy < MATRIX_HEIGHT) {
          uint8_t px = (uint8_t)wrapCylinderX((float)ix);
          uint32_t ffCol = strip.gamma32(scaleColor(cFireflyGlow, pulse * 0.95f));
          setPixelXY(px, iy, ffCol);

          // Hào quang tím nhạt lan nhẹ sang pixel lân cận
          if (pulse > 0.5f) {
            float halo = (pulse - 0.5f) * 0.4f;
            uint32_t haloCol = strip.gamma32(scaleColor(cPastelLilac, halo));
            setPixelXY((uint8_t)wrapCylinderX((float)px - 1), iy, haloCol);
            setPixelXY((uint8_t)wrapCylinderX((float)px + 1), iy, haloCol);
          }
        }
      }
    }

    showDisplay();
    if (delayWithTouch(26)) return;
  }
}

// ============================================================================
// CHẠY CHỮ 360° QUANH TRỤ: KHUNG CHỮ 6x8 (BỎ 2 HÀNG BIÊN TRÊN/DƯỚI)
// ============================================================================
static char g_marqueeText[128] = "LOVE * YOU";
static uint32_t g_marqueeColor = 0; // 0 = Cyan mặc định
static uint16_t g_marqueeSpeed = 125; // 125ms mặc định (~8 col/s, rất êm và dễ đọc)

void setMarqueeState(const char* text, uint32_t color, uint16_t speedDelay) {
  if (text != NULL && strlen(text) > 0) {
    strncpy(g_marqueeText, text, sizeof(g_marqueeText) - 1);
    g_marqueeText[sizeof(g_marqueeText) - 1] = '\0';
  }
  if (color != 0) {
    g_marqueeColor = color;
  }
  if (speedDelay >= 40 && speedDelay <= 500) {
    g_marqueeSpeed = speedDelay;
  }
}

void getMarqueeState(char* text, size_t maxLen, uint32_t &color, uint16_t &speedDelay) {
  if (text != NULL && maxLen > 0) {
    strncpy(text, g_marqueeText, maxLen - 1);
    text[maxLen - 1] = '\0';
  }
  color = (g_marqueeColor != 0) ? g_marqueeColor : strip.Color(0, 220, 255);
  speedDelay = (g_marqueeSpeed >= 40 && g_marqueeSpeed <= 500) ? g_marqueeSpeed : 125;
}

// Hàm nội bộ render 1 khung hình dải ruy băng chữ 360° khép kín quanh trụ
static void renderMarqueeRibbonFrame(const char* cleanText, size_t textLen, uint16_t loopWidth, uint32_t step, uint32_t color) {
  const uint8_t CHAR_WIDTH = 6;
  uint16_t textPixelWidth = (uint16_t)(textLen * CHAR_WIDTH);

  for (uint8_t screenX = 0; screenX < MATRIX_WIDTH; screenX++) {
    // Quấn chu kỳ 360 độ khép kín:
    uint16_t ribbonX = (screenX + step) % loopWidth;
    if (ribbonX < textPixelWidth) {
      uint8_t charIdx = ribbonX / CHAR_WIDTH;
      uint8_t colInChar = ribbonX % CHAR_WIDTH;
      if (colInChar < 5) {
        uint8_t colBits = getCharColBits(cleanText[charIdx], colInChar);
        for (uint8_t r = 0; r < 6; r++) {
          setPixelXY(screenX, 1 + r, ((colBits >> r) & 0x01) ? color : 0);
        }
      } else {
        // Cột khoảng trắng 1 pixel giữa các ký tự
        for (uint8_t r = 1; r <= 6; r++) setPixelXY(screenX, r, 0);
      }
    } else {
      // Khoảng trắng giữa các vòng lặp tin nhắn
      for (uint8_t r = 1; r <= 6; r++) setPixelXY(screenX, r, 0);
    }
    // Hàng 0 và hàng 7 để trống viền tinh tế
    setPixelXY(screenX, 0, 0);
    setPixelXY(screenX, 7, 0);
  }
}

void effectCylinderScrollText(const char* text, uint32_t color, uint16_t speedDelay) {
  if (text == NULL || strlen(text) == 0) return;
  char cleanText[128];
  sanitizeTextForDisplay(text, cleanText, sizeof(cleanText));
  size_t len = strlen(cleanText);
  if (len == 0) return;

  const uint8_t CHAR_WIDTH = 6;
  const uint8_t GAP_COLS = 8;
  uint16_t textPixelWidth = (uint16_t)(len * CHAR_WIDTH);
  uint16_t loopWidth = textPixelWidth + GAP_COLS;
  if (loopWidth < MATRIX_WIDTH) {
    loopWidth = MATRIX_WIDTH;
  }

  if (color == 0) color = strip.Color(0, 220, 255);
  if (speedDelay < 40 || speedDelay > 500) speedDelay = 125;

  // Cuộn tối thiểu 2 chu kỳ hoàn chỉnh quanh trụ
  uint32_t totalSteps = (uint32_t)loopWidth * 2;
  for (uint32_t step = 0; step < totalSteps; step++) {
    renderMarqueeRibbonFrame(cleanText, len, loopWidth, step, color);
    showDisplay();
    if (delayWithTouch(speedDelay)) return;
  }
}

// Mode 13: Chạy chữ 360° lãng mạn liên tục quanh thân đèn
void effectMarqueeMode(uint32_t durationMs) {
  char cleanText[128];
  sanitizeTextForDisplay(g_marqueeText, cleanText, sizeof(cleanText));
  size_t len = strlen(cleanText);
  if (len == 0) {
    strcpy(cleanText, "LOVE * YOU");
    len = strlen(cleanText);
  }

  const uint8_t CHAR_WIDTH = 6;
  const uint8_t GAP_COLS = 8;
  uint16_t textPixelWidth = (uint16_t)(len * CHAR_WIDTH);
  uint16_t loopWidth = textPixelWidth + GAP_COLS;
  if (loopWidth < MATRIX_WIDTH) {
    loopWidth = MATRIX_WIDTH;
  }

  uint32_t color = (g_marqueeColor != 0) ? g_marqueeColor : strip.Color(0, 220, 255);
  uint16_t speedDelay = (g_marqueeSpeed >= 40 && g_marqueeSpeed <= 500) ? g_marqueeSpeed : 125;

  unsigned long start = millis();
  uint32_t step = 0;

  while (durationMs == 0 || (millis() - start < durationMs)) {
    renderMarqueeRibbonFrame(cleanText, len, loopWidth, step, color);
    showDisplay();

    step = (step + 1) % loopWidth;
    if (delayWithTouch(speedDelay)) return;
  }
}

// ============================================================================
// MODE 5: LỬA ẤM ĐÊM ĐÔNG (COZY FIREPLACE ROMANCE - LÒ SƯỞI ẤM ÁP TÌNH NỒNG)
// ============================================================================
void effectCozyFireplace(uint32_t durationMs) {
  unsigned long start = millis();

  // Các đốm tàn than hồng lách tách bay lên trời đêm 360 độ
  struct FireEmber {
    float x;
    float y;
    float vy;
    float vx;
    float life;
  };
  const int NUM_EMBERS = 14;
  FireEmber embers[NUM_EMBERS];
  for (int i = 0; i < NUM_EMBERS; i++) {
    embers[i].x = (float)random(0, MATRIX_WIDTH);
    embers[i].y = (float)random(4, 8);
    embers[i].vy = 0.05f + ((float)random(25, 75) / 1000.0f);
    embers[i].vx = ((float)random(-20, 20) / 1000.0f);
    embers[i].life = (float)random(20, 100) / 100.0f;
  }

  // Tông màu ngọn lửa lò sưởi ấm cúng:
  const uint32_t cEmberBase = strip.Color(160, 18, 5);    // Đỏ than hồng rực rỡ đáy lò
  const uint32_t cFireWarm  = strip.Color(255, 85, 12);   // Cam lửa ấm nồng
  const uint32_t cFlameCore = strip.Color(255, 205, 55);  // Vàng sáng tâm ngọn lửa
  const uint32_t cRoseGlow  = strip.Color(210, 30, 60);   // Hồng ngọc tỏa nhiệt lãng mạn
  const uint32_t cDarkHearth= strip.Color(18, 2, 4);      // Không gian đêm ấm áp quanh lò

  float tiltBias = 0.0f;

  while (millis() - start < durationMs) {
    float t = (millis() - start) / 1000.0f;

    // Đọc góc nghiêng nhẹ từ MPU để ngọn lửa lượn theo tay khi cầm đèn
    int16_t ax, ay, az;
    if (readMpuAccel(ax, ay, az)) {
      tiltBias += ((ax / 16384.0f) * 1.5f - tiltBias) * 0.1f;
    }

    // 1. Cập nhật các hạt tàn than hồng lách tách
    for (int i = 0; i < NUM_EMBERS; i++) {
      embers[i].y -= embers[i].vy;
      embers[i].x += embers[i].vx + tiltBias * 0.03f;
      embers[i].life -= 0.015f;

      if (embers[i].y < -0.5f || embers[i].life <= 0.0f) {
        embers[i].x = (float)random(0, MATRIX_WIDTH);
        embers[i].y = (float)random(5, 8);
        embers[i].vy = 0.06f + ((float)random(25, 80) / 1000.0f);
        embers[i].vx = ((float)random(-25, 25) / 1000.0f);
        embers[i].life = 1.0f;
      }
    }

    // 2. Tính toán ma trận ngọn lửa 32x8
    for (int x = 0; x < MATRIX_WIDTH; x++) {
      // Sóng ngọn lửa hữu cơ đa tầng khép kín 360 độ
      float nx = (float)x / 4.0f;
      float fireNoise = noise3(nx + tiltBias * 0.2f, t * 1.6f, 0.5f, 8);
      float flameTop = 2.0f + 2.8f * fireNoise + 0.6f * sinsf(t * 3.5f + x * 0.8f);

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        // Càng gần đáy (y=7), ngọn lửa càng đặc và nóng; càng lên đỉnh (y=0), lửa thon dần
        float flameDist = (float)y - flameTop;
        float heat = constrain(flameDist / 3.8f, 0.0f, 1.0f);

        uint32_t col;
        if (heat > 0.70f) {
          // Lõi lửa nóng rực: cam sáng sang vàng mật ong
          float k = (heat - 0.70f) / 0.30f;
          col = lerpColor(cFireWarm, cFlameCore, k);
        } else if (heat > 0.25f) {
          // Thân ngọn lửa: đỏ than hồng sang cam ấm
          float k = (heat - 0.25f) / 0.45f;
          col = lerpColor(cRoseGlow, cFireWarm, k);
        } else {
          // Ngọn lửa tàn và không gian ấm xung quanh
          float k = heat / 0.25f;
          col = lerpColor(cDarkHearth, cRoseGlow, k);
        }

        // 3. Hòa trộn các tia tàn than hồng lách tách bay lên
        for (int i = 0; i < NUM_EMBERS; i++) {
          float edx = fabsf((float)x - embers[i].x);
          if (edx > MATRIX_WIDTH * 0.5f) edx = MATRIX_WIDTH - edx;
          float edy = fabsf((float)y - embers[i].y);
          if (edx < 1.0f && edy < 1.0f) {
            float sparkBri = (1.0f - edx) * (1.0f - edy) * embers[i].life;
            col = lerpColor(col, strip.Color(255, 230, 160), constrain(sparkBri, 0.0f, 0.95f));
          }
        }

        setPixelXY(x, y, strip.gamma32(col));
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
// CHẾ ĐỘ ĐÈN NẰM NGANG (ÁNH SÁNG VÀNG THÂN THIỆN CHO MẮT, DỊU DÀNG VỀ ĐÊM)
// Khi xoay ngang hoặc đặt nằm đèn xuống bàn/đầu giường: đèn tự động chuyển sang
// ánh sáng vàng mật ong ấm áp 2200K, không phát ánh sáng xanh, chống mỏi mắt.
// Dựng đứng đèn trở lại sẽ tự động khôi phục chế độ lãng mạn ban đầu.
// ============================================================================
void effectHorizontalWarmLight(uint32_t maxDurationMs) {
  dbgLog(TAG_FX, "KÍCH HOẠT ĐÈN NẰM NGANG: Ánh sáng vàng ấm thân thiện cho mắt!");
  unsigned long start = millis();

  // Tông màu vàng ấm dịu mắt 2200K - 2400K (chuẩn ánh sáng hoàng hôn ấm áp)
  const uint32_t cWarmGold     = strip.Color(255, 160, 40); // Vàng hổ phách mật ong dịu êm
  const uint32_t cSoftGlow     = strip.Color(255, 195, 75); // Vầng sáng ấm tỏa nhẹ
  const uint32_t cCozyAmber    = strip.Color(210, 115, 20); // Đỏ cam hoàng hôn trầm ấm

  fadeTransition(160);

  float smoothRollAngle = 0.0f;

  while ((maxDurationMs == 0) ? true : (millis() - start < maxDurationMs)) {
    // 1. Nếu dựng đứng đèn trở lại -> Tự động thoát và quay về chế độ trước
    if (!checkHorizontalOrientation()) {
      dbgLog(TAG_FX, "Đèn đã được dựng đứng lại -> Tự động khôi phục chế độ bình thường!");
      fadeTransition(180);
      return;
    }

    float t = (millis() - start) / 1000.0f;
    // Nhịp thở sinh học vô cùng êm dịu (~4.5s/chu kỳ, biên độ nhẹ ±8% giữ độ sáng ổn định cho mắt)
    float gentleBreath = 0.92f + 0.08f * sin01(t * 1.4f);

    // Đọc góc xoay quanh thân đèn từ MPU khi đang nằm ngang
    int16_t ax, ay, az;
    if (readMpuAccel(ax, ay, az)) {
      float gx = (float)ax / 16384.0f;
      float gy = (float)ay / 16384.0f;
      float rollTarget = atan2f(gy, gx);
      float dAngle = rollTarget - smoothRollAngle;
      while (dAngle > PI) dAngle -= 2.0f * PI;
      while (dAngle < -PI) dAngle += 2.0f * PI;
      smoothRollAngle += dAngle * 0.12f;
    }

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      float theta = (x * 2.0f * PI) / MATRIX_WIDTH;
      // Góc tương đối so với mặt hướng lên trên khi nằm ngang
      float relAngle = theta - smoothRollAngle;
      float surfaceFactor = 0.85f + 0.15f * cos01(relAngle);

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        // Chuyển sắc dọc nhẹ nhàng từ chân đến ngọn trụ
        float vGrad = (float)y / (float)(MATRIX_HEIGHT - 1);
        uint32_t baseCol = lerpColor(cCozyAmber, cWarmGold, smooth01(vGrad * 0.8f + 0.2f));

        // Hòa sắc điểm nhấn ấm áp
        uint32_t finalCol = lerpColor(baseCol, cSoftGlow, surfaceFactor * 0.25f);

        // Áp dụng nhịp thở êm dịu và gamma chống chói
        setPixelXY(x, y, strip.gamma32(scaleColor(finalCol, gentleBreath)));
      }
    }

    showDisplay();
    if (delayWithTouch(30)) {
      fadeTransition(160);
      return;
    }
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
// ============================================================================
// CHẾ ĐỘ MÀU TĨNH TÙY CHỌN THEO APP (MODE 10 - PHẢN ỨNG CHUYỂN ĐỘNG MPU)
// Khi đứng yên trên bàn: hiển thị 100% màu tĩnh thuần khiết nguyên bản.
// Khi nghiêng hoặc nhấc đèn: ánh sáng dâng quầng lụa theo góc nghiêng trọng lực
// và tạo làn sóng gợn lung linh phản hồi theo từng cử động của bàn tay!
// ============================================================================
void effectStaticColor(uint32_t durationMs) {
  unsigned long start = millis();
  float smoothAngle = 0.0f;
  float smoothTilt = 0.0f;
  float motionImpulse = 0.0f;
  float prevTotalG = 1.0f;

  while (millis() - start < durationMs) {
    uint8_t r, g, b;
    getCustomStaticColor(r, g, b);
    uint32_t baseCol = strip.Color(r, g, b);
    float t = (millis() - start) / 1000.0f;

    // Đọc cảm biến gia tốc MPU-6050
    int16_t ax, ay, az;
    if (readMpuAccel(ax, ay, az)) {
      float gx = (float)ax / 16384.0f;
      float gy = (float)ay / 16384.0f;
      float gz = (float)az / 16384.0f;

      // 1. Nhận diện góc nghiêng 360 độ quanh thân trụ
      float targetAngle = atan2f(gy, gx);
      float targetTilt = constrain(sqrtf(gx * gx + gy * gy) / 0.65f, 0.0f, 1.0f);

      // Lọc thông thấp mượt mà góc và độ nghiêng (Low-pass filter)
      smoothTilt += (targetTilt - smoothTilt) * 0.15f;
      float dAngle = targetAngle - smoothAngle;
      if (dAngle > PI) dAngle -= 2.0f * PI;
      else if (dAngle < -PI) dAngle += 2.0f * PI;
      smoothAngle += dAngle * 0.15f;

      // 2. Nhận diện gia tốc chuyển động & chạm lắc (Dynamic Motion Jerk)
      float totalG = sqrtf(gx * gx + gy * gy + gz * gz);
      float dG = fabsf(totalG - prevTotalG);
      prevTotalG = totalG;

      if (dG > 0.045f) {
        motionImpulse += dG * 2.5f;
        if (motionImpulse > 1.0f) motionImpulse = 1.0f;
      }
    }
    // Suy hao êm dịu động năng chuyển động
    motionImpulse *= 0.91f;

    // 3. Kết xuất ma trận 32x8 phản ứng với MPU
    for (int x = 0; x < MATRIX_WIDTH; x++) {
      float a = (x * 2.0f * PI) / MATRIX_WIDTH;
      // Hướng mặt đối diện với góc nghiêng (1.0 = phía nghiêng xuống, 0.0 = phía đối diện)
      float facing = cos01(a - smoothAngle);

      // Quầng sáng lụa trôi theo góc nghiêng (Tilt Sheen): phía nghiêng sáng bừng hơn (+35%)
      float tiltFactor = (facing * facing) * smoothTilt;
      float tiltBriMod = (1.0f - smoothTilt * 0.20f) + tiltFactor * 0.55f;

      // Sóng gợn ánh sáng khi chạm / lắc / nhấc đèn (Motion Ripple Wave)
      float wave = 0.0f;
      if (motionImpulse > 0.02f) {
        wave = sinsf(a * 2.0f - t * 7.0f) * motionImpulse * 0.35f;
      }

      float finalBri = constrain(tiltBriMod + wave, 0.20f, 1.45f);
      uint32_t col = scaleColor(baseCol, finalBri);

      // Khi chuyển động rõ rệt: viền sáng ánh kim lướt qua thân đèn
      if (motionImpulse > 0.25f) {
        float sparkle = (motionImpulse - 0.25f) / 0.75f;
        col = lerpColor(col, strip.Color(255, 248, 255), sparkle * 0.28f);
      }

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        setPixelXY(x, y, strip.gamma32(col));
      }
    }

    showDisplay();
    if (delayWithTouch(22)) return;
  }
}

// ============================================================================
// MODE 11: 4 KHUNG HÌNH KỶ NIỆM (4-SLOT LOVE MEMORIES - PHẢN ỨNG MPU)
// Khi nghiêng đèn: rọi sáng nổi bật bức tranh ở mặt hướng về người nhìn (+35%).
// Khi nhấc đèn hoặc xoay: vệt hào quang pha lê quét qua 4 khung tranh lung linh.
// ============================================================================
void effectFourSlotsCustom(uint32_t durationMs) {
  unsigned long start = millis();
  float smoothAngle = 0.0f;
  float smoothTilt = 0.0f;
  float motionImpulse = 0.0f;
  float prevTotalG = 1.0f;

  while (millis() - start < durationMs) {
    float t = (millis() - start) / 1000.0f;
    uint8_t frame = (millis() / 350) % 2;

    // 1. Vẽ 4 ô hình kỷ niệm nguyên bản
    renderFourSlotsAnimated(frame);

    // 2. Đọc cảm biến chuyển động MPU-6050
    int16_t ax, ay, az;
    if (readMpuAccel(ax, ay, az)) {
      float gx = (float)ax / 16384.0f;
      float gy = (float)ay / 16384.0f;
      float gz = (float)az / 16384.0f;

      float targetAngle = atan2f(gy, gx);
      float targetTilt = constrain(sqrtf(gx * gx + gy * gy) / 0.65f, 0.0f, 1.0f);

      smoothTilt += (targetTilt - smoothTilt) * 0.15f;
      float dAngle = targetAngle - smoothAngle;
      if (dAngle > PI) dAngle -= 2.0f * PI;
      else if (dAngle < -PI) dAngle += 2.0f * PI;
      smoothAngle += dAngle * 0.15f;

      float totalG = sqrtf(gx * gx + gy * gy + gz * gz);
      float dG = fabsf(totalG - prevTotalG);
      prevTotalG = totalG;
      if (dG > 0.045f) {
        motionImpulse += dG * 2.5f;
        if (motionImpulse > 1.0f) motionImpulse = 1.0f;
      }
    }
    motionImpulse *= 0.91f;

    // 3. Nếu có độ nghiêng hoặc chuyển động -> Điều biến ánh sáng tương tác MPU
    if (smoothTilt > 0.05f || motionImpulse > 0.02f) {
      for (int x = 0; x < MATRIX_WIDTH; x++) {
        float a = (x * 2.0f * PI) / MATRIX_WIDTH;
        float facing = cos01(a - smoothAngle);

        // Mặt nghiêng về hướng người dùng được rọi sáng nổi bật (+35%)
        float tiltFactor = (facing * facing) * smoothTilt;
        float tiltMod = (1.0f - smoothTilt * 0.18f) + tiltFactor * 0.50f;

        // Vệt hào quang lấp lánh (Shimmer beam) quét qua tranh khi có chuyển động
        float sheen = 0.0f;
        if (motionImpulse > 0.02f) {
          sheen = sinsf(a * 2.0f - t * 6.5f) * motionImpulse * 0.35f;
        }

        float factor = constrain(tiltMod + sheen, 0.25f, 1.45f);

        for (int y = 0; y < MATRIX_HEIGHT; y++) {
          uint16_t idx = XY(x, y);
          uint32_t c = strip.getPixelColor(idx);
          if (c != 0) { // Chỉ điều biến các pixel có màu của bức tranh
            uint32_t modCol = scaleColor(c, factor);
            if (motionImpulse > 0.30f) {
              modCol = lerpColor(modCol, strip.Color(255, 245, 255), (motionImpulse - 0.30f) * 0.25f);
            }
            strip.setPixelColor(idx, modCol);
          }
        }
      }
    }

    showDisplay();
    if (delayWithTouch(28)) return;
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

// ============================================================================
// MODE 12: ĐÓA HỒNG HÉ NỞ (BLOOMING ROSE OF LOVE - HOA HỒNG TÌNH YÊU NỞ RỘ)
// ============================================================================
void effectBloomingRose(uint32_t durationMs) {
  unsigned long start = millis();
  const float CYCLE_SEC = 5.6f; // Chu kỳ hoa hồng nở rồi khép ~5.6 giây

  const uint32_t cRoseCore  = strip.Color(210, 8, 45);    // Đỏ nhung ruby tâm hoa
  const uint32_t cPetalMid  = strip.Color(255, 45, 125);  // Hồng cánh sen rực rỡ
  const uint32_t cPetalEdge = strip.Color(255, 165, 195); // Hồng phấn viền cánh
  const uint32_t cGlowGold  = strip.Color(255, 220, 145); // Ánh sương sớm vàng kim lấp lánh
  const uint32_t cBgDream   = strip.Color(20, 2, 14);     // Đêm huyền ảo mộng mơ

  while (millis() - start < durationMs) {
    float elapsed = (millis() - start) / 1000.0f;
    float normCycle = fmodf(elapsed, CYCLE_SEC) / CYCLE_SEC; // 0.0 -> 1.0

    // Nhịp bung nở hoa hồng: Từ nụ e ấp (0.0 -> 0.45), Nở rộ cực đại (0.45 -> 0.70), Khép lại thư thái (0.70 -> 1.0)
    float bloom;
    if (normCycle < 0.45f) {
      bloom = smooth01(normCycle / 0.45f);
    } else if (normCycle < 0.70f) {
      bloom = 1.0f + 0.08f * sinsf((normCycle - 0.45f) * (PI / 0.25f)); // Hoa khẽ rung rinh trong gió nhẹ
    } else {
      bloom = 1.0f - smooth01((normCycle - 0.70f) / 0.30f);
    }

    float maxRadius = 1.4f + 3.6f * bloom; // Bán kính cánh hoa nở bung từ 1.4 lên 5.0

    for (int x = 0; x < MATRIX_WIDTH; x++) {
      int face = x / 8;
      float faceCenterX = face * 8.0f + 3.5f;
      float faceCenterY = 3.5f;
      float dx = (float)x - faceCenterX;

      for (int y = 0; y < MATRIX_HEIGHT; y++) {
        float dy = (float)y - faceCenterY;
        float dist = sqrtf(dx * dx + dy * dy);
        float angle = atan2f(dy, dx);

        // Tạo 5 cánh hoa hồng uốn lượn mềm mại (5-lobed rose petal shape)
        float petalWave = 0.28f * cosf(5.0f * angle + elapsed * 0.4f);
        float shapedDist = dist * (1.0f - petalWave);

        float petalFactor = shapedDist / (maxRadius + 0.001f);

        uint32_t col;
        if (petalFactor <= 0.35f) {
          // Tâm nhung ruby đỏ thắm
          float k = petalFactor / 0.35f;
          col = lerpColor(cRoseCore, cPetalMid, k);
        } else if (petalFactor <= 0.85f) {
          // Thân cánh hoa hồng rực rỡ
          float k = (petalFactor - 0.35f) / 0.50f;
          col = lerpColor(cPetalMid, cPetalEdge, k);
        } else if (petalFactor <= 1.15f) {
          // Viền cánh hoa phát sáng vàng óng ánh sương
          float k = (petalFactor - 0.85f) / 0.30f;
          col = lerpColor(cPetalEdge, cGlowGold, k);
        } else {
          // Vùng nền tỏa hào quang ấm quanh bông hoa
          float aura = expf(-(petalFactor - 1.15f) * 1.8f) * (0.35f + 0.35f * bloom);
          col = lerpColor(cBgDream, cPetalMid, constrain(aura, 0.0f, 0.75f));
        }

        // Điểm xuyết hạt sương lấp lánh trên cánh hoa
        float dew = sinsf(x * 17.13f + y * 31.41f + elapsed * 2.0f);
        if (dew > 0.88f && petalFactor < 1.0f) {
          col = lerpColor(col, strip.Color(255, 245, 230), (dew - 0.88f) * 6.0f);
        }

        setPixelXY(x, y, strip.gamma32(col));
      }
    }

    showDisplay();
    if (delayWithTouch(30)) return;
  }
}

// ----------------------------------------------------------------------------
// CẬP NHẬT THỜI TIẾT TỪ APP / CLI & KÍCH HOẠT ANIMATION THỜI TIẾT 360° SỐNG ĐỘNG
// ----------------------------------------------------------------------------
void triggerWeatherUpdate(int8_t temp, uint8_t weatherId, bool switchMode) {
  currentWeatherTemp = temp;
  currentWeatherId   = (weatherId >= 1 && weatherId <= 4) ? weatherId : 1;

  uint8_t targetMode = 14;
  const char* weatherDesc = "NANG DEP";

  switch (currentWeatherId) {
    case 1:
      targetMode = 14; // Mode 14: Nắng đẹp & mặt trời xoay
      weatherDesc = "NANG DEP";
      break;
    case 2:
      targetMode = 16; // Mode 16: Mây trời đa tầng
      weatherDesc = "NHIEU MAY";
      break;
    case 3:
      targetMode = 15; // Mode 15: Mưa rơi & vũng nước
      weatherDesc = "MUA ROI";
      break;
    case 4:
      targetMode = 17; // Mode 17: Giông bão sấm chớp
      weatherDesc = "GIONG BAO";
      break;
  }

  if (switchMode) {
    if (currentMode != targetMode) {
      fadeTransition(240);
    }
    currentMode = targetMode;
    markSettingsChanged();
    dbgLog(TAG_FX, "Đồng bộ thời tiết: %d°C | ID: %d (%s) -> Chuyển trực tiếp sang Animation Mode %d",
           temp, currentWeatherId, weatherDesc, targetMode);
  } else {
    dbgLog(TAG_FX, "Cập nhật thời tiết ngầm: %d°C | ID: %d (%s) (Giữ nguyên Mode %d)",
           temp, currentWeatherId, weatherDesc, currentMode);
  }
}

// Chạy hoạt hình thời tiết sống động 360° theo trạng thái hiện tại (dùng cho chu kỳ tuần hoàn Mode 0)
void effectWeatherLive(uint32_t durationMs) {
  switch (currentWeatherId) {
    case 1:  effectWeatherClearSky(durationMs); break;
    case 2:  effectWeatherCloudy(durationMs);   break;
    case 3:  effectWeatherRain(durationMs);     break;
    case 4:  effectWeatherStorm(durationMs);    break;
    default: effectWeatherClearSky(durationMs); break;
  }
}

// ============================================================================
// MODE 1: NHỊP ĐẬP TRÁI TIM LÃNG MẠN (HEARTBEAT PULSE - TÌNH YÊU NỒNG NÀN)
// ============================================================================
void effectHeartbeatPulse(uint32_t durationMs) {
  unsigned long start = millis();

  // Bitmap trái tim 8x8 căn chuẩn tâm (hàng 0..6 đầy đặn, hàng 7 nhọn đáy)
  static const uint8_t HEART_MAP[8] = {
    0b01100110,
    0b11111111,
    0b11111111,
    0b11111111,
    0b01111110,
    0b00111100,
    0b00011000,
    0b00000000
  };

  // Vùng tâm trái tim sáng điểm nhấn (inner highlight)
  static const uint8_t HEART_CORE[8] = {
    0b00000000,
    0b01000010,
    0b01100110,
    0b00111100,
    0b00011000,
    0b00000000,
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
  const int NUM_EMBERS = 10;
  FloatingEmber embers[NUM_EMBERS];
  for (int i = 0; i < NUM_EMBERS; i++) {
    embers[i].x = (float)random(0, MATRIX_WIDTH);
    embers[i].y = (float)random(0, MATRIX_HEIGHT);
    embers[i].vy = 0.04f + ((float)random(20, 60) / 1000.0f);
    embers[i].phase = ((float)random(0, 628) / 100.0f);
  }

  const uint32_t cDeepRose   = strip.Color(220, 12, 50);   // Đỏ hoa hồng nhung
  const uint32_t cWarmPink   = strip.Color(255, 75, 140);  // Hồng ấm rực rỡ
  const uint32_t cSoftWhite  = strip.Color(255, 240, 248); // Ánh trắng ngọc tại đỉnh nhịp đập
  const uint32_t cGlowPink   = strip.Color(225, 30, 100);  // Ánh hào quang hồng lan tỏa
  const uint32_t cBgBurgundy = strip.Color(35, 4, 14);     // Nền đỏ rượu vang sâu
  const uint32_t cBgWarmRose = strip.Color(55, 6, 25);     // Nền hồng cánh sen trầm ấm

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
// MODE 2: PHA LÊ THẠCH ANH TÍM (AMETHYST CRYSTAL DREAM)
// Khối trụ pha lê thạch anh hoàng gia lấp lánh phản chiếu đa giác khúc xạ
// ánh tím quý phái, những tia sáng ngọc bích dâng trào và tia kim cương lấp lánh.
// ============================================================================
void effectAmethystCrystal(uint32_t durationMs) {
  unsigned long start = millis();

  // Bảng màu đá quý thạch anh hoàng gia
  const uint32_t cDeepAmethyst = strip.Color(38, 5, 75);    // Tím hoàng gia huyền bí
  const uint32_t cVibrantViolet= strip.Color(135, 28, 205); // Tím thạch anh rực rỡ
  const uint32_t cRoseQuartz   = strip.Color(210, 85, 240); // Hồng tím thạch anh pha lê
  const uint32_t cDiamondSpark = strip.Color(255, 245, 255); // Tia sáng kim cương tinh khiết

  while (millis() - start < durationMs) {
    float t = (millis() - start) / 1000.0f;

    // Nhịp thở pha lê sâu lắng, vương giả
    float crystalBreath = 0.80f + 0.20f * sin01(t * 0.75f);

    for (int y = 0; y < MATRIX_HEIGHT; y++) {
      for (int x = 0; x < MATRIX_WIDTH; x++) {
        float theta = (x * 2.0f * PI) / MATRIX_WIDTH;

        // 1. Khúc xạ bề mặt góc đa diện pha lê (6 mặt lăng kính khúc xạ ánh sáng 360°)
        float facet1 = sin01(theta * 6.0f + (float)y * 0.65f + t * 0.55f);
        float facet2 = sin01(theta * 3.0f - (float)y * 0.95f - t * 0.40f);
        float internalLight = 0.55f * facet1 + 0.45f * facet2;
        float caustic = powf(internalLight, 2.0f);

        // 2. Tia sáng năng lượng thạch anh dâng trào từ đáy lên đỉnh
        float upWave = sin01((float)y * 0.85f - t * 1.3f + sinsf(theta * 2.0f + t * 0.6f) * 1.2f);
        float crystalEnergy = caustic * 0.65f + upWave * 0.35f;

        // Nội suy màu sắc từ thạch anh tím sâu thẳm đến tím sáng và hồng thạch anh
        uint32_t col = palette3(crystalEnergy, cDeepAmethyst, cVibrantViolet, cRoseQuartz);

        // 3. Vết lóe sáng kim cương (Prismatic diamond glints) trên các giác cắt
        float glintNoise = noise3((float)x * 0.25f, (float)y * 0.5f, t * 1.6f, 8);
        if (glintNoise > 0.80f) {
          float glintStrength = (glintNoise - 0.80f) / 0.20f;
          col = lerpColor(col, cDiamondSpark, powf(glintStrength, 1.8f) * 0.90f);
        }

        setPixelXY(x, y, strip.gamma32(scaleColor(col, crystalBreath)));
      }
    }

    showDisplay();
    if (delayWithTouch(25)) return;
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
  const uint32_t cWarmRose = strip.Color(255, 95, 120); // Hồng cánh sen ấm dịu lãng mạn (không dùng xanh lá)

  while ((durationMs == 0) ? true : (millis() - start < durationMs)) {
    if (userOverrideCharging || updateChargeState() != FULL) break;

    for (int i = 0; i < NUM_LEDS; i++) {
      strip.setPixelColor(i, cWarmRose);
    }
    showDisplay();
    if (delayWithTouch(60)) break;
  }
}
