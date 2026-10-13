package com.esp32.ledmatrix360.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.esp32.ledmatrix360.ble.BleManager
import com.esp32.ledmatrix360.data.PreferencesRepository
import com.esp32.ledmatrix360.data.WeatherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Worker định kỳ (30 phút) đồng bộ thời gian và thời tiết từ Internet xuống đèn LED qua Frame 0x04 & 0x05
 */
class WeatherTimeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "WeatherTimeWorker"
        const val WORK_NAME = "periodic_weather_time_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WeatherTimeWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Đã lên lịch định kỳ 30 phút đồng bộ Thời tiết & Giờ.")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ble = BleManager.getInstance(applicationContext)
        val prefs = PreferencesRepository.getInstance(applicationContext)
        val weatherRepo = WeatherRepository()

        // 1. Đồng bộ Giờ hệ thống (Frame 0x05)
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)

        if (ble.isConnected.value) {
            ble.sendTimeSync(h, m, s)
            Log.d(TAG, "Đã đồng bộ giờ xuống đèn: $h:$m:$s")
        }

        // 2. Kéo dữ liệu thời tiết & đồng bộ Frame 0x04
        try {
            val selectedCity = prefs.selectedCityFlow.first()
            val weatherData = weatherRepo.fetchWeather(selectedCity)

            if (ble.isConnected.value) {
                // Gửi Frame 0x04 [Temp, Weather_ID]
                ble.sendWeather(weatherData.temp, weatherData.weatherId)

                // Đổi Mode hoạt hình tương ứng thời tiết
                ble.setEffect(weatherData.recommendedModeId, speed = 50)

                Log.d(TAG, "Đã đồng bộ thời tiết thành công: ${weatherData.cityName} - ${weatherData.temp}°C, Weather ID: ${weatherData.weatherId}, Animation Mode: ${weatherData.recommendedModeId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đồng bộ thời tiết trong Worker: ${e.message}")
        }

        Result.success()
    }
}
