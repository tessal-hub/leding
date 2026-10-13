#include "ble_service.h"
#include "uart_debug.h"
#include "sensors.h"
#include "display.h"
#include "effects.h"
#include <NimBLEDevice.h>
#include <Update.h>
#include <string>
#include <queue>

// Biến quản lý BLE
static NimBLEServer *pServer = NULL;
static NimBLECharacteristic *pTxCharacteristic = NULL;
static NimBLECharacteristic *pRxCharacteristic = NULL;
static bool deviceConnected = false;
static bool oldDeviceConnected = false;
static unsigned long lastTelemetryTime = 0;

// Trạng thái OTA BLE
static bool otaInProgress = false;
static uint32_t otaTotalSize = 0;
static uint32_t otaBytesReceived = 0;
static uint8_t otaLastPct = 0;

// Hàng đợi nhận lệnh an toàn đa luồng
static std::queue<std::string> bleRxQueue;
static portMUX_TYPE bleMux = portMUX_INITIALIZER_UNLOCKED;

// Callbacks xử lý kết nối / ngắt kết nối
class BleServerCallbacks: public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* pServer) {
    deviceConnected = true;
    dbgLog(TAG_BLE, "Điện thoại đã kết nối Bluetooth BLE thành công!");
  }

  void onDisconnect(NimBLEServer* pServer) {
    deviceConnected = false;
    dbgLog(TAG_BLE, "Điện thoại đã ngắt kết nối BLE.");
    if (otaInProgress) {
      Update.abort();
      otaInProgress = false;
      isOtaModeActive = false;
      displayOtaProgress(0, true);
      dbgLog(TAG_SYS, "OTA BLE: Điện thoại ngắt kết nối đột ngột -> Đã hủy OTA an toàn.");
    }
    NimBLEDevice::startAdvertising();
  }
};

// Callbacks nhận dữ liệu từ App qua Characteristic RX
class BleRxCallbacks: public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *pCharacteristic) {
    std::string rxValue = pCharacteristic->getValue();
    if (rxValue.length() > 0) {
      portENTER_CRITICAL(&bleMux);
      bleRxQueue.push(rxValue);
      portEXIT_CRITICAL(&bleMux);
    }
  }
};

void initBleService() {
  dbgLog(TAG_BLE, "Khởi tạo dịch vụ BLE Nordic UART Service (Tên: %s)...", BLE_DEVICE_NAME);

  // 1. Khởi tạo BLE Device
  NimBLEDevice::init(BLE_DEVICE_NAME);
  NimBLEDevice::setMTU(517); // Hỗ trợ trao đổi gói tin lớn đến 512 bytes

  // 2. Tạo BLE Server
  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new BleServerCallbacks());

  // 3. Tạo Nordic UART Service (NUS)
  NimBLEService *pService = pServer->createService(BLE_NUS_SERVICE_UUID);

  // 4. Characteristic TX (Notify từ ESP32 -> App)
  pTxCharacteristic = pService->createCharacteristic(
                        BLE_NUS_TX_UUID,
                        NIMBLE_PROPERTY::NOTIFY
                      );

  // 5. Characteristic RX (Write từ App -> ESP32)
  pRxCharacteristic = pService->createCharacteristic(
                        BLE_NUS_RX_UUID,
                        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
                      );
  pRxCharacteristic->setCallbacks(new BleRxCallbacks());

  // 6. Khởi động Service
  pService->start();

  // 7. Bật phát quảng bá (Advertising có giãn chu kỳ tiết kiệm radio)
  NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(BLE_NUS_SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinInterval(160); // 160 * 0.625ms = 100ms
  pAdvertising->setMaxInterval(320); // 320 * 0.625ms = 200ms
  pAdvertising->start();

  dbgLog(TAG_BLE, "BLE đã sẵn sàng! UUID Service: %s", BLE_NUS_SERVICE_UUID);
  dbgLog(TAG_BLE, "Dùng app nRF Connect hoặc LightBlue trên điện thoại để quét '%s'.", BLE_DEVICE_NAME);
}

void bleNotify(const char* data) {
  if (!deviceConnected || pTxCharacteristic == NULL || data == NULL) return;
  pTxCharacteristic->setValue((uint8_t*)data, strlen(data));
  pTxCharacteristic->notify();
}

void bleSendTelemetry(bool force) {
  if (!deviceConnected) return;
  if (otaInProgress || isOtaModeActive) return; // Không gửi telemetry làm gián đoạn kênh truyền OTA
  unsigned long now = millis();
  if (!force && (now - lastTelemetryTime < BLE_TELEMETRY_INTERVAL_MS)) return;
  lastTelemetryTime = now;

  float pitch = 0.0f, roll = 0.0f;
  if (mpuFound) {
    int16_t ax, ay, az;
    if (readMpuAccel(ax, ay, az)) {
      pitch = atan2((float)ax, sqrt((float)ay * ay + (float)az * az)) * 180.0f / PI;
      roll  = atan2((float)ay, sqrt((float)ax * ax + (float)az * az)) * 180.0f / PI;
    }
  }

  char teleJson[220];
  snprintf(teleJson, sizeof(teleJson),
           "{\"m\":%d,\"mn\":\"%s\",\"b\":%d,\"pwr\":%d,\"vbus\":%d,\"stat\":%u,\"pitch\":%.1f,\"roll\":%.1f,\"heap\":%u}",
           currentMode,
           getModeName(currentMode),
           currentBrightness,
           (int)currentChargeState,
           isVbusPlugged() ? 1 : 0,
           getStatPinMilliVolts(),
           pitch, roll,
           ESP.getFreeHeap());

  bleNotify(teleJson);
}

bool isBleConnected() {
  return deviceConnected;
}

// Xử lý gói tin dạng nhị phân, JSON đơn giản hoặc lệnh văn bản thuần
static bool parseAndExecuteBleCommand(const std::string& raw) {
  if (raw.empty()) return false;

  // 1. KIỂM TRA GÓI TIN NHỊ PHÂN BLE GATT (0x01 .. 0x05 & 0x10 .. 0x13)
  uint8_t op = (uint8_t)raw[0];
  if ((op >= 0x01 && op <= 0x05) || (op >= 0x10 && op <= 0x13)) {
    if (op <= 0x05) {
      dbgLog(TAG_BLE, "Nhận Binary Frame BLE Opcode: 0x%02X (%d bytes)", op, raw.length());
    }
    bool needInterrupt = false;

    switch (op) {
      case 0x01: { // Set Static Color: [0x01, R, G, B, Brightness]
        if (raw.length() >= 4) {
          uint8_t r = (uint8_t)raw[1];
          uint8_t g = (uint8_t)raw[2];
          uint8_t b = (uint8_t)raw[3];
          if (raw.length() >= 5 && (uint8_t)raw[4] > 0) {
            currentBrightness = constrain((uint8_t)raw[4], (uint8_t)MIN_BRIGHTNESS, (uint8_t)MAX_BRIGHTNESS);
            strip.setBrightness(currentBrightness);
          }
          setCustomStaticColor(r, g, b);
          currentMode = 10;
          markSettingsChanged();
          dbgLog(TAG_BLE, "Binary 0x01: Đổi màu tĩnh RGB(%d, %d, %d), Bright: %d", r, g, b, currentBrightness);
          needInterrupt = true;
        }
        break;
      }
      case 0x02: { // Set Effect: [0x02, Effect_ID, Speed, Brightness, Palette_ID]
        if (raw.length() >= 2) {
          uint8_t m = (uint8_t)raw[1];
          if (m < TOTAL_MODES) {
            currentMode = m;
            needInterrupt = true;
          }
          if (raw.length() >= 4 && (uint8_t)raw[3] > 0) {
            currentBrightness = constrain((uint8_t)raw[3], (uint8_t)MIN_BRIGHTNESS, (uint8_t)MAX_BRIGHTNESS);
            strip.setBrightness(currentBrightness);
          }
          markSettingsChanged();
          dbgLog(TAG_BLE, "Binary 0x02: Chọn Mode [%d] %s", currentMode, getModeName(currentMode));
        }
        break;
      }
      case 0x03: { // Notification Interrupt: [0x03, R, G, B, Flash_Count, Delay_10ms]
        if (raw.length() >= 4) {
          uint8_t r = (uint8_t)raw[1];
          uint8_t g = (uint8_t)raw[2];
          uint8_t b = (uint8_t)raw[3];
          uint8_t count = (raw.length() >= 5 && (uint8_t)raw[4] > 0) ? (uint8_t)raw[4] : 3;
          uint16_t d = (raw.length() >= 6 && (uint8_t)raw[5] > 0) ? ((uint8_t)raw[5] * 10) : 150;
          triggerNotificationFlash(r, g, b, count, d);
          needInterrupt = true;
        }
        break;
      }
      case 0x04: { // Weather Data: [0x04, Temp_Signed, Weather_ID]
        if (raw.length() >= 3) {
          int8_t temp = (int8_t)raw[1];
          uint8_t wId = (uint8_t)raw[2];
          dbgLog(TAG_BLE, "Nhận Frame 0x04 Thời Tiết: %d°C | ID: %d", temp, wId);
          triggerWeatherUpdate(temp, wId);
          needInterrupt = true;
        }
        break;
      }
      case 0x05: // Time Sync
        break;
      case 0x10: { // OTA Begin: [0x10, Size0, Size1, Size2, Size3 (Little Endian)]
        uint32_t fwSize = UPDATE_SIZE_UNKNOWN;
        if (raw.length() >= 5) {
          fwSize = ((uint8_t)raw[1]) | (((uint8_t)raw[2]) << 8) | (((uint8_t)raw[3]) << 16) | (((uint8_t)raw[4]) << 24);
        }
        dbgLog(TAG_SYS, "OTA BLE: Bắt đầu nhận nạp firmware (Kích thước: %u bytes)", fwSize);
        if (Update.begin(fwSize > 0 ? fwSize : UPDATE_SIZE_UNKNOWN)) {
          otaInProgress = true;
          otaTotalSize = fwSize;
          otaBytesReceived = 0;
          otaLastPct = 0;
          isOtaModeActive = true;
          displayOtaProgress(1, false);
          bleNotify("{\"ota\":\"start\",\"res\":0}");
          needInterrupt = true;
        } else {
          otaInProgress = false;
          displayOtaProgress(0, true);
          char errBuf[64];
          snprintf(errBuf, sizeof(errBuf), "{\"ota\":\"error\",\"err\":%d}", Update.getError());
          bleNotify(errBuf);
        }
        break;
      }
      case 0x11: { // OTA Data Chunk: [0x11, Payload...]
        if (otaInProgress && raw.length() > 1) {
          size_t chunkSize = raw.length() - 1;
          size_t written = Update.write((uint8_t*)raw.data() + 1, chunkSize);
          if (written == chunkSize) {
            otaBytesReceived += written;
            uint8_t pct = (otaTotalSize > 0) ? (uint8_t)((otaBytesReceived * 100ULL) / otaTotalSize) : 50;
            if (pct != otaLastPct) {
              otaLastPct = pct;
              displayOtaProgress(pct, false);
              char progBuf[48];
              snprintf(progBuf, sizeof(progBuf), "{\"ota\":\"prog\",\"p\":%d}", pct);
              bleNotify(progBuf);
            }
          } else {
            dbgLog(TAG_SYS, "OTA BLE: Ghi Flash lỗi! (Đã ghi: %u / Gói: %u)", written, chunkSize);
            Update.abort();
            otaInProgress = false;
            displayOtaProgress(0, true);
            bleNotify("{\"ota\":\"error\",\"err\":101}");
          }
        }
        return false; // Không ngắt vòng lặp chính khi đang nạp data
      }
      case 0x12: { // OTA End: [0x12]
        if (otaInProgress) {
          dbgLog(TAG_SYS, "OTA BLE: Đã nhận đủ %u bytes! Đang kiểm tra toàn vẹn...", otaBytesReceived);
          if (Update.end(true)) {
            displayOtaProgress(100, false);
            bleNotify("{\"ota\":\"success\"}");
            dbgLog(TAG_SYS, "OTA BLE HOÀN TẤT THÀNH CÔNG! Đèn sẽ khởi động lại sau 1.2s...");
            delay(1200);
            ESP.restart();
          } else {
            displayOtaProgress(0, true);
            char errBuf[64];
            snprintf(errBuf, sizeof(errBuf), "{\"ota\":\"error\",\"err\":%d}", Update.getError());
            bleNotify(errBuf);
            dbgLog(TAG_SYS, "OTA BLE LỖI CHECK: Mã %d", Update.getError());
          }
          otaInProgress = false;
          isOtaModeActive = false;
        }
        break;
      }
      case 0x13: { // OTA Abort: [0x13]
        if (otaInProgress) {
          Update.abort();
          otaInProgress = false;
        }
        isOtaModeActive = false;
        clearMatrix();
        bleNotify("{\"ota\":\"aborted\"}");
        dbgLog(TAG_SYS, "Đã hủy tiến trình OTA BLE.");
        break;
      }
    }
    if (op <= 0x05) bleSendTelemetry(true);
    return needInterrupt;
  }

  // 2. Cắt tỉa ký tự thừa \r \n khoảng trắng cho văn bản
  size_t start = raw.find_first_not_of(" \t\r\n");
  if (start == std::string::npos) return false;
  size_t end = raw.find_last_not_of(" \t\r\n");
  std::string cmd = raw.substr(start, end - start + 1);

  dbgLog(TAG_BLE, "Nhận lệnh từ BLE: '%s'", cmd.c_str());

  bool needInterrupt = false;

  // 3. NẾU LÀ GÓI TIN JSON: Ví dụ {"m": 2}, {"b": 70}, {"t": "HELLO"}, {"c": [255,100,0]}
  if (cmd.front() == '{' && cmd.back() == '}') {
    // a. Đổi Mode: "m": <n> hoặc "mode": <n>
    size_t posM = cmd.find("\"m\":");
    if (posM == std::string::npos) posM = cmd.find("\"mode\":");
    if (posM != std::string::npos) {
      int m = atoi(cmd.c_str() + cmd.find_first_of("0123456789", posM));
      if (m >= 0 && m < TOTAL_MODES) {
        currentMode = m;
        markSettingsChanged();
        needInterrupt = true;
      }
    }

    // b. Đổi độ sáng: "b": <n> hoặc "bright": <n>
    size_t posB = cmd.find("\"b\":");
    if (posB == std::string::npos) posB = cmd.find("\"bright\":");
    if (posB == std::string::npos) posB = cmd.find("\"bri\":");
    if (posB != std::string::npos) {
      int b = atoi(cmd.c_str() + cmd.find_first_of("0123456789", posB));
      currentBrightness = constrain((uint8_t)b, (uint8_t)MIN_BRIGHTNESS, (uint8_t)MAX_BRIGHTNESS);
      strip.setBrightness(currentBrightness);
      strip.show();
      markSettingsChanged();
    }

    // c. Công tắc nguồn: "p": 0 (Tắt) hoặc "p": 1 (Bật)
    size_t posP = cmd.find("\"p\":");
    if (posP == std::string::npos) posP = cmd.find("\"power\":");
    if (posP != std::string::npos) {
      int p = atoi(cmd.c_str() + cmd.find_first_of("0123456789", posP));
      currentMode = (p == 0) ? 9 : 1;
      markSettingsChanged();
      needInterrupt = true;
    }

    // d. Chọn màu toàn ma trận: "c": [r, g, b]
    size_t posC = cmd.find("\"c\":");
    if (posC == std::string::npos) posC = cmd.find("\"color\":");
    if (posC != std::string::npos) {
      int r = 0, g = 0, b = 0;
      size_t openBracket = cmd.find('[', posC);
      if (openBracket != std::string::npos) {
        if (sscanf(cmd.c_str() + openBracket + 1, "%d,%d,%d", &r, &g, &b) == 3) {
          setCustomStaticColor(r, g, b);
          currentMode = 10;
          markSettingsChanged();
          needInterrupt = true;
        }
      }
    }

    // e. Chạy chữ: "t": "nội dung"
    size_t posT = cmd.find("\"t\":");
    if (posT == std::string::npos) posT = cmd.find("\"text\":");
    if (posT != std::string::npos) {
      size_t firstQuote = cmd.find('\"', posT + 4);
      if (firstQuote != std::string::npos) {
        size_t secondQuote = cmd.find('\"', firstQuote + 1);
        if (secondQuote != std::string::npos) {
          std::string textStr = cmd.substr(firstQuote + 1, secondQuote - firstQuote - 1);
          char buf[128];
          snprintf(buf, sizeof(buf), "text %s", textStr.c_str());
          needInterrupt |= executeCliCommand(buf);
        }
      }
    }

    // f. Đồng bộ thời tiết JSON: {"w": 1, "temp": 28}
    size_t posW = cmd.find("\"w\":");
    if (posW == std::string::npos) posW = cmd.find("\"weather\":");
    if (posW != std::string::npos) {
      int wId = atoi(cmd.c_str() + cmd.find_first_of("0123456789", posW));
      int temp = 25;
      size_t posTemp = cmd.find("\"temp\":");
      if (posTemp != std::string::npos) {
        temp = atoi(cmd.c_str() + cmd.find_first_of("-0123456789", posTemp));
      }
      triggerWeatherUpdate((int8_t)temp, (uint8_t)wId);
      needInterrupt = true;
    }
  }
  // 2. NẾU LÀ LỆNH VĂN BẢN THUẦN (CLI Text Command): Ví dụ "mode 2", "bright 60", "text HELLO"
  else {
    needInterrupt = executeCliCommand(cmd.c_str());
  }

  // Gửi telemetry cập nhật tức thì về App sau khi thực thi lệnh
  bleSendTelemetry(true);
  return needInterrupt;
}

bool handleBleService() {
  bool needInterrupt = false;

  // 1. Quản lý trạng thái kết nối & khôi phục phát quảng bá
  if (!deviceConnected && oldDeviceConnected) {
    delay(20);
    pServer->startAdvertising();
    dbgLog(TAG_BLE, "Đã bật lại quảng bá (Advertising) để điện thoại kết nối tiếp.");
    oldDeviceConnected = deviceConnected;
  }
  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
    bleSendTelemetry(true); // Gửi telemetry ngay khi điện thoại vừa kết nối
  }

  // 2. Lấy lệnh từ hàng đợi RX ra thực thi
  while (true) {
    std::string nextCmd = "";
    portENTER_CRITICAL(&bleMux);
    if (!bleRxQueue.empty()) {
      nextCmd = bleRxQueue.front();
      bleRxQueue.pop();
    }
    portEXIT_CRITICAL(&bleMux);

    if (nextCmd.empty()) break;

    if (parseAndExecuteBleCommand(nextCmd)) {
      needInterrupt = true;
    }
  }

  // 3. Gửi nhịp Telemetry định kỳ lên App qua Notify
  bleSendTelemetry(false);

  return needInterrupt;
}
