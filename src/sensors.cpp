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
volatile uint8_t currentMode = 0;

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
static bool wasPomodoroTilted = false;
bool checkPomodoroOrientation();

#if CONFIG_IDF_TARGET_ESP32C3
static void initAdcCal() {
  analogReadResolution(12);
  dbgLog(TAG_PWR, "ADC eFuse: Kích hoạt bộ chuyển đổi ADC hiệu chuẩn nhà máy của chip ESP32-C3!");
}

uint16_t getStatPinMilliVolts() {
  uint32_t maxVal = 0;
  for (int i = 0; i < 6; i++) {
    uint32_t mv = analogReadMilliVolts(PIN_CHG_STAT);
    if (mv > maxVal) maxVal = mv;
    delayMicroseconds(60);
  }
  return (uint16_t)maxVal;
}

bool isVbusPlugged() {
  uint32_t mv = analogReadMilliVolts(PIN_VBUS);
  return (mv >= 1200) || (digitalRead(PIN_VBUS) == HIGH);
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
  uint32_t maxVal = 0;
  for (int i = 0; i < 6; i++) {
    uint32_t raw = analogRead(PIN_CHG_STAT);
    if (raw > maxVal) maxVal = raw;
    delayMicroseconds(60);
  }
  if (efuseCalAvailable) {
    return (uint16_t)esp_adc_cal_raw_to_voltage(maxVal, &adcChars);
  }
  return (uint16_t)((maxVal * 3300) / 4095);
}

bool isVbusPlugged() {
  uint32_t raw = analogRead(PIN_VBUS);
  uint16_t mv = efuseCalAvailable ? (uint16_t)esp_adc_cal_raw_to_voltage(raw, &adcChars) : (uint16_t)((raw * 3300) / 4095);
  return (mv >= 1200) || (digitalRead(PIN_VBUS) == HIGH);
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

static bool lastVbusState = false;
static unsigned long vbusConnectTime = 0;

ChargeState updateChargeState() {
  unsigned long now = millis();
  static unsigned long lastChgCheck = 0;
  if (now - lastChgCheck < 150 && lastChgCheck != 0) {
    return currentChargeState;
  }
  lastChgCheck = now;

  bool vbusPlugged = isVbusPlugged();

  // Bắt sự kiện vừa cắm sạc để mở khoảng đệm chờ IP2312 khởi động mềm
  if (vbusPlugged && !lastVbusState) {
    vbusConnectTime = millis();
    lastVbusState = true;
  } else if (!vbusPlugged) {
    lastVbusState = false;
    hasRedPulse = false; // Reset cờ xung khi rút sạc
  }

  bool statHigh = isStatPinHigh();
  // Nếu chgStatActiveLow = true  (Cathode kéo mass): Đang sạc = 0V / LOW, Đầy = >700mV
  // Nếu chgStatActiveLow = false (Anode kích dương): Đang sạc = >700mV, Đầy = 0V / LOW
  bool redLedActive = chgStatActiveLow ? (!statHigh) : statHigh;

  ChargeState newState;
  if (!vbusPlugged) {
    newState = DISCHARGING;
  } else if (redLedActive) {
    newState = CHARGING;
  } else {
    // Khoảng đệm chống nháy xanh khi vừa cắm sạc:
    // Chip IP2312 mất ~200ms - 500ms khởi động mềm kiểm tra pin trước khi bật LED đỏ.
    // Trong thời gian này, giữ nguyên trạng thái CHARGING (màu cam).
    // Chỉ sau khi hết thời gian đệm mà LED đỏ vẫn tắt thì mới xác nhận là PIN ĐÃ ĐẦY (FULL).
    if (millis() - vbusConnectTime < CHG_SOFT_START_HOLD_MS) {
      newState = CHARGING;
    } else {
      newState = FULL;
      hasRedPulse = false;
    }
  }

  // Chống dội tín hiệu mượt mà (80ms)
  if (newState != currentChargeState) {
    if (newState != rawLastState) {
      rawLastState = newState;
      stateDebounceTimer = millis();
    } else if (millis() - stateDebounceTimer > 80) {
      currentChargeState = rawLastState;

      if (currentChargeState == DISCHARGING) {
        userOverrideCharging = false; // Rút sạc: quay về dùng bình thường
        dbgLog(TAG_PWR, "ĐÃ RÚT CÁP SẠC 5V -> CHUYỂN SANG DÙNG PIN 18650");
      } else if (currentChargeState == CHARGING) {
        userOverrideCharging = false;
        uint16_t statMv = getStatPinMilliVolts();
        dbgLog(TAG_PWR, "ĐÃ CẮM CÁP SẠC 5V -> ĐANG SẠC PIN (VBUS=1, STAT=%dmV%s)",
               statMv, (statMv >= CHG_STAT_THRESHOLD_MV) ? " / LED ĐỎ SÁNG" : " / ĐANG KHỞI ĐỘNG MỀM");
      } else if (currentChargeState == FULL) {
        dbgLog(TAG_PWR, "PIN ĐÃ ĐẦY 100%% -> SẠC ĐẦY (VBUS=1, STAT=%dmV / LED ĐỎ TẮT)", getStatPinMilliVolts());
      }
    }
  }

  return currentChargeState;
}

const char* getModeName(uint8_t mode) {
  switch (mode) {
    case 0: return "Tu dong chuyen doi (Auto Cycle Curated)";
    case 1: return "Den dung nham 3D Noise (Lava Noise - Mac dinh)";
    case 2: return "Den vien 4 canh hop (Edge Light - Toi gian)";
    case 3: return "Song nhip 4 mat hop (Face Wave - Chuyen mau)";
    case 4: return "Den nghieng & lac MPU (Tilt Glow MPU - Tuong tac)";
    case 5: return "Nuoc chay & song xo lech (Water Physics MPU - Showpiece)";
    case 6: return "Nhip tho ban dem (Night Breathing - Em diu)";
    case 7: return "Cuc quang uon luon (Aurora Flow - Du bi)";
    case 8: return "DO HUONG DI DAY LED 0 -> 255 (Calibration Debug)";
    case 9: return "Tat den (Standby / Off)";
    case 10: return "Mau tinh tuy chon theo App (Static Color)";
    case 11: return "Hien thi 4 o tuy bien (4-Slot Custom Bitmap)";
    case 12: return "Dong ho de ban 360 do (Desk Clock)";
    case 13: return "Nhip dap trai tim (Heartbeat Pulse - Lang man)";
    case 14: return "Song tinh yeu lan toa (Love Ripple - Mo mong)";
    case 15: return "Thoi tiet 360 do Ngay/Dem (Live Weather Scene)";
    default: return "Khong xac dinh";
  }
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
  if (currentMode >= TOTAL_MODES) {
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
  pinMode(PIN_VBUS, INPUT);
  pinMode(PIN_CHG_STAT, INPUT);
  updateChargeState(); // Khởi tạo bộ đếm sạc
  delay(100);
  updateChargeState(); // Chốt trạng thái sạc ngay khi khởi động
  dbgLog(TAG_PWR, "Giám sát sạc IP2312: VBUS GPIO %d, STAT GPIO %d (Khởi đầu: %s)",
         PIN_VBUS, PIN_CHG_STAT, (currentChargeState == 0) ? "PIN" : ((currentChargeState == 1) ? "ĐANG SẠC" : "PIN ĐẦY"));

  // Đọc Flash NVS
  loadSettingsFromFlash();

  // Khởi tạo MPU-6050
  initMPU6050();
  if (mpuFound) {
    wasPomodoroTilted = checkPomodoroOrientation();
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
      strip.show();
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
      currentMode = (currentMode + 1) % TOTAL_MODES;
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
  if (!isPomodoroActive && !isEmergencyActive && currentSpeed >= 10 && currentSpeed <= 100 && currentSpeed != 50) {
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
    // Lật hộp Pomodoro (chỉ khi không đang chạy Pomodoro)
    if (!isPomodoroActive && checkPomodoroTrigger()) {
      pendingPomodoroTrigger = true;
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
// CỬ CHỈ VẬT LÝ MPU-6050 (POMODORO & ĐÈN PIN KHẨN CẤP)
// ============================================================================
bool isPomodoroActive = false;
bool isEmergencyActive = false;
bool pendingEmergencyFlashlight = false;
bool pendingPomodoroTrigger = false;

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

bool checkPomodoroOrientation() {
  if (!mpuFound) return false;
  int16_t ax, ay, az;
  if (!readMpuAccel(ax, ay, az)) return false;

  float pitch = atan2((float)ax, sqrt((float)ay * ay + (float)az * az)) * 180.0f / PI;
  float roll  = atan2((float)ay, sqrt((float)ax * ax + (float)az * az)) * 180.0f / PI;

  // Khi người dùng lật hộp nằm nghiêng hẳn sang một bên (> 60 độ)
  return (abs(pitch) > 60.0f || abs(roll) > 60.0f);
}

bool checkPomodoroTrigger() {
  if (!mpuFound || millis() < 3000) return false;
  bool isTilted = checkPomodoroOrientation();
  bool trigger = (!wasPomodoroTilted && isTilted);
  wasPomodoroTilted = isTilted;
  return trigger;
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

