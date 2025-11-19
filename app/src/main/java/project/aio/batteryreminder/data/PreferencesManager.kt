// ===== batteryreminder\app\src\main\java\project\aio\batteryreminder\data\PreferencesManager.kt =====
package project.aio.batteryreminder.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import project.aio.batteryreminder.data.model.Threshold
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "battery_settings")

@Singleton
class PreferencesManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val gson = Gson()

    companion object {
        val THRESHOLDS_JSON = stringPreferencesKey("thresholds_json")
        val SOUND_URI = stringPreferencesKey("sound_uri")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val FLASH_ENABLED = booleanPreferencesKey("flash_enabled")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val ALERT_DURATION = intPreferencesKey("alert_duration") // Seconds. -1 for infinite.

        // New Prediction Keys
        val EMERGENCY_THRESHOLD = intPreferencesKey("emergency_threshold_seconds") // Default 120s (2 mins)
        val AUTO_DIM_ENABLED = booleanPreferencesKey("auto_dim_enabled")
    }

    // Default thresholds: 20% and 80%
    val thresholds: Flow<List<Threshold>> = context.dataStore.data.map { preferences ->
        val json = preferences[THRESHOLDS_JSON]
        if (json.isNullOrEmpty()) {
            listOf(Threshold(20), Threshold(80))
        } else {
            val type = object : TypeToken<List<Threshold>>() {}.type
            gson.fromJson(json, type)
        }
    }

    val soundUri: Flow<String> = context.dataStore.data.map { it[SOUND_URI] ?: "" }
    val soundEnabled: Flow<Boolean> = context.dataStore.data.map { it[SOUND_ENABLED] ?: true }
    val flashEnabled: Flow<Boolean> = context.dataStore.data.map { it[FLASH_ENABLED] ?: false }
    val vibrationEnabled: Flow<Boolean> = context.dataStore.data.map { it[VIBRATION_ENABLED] ?: true }
    val ttsEnabled: Flow<Boolean> = context.dataStore.data.map { it[TTS_ENABLED] ?: false }
    val alertDuration: Flow<Int> = context.dataStore.data.map { it[ALERT_DURATION] ?: 30 }

    // Prediction Flows
    val emergencyThreshold: Flow<Int> = context.dataStore.data.map { it[EMERGENCY_THRESHOLD] ?: 120 }
    val autoDimEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_DIM_ENABLED] ?: true }

    suspend fun updateAlertDuration(seconds: Int) {
        context.dataStore.edit { it[ALERT_DURATION] = seconds }
    }

    suspend fun updateThresholds(list: List<Threshold>) {
        val json = gson.toJson(list)
        context.dataStore.edit { it[THRESHOLDS_JSON] = json }
    }

    suspend fun updateSoundUri(uri: String) {
        context.dataStore.edit { it[SOUND_URI] = uri }
    }

    suspend fun updateSound(enabled: Boolean) = context.dataStore.edit { it[SOUND_ENABLED] = enabled }
    suspend fun updateFlash(enabled: Boolean) = context.dataStore.edit { it[FLASH_ENABLED] = enabled }
    suspend fun updateVibration(enabled: Boolean) = context.dataStore.edit { it[VIBRATION_ENABLED] = enabled }
    suspend fun updateTts(enabled: Boolean) = context.dataStore.edit { it[TTS_ENABLED] = enabled }

    suspend fun updateEmergencyThreshold(seconds: Int) = context.dataStore.edit { it[EMERGENCY_THRESHOLD] = seconds }
    suspend fun updateAutoDim(enabled: Boolean) = context.dataStore.edit { it[AUTO_DIM_ENABLED] = enabled }
}