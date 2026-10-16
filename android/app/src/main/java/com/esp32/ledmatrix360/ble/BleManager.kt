package com.esp32.ledmatrix360.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.esp32.ledmatrix360.data.PreferencesRepository
import com.esp32.ledmatrix360.model.BleConnectionState
import com.esp32.ledmatrix360.model.DiscoveredLampDevice
import com.esp32.ledmatrix360.model.LampConstants
import com.esp32.ledmatrix360.model.PixelCanvasState
import com.esp32.ledmatrix360.model.TelemetryState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<DiscoveredLampDevice>>(emptyList())
    val scannedDevices: StateFlow<List<DiscoveredLampDevice>> = _scannedDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow("LED-MATRIX-360")
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceAddress = MutableStateFlow("")
    val connectedDeviceAddress: StateFlow<String> = _connectedDeviceAddress.asStateFlow()

    private val _signalRssi = MutableStateFlow(0)
    val signalRssi: StateFlow<Int> = _signalRssi.asStateFlow()

    private val _isAutoConnectEnabled = MutableStateFlow(true)
    val isAutoConnectEnabled: StateFlow<Boolean> = _isAutoConnectEnabled.asStateFlow()

    private val _telemetryState = MutableStateFlow(TelemetryState())
    val telemetryState: StateFlow<TelemetryState> = _telemetryState.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _otaProgress = MutableStateFlow<Float?>(null)
    val otaProgress: StateFlow<Float?> = _otaProgress.asStateFlow()

    private val _otaStatus = MutableStateFlow<String>("")
    val otaStatus: StateFlow<String> = _otaStatus.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var connectionAttempts = 0
    private var targetScanAddress: String? = null
    private val incomingBuffer = StringBuilder()

    @Volatile
    private var writeAckDeferred: CompletableDeferred<Int>? = null
    @Volatile
    private var otaResultDeferred: CompletableDeferred<Boolean>? = null

    init {
        // Load initial auto-connect preference
        ioScope.launch {
            try {
                val prefs = PreferencesRepository.getInstance(context)
                _isAutoConnectEnabled.value = prefs.autoConnectFlow.first()
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi đọc cấu hình auto-connect: ${e.message}")
            }
        }
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        _isAutoConnectEnabled.value = enabled
        ioScope.launch {
            try {
                PreferencesRepository.getInstance(context).saveAutoConnect(enabled)
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi lưu cấu hình auto-connect: ${e.message}")
            }
        }
    }

    fun log(message: String) {
        Log.d(TAG, message)
        val current = _logs.value.toMutableList()
        if (current.size > 100) current.removeAt(0)
        current.add(message)
        _logs.value = current
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun clearScannedDevices() {
        _scannedDevices.value = emptyList()
    }

    private val scanTimeoutRunnable = Runnable {
        if (_isScanning.value) {
            log("Hết thời gian quét BLE (15s). Tự động dừng quét.")
            stopScan()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val scanRecordName = result.scanRecord?.deviceName
            val cachedName = device.name
            val address = device.address ?: ""
            val rssi = result.rssi

            // Ưu tiên tên tươi từ ScanRecord (chứa cả ScanResponse), sau đó mới tới cachedName
            val advertisedName = scanRecordName?.takeIf { it.isNotBlank() }
                ?: cachedName?.takeIf { it.isNotBlank() }
                ?: ""

            val hasNusUuid = result.scanRecord?.serviceUuids?.any {
                it.uuid.toString().equals(LampConstants.NUS_SERVICE_UUID, ignoreCase = true)
            } == true

            val isLamp = advertisedName.equals(LampConstants.DEVICE_NAME, ignoreCase = true) ||
                    advertisedName.equals(LampConstants.SHORT_NAME, ignoreCase = true) ||
                    advertisedName.contains("LED-MATRIX", ignoreCase = true) ||
                    advertisedName.contains("LED-360", ignoreCase = true) ||
                    advertisedName.contains("CubeLamp", ignoreCase = true) ||
                    hasNusUuid ||
                    (!targetScanAddress.isNullOrBlank() && address.equals(targetScanAddress, ignoreCase = true))

            // ĐỒNG BỘ TÊN VỚI FIRMWARE ESP32:
            // Nếu bắt được tên ngắn 8-byte từ gói quảng bá chính ("LED-360") hoặc chỉ bắt được UUID,
            // tự động chuẩn hóa về tên đầy đủ chính thức trong firmware ESP32 ("LED-MATRIX-360")!
            val displayName = when {
                advertisedName.equals(LampConstants.DEVICE_NAME, ignoreCase = true) -> LampConstants.DEVICE_NAME
                advertisedName.equals(LampConstants.SHORT_NAME, ignoreCase = true) -> LampConstants.DEVICE_NAME
                advertisedName.equals("LED-360", ignoreCase = true) -> LampConstants.DEVICE_NAME
                advertisedName.contains("LED-MATRIX", ignoreCase = true) -> LampConstants.DEVICE_NAME
                advertisedName.isNotBlank() -> advertisedName
                isLamp -> LampConstants.DEVICE_NAME
                else -> "Thiết bị BLE"
            }

            val item = DiscoveredLampDevice(device, displayName, address, rssi)

            // Cập nhật danh sách thiết bị quét được cho giao diện người dùng
            val currentList = _scannedDevices.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.address == address }
            if (existingIndex >= 0) {
                val existing = currentList[existingIndex]
                val finalName = if (existing.name == LampConstants.DEVICE_NAME) {
                    LampConstants.DEVICE_NAME
                } else if (displayName == LampConstants.DEVICE_NAME) {
                    LampConstants.DEVICE_NAME
                } else {
                    displayName
                }
                currentList[existingIndex] = item.copy(name = finalName)
            } else {
                currentList.add(item)
            }
            _scannedDevices.value = currentList

            // Tự động kết nối nếu tìm thấy đèn và chưa có kết nối đang chạy
            if (isLamp && !_isConnected.value && _connectionState.value == BleConnectionState.SCANNING) {
                log("Phát hiện đèn: $displayName ($address) RSSI: ${rssi}dBm. Bắt đầu kết nối...")
                stopScan()
                // Đệm 150ms trên Main Looper trước khi gọi connectGatt để tránh lỗi GATT 133
                mainHandler.postDelayed({
                    connect(device, displayName)
                }, 150L)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Quét đã đang chạy"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Đăng ký quét thất bại"
                SCAN_FAILED_INTERNAL_ERROR -> "Lỗi nội bộ Bluetooth HAL"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Tính năng quét BLE không được hỗ trợ"
                else -> "Mã lỗi: $errorCode"
            }
            log("Lỗi quét BLE: $errorMsg")
            _isScanning.value = false
            _connectionState.value = if (_isConnected.value) BleConnectionState.CONNECTED else BleConnectionState.DISCONNECTED
        }
    }

    /**
     * Bắt đầu quét thiết bị BLE (kết hợp bộ lọc chuẩn và fallback quét mở rộng)
     */
    fun startScan(timeoutMs: Long = 15_000L, targetAddress: String? = null) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth chưa được bật hoặc không khả dụng!")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth LE Scanner không khả dụng trên hệ thống!")
            return
        }
        if (_isScanning.value) return

        targetScanAddress = targetAddress
        _isScanning.value = true
        _connectionState.value = BleConnectionState.SCANNING
        log("Bắt đầu quét tìm đèn LED Matrix 360...")

        val filters = mutableListOf<ScanFilter>()
        if (!targetAddress.isNullOrBlank()) {
            try {
                filters.add(ScanFilter.Builder().setDeviceAddress(targetAddress).build())
            } catch (_: Exception) {}
        }
        // Bộ lọc Service UUID & Tên thiết bị cho Android 12+ (tránh bị lọc khi dùng neverForLocation)
        filters.add(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(LampConstants.NUS_SERVICE_UUID))
                .build()
        )
        filters.add(
            ScanFilter.Builder()
                .setDeviceName(LampConstants.DEVICE_NAME)
                .build()
        )
        filters.add(
            ScanFilter.Builder()
                .setDeviceName("LED-360")
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            log("Quét có bộ lọc gặp lỗi (${e.message}), chuyển sang quét rộng...")
            try {
                scanner.startScan(emptyList(), settings, scanCallback)
            } catch (e2: Exception) {
                log("Không thể bắt đầu quét: ${e2.message}")
                _isScanning.value = false
                _connectionState.value = if (_isConnected.value) BleConnectionState.CONNECTED else BleConnectionState.DISCONNECTED
                return
            }
        }

        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.postDelayed(scanTimeoutRunnable, timeoutMs)
    }

    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        if (!_isScanning.value) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan error: ${e.message}")
        }
        _isScanning.value = false
        if (!_isConnected.value && _connectionState.value == BleConnectionState.SCANNING) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    /**
     * Tự động quét và kết nối lại thiết bị đèn đã lưu lần trước
     */
    fun autoConnectLastDevice() {
        ioScope.launch {
            try {
                val prefs = PreferencesRepository.getInstance(context)
                val autoEnabled = prefs.autoConnectFlow.first()
                val lastAddr = prefs.lastDeviceAddressFlow.first()
                if (autoEnabled && !lastAddr.isNullOrBlank() && !_isConnected.value && !_isScanning.value) {
                    val lastName = prefs.lastDeviceNameFlow.first() ?: "LED-MATRIX-360"
                    log("Tự động kết nối lại đèn đã lưu: $lastName ($lastAddr)")
                    withContext(Dispatchers.Main) {
                        startScan(timeoutMs = 15_000L, targetAddress = lastAddr)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "autoConnectLastDevice error: ${e.message}")
            }
        }
    }

    /**
     * Tạo kết nối GATT tới thiết bị cụ thể
     */
    fun connect(device: BluetoothDevice, preferredName: String? = null) {
        val resolvedName = when {
            !preferredName.isNullOrBlank() && !preferredName.equals(LampConstants.SHORT_NAME, ignoreCase = true) -> preferredName
            !device.name.isNullOrBlank() && !device.name.equals(LampConstants.SHORT_NAME, ignoreCase = true) -> device.name
            else -> LampConstants.DEVICE_NAME
        }
        log("Đang tạo GATT Connection tới: $resolvedName (${device.address})...")
        _connectionState.value = BleConnectionState.CONNECTING
        _connectedDevice.value = device
        _connectedDeviceAddress.value = device.address
        _connectedDeviceName.value = resolvedName

        // Đóng GATT cũ một cách an toàn
        closeGatt()

        connectionAttempts++
        mainHandler.postDelayed({
            try {
                bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }
            } catch (e: Exception) {
                log("Lỗi tạo connectGatt: ${e.message}")
                handleDisconnect()
            }
        }, 100L)
    }

    /**
     * Kết nối qua địa chỉ MAC
     */
    fun connectByAddress(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            log("Bluetooth chưa được bật!")
            return
        }
        try {
            val device = adapter.getRemoteDevice(address)
            connect(device)
        } catch (e: Exception) {
            log("Địa chỉ MAC không hợp lệ: $address (${e.message})")
        }
    }

    /**
     * Ngắt kết nối thủ công
     */
    fun disconnect() {
        log("Đang ngắt kết nối...")
        connectionAttempts = 3 // Ngừng tự động thử lại khi người dùng chủ động ngắt
        try {
            bluetoothGatt?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect error: ${e.message}")
        }
        handleDisconnect()
    }

    private fun closeGatt() {
        bluetoothGatt?.let { oldGatt ->
            try {
                oldGatt.disconnect()
            } catch (_: Exception) {}
            try {
                oldGatt.close()
            } catch (_: Exception) {}
        }
        bluetoothGatt = null
    }

    private fun handleDisconnect() {
        _isConnected.value = false
        _connectionState.value = BleConnectionState.DISCONNECTED
        rxCharacteristic = null
        txCharacteristic = null
        closeGatt()
        incomingBuffer.clear()
        writeAckDeferred?.complete(-1)
        writeAckDeferred = null
    }

    fun readRssi() {
        try {
            bluetoothGatt?.readRemoteRssi()
        } catch (_: Exception) {}
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            log("GATT State: status=$status, newState=$newState")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("GATT lỗi: status=$status (133=GATT_ERROR, 8=TIMEOUT, 19/22=PEER_TERMINATED)")
                handleDisconnect()

                // Tự động thử lại tối đa 2 lần nếu lỗi kết nối
                if (connectionAttempts < 3 && _isAutoConnectEnabled.value) {
                    log("Tự động thử kết nối lại (Lần $connectionAttempts)...")
                    mainHandler.postDelayed({
                        val dev = _connectedDevice.value
                        if (dev != null && !_isConnected.value) {
                            connect(dev)
                        }
                    }, 1200L)
                }
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionAttempts = 0
                log("Đã kết nối tầng vật lý! Đang yêu cầu MTU 512...")
                _connectionState.value = BleConnectionState.NEGOTIATING_MTU

                // Trì hoãn 150ms trên Main Looper trước khi yêu cầu MTU để GATT ổn định
                mainHandler.postDelayed({
                    val mtuSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        try {
                            gatt?.requestMtu(512) ?: false
                        } catch (e: Exception) {
                            false
                        }
                    } else false

                    if (!mtuSuccess) {
                        log("Không thể yêu cầu MTU 512, tiến hành khám phá dịch vụ ngay...")
                        _connectionState.value = BleConnectionState.DISCOVERING_SERVICES
                        mainHandler.postDelayed({ gatt?.discoverServices() }, 200L)
                    }
                }, 150L)

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Đã ngắt kết nối GATT.")
                handleDisconnect()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            log("BLE MTU cập nhật: $mtu (status=$status). Đang khám phá dịch vụ...")
            _connectionState.value = BleConnectionState.DISCOVERING_SERVICES
            mainHandler.postDelayed({
                val res = gatt?.discoverServices() ?: false
                if (!res) {
                    log("Cảnh báo: gatt.discoverServices() trả về false!")
                }
            }, 150L)
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

                    val txChar = txCharacteristic
                    val rxChar = rxCharacteristic
                    if (txChar != null && rxChar != null) {
                        _connectionState.value = BleConnectionState.ENABLING_NOTIFICATIONS
                        log("Đã tìm thấy Nordic UART Service! Đang bật Notify (CCCD)...")

                        gatt.setCharacteristicNotification(txChar, true)
                        val cccd = txChar.getDescriptor(UUID.fromString(LampConstants.CCCD_UUID))
                        if (cccd != null) {
                            mainHandler.postDelayed({
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                } else {
                                    @Suppress("DEPRECATION")
                                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    @Suppress("DEPRECATION")
                                    gatt.writeDescriptor(cccd)
                                }
                            }, 100L)
                        } else {
                            // Nếu không tìm thấy CCCD, hoàn tất kết nối luôn
                            finalizeConnection(gatt)
                        }
                    } else {
                        log("Thiếu RX hoặc TX Characteristic trong Nordic UART Service!")
                        handleDisconnect()
                    }
                } else {
                    log("Không tìm thấy Nordic UART Service trên đèn!")
                    handleDisconnect()
                }
            } else {
                log("Khám phá dịch vụ thất bại: status=$status")
                handleDisconnect()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            log("onDescriptorWrite status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                finalizeConnection(gatt)
            } else {
                log("Cảnh báo: Ghi CCCD kết thúc với status=$status, vẫn kích hoạt kết nối.")
                if (gatt != null) finalizeConnection(gatt)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _signalRssi.value = rssi
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

    private fun finalizeConnection(gatt: BluetoothGatt) {
        _isConnected.value = true
        _connectionState.value = BleConnectionState.CONNECTED
        val rawDevName = gatt.device.name
        val name = when {
            _connectedDeviceName.value.isNotBlank() && !_connectedDeviceName.value.equals(LampConstants.SHORT_NAME, ignoreCase = true) -> _connectedDeviceName.value
            !rawDevName.isNullOrBlank() && !rawDevName.equals(LampConstants.SHORT_NAME, ignoreCase = true) -> rawDevName
            else -> LampConstants.DEVICE_NAME
        }
        val addr = gatt.device.address
        _connectedDeviceName.value = name
        _connectedDeviceAddress.value = addr
        log("Sẵn sàng giao tiếp với đèn $name ($addr) qua Nordic UART Service!")

        // Lưu thông tin đèn vào Preferences để tự động kết nối lại lần sau
        ioScope.launch {
            try {
                PreferencesRepository.getInstance(context).saveLastDevice(addr, name)
            } catch (e: Exception) {
                Log.w(TAG, "Lỗi lưu thiết bị: ${e.message}")
            }
        }

        // Tự động đồng bộ giờ hệ thống RTC sau 250ms (đảm bảo bus GATT đã rảnh rỗi hoàn toàn)
        mainHandler.postDelayed({
            val cal = java.util.Calendar.getInstance()
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val m = cal.get(java.util.Calendar.MINUTE)
            val s = cal.get(java.util.Calendar.SECOND)
            sendTimeSync(h, m, s)
            log("-> Tự động đồng bộ giờ hệ thống RTC: $h:$m:$s")
            readRssi()
        }, 250L)
    }

    /**
     * Xử lý gói tin nhận được từ ESP32 (hỗ trợ ghép mảnh JSON khi MTU bị chia nhỏ)
     */
    private fun parseIncomingBytes(bytes: ByteArray) {
        val chunk = String(bytes, Charsets.UTF_8)
        incomingBuffer.append(chunk)

        while (true) {
            val content = incomingBuffer.toString()
            val start = content.indexOf('{')
            if (start < 0) {
                if (content.contains('\n')) {
                    val line = content.substringBefore('\n').trim()
                    if (line.isNotEmpty()) log("UART: $line")
                    incomingBuffer.delete(0, content.indexOf('\n') + 1)
                    continue
                }
                break
            }

            val end = content.indexOf('}', start)
            if (end < 0) {
                if (incomingBuffer.length > 2048) {
                    incomingBuffer.clear()
                }
                break
            }

            val jsonStr = content.substring(start, end + 1)
            incomingBuffer.delete(0, end + 1)

            try {
                val json = JSONObject(jsonStr)
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
                    continue
                }

                val cur = _telemetryState.value
                _telemetryState.value = cur.copy(
                    mode = json.optInt("m", cur.mode),
                    modeName = json.optString("mn", cur.modeName),
                    brightness = json.optInt("b", cur.brightness),
                    speed = json.optInt("sp", cur.speed),
                    powerState = json.optInt("pwr", cur.powerState),
                    vbus = json.optInt("vbus", cur.vbus),
                    statMv = json.optInt("stat", cur.statMv),
                    pitch = json.optDouble("pitch", cur.pitch.toDouble()).toFloat(),
                    roll = json.optDouble("roll", cur.roll.toDouble()).toFloat(),
                    heap = json.optLong("heap", cur.heap)
                )
            } catch (e: Exception) {
                log("Data: $jsonStr")
            }
        }
    }

    /**
     * Gửi lệnh văn bản xuống ESP32 qua Characteristic RX
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
            log("Chưa kết nối! Bỏ qua gửi dữ liệu (${data.size} bytes)")
            return
        }

        try {
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
        } catch (e: Exception) {
            log("Lỗi gửi dữ liệu: ${e.message}")
        }
    }

    /**
     * Gửi lệnh chạy chữ 360° lên đèn (hỗ trợ màu sắc, tốc độ và chuẩn hóa tiếng Việt)
     */
    fun sendMarqueeText(
        text: String,
        r: Int? = null,
        g: Int? = null,
        b: Int? = null,
        speedDelay: Int = 120
    ) {
        val cleanText = sanitizeVietnameseForLed(text).trim()
        if (cleanText.isNotEmpty()) {
            val spd = speedDelay.coerceIn(40, 500)
            if (r != null && g != null && b != null) {
                sendCommand("text $r $g $b $spd $cleanText")
            } else {
                sendCommand("text $spd $cleanText")
            }
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
     * Kích hoạt Ánh sáng vàng thân thiện mắt (Warm Light 2200K)
     */
    fun triggerWarmLight() {
        sendCommand("warm")
    }

    /**
     * Tương thích ngược với các phiên bản trước
     */
    fun triggerPomodoro() {
        triggerWarmLight()
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
     * Cập nhật tốc độ hiệu ứng tức thì (10..100) và gửi lệnh BLE xuống ESP32
     */
    fun setSpeed(speed: Int) {
        val clamped = speed.coerceIn(10, 100)
        _telemetryState.value = _telemetryState.value.copy(speed = clamped)
        sendCommand("speed $clamped")
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
     * Frame 0x04: Đồng bộ thời tiết [0x04, Temp_Signed, Weather_ID, SwitchMode]
     */
    fun sendWeather(tempSigned: Int, weatherId: Int, switchMode: Boolean = false) {
        val modeId = when (weatherId) {
            1 -> 14
            2 -> 16
            3 -> 15
            4 -> 17
            else -> 14
        }
        if (switchMode) {
            _telemetryState.value = _telemetryState.value.copy(
                mode = modeId,
                modeName = LampConstants.MODES.find { it.id == modeId }?.name ?: "Thời tiết 360°"
            )
        }
        sendBytes(byteArrayOf(0x04.toByte(), tempSigned.toByte(), weatherId.toByte(), if (switchMode) 1.toByte() else 0.toByte()))
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
     */
    suspend fun sendSlotRGB565(slot: Int, pixels: IntArray) {
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

    suspend fun sendSlotRGB565(slot: Int, pixels: List<Int>) {
        sendSlotRGB565(slot, pixels.toIntArray())
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
        setEffect(11)
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

        val ack = CompletableDeferred<Int>()
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

        val status = withTimeoutOrNull(timeoutMs) { ack.await() }
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

            val resultDeferred = CompletableDeferred<Boolean>()
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
                if (!_isConnected.value) {
                    _otaStatus.value = "Bị ngắt kết nối BLE trong khi đang truyền firmware!"
                    _otaProgress.value = null
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

            val verified = withTimeoutOrNull(5000L) { resultDeferred.await() } ?: false
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
        private const val TAG = "BleManager"

        @Volatile
        private var instance: BleManager? = null

        fun getInstance(context: Context): BleManager {
            return instance ?: synchronized(this) {
                instance ?: BleManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

/**
 * Chuẩn hóa chuỗi ký tự tiếng Việt có dấu, ký hiệu cảm xúc thành ASCII hoa và biểu tượng cho ma trận LED
 */
fun sanitizeVietnameseForLed(input: String): String {
    if (input.isBlank()) return ""
    var str = input
    // Thay thế ký hiệu trái tim và emoji tình yêu thành dấu sao '*' (trong font LED hiển thị hình trái tim)
    str = str.replace("<3", "*")
        .replace("❤️", "*")
        .replace("♥", "*")
        .replace("💕", "*")
        .replace("💖", "*")
        .replace("💗", "*")
        .replace("💘", "*")
        .replace("💓", "*")
        .replace("✨", "*")

    // Thay thế chữ đ/Đ trước khi chuẩn hóa NFD
    str = str.replace("đ", "d").replace("Đ", "D")

    // Chuẩn hóa NFD để tách các dấu thanh (sắc, huyền, hỏi, ngã, nặng)
    val normalized = java.text.Normalizer.normalize(str, java.text.Normalizer.Form.NFD)
    val regex = "\\p{InCombiningDiacriticalMarks}+".toRegex()
    val stripped = regex.replace(normalized, "")

    // Chuyển toàn bộ thành in hoa và giữ các ký tự ASCII an toàn
    return stripped.uppercase()
}
