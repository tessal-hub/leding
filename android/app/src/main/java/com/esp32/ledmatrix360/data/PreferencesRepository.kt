package com.esp32.ledmatrix360.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.esp32.ledmatrix360.model.AppNotificationConfig
import com.esp32.ledmatrix360.model.LampConstants
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
                            isEnabled = obj.optBoolean("enabled", true)
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
}
