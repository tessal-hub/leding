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
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.esp32.ledmatrix360.ble.BleManager
import com.esp32.ledmatrix360.data.PreferencesRepository
import com.esp32.ledmatrix360.data.WeatherRepository
import com.esp32.ledmatrix360.model.LampConstants
import com.esp32.ledmatrix360.model.SmartRoutineConfig
import com.esp32.ledmatrix360.model.WeatherData
import com.esp32.ledmatrix360.service.BleForegroundService
import com.esp32.ledmatrix360.ui.*
import com.esp32.ledmatrix360.worker.WeatherTimeWorker
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    onRequestPermissions = {
                        if (!hasRequiredPermissions()) {
                            checkAndRequestPermissions()
                        } else {
                            if (bleManager.isConnected.value) {
                                bleManager.disconnect()
                                Toast.makeText(this@MainActivity, "Đã ngắt kết nối với đèn LED Matrix 360", Toast.LENGTH_SHORT).show()
                            } else if (bleManager.isScanning.value) {
                                bleManager.stopScan()
                                Toast.makeText(this@MainActivity, "Đã dừng quét Bluetooth", Toast.LENGTH_SHORT).show()
                            } else {
                                bleManager.startScan()
                                Toast.makeText(this@MainActivity, "Đang quét tìm đèn LED Matrix 360...", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onOpenNotificationSettings = { openNotificationListenerSettings() },
                    onRequestBatteryOptimization = { requestIgnoreBatteryOptimizations() },
                    onSyncNow = { triggerManualSync() },
                    onPickFirmwareFile = { firmwarePickerLauncher.launch("*/*") }
                )
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
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

/**
 * 4 Canonical Material Design 3 Destinations
 */
enum class MainDestination(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    STUDIO("Hiệu ứng", Icons.Filled.Lightbulb, Icons.Outlined.Lightbulb),
    CREATIVE("Sáng tạo", Icons.Filled.Palette, Icons.Outlined.Palette),
    ROUTINES("Kịch bản", Icons.Filled.Alarm, Icons.Outlined.Alarm),
    DEVICE("Thiết bị", Icons.Filled.Tune, Icons.Outlined.Tune)
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val smartRoutineConfig by prefsRepository.smartRoutinesFlow.collectAsState(initial = SmartRoutineConfig())
    val lastMarquee by prefsRepository.lastMarqueeFlow.collectAsState(initial = "LED MATRIX 360")
    val effectSpeed by prefsRepository.effectSpeedFlow.collectAsState(initial = 50)

    var weatherData by remember { mutableStateOf(WeatherData(cityName = selectedCity)) }
    var currentDestination by remember { mutableStateOf(MainDestination.STUDIO) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    fun showSnack(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }
    fun showSnackConnect(msg: String) {
        scope.launch {
            val r = snackbarHostState.showSnackbar(msg, actionLabel = "Kết nối")
            if (r == SnackbarResult.ActionPerformed) onRequestPermissions()
        }
    }

    // Tự động kéo dữ liệu thời tiết định kỳ (15 phút) và đồng bộ xuống đèn ngay khi kết nối
    LaunchedEffect(selectedCity, isConnected) {
        while (isActive) {
            try {
                val data = withContext(Dispatchers.IO) {
                    weatherRepository.fetchWeather(selectedCity)
                }
                weatherData = data
                if (bleManager.isConnected.value) {
                    bleManager.sendWeather(data.temp, data.weatherId)
                    val cal = Calendar.getInstance()
                    bleManager.sendTimeSync(
                        cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE),
                        cal.get(Calendar.SECOND)
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            delay(15 * 60 * 1000L) // Tự động cập nhật mỗi 15 phút
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Lightbulb,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Text(
                            text = "LED Lamp 360°",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    // M3 Connection Status Chip
                    FilterChip(
                        selected = isConnected,
                        onClick = onRequestPermissions,
                        label = {
                            Text(
                                text = if (isConnected) "Đã kết nối" else (if (isScanning) "Đang tìm..." else "Kết nối"),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        leadingIcon = {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isConnected) GreenJade else MaterialTheme.colorScheme.outline
                                        )
                                )
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp
            ) {
                MainDestination.entries.forEach { destination ->
                    val isSelected = currentDestination == destination
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentDestination = destination },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = destination.title
                            )
                        },
                        label = {
                            Text(
                                text = destination.title,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Crossfade(
                targetState = currentDestination,
                label = "DestinationCrossfade"
            ) { destination ->
                when (destination) {
                    MainDestination.STUDIO -> {
                        StudioScreen(
                            telemetry = telemetry,
                            effectSpeed = effectSpeed,
                            onBrightnessChange = { newBri ->
                                bleManager.setBrightness(newBri)
                            },
                            onSpeedChange = { newSpeed ->
                                scope.launch { prefsRepository.saveEffectSpeed(newSpeed) }
                                bleManager.setEffect(
                                    effectId = telemetry.mode,
                                    speed = newSpeed,
                                    brightness = telemetry.brightness
                                )
                            },
                            onPowerToggle = {
                                if (telemetry.mode == 9) {
                                    bleManager.setEffect(1, speed = effectSpeed, brightness = telemetry.brightness)
                                    showSnack("Đã bật đèn (Lửa cuộn 360°)")
                                } else {
                                    bleManager.setEffect(9)
                                    showSnack("Đã chuyển sang chế độ chờ")
                                }
                            },
                            onModeSelect = { modeId ->
                                bleManager.setEffect(modeId, speed = effectSpeed, brightness = telemetry.brightness)
                                val modeName = LampConstants.MODES.find { it.id == modeId }?.name ?: "Chế độ $modeId"
                                if (!isConnected) {
                                    showSnackConnect("Đã chọn $modeName — chưa kết nối đèn")
                                }
                            }
                        )
                    }

                    MainDestination.CREATIVE -> {
                        CreativeScreen(
                            initialMarqueeText = lastMarquee,
                            onColorSelected = { r, g, b ->
                                bleManager.setStaticColor(r, g, b, telemetry.brightness)
                                if (!isConnected) {
                                    showSnackConnect("Đã chọn màu RGB($r, $g, $b) — chưa kết nối đèn")
                                }
                            },
                            onSendMarqueeText = { text, r, g, b ->
                                if (r != null && g != null && b != null) {
                                    bleManager.sendCommand("text $r $g $b $text")
                                } else {
                                    bleManager.sendMarqueeText(text)
                                }
                                scope.launch { prefsRepository.saveLastMarquee(text) }
                                if (isConnected) showSnack("Đã gửi chữ lên đèn: $text")
                                else showSnackConnect("Đã lưu chữ: $text — chưa kết nối đèn")
                            },
                            onSendSlot = { slot, r, g, b, rows ->
                                bleManager.sendSlotBitmap(slot, r, g, b, rows)
                                if (isConnected) showSnack("Đã gửi Mặt $slot lên đèn")
                                else showSnackConnect("Chưa kết nối đèn — không gửi được Mặt $slot")
                            },
                            onSendAllSlots = { slots ->
                                scope.launch {
                                    bleManager.sendAllFourSlots(slots)
                                    if (isConnected) showSnack("Đã gửi 4 Mặt lên đèn (Mode 11)")
                                    else showSnackConnect("Chưa kết nối đèn — không gửi được 4 Mặt")
                                }
                            },
                            onSendFullCanvas = { fullCanvasState ->
                                scope.launch {
                                    bleManager.sendFullCanvas32x8(fullCanvasState)
                                    if (isConnected) showSnack("Đã gửi Canvas 32x8 lên đèn")
                                    else showSnackConnect("Chưa kết nối đèn — không gửi được Canvas")
                                }
                            },
                            onActivateMode11 = {
                                bleManager.setEffect(11)
                                showSnack("Đã kích hoạt Chế độ 11: 4 Mặt Tùy chỉnh")
                            }
                        )
                    }

                    MainDestination.ROUTINES -> {
                        RoutinesScreen(
                            routineConfig = smartRoutineConfig,
                            weatherData = weatherData,
                            onUpdateRoutine = { updated ->
                                scope.launch { prefsRepository.saveSmartRoutines(updated) }
                            },
                            onCitySelected = { city ->
                                scope.launch { prefsRepository.saveSelectedCity(city) }
                            },
                            onSyncWeatherNow = {
                                onSyncNow()
                                scope.launch {
                                    val data = withContext(Dispatchers.IO) {
                                        weatherRepository.fetchWeather(selectedCity)
                                    }
                                    weatherData = data
                                    if (bleManager.isConnected.value) {
                                        bleManager.sendWeather(data.temp, data.weatherId)
                                    }
                                    showSnack("Đã cập nhật: ${data.cityName} ${data.temp}°C (${data.weatherName})")
                                }
                            },
                            onPreviewModeSelect = { modeId ->
                                bleManager.setEffect(modeId, speed = effectSpeed)
                            },
                            onTriggerSunriseDemo = {
                                scope.launch {
                                    showSnack("Bắt đầu chạy thử Bình minh (Demo 10s)...")
                                    bleManager.setStaticColor(140, 25, 0, 15)
                                    delay(2000)
                                    bleManager.setStaticColor(210, 60, 5, 80)
                                    delay(2000)
                                    bleManager.setStaticColor(255, 120, 15, 160)
                                    delay(2000)
                                    bleManager.setStaticColor(255, 190, 40, 255)
                                    delay(2000)
                                    bleManager.setEffect(12) // Chuyển sang Mode 12 (Đồng hồ 360°)
                                    showSnack("Hoàn tất kịch bản thức dậy!")
                                }
                            },
                            onActivateBedtimeRoutine = {
                                scope.launch {
                                    bleManager.setEffect(6, speed = effectSpeed, brightness = 10)
                                    showSnack("Đã bật Đèn ngủ (Mode 6). Đèn sẽ tự tắt sau ${smartRoutineConfig.bedtimeDurationMin} phút.")
                                    delay(smartRoutineConfig.bedtimeDurationMin * 60_000L)
                                    if (bleManager.isConnected.value && bleManager.telemetryState.value.mode == 6) {
                                        bleManager.setEffect(9) // Mode 9: Standby / Off
                                    }
                                }
                            }
                        )
                    }

                    MainDestination.DEVICE -> {
                        DeviceScreen(
                            telemetry = telemetry,
                            logs = logs,
                            bleManager = bleManager,
                            notifConfigs = notifConfigs,
                            onSaveAppConfig = { updated ->
                                val updatedList = notifConfigs.map { if (it.packageName == updated.packageName) updated else it }
                                scope.launch { prefsRepository.saveNotificationConfigs(updatedList) }
                            },
                            onAddAppConfig = { newApp ->
                                val updatedList = notifConfigs.filter { it.packageName != newApp.packageName } + newApp
                                scope.launch { prefsRepository.saveNotificationConfigs(updatedList) }
                            },
                            onDeleteAppConfig = { pkg ->
                                val updatedList = notifConfigs.filter { it.packageName != pkg }
                                scope.launch { prefsRepository.saveNotificationConfigs(updatedList) }
                            },
                            onConfigToggle = { pkg, enabled ->
                                val updatedList = notifConfigs.map { if (it.packageName == pkg) it.copy(isEnabled = enabled) else it }
                                scope.launch { prefsRepository.saveNotificationConfigs(updatedList) }
                            },
                            onTestFlash = { r, g, b, count ->
                                bleManager.sendNotificationInterrupt(r, g, b, count = count, delay10ms = 15)
                            },
                            onOpenNotificationSettings = onOpenNotificationSettings,
                            onTriggerPomodoro = { bleManager.triggerPomodoro() },
                            onSyncNow = onSyncNow,
                            onRequestBatteryOptimization = onRequestBatteryOptimization,
                            onPickFirmwareFile = onPickFirmwareFile,
                            onShowMessage = { showSnack(it) },
                            onShowMessageConnect = { showSnackConnect(it) }
                        )
                    }
                }
            }
        }
    }
}
