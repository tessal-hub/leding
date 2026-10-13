package com.esp32.ledmatrix360.data

import android.util.Log
import com.esp32.ledmatrix360.model.LampConstants
import com.esp32.ledmatrix360.model.WeatherData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WeatherRepository {

    suspend fun fetchWeather(cityName: String = "Hà Nội"): WeatherData = withContext(Dispatchers.IO) {
        val coords = LampConstants.CITIES.firstOrNull { it.first == cityName }?.second
            ?: Pair(21.0285, 105.8542) // Mặc định Hà Nội

        val lat = coords.first
        val lon = coords.second

        var conn: HttpURLConnection? = null
        try {
            val apiUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code"
            conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(responseText)
                val current = root.getJSONObject("current")
                val temp = current.getDouble("temperature_2m").toInt()
                val code = current.getInt("weather_code")

                val (weatherName, weatherIcon, weatherId, modeId) = parseWmoWeatherCode(code)

                Log.d("WeatherRepository", "Kéo thời tiết $cityName: $temp°C, $weatherName (Code $code)")
                return@withContext WeatherData(
                    temp = temp,
                    weatherCode = code,
                    weatherName = weatherName,
                    weatherIcon = weatherIcon,
                    weatherId = weatherId,
                    recommendedModeId = modeId,
                    cityName = cityName
                )
            }
        } catch (e: Exception) {
            Log.e("WeatherRepository", "Lỗi kéo thời tiết: ${e.message}")
        } finally {
            conn?.disconnect()
        }

        // Mặc định nếu API lỗi
        return@withContext WeatherData(
            temp = 28,
            weatherCode = 0,
            weatherName = "Trời Nắng (Ngoại tuyến)",
            weatherIcon = "☀️",
            weatherId = 1,
            recommendedModeId = 1,
            cityName = cityName
        )
    }

    private fun parseWmoWeatherCode(code: Int): Quadruple<String, String, Int, Int> {
        return when (code) {
            0 -> Quadruple("Nắng đẹp", "☀️", 1, 1) // Mode 1: Lava Noise (Sáng rực rỡ)
            1, 2 -> Quadruple("Ít mây", "⛅", 2, 6) // Mode 6: Night Breathing
            3, 45, 48 -> Quadruple("Nhiều mây / Sương mù", "☁️", 2, 2) // Mode 2: Edge Light
            51, 53, 55, 61, 63, 65, 80, 81, 82 -> Quadruple("Có mưa", "🌧️", 3, 5) // Mode 5: Water Physics
            95, 96, 99 -> Quadruple("Giông sấm chớp", "🌩️", 4, 4) // Mode 4: Tilt Glow
            else -> Quadruple("Thời tiết dịu mát", "🌤️", 1, 3) // Mode 3: Face Wave
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
