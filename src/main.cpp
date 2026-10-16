#include <Arduino.h>
#include "config.h"
#include "display.h"
#include "sensors.h"
#include "effects.h"
#include "uart_debug.h"
#include "ble_service.h"
#include <esp_sleep.h>

/**
 * ============================================================================
 * DỰ ÁN: VÒNG TRỤ LED RGB 360° (4x MA TRẬN 8x8 WS2812B)
 *        + CẢM BIẾN CHẠM TOUCH GPIO 5 (BẤM ĐỔI MODE / GIỮ CHỈNH ĐỘ SÁNG)
 *        + CẢM BIẾN GIA TỐC MPU-6050 (NGHIÊNG ĐỔI SÁNG / VẬT LÝ NƯỚC XÔ LỆCH)
 *        + BỘ NHỚ FLASH NVS (LƯU VÀ KHÓA CỐ ĐỊNH ĐỘ SÁNG)
 *        + QUẢN LÝ SẠC NHANH IP2312 (VBUS GPIO 32, STAT GPIO 33)
 *        + TOÀN DIỆN UART TELEMETRY & DEBUG CLI CONSOLE (ĐIỀU KHIỂN TẤT CẢ)
 *        + ĐIỀU KHIỂN BLUETOOTH LOW ENERGY (BLE) NORDIC UART SERVICE (NUS)
 * ============================================================================
 */

void setup() {
  // 0. Khóa xung nhịp CPU tối đa (160MHz cho ESP32-C3 RISC-V, 240MHz cho ESP32 Classic)
#if CONFIG_IDF_TARGET_ESP32C3
  setCpuFrequencyMhz(160);
#else
  setCpuFrequencyMhz(240);
#endif

  // 1. Khởi tạo UART Debug & Serial CLI Console
  initUartDebug();

  // 2. Khởi tạo toàn bộ cảm biến (Touch, MPU-6050, IP2312) & đọc Flash NVS
  initSensors();

  // 3. Khởi tạo dải LED 256 bóng với độ sáng đã lưu từ Flash
  initDisplay(currentBrightness);

  // 3b. Khởi tạo & khôi phục dữ liệu 4 Ô Bitmap từ Flash NVS
  initSlots();

  // 4. Khởi tạo Bluetooth Low Energy (BLE) Nordic UART Service
  initBleService();

  dbgLog(TAG_SYS, "Khởi động thành công! Đèn bắt đầu tại Mode [%d]: %s (Độ sáng: %d/255)",
         currentMode, getModeName(currentMode), currentBrightness);
}

void loop() {
  processDeferredSettingsSave();
  processDeferredSlotsSave();

  // -1. Chế độ nạp Firmware OTA qua Bluetooth BLE
  if (isOtaModeActive) {
    unsigned long otaWaitStart = millis();
    while (isOtaModeActive) {
      handleBleService();
      handleUartDebug();
      checkButtonAndMPU();
      processDeferredSettingsSave();
      processDeferredSlotsSave();

      // Nếu không có thiết bị kết nối BLE và không đang nạp OTA trong 30 giây -> tự động thoát
      if (!isBleConnected() && !isBleOtaInProgress()) {
        if (millis() - otaWaitStart > 30000) {
          dbgLog(TAG_SYS, "OTA BLE: Hết thời gian chờ kết nối (30s) -> Tự động quay về chế độ bình thường.");
          exitBleOtaMode();
          break;
        }
      } else {
        otaWaitStart = millis();
      }

      delay(5);
    }
    return;
  }

  // 0. Cử chỉ MPU-6050 vật lý: Đèn pin khẩn cấp & Đồng hồ Pomodoro
  if (pendingEmergencyFlashlight || (!isEmergencyActive && checkEmergencyShake())) {
    pendingEmergencyFlashlight = false;
    isEmergencyActive = true;
    effectEmergencyFlashlight();
    isEmergencyActive = false;
    return;
  }
  if (pendingHorizontalTrigger || (!isHorizontalActive && checkHorizontalTrigger())) {
    pendingHorizontalTrigger = false;
    isHorizontalActive = true;
    effectHorizontalWarmLight();
    isHorizontalActive = false;
    return;
  }

  // 1. Xử lý nhận lệnh điều khiển từ UART Serial CLI & BLE
  if (handleUartDebug() || handleBleService()) {
    return; // Nếu có lệnh chuyển mode, lập tức thoát để chạy mode mới
  }

  // 1. Quản lý trạng thái sạc pin IP2312 (VBUS: GPIO 32, STAT: GPIO 33)
  ChargeState charge = updateChargeState();
  if (!userOverrideCharging) {
    if (charge == CHARGING) {
      effectChargingAmber(0);
      return;
    } else if (charge == FULL) {
      effectChargeFullGreen(0);
      return;
    }
  }

  // 2. Chế độ Tắt đèn (Standby - Mode 9)
  if (currentMode == 9) {
    clearMatrix();
    dbgLog(TAG_SYS, "Đèn đang ở chế độ Tắt (Mode 9). Chạm nút hoặc gõ lệnh UART/BLE để bật lại.");

    while (currentMode == 9) {
      if (checkButtonAndMPU()) break;
      if (isOtaModeActive) break;
      if (handleUartDebug()) break;
      if (handleBleService()) break;
      // Nếu cắm sạc khi đang tắt đèn -> thoát ra để hiển thị trạng thái sạc
      if (updateChargeState() != DISCHARGING && !userOverrideCharging) {
        break;
      }
      processDeferredSettingsSave();
      delay(20);
    }
    return;
  }

  // 2. Chế độ 0: Tự động chuyển đổi các Mode lãng mạn tuyển chọn (Romantic Cycle)
  if (currentMode == 0) {
    // 1. Nhịp đập trái tim lãng mạn (Mở đầu bừng sáng yêu thương)
    effectHeartbeatPulse(14000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 2. Pha Lê Thạch Anh Tím (Cột đá quý lung linh 360°)
    effectAmethystCrystal(12000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 3. Ánh nến hẹn hò lung linh (Ấm cúng, thân mật)
    effectCandlelight(12000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 4. Cử chỉ yêu thương MPU (Tương tác bám góc nghiêng âu yếm)
    effectTiltGlow(12000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 5. Lửa ấm đêm đông (Lò sưởi ấm cúng hẹn hò)
    effectCozyFireplace(12000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 6. Biển hoa oải hương tím (Cánh đồng tím hoàng hôn)
    effectLavenderBreeze(12000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 7. Cực quang tình nhân (Huyền ảo tím & hồng mộng mơ)
    effectAurora(10000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 8. Bầu trời ngàn sao hẹn ước (Lãng mạn & sao băng ước nguyện)
    effectStarryNight(12000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 12. Đóa hồng hé nở (Hoa hồng bung nở tình yêu)
    effectBloomingRose(12000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 14..17. Thời tiết sống động 360° theo thời gian thực (Live Weather Animation)
    effectWeatherLive(12000);
    if (currentMode != 0) return;
    fadeTransition(240);

    return;
  }

  // 3. Các chế độ cố định (Mode 1 -> 12): Chạy liên tục cho đến khi bấm chuyển Mode
  switch (currentMode) {
    case 1:
      // Mode 1: Nhịp đập trái tim (Heartbeat Pulse - MẶC ĐỊNH)
      effectHeartbeatPulse(60000);
      break;

    case 2:
      // Mode 2: Pha Lê Thạch Anh Tím (Amethyst Crystal Dream)
      effectAmethystCrystal(60000);
      break;

    case 3:
      // Mode 3: Ánh nến hẹn hò lung linh (Candlelight Romance)
      effectCandlelight(60000);
      break;

    case 4:
      // Mode 4: Cử chỉ yêu thương MPU (Love Tilt & Caress)
      effectTiltGlow(60000);
      break;

    case 5:
      // Mode 5: Lửa ấm đêm đông (Cozy Fireplace Romance)
      effectCozyFireplace(60000);
      break;

    case 6:
      // Mode 6: Biển hoa oải hương tím (Lavender Twilight Breeze)
      effectLavenderBreeze(60000);
      break;

    case 7:
      // Mode 7: Cực quang tình nhân (Lover's Aurora)
      effectAurora(60000);
      break;

    case 8:
      // Mode 8: Bầu trời ngàn sao hẹn ước (Starry Night Romance)
      effectStarryNight(60000);
      break;

    case 10:
      // Mode 10: Màu tĩnh lãng mạn tùy chọn theo App (Romantic Mood Color - phản ứng MPU)
      effectStaticColor(60000);
      break;

    case 11:
      // Mode 11: 4 Khung hình kỷ niệm tình yêu (4-Slot Love Memories)
      effectFourSlotsCustom(60000);
      break;

    case 12:
      // Mode 12: Đóa hồng hé nở (Blooming Rose of Love)
      effectBloomingRose(60000);
      break;

    case 13:
      // Mode 13: Chạy chữ 360° lãng mạn liên tục quanh thân đèn (Romantic Cylinder Marquee)
      effectMarqueeMode(60000);
      break;

    case 14:
      // Mode 14: Thời tiết - Nắng đẹp & tia nắng xoay 360° (Clear Sky & Sunbeams)
      effectWeatherClearSky(60000);
      break;

    case 15:
      // Mode 15: Thời tiết - Mưa rơi & vũng nước bắn sóng 360° (Rain & Puddle Ripples)
      effectWeatherRain(60000);
      break;

    case 16:
      // Mode 16: Thời tiết - Mây trôi đa tầng 360° (Volumetric Clouds)
      effectWeatherCloudy(60000);
      break;

    case 17:
      // Mode 17: Thời tiết - Giông bão sấm chớp 360° (Thunderstorm & Lightning)
      effectWeatherStorm(60000);
      break;

  }
}