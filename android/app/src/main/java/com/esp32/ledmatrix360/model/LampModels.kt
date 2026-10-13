package com.esp32.ledmatrix360.model

import androidx.compose.ui.graphics.Color

/**
 * Dữ liệu trạng thái phản hồi thời gian thực từ ESP32 qua BLE TX (Notify)
 */
data class TelemetryState(
    val mode: Int = 1,
    val modeName: String = "Lava Noise",
    val brightness: Int = 68,
    val powerState: Int = 0, // 0: Dùng Pin, 1: Đang sạc, 2: Sạc đầy 100%
    val vbus: Int = 0,
    val statMv: Int = 0,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val heap: Long = 0L
)

/**
 * Định nghĩa Chế độ đèn (10 Chế độ tinh chọn)
 */
data class LampMode(
    val id: Int,
    val name: String,
    val icon: String,
    val description: String
)

/**
 * Màu Preset nhanh
 */
data class ColorPreset(
    val name: String,
    val color: Color,
    val r: Int,
    val g: Int,
    val b: Int
)

/**
 * Cấu hình màu thông báo cho từng ứng dụng
 */
data class AppNotificationConfig(
    val packageName: String,
    val appName: String,
    val iconEmoji: String,
    val r: Int,
    val g: Int,
    val b: Int,
    val isEnabled: Boolean = true
)

/**
 * Dữ liệu Thời tiết thu thập từ API Open-Meteo
 */
data class WeatherData(
    val temp: Int = 28,
    val weatherCode: Int = 0,
    val weatherName: String = "Trời Nắng",
    val weatherIcon: String = "☀️",
    val weatherId: Int = 1, // 1: Nắng, 2: Mây, 3: Mưa, 4: Sấm chớp
    val recommendedModeId: Int = 1, // Mode tương ứng hiển thị hoạt hình
    val cityName: String = "Hà Nội"
)

/**
 * Trạng thái ma trận Pixel Art 32 cột x 8 hàng (256 bóng LED)
 */
class PixelCanvasState(
    val width: Int = 32,
    val height: Int = 8
) {
    val pixels: Array<IntArray> = Array(height) { IntArray(width) { android.graphics.Color.BLACK } }

    fun setPixel(x: Int, y: Int, color: Int) {
        if (x in 0 until width && y in 0 until height) {
            pixels[y][x] = color
        }
    }

    fun getPixel(x: Int, y: Int): Int {
        return if (x in 0 until width && y in 0 until height) pixels[y][x] else android.graphics.Color.BLACK
    }

    fun clear() {
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y][x] = android.graphics.Color.BLACK
            }
        }
    }
}

/**
 * Mẫu vẽ Pixel Art 32x8 sẵn có
 */
data class PixelPreset(
    val name: String,
    val icon: String,
    val drawAction: (PixelCanvasState) -> Unit
)

object LampConstants {
    const val DEVICE_NAME = "LED-MATRIX-360"
    const val NUS_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val NUS_RX_UUID      = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // App Ghi xuống ESP32
    const val NUS_TX_UUID      = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // ESP32 Bắn Notify lên App
    const val CCCD_UUID        = "00002902-0000-1000-8000-00805F9B34FB"

    val MODES = listOf(
        LampMode(0, "Tự động đổi", "🔄", "Auto-Cycle 5 mode đỉnh cao"),
        LampMode(1, "Lava Noise", "🌋", "Dung nham 3D mượt mà (Mặc định)"),
        LampMode(2, "Edge Light", "✨", "Đèn viền 4 cạnh góc tối giản"),
        LampMode(3, "Face Wave", "🌊", "Sóng nhịp 4 mặt chuyển sắc"),
        LampMode(4, "Tilt Glow", "🎯", "Bám góc nghiêng & lắc bừng sáng"),
        LampMode(5, "Water Physics", "💧", "Showpiece mặt nước & giọt bắn"),
        LampMode(6, "Night Breathing", "🌙", "Nhịp thở ban đêm êm dịu"),
        LampMode(7, "Aurora Flow", "🌌", "Cực quang uốn lượn tím - cyan"),
        LampMode(8, "Dò dây LED", "🔍", "Calibration test 0 -> 255"),
        LampMode(9, "Tắt đèn", "💤", "Standby ngủ đông"),
        LampMode(10, "Màu tĩnh", "🎨", "Màu tùy chọn từ bảng màu")
    )

    val PRESET_COLORS = listOf(
        ColorPreset("Cyan Neon", Color(0xFF00F2FE), 0, 242, 254),
        ColorPreset("Pink Cyber", Color(0xFFFF007F), 255, 0, 127),
        ColorPreset("Amber Ấm", Color(0xFFFF9900), 255, 153, 0),
        ColorPreset("Ngọc Lục", Color(0xFF10B981), 16, 185, 129),
        ColorPreset("Tím Đêm", Color(0xFF8B5CF6), 139, 92, 246),
        ColorPreset("Đỏ Tươi", Color(0xFFEF4444), 239, 68, 68),
        ColorPreset("Vàng Nắng", Color(0xFFFACC15), 250, 204, 21),
        ColorPreset("Biển Sâu", Color(0xFF38BDF8), 56, 189, 248),
        ColorPreset("Trắng Ấm", Color(0xFFFFF7ED), 255, 247, 237)
    )

    val DEFAULT_NOTIF_CONFIGS = listOf(
        AppNotificationConfig("com.google.android.dialer", "Cuộc gọi đến", "📞", 255, 20, 20, true),
        AppNotificationConfig("com.zing.zalo", "Zalo", "💬", 0, 140, 255, true),
        AppNotificationConfig("com.facebook.orca", "Facebook Messenger", "⚡", 180, 40, 240, true),
        AppNotificationConfig("org.telegram.messenger", "Telegram", "✈️", 0, 210, 240, true),
        AppNotificationConfig("com.whatsapp", "WhatsApp", "🟢", 20, 240, 70, true),
        AppNotificationConfig("com.google.android.gm", "Gmail & Khác", "✉️", 255, 170, 20, true)
    )

    val CITIES = listOf(
        Pair("Hà Nội", Pair(21.0285, 105.8542)),
        Pair("TP. Hồ Chí Minh", Pair(10.8231, 106.6297)),
        Pair("Đà Nẵng", Pair(16.0544, 108.2022)),
        Pair("Hải Phòng", Pair(20.8449, 106.6881)),
        Pair("Cần Thơ", Pair(10.0452, 105.7469))
    )
}
