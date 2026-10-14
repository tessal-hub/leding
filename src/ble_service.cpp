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
static unsigned long lastOtaChunkTime = 0;

void abortBleOta() {
  if (otaInProgress) {
    Update.abort();
    otaInProgress = false;
  }
  isOtaModeActive = false;
  displayOtaProgress(0, true);
  clearMatrix();
  dbgLog(TAG_SYS, "OTA BLE: Đã hủy tiến trình OTA an toàn.");
}

bool isBleOtaInProgress() {
  return otaInProgress;
}

// Hàng đợi nhận lệnh an toàn đa luồng (mutex blockable thay spinlock: cho phép
// malloc bên trong queue khi nhận gói OTA 512B liên tục mà không khóa ngắt RMT)
// ponytail: chưa tách ring-buffer riêng cho OTA 0x11, thêm khi OTA nghẽn đo được
static std::queue<std::string> bleRxQueue;
static SemaphoreHandle_t bleMutex = NULL;

static inline bool bleLock(TickType_t wait = pdMS_TO_TICKS(20)) {
  return bleMutex != NULL && xSemaphoreTake(bleMutex, wait) == pdTRUE;
}
static inline void bleUnlock() {
  if (bleMutex != NULL) xSemaphoreGive(bleMutex);
}

// Callbacks xử lý kết nối / ngắt kết nối & Bảo mật Ghép đôi (Security / Bonding)
class BleServerCallbacks: public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* pServer, ble_gap_conn_desc* desc) override {
    deviceConnected = true;
    dbgLog(TAG_BLE, "Điện thoại đã kết nối Bluetooth BLE thành công! (MAC: %s)",
           desc ? NimBLEAddress(desc->peer_ota_addr).toString().c_str() : "unknown");
  }

  void onDisconnect(NimBLEServer* pServer, ble_gap_conn_desc* desc) override {
    deviceConnected = false;
    dbgLog(TAG_BLE, "Điện thoại đã ngắt kết nối BLE.");
    if (otaInProgress || isOtaModeActive) {
      abortBleOta();
    }
    // Bật lại quảng bá ngay lập tức để điện thoại quét lại được tức thì
    NimBLEDevice::startAdvertising();
  }

  uint32_t onPassKeyRequest() override {
    dbgLog(TAG_BLE, "BLE Security: Yêu cầu Passkey -> 0");
    return 0;
  }

  bool onConfirmPIN(uint32_t pin) override {
    dbgLog(TAG_BLE, "BLE Security: Xác nhận PIN %u -> Chấp nhận (Just Works)", pin);
    return true;
  }

  void onAuthenticationComplete(ble_gap_conn_desc* desc) override {
    if (desc && desc->sec_state.bonded) {
      dbgLog(TAG_BLE, "BLE Security: Ghép đôi (Bonding) thành công!");
    } else {
      dbgLog(TAG_BLE, "BLE Security: Xác thực kết nối hoàn tất.");
    }
  }
};

// Callbacks nhận dữ liệu từ App qua Characteristic RX
class BleRxCallbacks: public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *pCharacteristic) {
    std::string rxValue = pCharacteristic->getValue();
    if (rxValue.length() > 0) {
      if (bleLock()) {
        if (bleRxQueue.size() < 40) {
          bleRxQueue.push(std::move(rxValue));
        }
        bleUnlock();
      }
    }
  }
};

void initBleService() {
  dbgLog(TAG_BLE, "Khởi tạo dịch vụ BLE Nordic UART Service (Tên: %s)...", BLE_DEVICE_NAME);

  if (bleMutex == NULL) bleMutex = xSemaphoreCreateMutex();

  // 1. Khởi tạo BLE Device & Tăng công suất phát sóng tối đa (+9dBm)
  NimBLEDevice::init(BLE_DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9); // Công suất phát tối đa +9dBm (xuyên vỏ hộp)
  NimBLEDevice::setMTU(517);              // Hỗ trợ trao đổi gói tin lớn đến 512 bytes

  // 2. Cấu hình bảo mật & ghép đôi (Hỗ trợ Ghép đôi "Just Works" tương thích tối đa với mọi máy)
  // sc=false: Sử dụng Legacy Just Works để tránh lỗi SMP mismatch trên các dòng điện thoại
  NimBLEDevice::setSecurityAuth(/*bonding=*/true, /*mitm=*/false, /*sc=*/false);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);
  NimBLEDevice::setSecurityInitKey(BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID);
  NimBLEDevice::setSecurityRespKey(BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID);

  // 3. Tạo BLE Server
  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new BleServerCallbacks());

  // 4. Tạo Nordic UART Service (NUS)
  NimBLEService *pService = pServer->createService(BLE_NUS_SERVICE_UUID);

  // 5. Characteristic TX (Notify từ ESP32 -> App)
  pTxCharacteristic = pService->createCharacteristic(
                        BLE_NUS_TX_UUID,
                        NIMBLE_PROPERTY::NOTIFY
                      );

  // 6. Characteristic RX (Write từ App -> ESP32)
  pRxCharacteristic = pService->createCharacteristic(
                        BLE_NUS_RX_UUID,
                        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
                      );
  pRxCharacteristic->setCallbacks(new BleRxCallbacks());

  // 7. Khởi động Service
  pService->start();

  // 8. Bật phát quảng bá Bluetooth
  NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();

  // Gói quảng bá chính (Primary Advertisement Packet - tối đa 31 bytes):
  // Flags (3B) + 128-bit Service UUID (18B) + Tên "LED-360" (9B) = 30 bytes <= 31 bytes.
  // QUAN TRỌNG CHO WINDOWS: Windows WinRT BLE API chỉ lọc Service UUID trong gói quảng bá chính!
  NimBLEAdvertisementData advData;
  advData.setFlags(0x06); // General Discoverable Mode + BR/EDR Not Supported
  advData.setCompleteServices(NimBLEUUID(BLE_NUS_SERVICE_UUID));
  advData.setName("LED-360");
  pAdvertising->setAdvertisementData(advData);

  // Gói phản hồi (Scan Response): Chứa TÊN ĐẦY ĐỦ "LED-MATRIX-360"
  NimBLEAdvertisementData scanData;
  scanData.setName(BLE_DEVICE_NAME);
  pAdvertising->setScanResponseData(scanData);

  pAdvertising->setScanResponse(true);
  pAdvertising->setMinInterval(48);  // 48 * 0.625ms = 30ms (Quét cực nhạy, tức thì)
  pAdvertising->setMaxInterval(80);  // 80 * 0.625ms = 50ms

  bool started = pAdvertising->start();
  if (!started) {
    delay(100);
    started = pAdvertising->start();
  }

  if (started) {
    dbgLog(TAG_BLE, "BLE đã sẵn sàng! UUID Service: %s (TxPower: +9dBm)", BLE_NUS_SERVICE_UUID);
    dbgLog(TAG_BLE, "Thiết bị '%s': ĐANG PHÁT SÓNG QUẢNG BÁ (Sẵn sàng kết nối).", BLE_DEVICE_NAME);
  } else {
    dbgLog(TAG_BLE, "CẢNH BÁO: pAdvertising->start() thất bại!");
  }
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

  char teleJson[230];
  snprintf(teleJson, sizeof(teleJson),
           "{\"m\":%d,\"mn\":\"%s\",\"b\":%d,\"sp\":%d,\"pwr\":%d,\"vbus\":%d,\"stat\":%u,\"pitch\":%.1f,\"roll\":%.1f,\"heap\":%u}",
           currentMode,
           getModeName(currentMode),
           currentBrightness,
           currentSpeed,
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

// Đọc số nguyên ngay sau dấu ':' của key (strtol: đúng vị trí, chịu giá trị
// âm/rỗng — atoi + find_first_of sẽ đọc lố sang số của trường kế tiếp)
static int jsonIntAfterKey(const std::string& cmd, size_t keyPos) {
  size_t colon = cmd.find(':', keyPos);
  if (colon == std::string::npos) return 0;
  return (int)strtol(cmd.c_str() + colon + 1, NULL, 10);
}

// Xử lý gói tin dạng nhị phân, JSON đơn giản hoặc lệnh văn bản thuần
static bool parseAndExecuteBleCommand(const std::string& raw) {
  if (raw.empty()) return false;

  // 1. KIỂM TRA GÓI TIN NHỊ PHÂN BLE GATT (0x01 .. 0x07 & 0x10 .. 0x13)
  uint8_t op = (uint8_t)raw[0];
  if ((op >= 0x01 && op <= 0x07) || (op >= 0x10 && op <= 0x13)) {
    if (op <= 0x07) {
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
            currentBrightness = constrain((uint8_t)raw[4], (uint8_t)5, (uint8_t)255);
            strip.setBrightness(toHardwareBrightness(currentBrightness));
          }
          setCustomStaticColor(r, g, b);
          currentMode = 10;
          markSettingsChanged();
          dbgLog(TAG_BLE, "Binary 0x01: Đổi màu tĩnh RGB(%d, %d, %d), Bright: %d (HW: %d)",
                 r, g, b, currentBrightness, toHardwareBrightness(currentBrightness));
          needInterrupt = true;
        }
        break;
      }
      case 0x02: { // Set Effect: [0x02, Effect_ID, Speed, Brightness, Palette_ID]
        if (raw.length() >= 2) {
          uint8_t m = (uint8_t)raw[1];
          if (m < TOTAL_MODES) {
            if (m != currentMode) {
              fadeTransition(240);
            }
            currentMode = m;
            needInterrupt = true;
          }
          if (raw.length() >= 3 && (uint8_t)raw[2] >= 10 && (uint8_t)raw[2] <= 100) {
            currentSpeed = (uint8_t)raw[2];
          }
          if (raw.length() >= 4 && (uint8_t)raw[3] > 0) {
            currentBrightness = constrain((uint8_t)raw[3], (uint8_t)5, (uint8_t)255);
            strip.setBrightness(toHardwareBrightness(currentBrightness));
          }
          markSettingsChanged();
          dbgLog(TAG_BLE, "Binary 0x02: Chọn Mode [%d] %s | Speed: %d | Bright: %d (HW: %d)",
                 currentMode, getModeName(currentMode), currentSpeed, currentBrightness, toHardwareBrightness(currentBrightness));
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
      case 0x04: { // Weather Data: [0x04, Temp_Signed, Weather_ID, Optional_ForceShow]
        if (raw.length() >= 3) {
          int8_t temp = (int8_t)raw[1];
          uint8_t wId = (uint8_t)raw[2];
          bool forceShow = (raw.length() >= 4 && (uint8_t)raw[3] == 1);
          dbgLog(TAG_BLE, "Nhận Frame 0x04 Thời Tiết: %d°C | ID: %d | ForceShow: %d", temp, wId, forceShow);
          triggerWeatherUpdate(temp, wId, forceShow);
          if (forceShow || currentMode == 15) {
            needInterrupt = true;
          }
        }
        break;
      }
      case 0x05: { // Time Sync: [0x05, Hour, Minute, Second]
        if (raw.length() >= 4) {
          uint8_t h = (uint8_t)raw[1];
          uint8_t m = (uint8_t)raw[2];
          uint8_t s = (uint8_t)raw[3];
          syncDeviceTime(h, m, s);
          dbgLog(TAG_BLE, "Binary 0x05: Đồng bộ giờ %02d:%02d:%02d", h, m, s);
        }
        break;
      }
      case 0x06: { // Slot Bitmap (1-Bit): [0x06, Slot (1..4), R, G, B, Row0..Row7 (8 bytes)]
        if (raw.length() >= 13) {
          uint8_t slot = (uint8_t)raw[1];
          if (slot >= 1 && slot <= NUM_SLOTS) slot -= 1;
          if (slot < NUM_SLOTS) {
            uint8_t r = (uint8_t)raw[2];
            uint8_t g = (uint8_t)raw[3];
            uint8_t b = (uint8_t)raw[4];
            const uint8_t* rows = (const uint8_t*)raw.data() + 5;
            setSlotBitmap1Bit(slot, r, g, b, rows);
            currentMode = 11;
            renderFourSlotsToMatrix();
            strip.show();
            markSlotsDirty();
            dbgLog(TAG_BLE, "Binary 0x06: Cập nhật Ô %d (Mặt %d) Bitmap 8x8 RGB(%d, %d, %d)",
                   slot + 1, slot + 1, r, g, b);
            needInterrupt = true;
          }
        }
        break;
      }
      case 0x07: { // Slot Pixels RGB565 Chunk: [0x07, Slot (1..4), Chunk_Idx (0..3), Data...]
        if (raw.length() >= 4) {
          uint8_t slot = (uint8_t)raw[1];
          if (slot >= 1 && slot <= NUM_SLOTS) slot -= 1;
          if (slot < NUM_SLOTS) {
            uint8_t chunkIdx = (uint8_t)raw[2];
            setSlotPixelsRGB565(slot, chunkIdx, (const uint8_t*)raw.data() + 3, raw.length() - 3);
            currentMode = 11;
            renderFourSlotsToMatrix();
            strip.show();
            markSlotsDirty();
            dbgLog(TAG_BLE, "Binary 0x07: Nạp Chunk RGB %d cho Ô %d", chunkIdx, slot + 1);
            needInterrupt = true;
          }
        }
        break;
      }
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
          lastOtaChunkTime = millis();
          isOtaModeActive = true;
          displayOtaProgress(1, false);
          bleNotify("{\"ota\":\"start\",\"res\":0}");
          needInterrupt = true;
        } else {
          otaInProgress = false;
          isOtaModeActive = false;
          displayOtaProgress(0, true);
          char errBuf[64];
          snprintf(errBuf, sizeof(errBuf), "{\"ota\":\"error\",\"err\":%d}", Update.getError());
          bleNotify(errBuf);
        }
        break;
      }
      case 0x11: { // OTA Data Chunk: [0x11, Payload...]
        if (otaInProgress && raw.length() > 1) {
          lastOtaChunkTime = millis();
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
            isOtaModeActive = false;
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
        abortBleOta();
        bleNotify("{\"ota\":\"aborted\"}");
        dbgLog(TAG_SYS, "Đã hủy tiến trình OTA BLE theo yêu cầu.");
        break;
      }
    }
    if (op <= 0x07) bleSendTelemetry(true);
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
      int m = jsonIntAfterKey(cmd, posM);
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
      int b = jsonIntAfterKey(cmd, posB);
      currentBrightness = constrain((uint8_t)b, (uint8_t)5, (uint8_t)255);
      strip.setBrightness(toHardwareBrightness(currentBrightness));
      strip.show();
      markSettingsChanged();
    }

    // c. Công tắc nguồn: "p": 0 (Tắt) hoặc "p": 1 (Bật)
    size_t posP = cmd.find("\"p\":");
    if (posP == std::string::npos) posP = cmd.find("\"power\":");
    if (posP != std::string::npos) {
      int p = jsonIntAfterKey(cmd, posP);
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
      int wId = jsonIntAfterKey(cmd, posW);
      int temp = 25;
      size_t posTemp = cmd.find("\"temp\":");
      if (posTemp != std::string::npos) {
        temp = jsonIntAfterKey(cmd, posTemp);
      }
      triggerWeatherUpdate((int8_t)temp, (uint8_t)wId, true);
      needInterrupt = true;
    }

    // g. Cài đặt Ô 8x8 qua JSON: {"slot": 1, "p": "heart"} hoặc {"slot": 2, "clear": 1}
    size_t posSlot = cmd.find("\"slot\":");
    if (posSlot != std::string::npos) {
      int s = jsonIntAfterKey(cmd, posSlot);
      if (s >= 1 && s <= NUM_SLOTS) s -= 1;
      if (s >= 0 && s < NUM_SLOTS) {
        size_t posPres = cmd.find("\"preset\":");
        if (posPres == std::string::npos) posPres = cmd.find("\"p\":");
        if (posPres != std::string::npos) {
          size_t q1 = cmd.find('\"', posPres + 4);
          if (q1 != std::string::npos) {
            size_t q2 = cmd.find('\"', q1 + 1);
            if (q2 != std::string::npos) {
              std::string pName = cmd.substr(q1 + 1, q2 - q1 - 1);
              applySlotPreset((uint8_t)s, pName.c_str());
              currentMode = 11;
              renderFourSlotsToMatrix();
              strip.show();
              markSlotsDirty();
              needInterrupt = true;
            }
          }
        }
        if (cmd.find("\"clear\":") != std::string::npos) {
          clearSlot((uint8_t)s);
          currentMode = 11;
          renderFourSlotsToMatrix();
          strip.show();
          markSlotsDirty();
          needInterrupt = true;
        }
      }
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

  // 0. Timeout bảo vệ an toàn khi nạp OTA BLE (quá 15s không có gói tin mới)
  if (otaInProgress && (millis() - lastOtaChunkTime > 15000)) {
    dbgLog(TAG_SYS, "OTA BLE: Quá thời gian chờ (15s không có dữ liệu) -> Tự động hủy!");
    abortBleOta();
    bleNotify("{\"ota\":\"timeout\"}");
  }

  // 1. Quản lý trạng thái kết nối
  if (!deviceConnected && oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
    dbgLog(TAG_BLE, "Trạng thái BLE: Đã ngắt kết nối.");
  }
  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
    lastTelemetryTime = millis(); // Bắt đầu chu kỳ gửi telemetry mới
  }

  // 2. Lấy lệnh từ hàng đợi RX ra thực thi
  while (true) {
    std::string nextCmd = "";
    if (bleLock(portMAX_DELAY)) {
      if (!bleRxQueue.empty()) {
        nextCmd = std::move(bleRxQueue.front());
        bleRxQueue.pop();
      }
      bleUnlock();
    }

    if (nextCmd.empty()) break;

    if (parseAndExecuteBleCommand(nextCmd)) {
      needInterrupt = true;
    }
  }

  // 3. Gửi nhịp Telemetry định kỳ lên App qua Notify
  bleSendTelemetry(false);

  return needInterrupt;
}
