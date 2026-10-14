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

    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )
    @Volatile
    private var writeAckDeferred: kotlinx.coroutines.CompletableDeferred<Int>? = null
    @Volatile
    private var otaResultDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: ""
            val hasNusUuid = result.scanRecord?.serviceUuids?.any {
                it.uuid.toString().equals(LampConstants.NUS_SERVICE_UUID, ignoreCase = true)
            } == true

            if (name == LampConstants.DEVICE_NAME || name == "LED-360" || name.contains("LED-MATRIX") || hasNusUuid) {
                log("Tìm thấy thiết bị: ${name.ifEmpty { "LED-360" }} (${device.address}). Đang kết nối...")
                stopScan()
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Lỗi quét BLE: Mã $errorCode")
            _isScanning.value = false
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable {
        if (_isScanning.value) {
            log("Hết thời gian quét BLE (15s). Tự động dừng quét.")
            stopScan()
        }
    }

    fun startScan(timeoutMs: Long = 15_000L) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null || !bluetoothAdapter.isEnabled) {
            log("Bluetooth chưa được bật hoặc không khả dụng!")
            return
        }
        if (_isScanning.value) return

        _isScanning.value = true
        log("Bắt đầu quét tìm '${LampConstants.DEVICE_NAME}'...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(emptyList(), settings, scanCallback)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.postDelayed(scanTimeoutRunnable, timeoutMs)
    }

    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        if (!_isScanning.value) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w("BleManager", "stopScan error: ${e.message}")
        }
        _isScanning.value = false
    }

    fun connect(device: BluetoothDevice) {
        log("Đang tạo GATT Connection...")
        // Đóng GATT cũ trước khi tạo kết nối mới để tránh lỗi GATT 133
        bluetoothGatt?.let { oldGatt ->
            try {
                oldGatt.disconnect()
                oldGatt.close()
            } catch (_: Exception) {}
        }
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
                log("Đã kết nối GATT! Đang yêu cầu MTU 512...")
                _isConnected.value = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    gatt?.requestMtu(512)
                } else {
                    gatt?.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Đã ngắt kết nối GATT.")
                _isConnected.value = false
                rxCharacteristic = null
                txCharacteristic = null
                try {
                    gatt?.close()
                } catch (_: Exception) {}
                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            log("BLE MTU cập nhật: $mtu (status: $status). Đang khám phá dịch vụ...")
            gatt?.discoverServices()
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
                    // Tự động đồng bộ giờ hệ thống RTC
                    val cal = java.util.Calendar.getInstance()
                    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    val m = cal.get(java.util.Calendar.MINUTE)
                    val s = cal.get(java.util.Calendar.SECOND)
                    sendTimeSync(h, m, s)
                    log("-> Tự động đồng bộ giờ hệ thống: $h:$m:$s")
                } else {
                    log("Không tìm thấy Nordic UART Service trên đèn!")
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            writeAckDeferred?.complete(status)
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
                if (json.has("ota")) {
                    when (val otaEvent = json.optString("ota")) {
                        "ready" -> {
                            _otaStatus.value = "Đèn đã vào chế độ OTA! Sẵn sàng chọn file .bin"
                            log("ESP32 OTA: Sẵn sàng nhận firmware")
                        }
                        "start" -> log("ESP32 OTA: Đã mở phân vùng Flash thành công")
                        "prog" -> {
                            val pct = json.optInt("p", 0)
                            _otaProgress.value = pct / 100f
                        }
                        "success" -> {
                            _otaProgress.value = 1f
                            _otaStatus.value = "Cập nhật thành công! Đèn đang khởi động lại..."
                            otaResultDeferred?.complete(true)
                        }
                        "error", "timeout", "aborted" -> {
                            val errCode = json.optInt("err", -1)
                            _otaStatus.value = "Lỗi OTA từ đèn ($otaEvent, mã: $errCode)"
                            _otaProgress.value = null
                            otaResultDeferred?.complete(false)
                        }
                    }
                    return
                }

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
            // ponytail: transient text overlay, keep telemetry.mode unchanged (mode 9 is Standby per firmware)
            sendCommand("text $cleanText")
        }
    }

    /**
     * Gửi khung vẽ Pixel Art 32x8
     */
    fun sendPixelCanvas(canvasState: PixelCanvasState, brightness: Int = 0) {
        ioScope.launch {
            sendFullCanvas32x8(canvasState)
        }
    }

    /**
     * Kích hoạt Pomodoro 25p từ xa
     */
    fun triggerPomodoro() {
        // ponytail: transient overlay, keep telemetry.mode unchanged (12 is Desk Clock per firmware)
        sendCommand("pomo")
    }

    /**
     * Cập nhật độ sáng tức thì trong StateFlow và gửi lệnh BLE xuống ESP32
     */
    fun setBrightness(brightness: Int) {
        val clamped = brightness.coerceIn(5, 255)
        _telemetryState.value = _telemetryState.value.copy(brightness = clamped)
        sendCommand("bright $clamped")
    }

    /**
     * Frame 0x01: Đổi màu tĩnh RGB tức thì [0x01, R, G, B, Brightness]
     */
    fun setStaticColor(r: Int, g: Int, b: Int, brightness: Int = 0) {
        _telemetryState.value = _telemetryState.value.copy(
            mode = 10,
            modeName = LampConstants.MODES.find { it.id == 10 }?.name ?: "Màu tĩnh"
        )
        sendBytes(byteArrayOf(0x01.toByte(), r.toByte(), g.toByte(), b.toByte(), brightness.toByte()))
    }

    /**
     * Frame 0x02: Chuyển hiệu ứng [0x02, Effect_ID, Speed, Brightness, Palette_ID]
     */
    fun setEffect(effectId: Int, speed: Int = 50, brightness: Int = 0, paletteId: Int = 0) {
        _telemetryState.value = _telemetryState.value.copy(
            mode = effectId,
            modeName = LampConstants.MODES.find { it.id == effectId }?.name ?: "Chế độ $effectId"
        )
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
     * Frame 0x06: Gửi Bitmap 1-bit 8x8 cho Ô [1..4] kèm màu RGB: [0x06, Slot, R, G, B, Row0..Row7]
     */
    fun sendSlotBitmap(slot: Int, r: Int, g: Int, b: Int, rows: ByteArray) {
        if (rows.size < 8) return
        val packet = ByteArray(13)
        packet[0] = 0x06.toByte()
        packet[1] = slot.toByte() // 1..4
        packet[2] = r.toByte()
        packet[3] = g.toByte()
        packet[4] = b.toByte()
        System.arraycopy(rows, 0, packet, 5, 8)
        sendBytes(packet)
        log("-> Đã gửi Ô $slot Bitmap 8x8 RGB($r, $g, $b)")
    }

    /**
     * Frame 0x07: Gửi dữ liệu màu RGB565 chi tiết theo từng chunk 16 pixels
     * [0x07, Slot (1..4), Chunk_Idx (0..3), Data (32 bytes)...]
     */
    suspend fun sendSlotRGB565(slot: Int, pixels: List<Int>) {
        if (!_isConnected.value || pixels.size < 64) return
        for (chunkIdx in 0..3) {
            val packet = ByteArray(35)
            packet[0] = 0x07.toByte()
            packet[1] = slot.toByte()
            packet[2] = chunkIdx.toByte()
            for (i in 0 until 16) {
                val pxIdx = chunkIdx * 16 + i
                val color = pixels[pxIdx]
                val r5 = (android.graphics.Color.red(color) shr 3) and 0x1F
                val g6 = (android.graphics.Color.green(color) shr 2) and 0x3F
                val b5 = (android.graphics.Color.blue(color) shr 3) and 0x1F
                val c565 = (r5 shl 11) or (g6 shl 5) or b5
                packet[3 + i * 2] = (c565 and 0xFF).toByte()
                packet[3 + i * 2 + 1] = ((c565 shr 8) and 0xFF).toByte()
            }
            sendBytes(packet)
            kotlinx.coroutines.delay(20)
        }
    }

    /**
     * Gửi toàn bộ 4 Ô lên đèn và chuyển sang Chế độ 11
     */
    suspend fun sendAllFourSlots(slots: List<com.esp32.ledmatrix360.model.SlotBitmapState>) {
        if (!_isConnected.value) {
            log("Chưa kết nối! Không thể gửi 4 Ô Bitmap.")
            return
        }
        for (slotState in slots) {
            sendSlotRGB565(slotState.id, slotState.pixels)
            kotlinx.coroutines.delay(25)
        }
        kotlinx.coroutines.delay(50)
        setEffect(11) // Bật Chế độ 11: 4-Slot Custom Display
        log("-> Đã kích hoạt Mode 11 (Hiển thị 4 Ô Bitmap RGB565 đa sắc trên đèn)")
    }

    /**
     * Gửi toàn bộ khung vẽ 32x8 lên đèn bằng cách chia thành 4 Ô 8x8 qua Frame 0x07 RGB565
     */
    suspend fun sendFullCanvas32x8(canvasState: PixelCanvasState) {
        if (!_isConnected.value) {
            log("Chưa kết nối! Bỏ qua gửi Canvas 32x8.")
            return
        }
        for (slotIdx in 0 until 4) {
            val slotNum = slotIdx + 1
            val startX = slotIdx * 8
            val slotPixels = ArrayList<Int>(64)
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    slotPixels.add(canvasState.getPixel(startX + x, y))
                }
            }
            sendSlotRGB565(slotNum, slotPixels)
            kotlinx.coroutines.delay(25)
        }
        kotlinx.coroutines.delay(50)
        setEffect(11)
        log("-> Đã gửi toàn bộ Canvas 32x8 đa sắc RGB565 lên đèn thành công!")
    }


    suspend fun sendBytesWithAck(data: ByteArray, timeoutMs: Long = 3000L): Boolean {
        val gatt = bluetoothGatt
        val rxChar = rxCharacteristic
        if (gatt == null || rxChar == null || !_isConnected.value) return false

        val ack = kotlinx.coroutines.CompletableDeferred<Int>()
        writeAckDeferred = ack

        val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                rxChar,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            rxChar.value = data
            @Suppress("DEPRECATION")
            rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rxChar)
        }

        if (!initiated) {
            writeAckDeferred = null
            return false
        }

        val status = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { ack.await() }
        writeAckDeferred = null
        return status == BluetoothGatt.GATT_SUCCESS
    }

    /**
     * Nạp Firmware OTA qua BLE GATT với Flow Control (Write with Response & JSON confirmation)
     */
    suspend fun performOtaUpdate(
        firmwareBytes: ByteArray,
        chunkSize: Int = 240,
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

            val resultDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            otaResultDeferred = resultDeferred

            val size = firmwareBytes.size
            val startPacket = byteArrayOf(
                0x10.toByte(),
                (size and 0xFF).toByte(),
                ((size shr 8) and 0xFF).toByte(),
                ((size shr 16) and 0xFF).toByte(),
                ((size shr 24) and 0xFF).toByte()
            )
            if (!sendBytesWithAck(startPacket)) {
                _otaStatus.value = "Không thể gửi gói khởi tạo OTA (0x10)!"
                _otaProgress.value = null
                return false
            }

            kotlinx.coroutines.delay(200)

            var sent = 0
            val total = firmwareBytes.size

            while (sent < total) {
                if (resultDeferred.isCompleted && !resultDeferred.await()) {
                    return false
                }
                val end = minOf(sent + chunkSize, total)
                val len = end - sent
                val chunkPacket = ByteArray(1 + len)
                chunkPacket[0] = 0x11.toByte()
                System.arraycopy(firmwareBytes, sent, chunkPacket, 1, len)

                if (!sendBytesWithAck(chunkPacket, timeoutMs = 5000L)) {
                    _otaStatus.value = "Mất kết nối hoặc lỗi truyền gói dữ liệu tại byte $sent!"
                    _otaProgress.value = null
                    return false
                }
                sent += len

                val p = sent.toFloat() / total.toFloat()
                _otaProgress.value = p
                onProgress?.invoke(p)
                _otaStatus.value = "Đang truyền: ${(p * 100).toInt()}% ($sent / $total bytes)"
            }

            _otaStatus.value = "Đang chốt firmware và đợi ESP32 kiểm tra checksum..."
            sendBytesWithAck(byteArrayOf(0x12.toByte()))
            log("OTA: Đã gửi lệnh chốt firmware 0x12.")

            val verified = kotlinx.coroutines.withTimeoutOrNull(5000L) { resultDeferred.await() } ?: false
            otaResultDeferred = null
            if (!verified) {
                _otaProgress.value = null
                return false
            }
            return true
        } catch (e: Exception) {
            log("OTA Lỗi: ${e.message}")
            _otaStatus.value = "Lỗi trong quá trình nạp: ${e.message}"
            _otaProgress.value = null
            otaResultDeferred = null
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
