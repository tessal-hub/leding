package com.esp32.ledmatrix360.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.esp32.ledmatrix360.model.LampConstants
import com.esp32.ledmatrix360.model.PixelCanvasState
import com.esp32.ledmatrix360.model.TelemetryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _telemetryState = MutableStateFlow(TelemetryState())
    val telemetryState: StateFlow<TelemetryState> = _telemetryState.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _otaProgress = MutableStateFlow<Float?>(null)
    val otaProgress: StateFlow<Float?> = _otaProgress.asStateFlow()

    private val _otaStatus = MutableStateFlow<String>("")
    val otaStatus: StateFlow<String> = _otaStatus.asStateFlow()

    fun log(message: String) {
        Log.d("BleManager", message)
        val current = _logs.value.toMutableList()
        if (current.size > 80) current.removeAt(0)
        current.add(message)
        _logs.value = current
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val name = device.name ?: ""
                if (name == LampConstants.DEVICE_NAME || name.contains("LED-MATRIX")) {
                    log("Tìm thấy thiết bị: $name (${device.address}). Đang kết nối...")
                    stopScan()
                    connect(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Lỗi quét BLE: Mã $errorCode")
            _isScanning.value = false
        }
    }

    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth chưa được bật hoặc không khả dụng!")
            return
        }
        if (_isScanning.value) return

        _isScanning.value = true
        log("Bắt đầu quét tìm '${LampConstants.DEVICE_NAME}'...")

        val filter = ScanFilter.Builder().build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan() {
        if (!_isScanning.value) return
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        _isScanning.value = false
    }

    fun connect(device: BluetoothDevice) {
        log("Đang tạo GATT Connection...")
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        log("Đang ngắt kết nối...")
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _isConnected.value = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Đã kết nối GATT! Đang khám phá dịch vụ...")
                _isConnected.value = true
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Đã ngắt kết nối GATT.")
                _isConnected.value = false
                rxCharacteristic = null
                txCharacteristic = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val serviceUuid = UUID.fromString(LampConstants.NUS_SERVICE_UUID)
                val rxUuid = UUID.fromString(LampConstants.NUS_RX_UUID)
                val txUuid = UUID.fromString(LampConstants.NUS_TX_UUID)

                val nusService = gatt.getService(serviceUuid)
                if (nusService != null) {
                    rxCharacteristic = nusService.getCharacteristic(rxUuid)
                    txCharacteristic = nusService.getCharacteristic(txUuid)

                    // Bật Notify trên TX
                    txCharacteristic?.let { txChar ->
                        gatt.setCharacteristicNotification(txChar, true)
                        val cccd = txChar.getDescriptor(UUID.fromString(LampConstants.CCCD_UUID))
                        if (cccd != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(cccd)
                            }
                        }
                    }
                    log("Sẵn sàng giao tiếp với đèn qua Nordic UART Service!")
                } else {
                    log("Không tìm thấy Nordic UART Service trên đèn!")
                }
            }
        }

        // Xử lý gói tin Notify nhận từ ESP32 (Android < 13)
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            @Suppress("DEPRECATION")
            characteristic?.value?.let { parseIncomingBytes(it) }
        }

        // Xử lý gói tin Notify nhận từ ESP32 (Android 13+)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            parseIncomingBytes(value)
        }
    }

    private fun parseIncomingBytes(bytes: ByteArray) {
        val raw = String(bytes, Charsets.UTF_8).trim()
        if (raw.startsWith("{") && raw.endsWith("}")) {
            try {
                val json = JSONObject(raw)
                val cur = _telemetryState.value
                _telemetryState.value = cur.copy(
                    mode = json.optInt("m", cur.mode),
                    modeName = json.optString("mn", cur.modeName),
                    brightness = json.optInt("b", cur.brightness),
                    powerState = json.optInt("pwr", cur.powerState),
                    vbus = json.optInt("vbus", cur.vbus),
                    statMv = json.optInt("stat", cur.statMv),
                    pitch = json.optDouble("pitch", cur.pitch.toDouble()).toFloat(),
                    roll = json.optDouble("roll", cur.roll.toDouble()).toFloat(),
                    heap = json.optLong("heap", cur.heap)
                )
            } catch (e: Exception) {
                log("Data: $raw")
            }
        } else {
            log("UART: $raw")
        }
    }

    /**
     * Gửi lệnh xuống ESP32 qua Characteristic RX
     */
    fun sendCommand(cmd: String) {
        if (!_isConnected.value) {
            log("Chưa kết nối! Bỏ qua lệnh: $cmd")
            return
        }
        sendBytes((cmd + "\n").toByteArray(Charsets.UTF_8))
        log("-> Đã gửi: $cmd")
    }

    /**
     * Gửi gói tin nhị phân (Binary GATT Frame)
     */
    fun sendBytes(data: ByteArray) {
        val gatt = bluetoothGatt
        val rxChar = rxCharacteristic
        if (gatt == null || rxChar == null || !_isConnected.value) {
            log("Chưa kết nối! Bỏ qua gửi binary frame (${data.size} bytes)")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rxChar, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            rxChar.value = data
            @Suppress("DEPRECATION")
            rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rxChar)
        }
    }

    /**
     * Gửi lệnh chạy chữ 360°
     */
    fun sendMarqueeText(text: String) {
        val cleanText = text.trim()
        if (cleanText.isNotEmpty()) {
            sendCommand("text $cleanText")
        }
    }

    /**
     * Gửi khung vẽ Pixel Art 32x8
     */
    fun sendPixelCanvas(canvasState: PixelCanvasState, brightness: Int = 0) {
        // Lấy màu nổi bật nhất từ canvas để hiển thị màu tĩnh
        var sumR = 0
        var sumG = 0
        var sumB = 0
        var count = 0

        for (y in 0 until canvasState.height) {
            for (x in 0 until canvasState.width) {
                val color = canvasState.getPixel(x, y)
                if (color != android.graphics.Color.BLACK) {
                    sumR += android.graphics.Color.red(color)
                    sumG += android.graphics.Color.green(color)
                    sumB += android.graphics.Color.blue(color)
                    count++
                }
            }
        }

        if (count > 0) {
            setStaticColor(sumR / count, sumG / count, sumB / count, brightness)
        } else {
            setStaticColor(0, 0, 0, brightness)
        }
    }

    /**
     * Kích hoạt Pomodoro 25p từ xa
     */
    fun triggerPomodoro() {
        sendCommand("pomo")
    }

    /**
     * Frame 0x01: Đổi màu tĩnh RGB tức thì [0x01, R, G, B, Brightness]
     */
    fun setStaticColor(r: Int, g: Int, b: Int, brightness: Int = 0) {
        sendBytes(byteArrayOf(0x01.toByte(), r.toByte(), g.toByte(), b.toByte(), brightness.toByte()))
    }

    /**
     * Frame 0x02: Chuyển hiệu ứng [0x02, Effect_ID, Speed, Brightness, Palette_ID]
     */
    fun setEffect(effectId: Int, speed: Int = 50, brightness: Int = 0, paletteId: Int = 0) {
        sendBytes(byteArrayOf(0x02.toByte(), effectId.toByte(), speed.toByte(), brightness.toByte(), paletteId.toByte()))
    }

    /**
     * Frame 0x03: Ngắt chớp thông báo hệ thống [0x03, R, G, B, Flash_Count, Delay_10ms]
     */
    fun sendNotificationInterrupt(r: Int, g: Int, b: Int, count: Int = 3, delay10ms: Int = 15) {
        sendBytes(byteArrayOf(0x03.toByte(), r.toByte(), g.toByte(), b.toByte(), count.toByte(), delay10ms.toByte()))
    }

    /**
     * Frame 0x04: Đồng bộ thời tiết [0x04, Temp_Signed, Weather_ID]
     */
    fun sendWeather(tempSigned: Int, weatherId: Int) {
        sendBytes(byteArrayOf(0x04.toByte(), tempSigned.toByte(), weatherId.toByte()))
    }

    /**
     * Frame 0x05: Đồng bộ giờ hệ thống [0x05, Hour, Minute, Second]
     */
    fun sendTimeSync(hour: Int, minute: Int, second: Int) {
        sendBytes(byteArrayOf(0x05.toByte(), hour.toByte(), minute.toByte(), second.toByte()))
    }

    /**
     * Nạp Firmware OTA qua BLE GATT
     */
    suspend fun performOtaUpdate(
        firmwareBytes: ByteArray,
        chunkSize: Int = 256,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (!_isConnected.value || firmwareBytes.isEmpty()) {
            _otaStatus.value = "Chưa kết nối Bluetooth với đèn!"
            return false
        }

        try {
            _otaProgress.value = 0f
            _otaStatus.value = "Đang gửi lệnh bắt đầu OTA (${firmwareBytes.size} bytes)..."
            log("OTA: Bắt đầu nạp firmware (${firmwareBytes.size} bytes)...")

            val size = firmwareBytes.size
            val startPacket = ByteArray(5)
            startPacket[0] = 0x10.toByte()
            startPacket[1] = (size and 0xFF).toByte()
            startPacket[2] = ((size shr 8) and 0xFF).toByte()
            startPacket[3] = ((size shr 16) and 0xFF).toByte()
            startPacket[4] = ((size shr 24) and 0xFF).toByte()
            sendBytes(startPacket)

            kotlinx.coroutines.delay(250)

            var sent = 0
            val total = firmwareBytes.size

            while (sent < total) {
                val end = minOf(sent + chunkSize, total)
                val len = end - sent
                val chunkPacket = ByteArray(1 + len)
                chunkPacket[0] = 0x11.toByte()
                System.arraycopy(firmwareBytes, sent, chunkPacket, 1, len)

                sendBytes(chunkPacket)
                sent += len

                val p = sent.toFloat() / total.toFloat()
                _otaProgress.value = p
                onProgress?.invoke(p)
                _otaStatus.value = "Đang truyền: ${(p * 100).toInt()}% ($sent / $total bytes)"

                kotlinx.coroutines.delay(12)
            }

            kotlinx.coroutines.delay(250)

            _otaStatus.value = "Đang chốt firmware và đợi ESP32 kiểm tra..."
            sendBytes(byteArrayOf(0x12.toByte()))
            log("OTA: Đã gửi lệnh chốt firmware 0x12.")

            kotlinx.coroutines.delay(1500)
            _otaProgress.value = 1f
            _otaStatus.value = "Cập nhật thành công! Đèn đang khởi động lại..."
            return true
        } catch (e: Exception) {
            log("OTA Lỗi: ${e.message}")
            _otaStatus.value = "Lỗi trong quá trình nạp: ${e.message}"
            _otaProgress.value = null
            return false
        }
    }

    companion object {
        @Volatile
        private var instance: BleManager? = null

        fun getInstance(context: Context): BleManager {
            return instance ?: synchronized(this) {
                instance ?: BleManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
