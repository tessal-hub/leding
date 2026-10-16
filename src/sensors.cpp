#include "sensors.h"
#include "display.h"
#include "effects.h"
#include "uart_debug.h"
#include "ble_service.h"
#include <Wire.h>
#include <Preferences.h>
#include <esp_adc_cal.h>

// Biến trạng thái toàn cục
bool mpuFound = false;
uint8_t currentBrightness = DEFAULT_BRIGHTNESS;
uint8_t currentSpeed = 50;
volatile uint8_t currentMode = DEFAULT_MODE;

ChargeState currentChargeState = DISCHARGING;
bool userOverrideCharging = false;

static ChargeState rawLastState = DISCHARGING;
static unsigned long stateDebounceTimer = 0;

static Preferences prefs;
static float filteredBrightness = DEFAULT_BRIGHTNESS;
static unsigned long lastMPUReadTime = 0;

// Trạng thái nút bấm
static bool isButtonPressed = false;
static bool isAdjustingBrightness = false;
static unsigned long buttonPressStartTime = 0;

// Trạng thái phát hiện OTA qua giữ nút cố định
bool isOtaModeActive = false;
static int16_t initialAx = 0, initialAy = 0, initialAz = 0;
static bool hasMovedDuringHold = false;

bool chgStatActiveLow = CHG_STAT_ACTIVE_LOW;
static unsigned long lastRedPulseTime = 0;
static bool hasRedPulse = false;
static bool wasHorizontalTilted = false;
bool checkHorizontalOrientation();

#if CONFIG_IDF_TARGET_ESP32C3
static void initAdcCal() {
  analogReadResolution(12);
  analogSetAttenuation(ADC_11db);
  // Bật pull-down phần cứng ESP32-C3 cho 2 chân sạc
  gpio_pulldown_en((gpio_num_t)PIN_VBUS);
  gpio_pulldown_en((gpio_num_t)PIN_CHG_STAT);
  dbgLog(TAG_PWR, "ADC eFuse: Kích hoạt bộ chuyển đổi ADC hiệu chuẩn nhà máy của chip ESP32-C3!");
}

uint16_t getStatPinMilliVolts() {
  uint32_t sum = 0;
  for (int i = 0; i < 8; i++) {
    sum += analogReadMilliVolts(PIN_CHG_STAT);
    delayMicroseconds(40);
  }
  return (uint16_t)(sum / 8);
}

bool isVbusPlugged() {
  uint32_t sum = 0;
  for (int i = 0; i < 8; i++) {
    sum += analogReadMilliVolts(PIN_VBUS);
    delayMicroseconds(40);
  }
  uint16_t avgMv = (uint16_t)(sum / 8);
  return (avgMv >= 1500) || (digitalRead(PIN_VBUS) == HIGH && avgMv >= 1000);
}
#else
// eFuse VRef Calibration (Dành cho chip ESP32 Classic D0WDQ6)
static esp_adc_cal_characteristics_t adcChars;
static bool efuseCalAvailable = false;

static void initAdcCal() {
  esp_adc_cal_value_t val_type = esp_adc_cal_characterize(ADC_UNIT_1, ADC_ATTEN_DB_11, ADC_WIDTH_BIT_12, 1100, &adcChars);
  if (val_type == ESP_ADC_CAL_VAL_EFUSE_VREF) {
    efuseCalAvailable = true;
    dbgLog(TAG_PWR, "ADC eFuse: Kích hoạt thành công VRef hiệu chuẩn nhà máy của chip ESP32-D0WDQ6!");
  } else if (val_type == ESP_ADC_CAL_VAL_EFUSE_TP) {
    efuseCalAvailable = true;
    dbgLog(TAG_PWR, "ADC eFuse: Kích hoạt Two-Point calibration của chip ESP32-D0WDQ6!");
  } else {
    dbgLog(TAG_PWR, "ADC eFuse: Khởi tạo hoàn tất bộ ước lượng ADC.");
  }
}

uint16_t getStatPinMilliVolts() {
  uint32_t sum = 0;
  for (int i = 0; i < 8; i++) {
    uint32_t raw = analogRead(PIN_CHG_STAT);
    sum += efuseCalAvailable ? (uint16_t)esp_adc_cal_raw_to_voltage(raw, &adcChars) : (uint16_t)((raw * 3300) / 4095);
    delayMicroseconds(40);
  }
  return (uint16_t)(sum / 8);
}

bool isVbusPlugged() {
  uint32_t sum = 0;
  for (int i = 0; i < 8; i++) {
    uint32_t raw = analogRead(PIN_VBUS);
    sum += efuseCalAvailable ? (uint16_t)esp_adc_cal_raw_to_voltage(raw, &adcChars) : (uint16_t)((raw * 3300) / 4095);
    delayMicroseconds(40);
  }
  uint16_t avgMv = (uint16_t)(sum / 8);
  return (avgMv >= 1500) || (digitalRead(PIN_VBUS) == HIGH && avgMv >= 1000);
}
#endif

bool isStatPinHigh() {
  uint16_t mv = getStatPinMilliVolts();
  if (mv >= CHG_STAT_THRESHOLD_MV) {
    lastRedPulseTime = millis();
    hasRedPulse = true;
  }
  if (!hasRedPulse) return false;
  return (millis() - lastRedPulseTime < CHG_PULSE_HOLD_MS);
}

static bool debouncedVbus = false;
static bool lastRawVbus = false;
static unsigned long vbusDebounceTimer = 0;
static unsigned long vbusConnectTime = 0;

ChargeState updateChargeState() {
#if !ENABLE_BATTERY_MONITOR
  currentChargeState = DISCHARGING;
  return DISCHARGING;
#else
  unsigned long now = millis();
  static unsigned long lastChgCheck = 0;
  if (now - lastChgCheck < 100 && lastChgCheck != 0) {
    return currentChargeState;
  }
  lastChgCheck = now;

  bool rawVbus = isVbusPlugged();
  // Lọc chống dội VBUS 250ms (triệt tiêu 100% nhiễu khi rút cắm hoặc tải xung LED)
  if (rawVbus != lastRawVbus) {
    lastRawVbus = rawVbus;
    vbusDebounceTimer = now;
  } else if (now - vbusDebounceTimer >= 250) {
    if (rawVbus != debouncedVbus) {
      debouncedVbus = rawVbus;
      if (debouncedVbus) {
        vbusConnectTime = now;
        hasRedPulse = false;
        dbgLog(TAG_PWR, "VBUS: ĐÃ PHÁT HIỆN CẮM NGUỒN 5V USB (Điện áp VBUS >= 1.5V)!");
      } else {
        hasRedPulse = false;
        dbgLog(TAG_PWR, "VBUS: ĐÃ RÚT CÁP NGUỒN 5V USB (Chuyển sang pin)!");
      }
    }
  }

  bool statHigh = isStatPinHigh();
  // Active Low: Đang sạc = 0V, Đầy = >700mV
  // Active High: Đang sạc = >700mV, Đầy = 0V
  bool redLedActive = chgStatActiveLow ? (!statHigh) : statHigh;

  ChargeState newState;
  if (!debouncedVbus) {
    newState = DISCHARGING;
  } else if (redLedActive) {
    newState = CHARGING;
  } else {
    // Trong khoảng đệm 1.5s khởi động mềm của IP2312: giữ CHARGING, không nhảy nhầm sang FULL
    if (now - vbusConnectTime < CHG_SOFT_START_HOLD_MS) {
      newState = CHARGING;
    } else {
      newState = FULL;
    }
  }

  // Chống dội trạng thái (Debounce 120ms)
  if (newState != currentChargeState) {
    if (newState != rawLastState) {
      rawLastState = newState;
      stateDebounceTimer = now;
    } else if (now - stateDebounceTimer > 120) {
      currentChargeState = rawLastState;

      if (currentChargeState == DISCHARGING) {
        userOverrideCharging = false;
        dbgLog(TAG_PWR, "TRẠNG THÁI NGUỒN: DÙNG PIN 18650");
      } else if (currentChargeState == CHARGING) {
        userOverrideCharging = false;
        dbgLog(TAG_PWR, "TRẠNG THÁI NGUỒN: ĐANG SẠC PIN (Amber) [VBUS=1, STAT=%dmV%s]",
               getStatPinMilliVolts(), (getStatPinMilliVolts() >= CHG_STAT_THRESHOLD_MV) ? " / LED ĐỎ SÁNG" : " / KHỞI ĐỘNG MỀM");
      } else if (currentChargeState == FULL) {
        dbgLog(TAG_PWR, "TRẠNG THÁI NGUỒN: PIN ĐÃ ĐẦY 100%% [VBUS=1, STAT=%dmV / LED ĐỎ TẮT]", getStatPinMilliVolts());
      }
    }
  }
#endif

  return currentChargeState;
}

const char* getModeName(uint8_t mode) {
  switch (mode) {
    case 0: return "Tu dong doi canh Lang Man (Romantic Cycle)";
    case 1: return "Nhip dap trai tim (Heartbeat Pulse - Mac dinh)";
    case 2: return "Pha Le Thach Anh Tim (Amethyst Crystal Dream)";
    case 3: return "Anh nen hen ho lung linh (Candlelight Romance)";
    case 4: return "Cu chi yeu thuong MPU (Love Tilt & Caress)";
    case 5: return "Lua am dem dong (Cozy Fireplace Romance)";
    case 6: return "Bien Hoa Oai Huong Tim (Lavender Twilight Breeze)";
    case 7: return "Cuc quang tinh nhan (Lover's Aurora)";
    case 8: return "Bau troi ngan sao hen uoc (Starry Night Romance)";
    case 9: return "Tat den (Standby / Off)";
    case 10: return "Mau tinh lang man theo App (Romantic Mood Color)";
    case 11: return "4 Khung hinh ky niem tinh yeu (4-Slot Love Memories)";
    case 12: return "Doa hong he no (Blooming Rose of Love)";
    case 13: return "Chu chay 360 do (Romantic Cylinder Marquee)";
    case 14: return "Thoi tiet: Nang dep 360 do (Clear Sky & Sunbeams)";
    case 15: return "Thoi tiet: Mua roi 360 do (Rain & Puddle Ripples)";
    case 16: return "Thoi tiet: May troi 360 do (Volumetric Clouds)";
    case 17: return "Thoi tiet: Giong bao 360 do (Thunderstorm & Lightning)";
    default: return "Khong xac dinh";
  }
}

// ============================================================================
// CHU KỲ CHUYỂN MODE NÚT BẤM VẬT LÝ (TOUCH BUTTON CYCLE)
// ============================================================================
// Chỉ tuần hoàn qua các chế độ hiển thị lãng mạn độc lập 1 -> 12.
// Chế độ Auto-Cycle (Mode 0), Màu tĩnh (Mode 10), và Kỷ niệm (Mode 11) kích hoạt qua App.
static const uint8_t BUTTON_CYCLE_MODES[] = {
  1,  // Nhịp đập trái tim (Heartbeat Pulse - Mặc định)
  2,  // Pha Lê Thạch Anh Tím (Amethyst Crystal Dream)
  3,  // Ánh nến hẹn hò lung linh (Candlelight Romance)
  4,  // Cử chỉ yêu thương MPU (Love Tilt & Caress)
  5,  // Lửa ấm đêm đông (Cozy Fireplace Romance)
  6,  // Biển Hoa Oải Hương Tím (Lavender Twilight Breeze)
  7,  // Cực quang tình nhân (Lover's Aurora)
  8,  // Bầu trời ngàn sao hẹn ước (Starry Night Romance)
  12, // Đóa hồng hé nở (Blooming Rose of Love)
  14, // Thời tiết: Nắng đẹp 360° (Clear Sky & Sunbeams)
  15, // Thời tiết: Mưa rơi 360° (Rain & Puddle Ripples)
  16, // Thời tiết: Mây trời 360° (Volumetric Clouds)
  17  // Thời tiết: Giông bão 360° (Thunderstorm & Lightning)
};
static const uint8_t NUM_CYCLE_MODES = sizeof(BUTTON_CYCLE_MODES) / sizeof(BUTTON_CYCLE_MODES[0]);

uint8_t getNextCycleMode(uint8_t current) {
  for (uint8_t i = 0; i < NUM_CYCLE_MODES; i++) {
    if (BUTTON_CYCLE_MODES[i] == current) {
      return BUTTON_CYCLE_MODES[(i + 1) % NUM_CYCLE_MODES];
    }
  }
  return BUTTON_CYCLE_MODES[0]; // Nếu đang ở Mode 9 (tắt), 10 (màu tĩnh) hay 11 (số) -> chuyển về Mode 1
}

uint8_t getPrevCycleMode(uint8_t current) {
  for (uint8_t i = 0; i < NUM_CYCLE_MODES; i++) {
    if (BUTTON_CYCLE_MODES[i] == current) {
      return BUTTON_CYCLE_MODES[(i + NUM_CYCLE_MODES - 1) % NUM_CYCLE_MODES];
    }
  }
  return BUTTON_CYCLE_MODES[0];
}

static bool isSettingsDirty = false;
static unsigned long lastSettingChangeTime = 0;

void markSettingsChanged() {
  isSettingsDirty = true;
  lastSettingChangeTime = millis();
}

void processDeferredSettingsSave() {
  if (isSettingsDirty && (millis() - lastSettingChangeTime >= 3000)) {
    saveSettingsToFlash();
  }
}

void saveSettingsToFlash() {
  isSettingsDirty = false;
  uint8_t r, g, b;
  getCustomStaticColor(r, g, b);

  prefs.begin("matrix_cfg", false);
  prefs.putUChar("brightness", currentBrightness);
  prefs.putUChar("mode", currentMode);
  prefs.putUChar("speed", currentSpeed);
  prefs.putUChar("col_r", r);
  prefs.putUChar("col_g", g);
  prefs.putUChar("col_b", b);
  prefs.end();

  dbgLog(TAG_FLASH, "ĐÃ LƯU FLASH NVS: Độ sáng = %d/255 | Mode = %d (%s) | Speed = %d | StaticRGB = (%d,%d,%d)",
         currentBrightness, currentMode, getModeName(currentMode), currentSpeed, r, g, b);
}

void loadSettingsFromFlash() {
  prefs.begin("matrix_cfg", true);
  currentBrightness = prefs.getUChar("brightness", DEFAULT_BRIGHTNESS);
  currentMode = prefs.getUChar("mode", DEFAULT_MODE);
  currentSpeed = prefs.getUChar("speed", 50);
  uint8_t r = prefs.getUChar("col_r", 255);
  uint8_t g = prefs.getUChar("col_g", 160);
  uint8_t b = prefs.getUChar("col_b", 60);
  prefs.end();

  setCustomStaticColor(r, g, b);

  if (currentBrightness < 5) {
    currentBrightness = 5;
  }
  if (currentMode >= TOTAL_MODES || currentMode == 0) {
    currentMode = DEFAULT_MODE;
  }
  if (currentSpeed < 10 || currentSpeed > 100) {
    currentSpeed = 50;
  }

  filteredBrightness = (float)currentBrightness;
  dbgLog(TAG_FLASH, "KHÔI PHỤC FLASH NVS: Độ sáng = %d/255 | Mode = %d (%s) | Speed = %d | StaticRGB = (%d,%d,%d)",
         currentBrightness, currentMode, getModeName(currentMode), currentSpeed, r, g, b);
}

void initMPU6050() {
  Wire.begin(MPU_SDA_PIN, MPU_SCL_PIN);
  Wire.setClock(400000); // Tối ưu hóa phần cứng: Nâng tốc độ I2C Bus lên 400kHz Fast Mode (nhanh gấp 4 lần 100kHz)
  delay(50);

  // 1. Đánh thức MPU-6050 & khóa xung nhịp thạch anh Gyro X (ổn định hơn dao động nội)
  Wire.beginTransmission(MPU_I2C_ADDR);
  Wire.write(0x6B); // PWR_MGMT_1
  Wire.write(0x01); // Clock Source: PLL với trục Gyro X
  byte error = Wire.endTransmission();

  if (error == 0) {
    mpuFound = true;

    // 2. Kích hoạt bộ lọc phần cứng kỹ thuật số DLPF (Digital Low Pass Filter) tại 0x1A
    // DLPF_CFG = 3 (~44Hz): Lọc sạch nhiễu rung tay, nhiễu nguồn và tiếng ồn vi mô
    Wire.beginTransmission(MPU_I2C_ADDR);
    Wire.write(0x1A); // CONFIG
    Wire.write(0x03); // DLPF_CFG = 3
    Wire.endTransmission();

    // 3. Cấu hình thang đo gia tốc ±2g (16384 LSB/g) với độ nhạy tối đa
    Wire.beginTransmission(MPU_I2C_ADDR);
    Wire.write(0x1C); // ACCEL_CONFIG
    Wire.write(0x00); // AFS_SEL = 0 (±2g)
    Wire.endTransmission();

    dbgLog(TAG_MPU, "Tìm thấy MPU-6050 (SDA: GPIO %d, SCL: GPIO %d, Addr: 0x%02X) [I2C Fast Mode: 400kHz, DLPF: 44Hz]",
           MPU_SDA_PIN, MPU_SCL_PIN, MPU_I2C_ADDR);
    dbgLog(TAG_MPU, "Giữ nút chạm để nghiêng điều chỉnh độ sáng [%d -> %d]",
           MIN_BRIGHTNESS, MAX_BRIGHTNESS);
  } else {
    mpuFound = false;
    dbgLog(TAG_MPU, "LỖI: Không tìm thấy MPU-6050 (Mã I2C: %d). Sẽ dùng độ sáng Flash.", error);
  }
}

void initSensors() {
  // Cảm biến chạm GPIO 4
  pinMode(TOUCH_PIN, INPUT_PULLDOWN);
  dbgLog(TAG_TOUCH, "Cảm biến Touch: Module Digital TTP223 tại GPIO %d", TOUCH_PIN);

  // Khởi tạo ADC hiệu chuẩn eFuse phần cứng
  initAdcCal();

  // Tuyến giám sát sạc pin IP2312
#if ENABLE_BATTERY_MONITOR
  pinMode(PIN_VBUS, INPUT_PULLDOWN);
  pinMode(PIN_CHG_STAT, INPUT_PULLDOWN);
  gpio_pulldown_en((gpio_num_t)PIN_VBUS);
  gpio_pulldown_en((gpio_num_t)PIN_CHG_STAT);
  updateChargeState(); // Khởi tạo bộ đếm sạc
  delay(100);
  updateChargeState(); // Chốt trạng thái sạc ngay khi khởi động
  dbgLog(TAG_PWR, "Giám sát sạc IP2312: VBUS GPIO %d, STAT GPIO %d (Khởi đầu: %s)",
         PIN_VBUS, PIN_CHG_STAT, (currentChargeState == 0) ? "PIN" : ((currentChargeState == 1) ? "ĐANG SẠC" : "PIN ĐẦY"));
#else
  currentChargeState = DISCHARGING;
  dbgLog(TAG_PWR, "Giám sát sạc IP2312: ĐÃ TẮT (ENABLE_BATTERY_MONITOR=false) chống thả nổi chân GPIO %d, %d", PIN_VBUS, PIN_CHG_STAT);
#endif

  // Đọc Flash NVS
  loadSettingsFromFlash();

  // Khởi tạo MPU-6050
  initMPU6050();
  if (mpuFound) {
    wasHorizontalTilted = checkHorizontalOrientation();
  }
}

bool readMpuAccel(int16_t &ax, int16_t &ay, int16_t &az) {
  if (!mpuFound) return false;
  unsigned long now = millis();
  static int16_t cachedAx = 0, cachedAy = 0, cachedAz = 0;
  static unsigned long lastMpuRead = 0;

  // Cache gia tốc 10ms (100Hz): Phản hồi siêu tốc, mượt mà gấp 2.5 lần cho vật lý nước và góc nghiêng
  if (now - lastMpuRead < 10 && lastMpuRead != 0) {
    ax = cachedAx;
    ay = cachedAy;
    az = cachedAz;
    return true;
  }

  Wire.beginTransmission(MPU_I2C_ADDR);
  Wire.write(0x3B);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((uint8_t)MPU_I2C_ADDR, (size_t)6, true) != 6) return false;
  cachedAx = (Wire.read() << 8) | Wire.read();
  cachedAy = (Wire.read() << 8) | Wire.read();
  cachedAz = (Wire.read() << 8) | Wire.read();
  ax = cachedAx;
  ay = cachedAy;
  az = cachedAz;
  lastMpuRead = now;
  return true;
}

void updateMPUBrightness() {
  if (!mpuFound) return;

  unsigned long now = millis();
  if (now - lastMPUReadTime < 35) return;
  lastMPUReadTime = now;

  int16_t ax, ay, az;
  if (readMpuAccel(ax, ay, az)) {

    float tiltAngle = 0.0f;
#if MPU_TILT_AXIS == 0
    tiltAngle = atan2((float)ax, sqrt((float)ay * ay + (float)az * az)) * 180.0f / PI;
#else
    tiltAngle = atan2((float)ay, sqrt((float)ax * ax + (float)az * az)) * 180.0f / PI;
#endif

#if INVERT_TILT_DIR
    tiltAngle = -tiltAngle;
#endif

    float clampedAngle = constrain(tiltAngle, -45.0f, 45.0f);
    float targetBrightness = 5.0f + ((clampedAngle + 45.0f) / 90.0f) * 250.0f;

    filteredBrightness += (targetBrightness - filteredBrightness) * 0.12f;
    uint8_t newBrightness = (uint8_t)constrain((int)round(filteredBrightness), 5, 255);

    if (newBrightness != currentBrightness) {
      currentBrightness = newBrightness;
      strip.setBrightness(toHardwareBrightness(currentBrightness));
      showDisplay();
      dbgLog(TAG_MPU, "Nghiêng: %.1f° -> Độ sáng cập nhật: %d/255 (HW: %d)", clampedAngle, currentBrightness, toHardwareBrightness(currentBrightness));
    }
  }
}

static bool isTouchActive() {
#if TOUCH_MODE_INTERNAL
  return touchRead(TOUCH_PIN) < TOUCH_THRESHOLD;
#else
  return digitalRead(TOUCH_PIN) == HIGH;
#endif
}

void startBleOtaMode() {
  if (isOtaModeActive) return;
  isOtaModeActive = true;
  isAdjustingBrightness = false;
  dbgLog(TAG_SYS, "=======================================================");
  dbgLog(TAG_SYS, ">>> ĐÃ VÀO TRẠNG THÁI OTA BLE QUA BLUETOOTH! <<<");
  dbgLog(TAG_SYS, "    Giữ nút cố định đã phát hiện. Sẵn sàng nhận firmware!");
  dbgLog(TAG_SYS, "=======================================================");
  displayOtaProgress(0, false);
  bleNotify("{\"ota\":\"ready\"}");
}

void exitBleOtaMode() {
  if (!isOtaModeActive) return;
  abortBleOta();
  dbgLog(TAG_SYS, "Thoát trạng thái OTA BLE. Quay về Mode [%d]", currentMode);
}

bool checkButtonAndMPU() {
  bool currentPinState = isTouchActive();
  unsigned long now = millis();

  // 1. Nhấn nút xuống (Cạnh lên)
  if (currentPinState && !isButtonPressed) {
    isButtonPressed = true;
    buttonPressStartTime = now;
    isAdjustingBrightness = false;
    hasMovedDuringHold = false;
    if (mpuFound) {
      readMpuAccel(initialAx, initialAy, initialAz);
    }
    dbgLog(TAG_TOUCH, "Cạnh lên: Bắt đầu chạm nút...");
  }
  // 2. Đang giữ nút
  else if (currentPinState && isButtonPressed) {
    unsigned long holdDuration = now - buttonPressStartTime;
    if (holdDuration >= HOLD_THRESHOLD_MS) {
      // Kiểm tra xem MPU có bị di chuyển/nghiêng không
      // ponytail: raw L1 delta threshold for stillness, gyro fusion if complex vibration filtering needed
      if (mpuFound && !hasMovedDuringHold) {
        int16_t ax, ay, az;
        if (readMpuAccel(ax, ay, az)) {
          int32_t delta = abs((int32_t)ax - initialAx) + abs((int32_t)ay - initialAy) + abs((int32_t)az - initialAz);
          if (delta > OTA_STILL_ACCEL_DELTA) {
            hasMovedDuringHold = true; // Người dùng đã nghiêng -> chuyển sang chỉnh sáng
          }
        }
      }

      // NẾU GIỮ NÚT CỐ ĐỊNH (KHÔNG DI CHUYỂN MPU) >= OTA_HOLD_TRIGGER_MS (3s) -> VÀO CHẾ ĐỘ OTA BLUETOOTH
      if (!hasMovedDuringHold && holdDuration >= OTA_HOLD_TRIGGER_MS && !isOtaModeActive) {
        startBleOtaMode();
        return true;
      }

      // NẾU CÓ DI CHUYỂN NGHIÊNG MPU -> ĐIỀU CHỈNH ĐỘ SÁNG BÌNH THƯỜNG
      if (hasMovedDuringHold && !isOtaModeActive) {
        if (!isAdjustingBrightness) {
          isAdjustingBrightness = true;
          dbgLog(TAG_TOUCH, "ĐANG GIỮ NÚT (>= %dms) -> Nghiêng MPU-6050 để chỉnh độ sáng [%d..%d]",
                 HOLD_THRESHOLD_MS, MIN_BRIGHTNESS, MAX_BRIGHTNESS);
        }
        updateMPUBrightness();
      }
    }
  }
  // 3. Thả nút ra (Cạnh xuống)
  else if (!currentPinState && isButtonPressed) {
    unsigned long pressDuration = now - buttonPressStartTime;
    isButtonPressed = false;

    // Nếu đang trong chế độ OTA, nhấp nhanh để thoát
    if (isOtaModeActive) {
      if (pressDuration >= 40 && pressDuration < 800) {
        exitBleOtaMode();
      }
      return false;
    }

    // THẢ NÚT SAU KHI ĐÃ GIỮ CHỈNH SÁNG -> LƯU VÀO FLASH
    if (isAdjustingBrightness) {
      isAdjustingBrightness = false;
      saveSettingsToFlash();
      dbgLog(TAG_TOUCH, "KHÓA ĐỘ SÁNG: Đã lưu cố định độ sáng %d/255 vào Flash", currentBrightness);
      return false;
    }
    // BẤM NHẢ NHANH -> CHUYỂN MODE ĐÈN (Debounced Wear-Leveling)
    else if (pressDuration >= 40 && pressDuration < HOLD_THRESHOLD_MS) {
      if (currentChargeState != DISCHARGING && !userOverrideCharging) {
        userOverrideCharging = true;
        dbgLog(TAG_PWR, "Người dùng chạm nút can thiệp: Bật đèn trong lúc cắm sạc!");
      }
      fadeTransition(240);
      currentMode = getNextCycleMode(currentMode);
      markSettingsChanged();

      dbgLog(TAG_TOUCH, "BẤM NHANH (%lums) -> Chuyển sang Mode [%d]: %s",
             pressDuration, currentMode, getModeName(currentMode));
      return true;
    }
  }

  return false;
}

bool delayWithTouch(uint16_t ms) {
  if (isOtaModeActive) return true;
  if (!isHorizontalActive && !isEmergencyActive && currentSpeed >= 10 && currentSpeed <= 100 && currentSpeed != 50) {
    ms = (uint16_t)max(1, (int)round((float)ms * (50.0f / (float)currentSpeed)));
  }
  unsigned long start = millis();
  ChargeState initCharge = currentChargeState;
  while (millis() - start < ms) {
    if (isOtaModeActive) return true;
    if (checkButtonAndMPU()) {
      return true;
    }
    // Ngắt delay tức thì khi cắm hoặc rút cáp sạc USB
    if (updateChargeState() != initCharge) {
      return true;
    }
    // Lắc mạnh 3 lần khẩn cấp (chỉ khi không đang bật đèn pin khẩn cấp)
    if (!isEmergencyActive && checkEmergencyShake()) {
      pendingEmergencyFlashlight = true;
      return true;
    }
    // Xoay ngang đèn (chỉ khi không đang ở chế độ đèn nằm ngang)
    if (!isHorizontalActive && checkHorizontalTrigger()) {
      pendingHorizontalTrigger = true;
      return true;
    }
    // Xử lý đọc và thực thi lệnh từ UART CLI ngay lập tức
    if (handleUartDebug()) {
      return true;
    }
    // Xử lý đọc và thực thi lệnh từ BLE ngay lập tức
    if (handleBleService()) {
      return true;
    }
    processDeferredSettingsSave();
    processDeferredSlotsSave();
    delay(5);
  }
  return false;
}

// ============================================================================
// CỬ CHỈ VẬT LÝ MPU-6050 (ĐÈN XOAY NGANG & ĐÈN PIN KHẨN CẤP)
// ============================================================================
bool isHorizontalActive = false;
bool isEmergencyActive = false;
bool pendingEmergencyFlashlight = false;
bool pendingHorizontalTrigger = false;

static uint8_t shakeCount = 0;
static unsigned long lastShakeTime = 0;

bool checkEmergencyShake() {
  if (!mpuFound || millis() < 3000) return false;
  int16_t ax, ay, az;
  if (!readMpuAccel(ax, ay, az)) return false;

  float totalG = sqrt((float)ax * ax + (float)ay * ay + (float)az * az) / 16384.0f;
  unsigned long now = millis();

  if (totalG > SHAKE_JERK_THRESHOLD) {
    if (now - lastShakeTime > 160 && now - lastShakeTime < 950) {
      shakeCount++;
      lastShakeTime = now;
      dbgLog(TAG_MPU, "Phát hiện nhịp lắc %d/3! (Gia tốc: %.2fg)", shakeCount, totalG);
      if (shakeCount >= 3) {
        shakeCount = 0;
        dbgLog(TAG_MPU, "KÍCH HOẠT ĐÈN PIN KHẨN CẤP: Lắc mạnh liên tục 3 lần!");
        return true;
      }
    } else if (now - lastShakeTime >= 950) {
      shakeCount = 1;
      lastShakeTime = now;
    }
  }
  return false;
}

static unsigned long horizontalTiltStart = 0;

bool checkHorizontalOrientation() {
  if (!mpuFound) return false;
  int16_t ax, ay, az;
  if (!readMpuAccel(ax, ay, az)) return false;

  float pitch = atan2((float)ax, sqrt((float)ay * ay + (float)az * az)) * 180.0f / PI;
  float roll  = atan2((float)ay, sqrt((float)ax * ax + (float)az * az)) * 180.0f / PI;
  float maxTilt = max(abs(pitch), abs(roll));

  // Khi đang ở chế độ đèn nằm ngang: chỉ thoát khi đèn đã được dựng đứng lại (<= HORIZONTAL_UPRIGHT_DEG)
  if (isHorizontalActive) {
    return (maxTilt > HORIZONTAL_UPRIGHT_DEG);
  }

  // Khi chưa ở chế độ nằm ngang: tính là nằm ngang khi góc dứt khoát >= HORIZONTAL_TILT_DEG
  return (maxTilt >= HORIZONTAL_TILT_DEG);
}

bool checkHorizontalTrigger() {
  if (!mpuFound || millis() < 3000) return false;
  // Bỏ qua hoàn toàn nếu người dùng đang chạm/giữ nút cảm ứng (tránh kích hoạt khi nghiêng chỉnh sáng)
  if (isButtonPressed || isAdjustingBrightness) {
    horizontalTiltStart = 0;
    return false;
  }

  int16_t ax, ay, az;
  if (!readMpuAccel(ax, ay, az)) {
    horizontalTiltStart = 0;
    return false;
  }

  float pitch = atan2((float)ax, sqrt((float)ay * ay + (float)az * az)) * 180.0f / PI;
  float roll  = atan2((float)ay, sqrt((float)ax * ax + (float)az * az)) * 180.0f / PI;
  float maxTilt = max(abs(pitch), abs(roll));
  float totalG = sqrt((float)ax * ax + (float)ay * ay + (float)az * az) / 16384.0f;

  // Nếu đèn đang dựng đứng (<= HORIZONTAL_UPRIGHT_DEG), sẵn sàng cho lần xoay ngang tiếp theo
  if (maxTilt <= HORIZONTAL_UPRIGHT_DEG) {
    wasHorizontalTilted = false;
    horizontalTiltStart = 0;
    return false;
  }

  // Nếu chưa dựng đứng lại từ lần trước, không kích hoạt lặp lại
  if (wasHorizontalTilted) {
    horizontalTiltStart = 0;
    return false;
  }

  // 1. Phải xoay ngang đèn (>= HORIZONTAL_TILT_DEG, mặc định 60 độ)
  // 2. Và phải tương đối ổn định trên mặt phẳng (không đang vung lắc mạnh: 0.7g < totalG < 1.3g)
  if (maxTilt >= HORIZONTAL_TILT_DEG && totalG > 0.70f && totalG < 1.30f) {
    if (horizontalTiltStart == 0) {
      horizontalTiltStart = millis();
    } else if (millis() - horizontalTiltStart >= HORIZONTAL_HOLD_TRIGGER_MS) {
      // Đã giữ nằm ngang ổn định liên tục đủ thời gian (350ms)
      horizontalTiltStart = 0;
      wasHorizontalTilted = true;
      dbgLog(TAG_MPU, "XÁC NHẬN XOAY NGANG ĐÈN: Góc nghiêng %.1f° -> Kích hoạt Ánh Sáng Vàng Thân Thiện!", maxTilt);
      return true;
    }
  } else {
    // Nếu góc chưa đủ nghiêng hoặc đang di chuyển/vung lắc -> xóa bộ đếm thời gian
    horizontalTiltStart = 0;
  }

  return false;
}

bool isLampStationaryOnTable() {
  if (!mpuFound) return false;
  int16_t ax, ay, az;
  if (!readMpuAccel(ax, ay, az)) return false;

  float pitch = atan2((float)ax, sqrt((float)ay * ay + (float)az * az)) * 180.0f / PI;
  float roll  = atan2((float)ay, sqrt((float)ax * ax + (float)az * az)) * 180.0f / PI;
  float totalG = sqrt((float)ax * ax + (float)ay * ay + (float)az * az) / 16384.0f;

  return (abs(pitch) < 16.0f && abs(roll) < 16.0f && abs(totalG - 1.0f) < 0.15f);
}

