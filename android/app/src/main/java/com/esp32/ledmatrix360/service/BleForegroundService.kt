package com.esp32.ledmatrix360.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.esp32.ledmatrix360.MainActivity
import com.esp32.ledmatrix360.ble.BleManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * Foreground Service duy trì kết nối BLE liên tục (Android 14+ connectedDevice)
 * Tự động quét và kết nối lại khi mất sóng.
 */
class BleForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bleManager: BleManager

    companion object {
        const val CHANNEL_ID = "ble_service_channel"
        const val NOTIFICATION_ID = 1001

        fun hasBlePermissions(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun startService(context: Context) {
            if (!hasBlePermissions(context)) return
            val intent = Intent(context, BleForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager.getInstance(this)
        createNotificationChannel()
        startInForeground("Khởi động dịch vụ kết nối Đèn LED 360...")

        // Quan sát trạng thái kết nối
        serviceScope.launch {
            bleManager.isConnected.collectLatest { connected ->
                if (connected) {
                    updateNotification("CubeLamp: Đã kết nối với đèn LED 360")
                } else {
                    updateNotification("CubeLamp: Đang tìm kiếm đèn...")
                    // Tự động quét lại sau khi ngắt kết nối
                    delay(3000)
                    if (!bleManager.isConnected.value && !bleManager.isScanning.value) {
                        bleManager.startScan()
                    }
                }
            }
        }

        // Vòng lặp kiểm tra kịch bản Báo thức bình minh & Đèn ngủ tự động mỗi 30 giây
        serviceScope.launch {
            val prefs = com.esp32.ledmatrix360.data.PreferencesRepository.getInstance(applicationContext)
            var lastTriggeredKey = ""
            while (isActive) {
                if (bleManager.isConnected.value) {
                    val cfg = prefs.smartRoutinesFlow.first()
                    val cal = java.util.Calendar.getInstance()
                    val curH = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    val curM = cal.get(java.util.Calendar.MINUTE)
                    val dayKey = "${cal.get(java.util.Calendar.DAY_OF_YEAR)}_$curH:$curM"

                    if (cfg.sunriseEnabled && curH == cfg.sunriseHour && curM == cfg.sunriseMinute && lastTriggeredKey != "sun_$dayKey") {
                        lastTriggeredKey = "sun_$dayKey"
                        launch {
                            val steps = 4
                            val stepDelay = (cfg.sunriseDurationMin * 60_000L) / steps
                            bleManager.setStaticColor(140, 25, 0, 15)
                            delay(stepDelay)
                            bleManager.setStaticColor(210, 60, 5, 80)
                            delay(stepDelay)
                            bleManager.setStaticColor(255, 120, 15, 160)
                            delay(stepDelay)
                            bleManager.setStaticColor(255, 190, 40, 255)
                            delay(stepDelay)
                            bleManager.setEffect(12)
                        }
                    }

                    if (cfg.bedtimeEnabled && curH == cfg.bedtimeHour && curM == cfg.bedtimeMinute && lastTriggeredKey != "bed_$dayKey") {
                        lastTriggeredKey = "bed_$dayKey"
                        launch {
                            bleManager.setEffect(6)
                            bleManager.setBrightness(10)
                            delay(cfg.bedtimeDurationMin * 60_000L)
                            if (bleManager.telemetryState.value.mode == 6) {
                                bleManager.setEffect(9)
                            }
                        }
                    }
                }
                delay(30_000L)
            }
        }

        // Tự động kết nối nếu chưa kết nối
        if (!bleManager.isConnected.value) {
            bleManager.startScan()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LED Lamp BLE Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Duy trì kết nối nền và đồng bộ thông báo với đèn LED 360"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startInForeground(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        val notification = buildNotification(content)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LED 360 Matrix Lamp")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
