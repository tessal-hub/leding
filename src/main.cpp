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
  // 0. Khóa xung nhịp CPU ở 240MHz tối đa cho chip ESP32-D0WDQ6 Dual-Core
  setCpuFrequencyMhz(240);

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
  if (pendingPomodoroTrigger || (!isPomodoroActive && checkPomodoroTrigger())) {
    pendingPomodoroTrigger = false;
    isPomodoroActive = true;
    effectPomodoro();
    isPomodoroActive = false;
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

  // 2. Chế độ 0: Tự động chuyển đổi các Mode tuyển chọn (Auto-Cycle Curated)
  if (currentMode == 0) {
    // 1. Lava Noise (Đèn trang trí không chán)
    effectLavaNoise(14000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 2. Edge Light (Đèn viền tối giản cao cấp)
    effectEdgeLight(12000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 3. Face Wave (Sóng nhịp chuyển sắc 4 mặt hộp)
    effectFaceWave(12000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 4. Tilt Glow MPU (Tương tác bám góc nghiêng & lắc bừng sáng)
    effectTiltGlow(12000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 5. Water Physics MPU (Showpiece vật lý nước & giọt bắn)
    effectWaterPhysics(12000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 6. Night Breathing (Đèn ngủ êm dịu thư giãn)
    effectBreathingGradient(10000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 7. Aurora Flow (Cực quang uốn lượn tím - cyan)
    effectAurora(10000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 8. Heartbeat Pulse (Nhịp đập trái tim lãng mạn)
    effectHeartbeatPulse(10000);
    if (currentMode != 0) return;
    fadeTransition(240);

    // 9. Love Ripple (Sóng tình yêu mơ mộng)
    effectLoveRipple(10000);
    if (currentMode != 0) return;
    fadeTransition(240);

    return;
  }

  // 3. Các chế độ cố định (Mode 1 -> 14): Chạy liên tục cho đến khi bấm chuyển Mode
  switch (currentMode) {
    case 1:
      // Mode 1: Lava Noise - Đèn trang trí mặc định
      effectLavaNoise(60000);
      break;

    case 2:
      // Mode 2: Edge Light - Đèn viền 4 cạnh tối giản
      effectEdgeLight(60000);
      break;

    case 3:
      // Mode 3: Face Wave - Sóng nhịp 4 mặt hộp
      effectFaceWave(60000);
      break;

    case 4:
      // Mode 4: Tilt Glow MPU - Tương tác nghiêng & lắc
      effectTiltGlow(60000);
      break;

    case 5:
      // Mode 5: Water Physics MPU - Showpiece vật lý nước
      effectWaterPhysics(60000);
      break;

    case 6:
      // Mode 6: Night Breathing - Chế độ ban đêm êm dịu
      effectBreathingGradient(60000);
      break;

    case 7:
      // Mode 7: Aurora Flow - Dự bị cực quang Bắc Cực
      effectAurora(60000);
      break;

    case 8:
      // Mode 8: Dò hướng đi dây LED 0 -> 255 (Debug Tool riêng, tách khỏi Auto)
      effectLedOrderTest(35);
      break;
    case 10:
      // Mode 10: Màu tĩnh tùy chọn theo App (Static Color)
      effectStaticColor();
      break;

    case 11:
      // Mode 11: Hiển thị 4 ô Bitmap tùy biến độc lập
      effectFourSlotsCustom(60000);
      break;

    case 12:
      // Mode 12: Đồng hồ để bàn 360 độ kỹ thuật số & thời tiết (Desk Clock)
      effectDeskClock(60000);
      break;

    case 13:
      // Mode 13: Heartbeat Pulse - Nhịp đập trái tim lãng mạn
      effectHeartbeatPulse(60000);
      break;

    case 14:
      // Mode 14: Love Ripple - Sóng tình yêu lan tỏa
      effectLoveRipple(60000);
      break;

  }
}