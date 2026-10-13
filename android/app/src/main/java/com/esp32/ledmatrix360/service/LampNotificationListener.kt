package com.esp32.ledmatrix360.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.esp32.ledmatrix360.ble.BleManager
import com.esp32.ledmatrix360.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Lắng nghe thông báo hệ thống và bắn ngắt nhấp nháy đèn LED 360 qua Frame 0x03
 */
class LampNotificationListener : NotificationListenerService() {

    private val lastNotiTimes = ConcurrentHashMap<String, Long>()
    private val debounceMs = 3000L // Chống spam lặp thông báo trong 3 giây
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "LampNotificationListener"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // 1. Bỏ qua thông báo tĩnh / đang chạy (nghe nhạc, tải file, sạc pin)
        if (sbn.isOngoing) return

        val pkg = sbn.packageName ?: return
        val now = System.currentTimeMillis()
        val lastTime = lastNotiTimes[pkg] ?: 0L

        // 2. Debounce chống spam
        if (now - lastTime < debounceMs) {
            return
        }
        lastNotiTimes[pkg] = now

        Log.d(TAG, "Bắt được thông báo từ ứng dụng: $pkg")

        serviceScope.launch {
            try {
                val prefs = PreferencesRepository.getInstance(applicationContext)
                val configs = prefs.notificationConfigsFlow.first()
                val matchedConfig = configs.firstOrNull { it.packageName == pkg }

                if (matchedConfig != null && !matchedConfig.isEnabled) {
                    Log.d(TAG, "Bỏ qua thông báo từ $pkg vì người dùng đã tắt")
                    return@launch
                }

                // 3. Phân loại màu đèn theo cấu hình người dùng hoặc mặc định
                val (r, g, b, count) = when {
                    matchedConfig != null -> NotiFlashPattern(matchedConfig.r, matchedConfig.g, matchedConfig.b, 3)
                    pkg.contains("dialer") || pkg.contains("telecom") || pkg.contains("incallui") -> NotiFlashPattern(255, 20, 20, 5)
                    pkg == "com.zing.zalo" -> NotiFlashPattern(0, 140, 255, 3)
                    pkg == "com.facebook.orca" -> NotiFlashPattern(180, 40, 240, 3)
                    pkg.contains("telegram") -> NotiFlashPattern(0, 210, 240, 3)
                    pkg.contains("whatsapp") -> NotiFlashPattern(20, 240, 70, 3)
                    pkg.contains("gm") || pkg.contains("email") -> NotiFlashPattern(240, 70, 40, 2)
                    else -> NotiFlashPattern(255, 170, 20, 2)
                }

                // 4. Bắn frame 0x03 tới ESP32
                val ble = BleManager.getInstance(applicationContext)
                if (ble.isConnected.value) {
                    ble.sendNotificationInterrupt(r, g, b, count, 15) // 15 = 150ms
                    Log.d(TAG, "Đã gửi ngắt Frame 0x03: $pkg -> RGB($r, $g, $b) x $count lần tới ESP32")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi xử lý màu thông báo: ${e.message}")
            }
        }
    }

    private data class NotiFlashPattern(val r: Int, val g: Int, val b: Int, val count: Int)
}
