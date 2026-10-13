package com.esp32.ledmatrix360.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.esp32.ledmatrix360.model.AppNotificationConfig
import com.esp32.ledmatrix360.model.LampConstants
import com.esp32.ledmatrix360.model.SmartRoutineConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "led360_settings")

class PreferencesRepository(private val context: Context) {

    companion object {
        private val KEY_SELECTED_CITY = stringPreferencesKey("selected_city")
        private val KEY_NOTIF_CONFIGS = stringPreferencesKey("notif_configs_json")
        private val KEY_LAST_MARQUEE   = stringPreferencesKey("last_marquee_text")
        private val KEY_ROUTINES_CONFIG = stringPreferencesKey("smart_routines_config")
        private val KEY_EFFECT_SPEED   = intPreferencesKey("effect_speed")

        @Volatile
        private var INSTANCE: PreferencesRepository? = null

        fun getInstance(context: Context): PreferencesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    val selectedCityFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_CITY] ?: "Hà Nội"
    }

    suspend fun saveSelectedCity(cityName: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SELECTED_CITY] = cityName
        }
    }

    val notificationConfigsFlow: Flow<List<AppNotificationConfig>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[KEY_NOTIF_CONFIGS]
        if (jsonStr.isNullOrEmpty()) {
            LampConstants.DEFAULT_NOTIF_CONFIGS
        } else {
            try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<AppNotificationConfig>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        AppNotificationConfig(
                            packageName = obj.getString("pkg"),
                            appName = obj.getString("name"),
                            iconEmoji = obj.optString("icon", "💬"),
                            r = obj.getInt("r"),
                            g = obj.getInt("g"),
                            b = obj.getInt("b"),
                            isEnabled = obj.optBoolean("enabled", true),
                            flashCount = obj.optInt("count", 3)
                        )
                    )
                }
                list
            } catch (e: Exception) {
                LampConstants.DEFAULT_NOTIF_CONFIGS
            }
        }
    }

    suspend fun saveNotificationConfigs(configs: List<AppNotificationConfig>) {
        val array = JSONArray()
        configs.forEach { item ->
            val obj = JSONObject().apply {
                put("pkg", item.packageName)
                put("name", item.appName)
                put("icon", item.iconEmoji)
                put("r", item.r)
                put("g", item.g)
                put("b", item.b)
                put("enabled", item.isEnabled)
                put("count", item.flashCount)
            }
            array.put(obj)
        }
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTIF_CONFIGS] = array.toString()
        }
    }

    val lastMarqueeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_MARQUEE] ?: "LED MATRIX 360"
    }

    suspend fun saveLastMarquee(text: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_MARQUEE] = text
        }
    }

    val smartRoutinesFlow: Flow<SmartRoutineConfig> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[KEY_ROUTINES_CONFIG]
        if (jsonStr.isNullOrEmpty()) {
            SmartRoutineConfig()
        } else {
            try {
                val obj = JSONObject(jsonStr)
                SmartRoutineConfig(
                    sunriseEnabled = obj.optBoolean("sunriseEnabled", false),
                    sunriseHour = obj.optInt("sunriseHour", 6),
                    sunriseMinute = obj.optInt("sunriseMinute", 30),
                    sunriseDurationMin = obj.optInt("sunriseDurationMin", 15),
                    bedtimeEnabled = obj.optBoolean("bedtimeEnabled", false),
                    bedtimeHour = obj.optInt("bedtimeHour", 23),
                    bedtimeMinute = obj.optInt("bedtimeMinute", 0),
                    bedtimeDurationMin = obj.optInt("bedtimeDurationMin", 30),
                    autoWeatherSync = obj.optBoolean("autoWeatherSync", false)
                )
            } catch (e: Exception) {
                SmartRoutineConfig()
            }
        }
    }

    suspend fun saveSmartRoutines(config: SmartRoutineConfig) {
        val obj = JSONObject().apply {
            put("sunriseEnabled", config.sunriseEnabled)
            put("sunriseHour", config.sunriseHour)
            put("sunriseMinute", config.sunriseMinute)
            put("sunriseDurationMin", config.sunriseDurationMin)
            put("bedtimeEnabled", config.bedtimeEnabled)
            put("bedtimeHour", config.bedtimeHour)
            put("bedtimeMinute", config.bedtimeMinute)
            put("bedtimeDurationMin", config.bedtimeDurationMin)
            put("autoWeatherSync", config.autoWeatherSync)
        }
        context.dataStore.edit { prefs ->
            prefs[KEY_ROUTINES_CONFIG] = obj.toString()
        }
    }

    val effectSpeedFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_EFFECT_SPEED] ?: 50
    }

    suspend fun saveEffectSpeed(speed: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_EFFECT_SPEED] = speed
        }
    }
}
