package com.example.neptuno.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.neptuno.core.model.AppLanguage
import com.example.neptuno.core.model.AvatarStyle
import com.example.neptuno.core.model.PersonalityMode
import com.example.neptuno.core.model.ThemeMode
import com.example.neptuno.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.neptunoDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "neptuno_user_preferences"
)

/**
 * Repositorio persistente de preferencias basado en Jetpack DataStore.
 * Conserva toda la configuración de Neptuno al cerrar y volver a abrir la aplicación.
 */
class UserPreferencesRepository(context: Context) {

    private val dataStore = context.applicationContext.neptunoDataStore

    private object Keys {
        val SELECTED_VOICE_ID = stringPreferencesKey("selected_voice_id")
        val PERSONALITY_MODE = stringPreferencesKey("personality_mode")
        val AVATAR_STYLE = stringPreferencesKey("avatar_style")
        val LANGUAGE = stringPreferencesKey("language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val OFFLINE_MODE = booleanPreferencesKey("offline_mode")
        val BARGE_IN_ENABLED = booleanPreferencesKey("barge_in_enabled")
        val PREFER_BLUETOOTH_AUDIO = booleanPreferencesKey("prefer_bluetooth_audio")
        val MEMORY_AUTHORIZED = booleanPreferencesKey("memory_authorized")
        val PERSIST_HISTORY = booleanPreferencesKey("persist_history")
        val PREFERRED_AUDIO_INPUT_ID = intPreferencesKey("preferred_audio_input_id")
        val PREFERRED_AUDIO_OUTPUT_ID = intPreferencesKey("preferred_audio_output_id")
        val PREFERRED_AI_PROVIDER_ID = stringPreferencesKey("preferred_ai_provider_id")
    }

    val userPreferences: Flow<UserPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            UserPreferences(
                selectedVoiceId = prefs[Keys.SELECTED_VOICE_ID] ?: "neptuno_ocean_neutral",
                personalityMode = PersonalityMode.entries.find { it.id == prefs[Keys.PERSONALITY_MODE] }
                    ?: PersonalityMode.ANALYTICAL,
                avatarStyle = AvatarStyle.entries.find { it.id == prefs[Keys.AVATAR_STYLE] }
                    ?: AvatarStyle.ABYSSAL_CORE,
                language = AppLanguage.entries.find { it.code == prefs[Keys.LANGUAGE] }
                    ?: AppLanguage.SPANISH,
                themeMode = ThemeMode.entries.find { it.id == prefs[Keys.THEME_MODE] }
                    ?: ThemeMode.DARK,
                offlineMode = prefs[Keys.OFFLINE_MODE] ?: false,
                bargeInEnabled = prefs[Keys.BARGE_IN_ENABLED] ?: true,
                preferBluetoothAudio = prefs[Keys.PREFER_BLUETOOTH_AUDIO] ?: true,
                memoryAuthorized = prefs[Keys.MEMORY_AUTHORIZED] ?: true,
                persistConversationHistory = prefs[Keys.PERSIST_HISTORY] ?: true,
                preferredAudioInputId = prefs[Keys.PREFERRED_AUDIO_INPUT_ID],
                preferredAudioOutputId = prefs[Keys.PREFERRED_AUDIO_OUTPUT_ID],
                preferredAiProviderId = prefs[Keys.PREFERRED_AI_PROVIDER_ID] ?: "gemini_live_pending"
            )
        }

    suspend fun setSelectedVoice(voiceId: String) {
        dataStore.edit { it[Keys.SELECTED_VOICE_ID] = voiceId }
    }

    suspend fun setPersonalityMode(mode: PersonalityMode) {
        dataStore.edit { it[Keys.PERSONALITY_MODE] = mode.id }
    }

    suspend fun setAvatarStyle(style: AvatarStyle) {
        dataStore.edit { it[Keys.AVATAR_STYLE] = style.id }
    }

    suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { it[Keys.LANGUAGE] = language.code }
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = themeMode.id }
    }

    suspend fun setOfflineMode(enabled: Boolean) {
        dataStore.edit { it[Keys.OFFLINE_MODE] = enabled }
    }

    suspend fun setBargeInEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BARGE_IN_ENABLED] = enabled }
    }

    suspend fun setPreferBluetoothAudio(enabled: Boolean) {
        dataStore.edit { it[Keys.PREFER_BLUETOOTH_AUDIO] = enabled }
    }

    suspend fun setMemoryAuthorized(authorized: Boolean) {
        dataStore.edit { it[Keys.MEMORY_AUTHORIZED] = authorized }
    }

    suspend fun setPersistConversationHistory(persist: Boolean) {
        dataStore.edit { it[Keys.PERSIST_HISTORY] = persist }
    }

    suspend fun setPreferredAudioInput(deviceId: Int?) {
        dataStore.edit { prefs ->
            if (deviceId == null) prefs.remove(Keys.PREFERRED_AUDIO_INPUT_ID)
            else prefs[Keys.PREFERRED_AUDIO_INPUT_ID] = deviceId
        }
    }

    suspend fun setPreferredAudioOutput(deviceId: Int?) {
        dataStore.edit { prefs ->
            if (deviceId == null) prefs.remove(Keys.PREFERRED_AUDIO_OUTPUT_ID)
            else prefs[Keys.PREFERRED_AUDIO_OUTPUT_ID] = deviceId
        }
    }

    suspend fun setPreferredAiProvider(providerId: String) {
        dataStore.edit { it[Keys.PREFERRED_AI_PROVIDER_ID] = providerId }
    }
}
