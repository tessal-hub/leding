package com.esp32.ledmatrix360.model

import android.bluetooth.BluetoothDevice
import androidx.compose.ui.graphics.Color

/**
 * Trạng thái chi tiết của kết nối BLE
 */
enum class BleConnectionState(val displayName: String) {
    DISCONNECTED("Chưa kết nối"),
    SCANNING("Đang quét tìm đèn..."),
    CONNECTING("Đang tạo kết nối GATT..."),
    NEGOTIATING_MTU("Đang đàm phán MTU..."),
    DISCOVERING_SERVICES("Đang tìm dịch vụ..."),
    ENABLING_NOTIFICATIONS("Đang bật nhận phản hồi..."),
    CONNECTED("Đã kết nối")
}

/**
 * Thiết bị đèn tìm thấy trong phiên quét
 */
data class DiscoveredLampDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * Dữ liệu trạng thái phản hồi thời gian thực từ ESP32 qua BLE TX (Notify)
 */
data class TelemetryState(
    val mode: Int = 1,
    val modeName: String = "Nhịp Đập Trái Tim",
    val brightness: Int = 68,
    val speed: Int = 50,
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
    val isEnabled: Boolean = true,
    val flashCount: Int = 3
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
    val cityName: String = "Hà Nội",
    val lastUpdatedTime: Long = System.currentTimeMillis()
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
 * Trạng thái 1 ô Bitmap 8x8 (64 bóng LED, tương ứng 1 mặt của hộp đèn)
 */
class SlotBitmapState(
    val id: Int, // 1..4
    val pixels: IntArray = IntArray(64) { android.graphics.Color.BLACK }
) {
    fun setPixel(x: Int, y: Int, color: Int) {
        if (x in 0..7 && y in 0..7) pixels[y * 8 + x] = color
    }

    fun getPixel(x: Int, y: Int): Int {
        return if (x in 0..7 && y in 0..7) pixels[y * 8 + x] else android.graphics.Color.BLACK
    }

    fun clear() {
        pixels.fill(android.graphics.Color.BLACK)
    }

    fun fill(color: Int) {
        pixels.fill(color)
    }

    fun copy(): SlotBitmapState {
        val s = SlotBitmapState(id)
        System.arraycopy(pixels, 0, s.pixels, 0, 64)
        return s
    }

    fun to1BitBitmap(): ByteArray {
        val bytes = ByteArray(8)
        for (y in 0..7) {
            var row = 0
            for (x in 0..7) {
                if (getPixel(x, y) != android.graphics.Color.BLACK) {
                    row = row or (1 shl (7 - x))
                }
            }
            bytes[y] = row.toByte()
        }
        return bytes
    }

    fun getDominantColor(): Triple<Int, Int, Int> {
        var sumR = 0
        var sumG = 0
        var sumB = 0
        var count = 0
        for (c in pixels) {
            if (c != android.graphics.Color.BLACK) {
                sumR += android.graphics.Color.red(c)
                sumG += android.graphics.Color.green(c)
                sumB += android.graphics.Color.blue(c)
                count++
            }
        }
        return if (count > 0) Triple(sumR / count, sumG / count, sumB / count) else Triple(0, 220, 255)
    }
}

data class SlotIconPreset(
    val name: String,
    val iconEmoji: String,
    val r: Int,
    val g: Int,
    val b: Int,
    val rows: IntArray // 8 hàng, mỗi hàng là giá trị 8-bit
)

data class SmartRoutineConfig(
    val sunriseEnabled: Boolean = false,
    val sunriseHour: Int = 6,
    val sunriseMinute: Int = 30,
    val sunriseDurationMin: Int = 15,
    val bedtimeEnabled: Boolean = false,
    val bedtimeHour: Int = 23,
    val bedtimeMinute: Int = 0,
    val bedtimeDurationMin: Int = 30,
    val autoWeatherSync: Boolean = false
)

data class FullCanvasPreset(
    val name: String,
    val iconEmoji: String,
    val description: String,
    val generator: (PixelCanvasState) -> Unit
)

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
    const val SHORT_NAME  = "LED-360"
    const val NUS_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val NUS_RX_UUID      = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // App Ghi xuống ESP32
    const val NUS_TX_UUID      = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // ESP32 Bắn Notify lên App
    const val CCCD_UUID        = "00002902-0000-1000-8000-00805F9B34FB"

    val MODES = listOf(
        LampMode(0, "Tự động đổi", "🔄", "Tuần hoàn các cảnh lãng mạn (Romantic Cycle)"),
        LampMode(1, "Nhịp Đập Trái Tim", "💓", "Nhịp đập trái tim lãng mạn (Mặc định)"),
        LampMode(2, "Thạch Anh Tím", "💎", "Pha Lê Thạch Anh Tím 360° (Amethyst Crystal)"),
        LampMode(3, "Ánh Nến Hẹn Hò", "🕯️", "Ngọn lửa nến ấm cúng hai người (Candlelight)"),
        LampMode(4, "Cử Chỉ Yêu Thương", "🎯", "Ánh sáng âu yếm theo góc nghiêng MPU (Love Tilt)"),
        LampMode(5, "Lò Sưởi Ấm Cúng", "🔥", "Lửa ấm đêm đông 360° (Cozy Fireplace)"),
        LampMode(6, "Hoa Oải Hương", "🪻", "Biển hoa oải hương tím hoàng hôn (Lavender Breeze)"),
        LampMode(7, "Cực Quang Tình Nhân", "🌌", "Cực quang tím & hồng mộng mơ (Lover's Aurora)"),
        LampMode(8, "Ngàn Sao Hẹn Ước", "⭐", "Bầu trời đêm & sao băng ước nguyện (Starry Night)"),
        LampMode(9, "Tắt đèn", "💤", "Chế độ Standby / Tắt"),
        LampMode(10, "Màu Tĩnh Lãng Mạn", "🎨", "Màu tĩnh tùy chọn từ App (Phản ứng MPU)"),
        LampMode(11, "Khung Hình Kỷ Niệm", "🖼️", "4 Khung hình kỷ niệm 8x8 (4-Slot Love Memories)"),
        LampMode(12, "Đóa Hồng Hé Nở", "🌹", "Đóa hoa hồng tình yêu nở rộ (Blooming Rose)"),
        LampMode(13, "Chữ Chạy 360°", "💬", "Dải ruy-băng tin nhắn chạy quanh thân đèn (Marquee)"),
        LampMode(14, "Nắng Đẹp 360°", "☀️", "Mặt trời & tia nắng xoay tròn theo giờ (Clear Sky)"),
        LampMode(15, "Mưa Rơi 360°", "🌧️", "Hạt mưa rơi & sóng nước bắn tung tóe (Rain & Puddles)"),
        LampMode(16, "Mây Trời 360°", "☁️", "Mây trôi đa tầng bồng bềnh 360° (Volumetric Clouds)"),
        LampMode(17, "Giông Bão 360°", "🌩️", "Mưa gió cuồng phong & sấm sét rực sáng (Storm & Lightning)")
    )

    val SMART_ROUTINES_DEFAULT = SmartRoutineConfig()

    val FULL_CANVAS_PRESETS = listOf(
        FullCanvasPreset("Sóng Âm Equalizer", "🎵", "Cột sóng âm nhạc trải đều 4 mặt") { canvas ->
            canvas.clear()
            val heights = intArrayOf(2, 4, 6, 8, 7, 5, 3, 2, 4, 6, 7, 8, 5, 3, 2, 4, 6, 8, 7, 5, 3, 2, 5, 7, 8, 6, 4, 2, 3, 5, 7, 4)
            for (x in 0 until 32) {
                val h = heights[x]
                for (y in (8 - h) until 8) {
                    val color = when {
                        y < 2 -> android.graphics.Color.RED
                        y < 5 -> android.graphics.Color.YELLOW
                        else -> android.graphics.Color.CYAN
                    }
                    canvas.setPixel(x, y, color)
                }
            }
        },
        FullCanvasPreset("Vũ Trụ Sao Băng", "✨", "Bầu trời đêm & các chòm sao lấp lánh") { canvas ->
            canvas.clear()
            val stars = listOf(
                Pair(2, 1), Pair(5, 5), Pair(9, 2), Pair(12, 6), Pair(15, 3),
                Pair(18, 1), Pair(21, 5), Pair(25, 2), Pair(28, 6), Pair(31, 3)
            )
            for ((x, y) in stars) {
                canvas.setPixel(x, y, android.graphics.Color.WHITE)
            }
            // Dải ngân hà tím ở đáy
            for (x in 0 until 32) {
                canvas.setPixel(x, 7, android.graphics.Color.rgb(120, 40, 200))
                if (x % 3 == 0) canvas.setPixel(x, 6, android.graphics.Color.rgb(0, 180, 255))
            }
        },
        FullCanvasPreset("Cầu Vồng 360°", "🌈", "Dải chuyển sắc mượt mà vòng quanh trụ") { canvas ->
            for (x in 0 until 32) {
                val hue = (x * 360f / 32f)
                val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1.0f, 1.0f))
                for (y in 1..6) {
                    canvas.setPixel(x, y, c)
                }
            }
        },
        FullCanvasPreset("Nhịp Tim ECG", "💓", "Đường nhịp tim điện tâm đồ nối liền") { canvas ->
            canvas.clear()
            for (x in 0 until 32) {
                val y = when (x % 8) {
                    2 -> 3
                    3 -> 1
                    4 -> 6
                    5 -> 2
                    else -> 4
                }
                canvas.setPixel(x, y, android.graphics.Color.rgb(255, 30, 80))
                canvas.setPixel(x, y + 1, android.graphics.Color.rgb(180, 10, 50))
            }
        },
        FullCanvasPreset("Cyberpunk City", "🏙️", "Skyline thành phố tương lai rực rỡ") { canvas ->
            canvas.clear()
            val buildings = intArrayOf(3, 5, 2, 6, 4, 7, 3, 5, 4, 6, 2, 7, 5, 3, 6, 4, 3, 5, 2, 6, 4, 7, 3, 5, 4, 6, 2, 7, 5, 3, 6, 4)
            for (x in 0 until 32) {
                val bH = buildings[x]
                for (y in (8 - bH) until 8) {
                    val c = if ((x + y) % 2 == 0) android.graphics.Color.rgb(0, 240, 255) else android.graphics.Color.rgb(255, 0, 128)
                    canvas.setPixel(x, y, c)
                }
            }
        }
    )

    val SLOT_PRESETS = listOf(
        SlotIconPreset("Số 1", "1️⃣", 0, 220, 255, intArrayOf(0x18, 0x38, 0x18, 0x18, 0x18, 0x18, 0x7E, 0x00)),
        SlotIconPreset("Số 2", "2️⃣", 255, 180, 20, intArrayOf(0x3C, 0x66, 0x06, 0x0C, 0x18, 0x30, 0x7E, 0x00)),
        SlotIconPreset("Số 3", "3️⃣", 20, 240, 100, intArrayOf(0x3C, 0x66, 0x06, 0x1C, 0x06, 0x66, 0x3C, 0x00)),
        SlotIconPreset("Số 4", "4️⃣", 255, 60, 180, intArrayOf(0x0C, 0x1C, 0x2C, 0x4C, 0x7E, 0x0C, 0x0C, 0x00)),
        SlotIconPreset("Trái Tim", "❤️", 255, 20, 60, intArrayOf(0x00, 0x66, 0xFF, 0xFF, 0xFF, 0x7E, 0x3C, 0x18)),
        SlotIconPreset("Mặt Cười", "😊", 255, 215, 0, intArrayOf(0x3C, 0x42, 0xA5, 0x81, 0xA5, 0x99, 0x42, 0x3C)),
        SlotIconPreset("Ngôi Sao", "⭐", 255, 165, 0, intArrayOf(0x18, 0x18, 0xFF, 0x7E, 0x3C, 0x66, 0xC3, 0x00)),
        SlotIconPreset("Sấm Sét", "⚡", 255, 255, 0, intArrayOf(0x0C, 0x18, 0x30, 0x7E, 0x0C, 0x18, 0x20, 0x40)),
        SlotIconPreset("Lửa", "🔥", 255, 70, 0, intArrayOf(0x10, 0x30, 0x78, 0x7C, 0xFE, 0xFE, 0x7C, 0x38)),
        SlotIconPreset("Nốt Nhạc", "🎵", 155, 50, 255, intArrayOf(0x0E, 0x0A, 0x0A, 0x0A, 0x3A, 0x7A, 0x72, 0x00)),
        SlotIconPreset("Con Ma", "👻", 0, 255, 255, intArrayOf(0x3C, 0x7E, 0xAB, 0xFF, 0xFF, 0xFF, 0xB5, 0x00)),
        SlotIconPreset("Pacman", "👾", 255, 230, 0, intArrayOf(0x3C, 0x7E, 0xFF, 0xF8, 0xF0, 0xF8, 0x7E, 0x3C))
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
