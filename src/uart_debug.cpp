#include "uart_debug.h"
#include "sensors.h"
#include "display.h"
#include "effects.h"
#include <ctype.h>
#include <math.h>

// Cấu hình cờ runtime
bool uartLoggingEnabled = UART_DEBUG_ENABLED;
uint32_t uartHeartbeatIntervalMs = UART_HEARTBEAT_MS;

// Bộ đệm dòng lệnh CLI
static char cmdBuffer[128];
static uint8_t cmdIdx = 0;
static unsigned long lastHeartbeatTime = 0;

void dbgLog(const char* tag, const char* format, ...) {
  if (!uartLoggingEnabled && strcmp(tag, TAG_CLI) != 0 && strcmp(tag, TAG_SYS) != 0) {
    return;
  }

  unsigned long ms = millis();
  unsigned long secs = ms / 1000;
  unsigned long mins = secs / 60;
  unsigned long hours = mins / 60;

  char timeStr[16];
  snprintf(timeStr, sizeof(timeStr), "[%02lu:%02lu:%02lu.%03lu]", hours % 24, mins % 60, secs % 60, ms % 1000);

  Serial.print(timeStr);
  Serial.print(" [");
  Serial.print(tag);
  Serial.print("] ");

  char logBuf[256];
  va_list args;
  va_start(args, format);
  vsnprintf(logBuf, sizeof(logBuf), format, args);
  va_end(args);

  Serial.println(logBuf);
}

void printUartHelp() {
  Serial.print(F(
    "\n=== LED MATRIX 360 CLI ===\n"
    " status, help, reboot\n"
    " mode <0..10>, next, prev, bright <5..255>, save\n"
    " mpu, touch, power, polarity [h/l], heartbeat <ms>\n"
    " text <msg>, color <r g b>, clear, log <on/off>\n"
    "==========================\n\n"
  ));
}

void printSystemStatusReport() {
  unsigned long ms = millis();
  unsigned long secs = ms / 1000;
  unsigned long mins = secs / 60;
  unsigned long hours = mins / 60;

  Serial.println("\n+------------------------------------------------------------------------------+");
  Serial.println("|                     BÁO CÁO TOÀN DIỆN HỆ THỐNG (SYSTEM STATUS)                |");
  Serial.println("+------------------------------------------------------------------------------+");
  Serial.printf("| [HỆ THỐNG] Uptime: %02lu:%02lu:%02lu | Free Heap: %u bytes | Min Free: %u bytes\n",
                hours, mins % 60, secs % 60, ESP.getFreeHeap(), ESP.getMinFreeHeap());
  Serial.printf("|            CPU Freq: %u MHz | Chip Rev: %d | SDK: %s\n",
                ESP.getCpuFreqMHz(), ESP.getChipRevision(), ESP.getSdkVersion());
  Serial.println("+------------------------------------------------------------------------------+");

  // Hiển thị LED
  Serial.printf("| [MA TRẬN LED] Chu vi: %d cột x Cao: %d hàng (%d LEDs WS2812B tại GPIO %d)\n",
                MATRIX_WIDTH, MATRIX_HEIGHT, NUM_LEDS, LED_PIN);
  Serial.printf("|               Chế độ hiện tại : [%d] %s\n", currentMode, getModeName(currentMode));
  Serial.printf("|               Độ sáng hiện tại: %d / 255 (Min: %d, Max: %d)\n",
                currentBrightness, MIN_BRIGHTNESS, MAX_BRIGHTNESS);
  Serial.printf("|               Hiệu chỉnh tọa độ: Swap=%s, InvertX=%s, InvertY=%s\n",
                SWAP_ROW_COL ? "YES" : "NO", INVERT_PANEL_X ? "YES" : "NO", INVERT_PANEL_Y ? "YES" : "NO");
  Serial.println("+------------------------------------------------------------------------------+");

  // Nguồn & Pin IP2312
  bool vbus = isVbusPlugged();
  uint16_t statMv = getStatPinMilliVolts();
  bool statHigh = isStatPinHigh();
  const char* pwrName = "KHÔNG RÕ";
  if (currentChargeState == DISCHARGING) pwrName = "ĐANG DÙNG PIN (RÚT SẠC)";
  else if (currentChargeState == CHARGING) pwrName = "ĐANG CẮM SẠC (CHARGING)";
  else if (currentChargeState == FULL) pwrName = "PIN ĐÃ ĐẦY 100% (FULL)";

  bool isCharging = chgStatActiveLow ? (!statHigh) : statHigh;
  Serial.printf("| [NGUỒN & PIN] Trạng thái: %s\n", pwrName);
  Serial.printf("|               VBUS 5V (GPIO %d): %s | STAT (GPIO %d): %u mV (%s | %s)\n",
                PIN_VBUS, vbus ? "HIGH (CÓ 5V)" : "LOW (RÚT CÁP)",
                PIN_CHG_STAT, statMv, statHigh ? "HIGH" : "LOW",
                isCharging ? "LED SẠC BẬT" : "LED SẠC TẮT/PIN ĐẦY");
  Serial.printf("|               Cực tính logic: %s | Ngưỡng ADC: %d mV\n",
                chgStatActiveLow ? "Active LOW (0V=Sạc)" : "Active HIGH (>700mV=Sạc, 0V=Đầy)", CHG_STAT_THRESHOLD_MV);
  Serial.printf("|               User Override Bật Đèn Khi Sạc: %s\n",
                userOverrideCharging ? "BẬT (Đang chạy đèn trang trí)" : "TẮT (Chạy hiệu ứng sạc)");
  Serial.println("+------------------------------------------------------------------------------+");

  // Cảm biến Touch & MPU
  bool touchActive = (digitalRead(TOUCH_PIN) == HIGH);
  Serial.printf("| [CẢM BIẾN TOUCH] Chân GPIO %d: %s\n", TOUCH_PIN, touchActive ? "ĐANG CHẠM (HIGH)" : "KHÔNG CHẠM (LOW)");

  if (mpuFound) {
    int16_t ax, ay, az;
    if (readMpuAccel(ax, ay, az)) {
      float pitch = atan2((float)ax, sqrt((float)ay * ay + (float)az * az)) * 180.0f / PI;
      float roll  = atan2((float)ay, sqrt((float)ax * ax + (float)az * az)) * 180.0f / PI;
      float mag   = sqrt((float)ax * ax + (float)ay * ay + (float)az * az) / 16384.0f;
      Serial.printf("| [MPU-6050 I2C] Trạng thái: KẾT NỐI TỐT (0x68)\n");
      Serial.printf("|                Gia tốc thô : Ax=%d, Ay=%d, Az=%d (Mag: %.2f g)\n", ax, ay, az, mag);
      Serial.printf("|                Góc nghiêng : Pitch=%.1f°, Roll=%.1f°\n", pitch, roll);
    } else {
      Serial.printf("| [MPU-6050 I2C] Trạng thái: Lỗi đọc dữ liệu I2C\n");
    }
  } else {
    Serial.printf("| [MPU-6050 I2C] Trạng thái: KHÔNG TÌM THẤY (Chưa nối dây SDA:21, SCL:22)\n");
  }

  Serial.println("+------------------------------------------------------------------------------+");
  Serial.printf("| [TELEMETRY] Log: %s | Heartbeat: %u ms\n",
                uartLoggingEnabled ? "ON" : "OFF", uartHeartbeatIntervalMs);
  Serial.println("+------------------------------------------------------------------------------+\n");
}

void initUartDebug() {
  Serial.begin(UART_BAUD_RATE);
  delay(100);

  Serial.println("\n\n");
  Serial.println("================================================================================");
  Serial.println("  ESP32 VÒNG TRỤ MA TRẬN LED 360° - TOÀN DIỆN UART TELEMETRY & CLI CONSOLE");
  Serial.println("================================================================================");
  dbgLog(TAG_SYS, "Khởi động giao diện UART Debug tại tốc độ Baud: %d bps", UART_BAUD_RATE);
  dbgLog(TAG_SYS, "ESP32 Core: %s | CPU: %u MHz | Free Heap: %u bytes",
         ESP.getSdkVersion(), ESP.getCpuFreqMHz(), ESP.getFreeHeap());
  dbgLog(TAG_SYS, "Ma trận LED: 4 tấm 8x8 = %d cột x %d hàng (%d bóng) tại GPIO %d",
         MATRIX_WIDTH, MATRIX_HEIGHT, NUM_LEDS, LED_PIN);
  dbgLog(TAG_SYS, "Cảm biến Touch: GPIO %d | MPU-6050: SDA 21, SCL 22", TOUCH_PIN);
  dbgLog(TAG_SYS, "Quản lý sạc IP2312: VBUS GPIO %d, STAT GPIO %d", PIN_VBUS, PIN_CHG_STAT);
  dbgLog(TAG_SYS, "Gõ 'help' hoặc '?' trong Serial Monitor để xem danh sách lệnh điều khiển.");
  Serial.println("================================================================================");
}

bool executeCliCommand(const char* cmdLine) {
  if (cmdLine == NULL) return false;
  char cmdBuf[128];
  strncpy(cmdBuf, cmdLine, sizeof(cmdBuf) - 1);
  cmdBuf[sizeof(cmdBuf) - 1] = '\0';

  char* cmd = cmdBuf;
  // Bỏ khoảng trắng đầu câu
  while (*cmd == ' ' || *cmd == '\t') cmd++;
  if (*cmd == '\0') return false;

  // Tách từ khóa chính
  char* space = strchr(cmd, ' ');
  char* arg = NULL;
  if (space != NULL) {
    *space = '\0';
    arg = space + 1;
    while (*arg == ' ' || *arg == '\t') arg++;
  }

  // Chuyển từ khóa về chữ thường
  for (int i = 0; cmd[i]; i++) {
    cmd[i] = tolower(cmd[i]);
  }

  dbgLog(TAG_CLI, "Thực thi lệnh: '%s'%s%s", cmd, arg ? " tham số: " : "", arg ? arg : "");

  // 1. HELP / ?
  if (strcmp(cmd, "help") == 0 || strcmp(cmd, "?") == 0) {
    printUartHelp();
    return false;
  }

  // 2. STATUS / INFO
  if (strcmp(cmd, "status") == 0 || strcmp(cmd, "info") == 0) {
    printSystemStatusReport();
    return false;
  }

  // 3. REBOOT / RESET
  if (strcmp(cmd, "reboot") == 0 || strcmp(cmd, "reset") == 0) {
    dbgLog(TAG_SYS, "Đang khởi động lại ESP32 theo lệnh UART...");
    Serial.flush();
    delay(200);
    ESP.restart();
    return true;
  }

  // 4. MODE <0..9>
  if (strcmp(cmd, "mode") == 0) {
    if (arg != NULL) {
      int m = atoi(arg);
      if (m >= 0 && m < TOTAL_MODES) {
        currentMode = m;
        saveSettingsToFlash();
        dbgLog(TAG_FX, "Đã chuyển sang Mode [%d]: %s", currentMode, getModeName(currentMode));
        return true; // Ngắt hiệu ứng cũ ngay lập tức
      } else {
        dbgLog(TAG_CLI, "Mode không hợp lệ! Vui lòng chọn từ 0 đến %d", TOTAL_MODES - 1);
      }
    } else {
      dbgLog(TAG_FX, "Chế độ hiện tại: [%d] %s", currentMode, getModeName(currentMode));
    }
    return false;
  }

  // 5. NEXT
  if (strcmp(cmd, "next") == 0) {
    currentMode = (currentMode + 1) % TOTAL_MODES;
    saveSettingsToFlash();
    dbgLog(TAG_FX, "Đã chuyển sang Mode tiếp theo: [%d] %s", currentMode, getModeName(currentMode));
    return true;
  }

  // 6. PREV
  if (strcmp(cmd, "prev") == 0) {
    currentMode = (currentMode + TOTAL_MODES - 1) % TOTAL_MODES;
    saveSettingsToFlash();
    dbgLog(TAG_FX, "Đã chuyển sang Mode trước đó: [%d] %s", currentMode, getModeName(currentMode));
    return true;
  }

  // 7. BRIGHT <5..255>
  if (strcmp(cmd, "bright") == 0 || strcmp(cmd, "brightness") == 0) {
    if (arg != NULL) {
      int b = atoi(arg);
      b = constrain(b, MIN_BRIGHTNESS, MAX_BRIGHTNESS);
      currentBrightness = b;
      strip.setBrightness(currentBrightness);
      strip.show();
      saveSettingsToFlash();
      dbgLog(TAG_LED, "Đã đặt độ sáng mới: %d/255 và lưu vào Flash", currentBrightness);
    } else {
      dbgLog(TAG_LED, "Độ sáng hiện tại: %d/255 (Giới hạn: %d -> %d)", currentBrightness, MIN_BRIGHTNESS, MAX_BRIGHTNESS);
    }
    return false;
  }

  // 8. SAVE
  if (strcmp(cmd, "save") == 0) {
    saveSettingsToFlash();
    dbgLog(TAG_FLASH, "Đã lưu cài đặt hiện tại vào Flash NVS thành công!");
    return false;
  }

  // 9. MPU
  if (strcmp(cmd, "mpu") == 0) {
    if (!mpuFound) {
      dbgLog(TAG_MPU, "Không tìm thấy cảm biến MPU-6050 trên bus I2C!");
    } else {
      int16_t ax, ay, az;
      if (readMpuAccel(ax, ay, az)) {
        float pitch = atan2((float)ax, sqrt((float)ay * ay + (float)az * az)) * 180.0f / PI;
        float roll  = atan2((float)ay, sqrt((float)ax * ax + (float)az * az)) * 180.0f / PI;
        float mag   = sqrt((float)ax * ax + (float)ay * ay + (float)az * az) / 16384.0f;
        dbgLog(TAG_MPU, "Ax=%d, Ay=%d, Az=%d | Pitch=%.1f°, Roll=%.1f° | Gia tốc: %.2fg",
               ax, ay, az, pitch, roll, mag);
      } else {
        dbgLog(TAG_MPU, "Lỗi đọc dữ liệu I2C từ MPU-6050!");
      }
    }
    return false;
  }

  // 10. TOUCH
  if (strcmp(cmd, "touch") == 0) {
    bool active = (digitalRead(TOUCH_PIN) == HIGH);
    dbgLog(TAG_TOUCH, "Chân cảm biến Touch GPIO %d: %s (Raw Digital = %d)",
           TOUCH_PIN, active ? "ĐANG CHẠM (ACTIVE)" : "KHÔNG CHẠM (IDLE)", active ? 1 : 0);
    return false;
  }

  // 11. POWER / BATT
  if (strcmp(cmd, "power") == 0 || strcmp(cmd, "batt") == 0) {
    updateChargeState();
    bool vbus = isVbusPlugged();
    uint16_t statMv = getStatPinMilliVolts();
    bool statHigh = isStatPinHigh();
    const char* st = "DÙNG PIN";
    if (currentChargeState == CHARGING) st = "ĐANG SẠC (Amber)";
    else if (currentChargeState == FULL) st = "PIN ĐẦY (Green)";

    dbgLog(TAG_PWR, "Trạng thái: %s | VBUS (GPIO %d)=%d | STAT (GPIO %d)=%u mV (%s) | Cực tính: %s | Override=%d",
           st, PIN_VBUS, vbus ? 1 : 0, PIN_CHG_STAT, statMv, statHigh ? "HIGH" : "LOW",
           chgStatActiveLow ? "Active LOW" : "Active HIGH", userOverrideCharging ? 1 : 0);
    return false;
  }

  // 12. POLARITY [high/low]
  if (strcmp(cmd, "polarity") == 0) {
    if (arg != NULL && (strcmp(arg, "high") == 0 || strcmp(arg, "1") == 0)) {
      chgStatActiveLow = false;
      dbgLog(TAG_PWR, "Đã đặt cực tính sạc: ACTIVE HIGH (Sạc = >%dmV, Đầy = 0mV)", CHG_STAT_THRESHOLD_MV);
    } else if (arg != NULL && (strcmp(arg, "low") == 0 || strcmp(arg, "0") == 0)) {
      chgStatActiveLow = true;
      dbgLog(TAG_PWR, "Đã đặt cực tính sạc: ACTIVE LOW (Sạc = 0mV, Đầy = >%dmV)", CHG_STAT_THRESHOLD_MV);
    } else {
      chgStatActiveLow = !chgStatActiveLow;
      dbgLog(TAG_PWR, "Đã đảo cực tính sạc: %s",
             chgStatActiveLow ? "ACTIVE LOW (Sạc=0V, Đầy=>700mV)" : "ACTIVE HIGH (Sạc=>700mV, Đầy=0V)");
    }
    updateChargeState();
    return true;
  }

  // 12. HEARTBEAT <ms>
  if (strcmp(cmd, "heartbeat") == 0) {
    if (arg != NULL) {
      uartHeartbeatIntervalMs = atoi(arg);
      dbgLog(TAG_SYS, "Chu kỳ in Telemetry Heartbeat được đặt thành: %u ms (0 = tắt)", uartHeartbeatIntervalMs);
    } else {
      dbgLog(TAG_SYS, "Chu kỳ Heartbeat hiện tại: %u ms", uartHeartbeatIntervalMs);
    }
    return false;
  }

  // 13. LOG <on/off>
  if (strcmp(cmd, "log") == 0) {
    if (arg != NULL && (strcmp(arg, "off") == 0 || strcmp(arg, "0") == 0)) {
      uartLoggingEnabled = false;
      Serial.println("[CLI] Đã TẮT in log tự động.");
    } else {
      uartLoggingEnabled = true;
      Serial.println("[CLI] Đã BẬT in log tự động.");
    }
    return false;
  }

  // 14. LED <x> <y> <r> <g> <b>
  if (strcmp(cmd, "led") == 0 && arg != NULL) {
    int x, y, r, g, b;
    if (sscanf(arg, "%d %d %d %d %d", &x, &y, &r, &g, &b) == 5) {
      if (x >= 0 && x < MATRIX_WIDTH && y >= 0 && y < MATRIX_HEIGHT) {
        setPixelXY(x, y, strip.Color(r, g, b));
        strip.show();
        dbgLog(TAG_LED, "Đã sáng LED tại tọa độ (%d, %d) màu RGB(%d, %d, %d)", x, y, r, g, b);
        return false;
      } else {
        dbgLog(TAG_CLI, "Tọa độ ngoài vùng ma trận! x: 0..%d, y: 0..%d", MATRIX_WIDTH - 1, MATRIX_HEIGHT - 1);
      }
    } else {
      dbgLog(TAG_CLI, "Cú pháp: led <x> <y> <r> <g> <b> (Ví dụ: led 0 0 255 0 0)");
    }
    return false;
  }

  // 15. FILL <r> <g> <b>
  if (strcmp(cmd, "fill") == 0 && arg != NULL) {
    int r, g, b;
    if (sscanf(arg, "%d %d %d", &r, &g, &b) == 3) {
      for (int x = 0; x < MATRIX_WIDTH; x++) {
        for (int y = 0; y < MATRIX_HEIGHT; y++) {
          setPixelXY(x, y, strip.Color(r, g, b));
        }
      }
      strip.show();
      dbgLog(TAG_LED, "Đã tô toàn bộ 256 LED với màu RGB(%d, %d, %d)", r, g, b);
      return false;
    } else {
      dbgLog(TAG_CLI, "Cú pháp: fill <r> <g> <b> (Ví dụ: fill 0 200 100)");
    }
    return false;
  }

  // 16. CLEAR
  if (strcmp(cmd, "clear") == 0) {
    clearMatrix();
    dbgLog(TAG_LED, "Đã xóa toàn bộ ma trận (Tắt hết 256 LED)");
    return false;
  }

  // 17. TEXT <nội dung> hoặc TEXT <r> <g> <b> <nội dung>
  if (strcmp(cmd, "text") == 0 || strcmp(cmd, "scroll") == 0) {
    if (arg != NULL && strlen(arg) > 0) {
      uint32_t textColor = strip.Color(0, 220, 255); // Mặc định xanh Cyan sáng
      const char* textContent = arg;
      int r, g, b;
      int consumed = 0;
      if (sscanf(arg, "%d %d %d %n", &r, &g, &b, &consumed) >= 3 && consumed > 0) {
        textColor = strip.Color(r, g, b);
        textContent = arg + consumed;
        while (*textContent == ' ') textContent++;
      }
      dbgLog(TAG_FX, "Bắt đầu chạy chữ 360°: '%s'", textContent);
      effectCylinderScrollText(textContent, textColor, 75);
      return true; // Ngắt hiệu ứng cũ
    } else {
      dbgLog(TAG_CLI, "Cú pháp: text <nội dung> hoặc text <r> <g> <b> <nội dung>");
    }
    return false;
  }

  // 18. COLOR <r> <g> <b>
  if (strcmp(cmd, "color") == 0) {
    if (arg != NULL) {
      int r, g, b;
      if (sscanf(arg, "%d %d %d", &r, &g, &b) == 3) {
        setCustomStaticColor(r, g, b);
        currentMode = 10;
        saveSettingsToFlash();
        dbgLog(TAG_LED, "Đã chuyển sang Chế độ Màu Tĩnh RGB(%d, %d, %d)", r, g, b);
        return true;
      }
    }
    dbgLog(TAG_CLI, "Cú pháp: color <r> <g> <b> (Ví dụ: color 255 120 0)");
    return false;
  }

  // 19. WEATHER <temp> <id>
  if (strcmp(cmd, "weather") == 0) {
    if (arg != NULL) {
      int temp = 25, wid = 1;
      if (sscanf(arg, "%d %d", &temp, &wid) >= 1) {
        triggerWeatherUpdate((int8_t)temp, (uint8_t)wid);
        return true;
      }
    }
    dbgLog(TAG_CLI, "Cú pháp: weather <nhiệt_độ> <id: 1=Nắng, 2=Mây, 3=Mưa, 4=Sấm>");
    return false;
  }

  // 20. OTA [start/abort]
  if (strcmp(cmd, "ota") == 0) {
    if (arg != NULL && strcmp(arg, "abort") == 0) {
      exitBleOtaMode();
      return true;
    }
    startBleOtaMode();
    return true;
  }

  // Lệnh không xác định
  dbgLog(TAG_CLI, "Lệnh không nhận dạng: '%s'. Gõ 'help' để xem danh sách lệnh hỗ trợ.", cmd);
  return false;
}

bool handleUartDebug() {
  bool needInterrupt = false;

  // 1. Đọc ký tự từ cổng Serial (Non-blocking)
  while (Serial.available() > 0) {
    char c = Serial.read();

    if (c == '\r' || c == '\n') {
      if (cmdIdx > 0) {
        cmdBuffer[cmdIdx] = '\0';
        if (executeCliCommand(cmdBuffer)) {
          needInterrupt = true;
        }
        cmdIdx = 0;
      }
    } else if (c == '\b' || c == 127) { // Phím Backspace
      if (cmdIdx > 0) {
        cmdIdx--;
        Serial.print("\b \b");
      }
    } else {
      if (cmdIdx < sizeof(cmdBuffer) - 1) {
        cmdBuffer[cmdIdx++] = c;
      }
    }
  }

  unsigned long now = millis();

  // In nhịp tim Telemetry định kỳ
  if (uartHeartbeatIntervalMs > 0 && (now - lastHeartbeatTime >= uartHeartbeatIntervalMs)) {
    lastHeartbeatTime = now;
    bool vbus = isVbusPlugged();
    uint16_t statMv = getStatPinMilliVolts();
    const char* pwr = (currentChargeState == 0) ? "PIN" : ((currentChargeState == 1) ? "CHARGING" : "FULL");

    Serial.printf("[HEARTBEAT] Up:%lus | Heap:%uB | Mode:[%d] %s | Bright:%d/255 | Power:%s(VBUS:%d,STAT:%umV)\n",
                  now / 1000, ESP.getFreeHeap(), currentMode, getModeName(currentMode), currentBrightness,
                  pwr, vbus ? 1 : 0, statMv);
  }

  return needInterrupt;
}
