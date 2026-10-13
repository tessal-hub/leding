package com.esp32.ledmatrix360

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.esp32.ledmatrix360.ble.BleManager
import com.esp32.ledmatrix360.data.PreferencesRepository
import com.esp32.ledmatrix360.data.WeatherRepository
import com.esp32.ledmatrix360.model.WeatherData
import com.esp32.ledmatrix360.service.BleForegroundService
import com.esp32.ledmatrix360.ui.*
import com.esp32.ledmatrix360.worker.WeatherTimeWorker

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager
    private lateinit var prefsRepository: PreferencesRepository
    private val weatherRepository = WeatherRepository()

    // Bộ quản lý chọn file firmware .bin để OTA
    private val firmwarePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFirmwareSelected(it) }
    }

    private fun handleFirmwareSelected(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Bắt đầu nạp firmware (${bytes.size} bytes)...", Toast.LENGTH_SHORT).show()
                    }
                    bleManager.performOtaUpdate(bytes)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Không thể đọc file firmware!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Lỗi nạp file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Bộ quản lý xin quyền Runtime
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            bleManager.startScan()
            BleForegroundService.startService(this)
        } else {
            Toast.makeText(this, "Cần cấp quyền Bluetooth và Thông báo để ứng dụng hoạt động đầy đủ!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        bleManager = BleManager.getInstance(applicationContext)
        prefsRepository = PreferencesRepository.getInstance(applicationContext)

        // 1. Tự động khởi động BLE Foreground Service & Lên lịch đồng bộ ngầm
        try {
            BleForegroundService.startService(this)
            WeatherTimeWorker.schedule(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Yêu cầu quyền hệ thống nếu cần
        checkAndRequestPermissions()

        setContent {
            LampTheme {
                MainAppScreen(
                    bleManager = bleManager,
                    prefsRepository = prefsRepository,
                    weatherRepository = weatherRepository,
                    onRequestPermissions = { checkAndRequestPermissions() },
                    onOpenNotificationSettings = { openNotificationListenerSettings() },
                    onRequestBatteryOptimization = { requestIgnoreBatteryOptimizations() },
                    onSyncNow = { triggerManualSync() },
                    onPickFirmwareFile = { firmwarePickerLauncher.launch("*/*") }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val neededPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            neededPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            neededPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(neededPermissions.toTypedArray())
    }

    private fun openNotificationListenerSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể mở cài đặt thông báo!", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun triggerManualSync() {
        val request = OneTimeWorkRequestBuilder<WeatherTimeWorker>().build()
        WorkManager.getInstance(this).enqueue(request)
        Toast.makeText(this, "Đang đồng bộ Giờ & Thời tiết xuống đèn...", Toast.LENGTH_SHORT).show()
    }
}

enum class ScreenTab(val title: String, val icon: ImageVector) {
    MODES("Hiệu ứng", Icons.Default.GridView),
    COLOR("Màu sắc", Icons.Default.Palette),
    MARQUEE("Chạy chữ", Icons.Default.Subtitles),
    PIXEL("Pixel", Icons.Default.GridOn),
    WEATHER("Thời tiết", Icons.Default.WbSunny),
    NOTIF("Thông báo", Icons.Default.Notifications),
    SENSORS("Cảm biến", Icons.Default.Explore),
    OTA("OTA", Icons.Default.SystemUpdate)
}

@Composable
fun MainAppScreen(
    bleManager: BleManager,
    prefsRepository: PreferencesRepository,
    weatherRepository: WeatherRepository,
    onRequestPermissions: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onSyncNow: () -> Unit,
    onPickFirmwareFile: () -> Unit
) {
    val isConnected by bleManager.isConnected.collectAsState()
    val isScanning by bleManager.isScanning.collectAsState()
    val telemetry by bleManager.telemetryState.collectAsState()
    val logs by bleManager.logs.collectAsState()

    val notifConfigs by prefsRepository.notificationConfigsFlow.collectAsState(initial = emptyList())
    val selectedCity by prefsRepository.selectedCityFlow.collectAsState(initial = "Hà Nội")

    var weatherData by remember { mutableStateOf(WeatherData(cityName = selectedCity)) }
    var selectedTab by remember { mutableStateOf(ScreenTab.MODES) }
    val scope = rememberCoroutineScope()

    // Tự động kéo thời tiết khi đổi thành phố
    LaunchedEffect(selectedCity) {
        scope.launch(Dispatchers.IO) {
            val data = weatherRepository.fetchWeather(selectedCity)
            withContext(Dispatchers.Main) {
                weatherData = data
            }
        }
    }

    Scaffold(
        containerColor = BgDark,
        bottomBar = {
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = CardBg,
                edgePadding = 8.dp
            ) {
                ScreenTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(text = tab.title, fontSize = 11.sp) },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.title, modifier = Modifier.size(20.dp)) },
                        selectedContentColor = CyanNeon,
                        unselectedContentColor = TextMuted
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BgDark)
        ) {
            // 1. Thanh Header chính
            HeaderBar(
                isConnected = isConnected,
                isScanning = isScanning,
                onConnectClick = onRequestPermissions
            )

            // 2. Thanh trạng thái Telemetry
            TelemetryBar(state = telemetry)

            // 3. Card điều khiển nhanh nguồn & độ sáng
            BrightnessPowerCard(
                brightness = telemetry.brightness,
                onBrightnessChange = { newBri ->
                    bleManager.sendCommand("bright $newBri")
                },
                onPowerToggle = {
                    if (telemetry.mode == 9) {
                        bleManager.setEffect(1) // Bật lại Mode 1 (Lava)
                    } else {
                        bleManager.setEffect(9) // Tắt đèn (Standby)
                    }
                }
            )

            // 4. Nội dung từng Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                ScreenTab.MODES -> {
                    ModesGrid(
                        currentMode = telemetry.mode,
                        onModeSelect = { modeId ->
                            bleManager.setEffect(modeId, speed = 50, brightness = telemetry.brightness)
                        }
                    )
                }
                ScreenTab.COLOR -> {
                    ColorPaletteSection(
                        onColorSelected = { r, g, b ->
                            bleManager.setStaticColor(r, g, b, telemetry.brightness)
                        }
                    )
                }
                ScreenTab.MARQUEE -> {
                    MarqueeSection(
                        onSendText = { text ->
                            bleManager.sendMarqueeText(text)
                            scope.launch { prefsRepository.saveLastMarquee(text) }
                        }
                    )
                }
                ScreenTab.PIXEL -> {
                    PixelArtCanvasSection(
                        onSendCanvas = { canvas ->
                            bleManager.sendPixelCanvas(canvas, telemetry.brightness)
                        }
                    )
                }
                ScreenTab.WEATHER -> {
                    WeatherSection(
                        weatherData = weatherData,
                        onCitySelected = { city ->
                            scope.launch { prefsRepository.saveSelectedCity(city) }
                        },
                        onSyncWeatherNow = {
                            onSyncNow()
                            scope.launch(Dispatchers.IO) {
                                val data = weatherRepository.fetchWeather(selectedCity)
                                withContext(Dispatchers.Main) {
                                    weatherData = data
                                }
                            }
                        },
                        onPreviewModeSelect = { modeId ->
                            bleManager.setEffect(modeId, speed = 50)
                        }
                    )
                }
                ScreenTab.NOTIF -> {
                    NotificationMappingSection(
                        configs = notifConfigs,
                        onConfigToggle = { pkg, enabled ->
                            val updated = notifConfigs.map { if (it.packageName == pkg) it.copy(isEnabled = enabled) else it }
                            scope.launch { prefsRepository.saveNotificationConfigs(updated) }
                        },
                        onTestFlash = { r, g, b ->
                            bleManager.sendNotificationInterrupt(r, g, b, count = 3, delay10ms = 15)
                        },
                        onOpenPermissionSettings = onOpenNotificationSettings
                    )
                }
                ScreenTab.SENSORS -> {
                    SensorSection(
                        state = telemetry,
                        logs = logs,
                        onTriggerPomodoro = { bleManager.triggerPomodoro() },
                        onSyncNow = onSyncNow,
                        onRequestBatteryOptimization = onRequestBatteryOptimization
                    )
                }
                ScreenTab.OTA -> {
                    OtaSection(
                        bleManager = bleManager,
                        onPickFirmwareFile = onPickFirmwareFile
                    )
                }
            }
        }
        }
    }
}
