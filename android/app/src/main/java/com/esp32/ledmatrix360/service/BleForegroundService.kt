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

        fun startService(context: Context) {
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
