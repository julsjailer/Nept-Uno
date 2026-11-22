package com.example.neptuno.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.neptuno.ai.api.AiProvider
import com.example.neptuno.ai.api.AiProviderRegistry
import com.example.neptuno.audio.engine.VoiceProfileCatalog
import com.example.neptuno.core.model.AppLanguage
import com.example.neptuno.core.model.AvatarStyle
import com.example.neptuno.core.model.PersonalityMode
import com.example.neptuno.core.model.ThemeMode
import com.example.neptuno.core.model.UserPreferences
import com.example.neptuno.core.model.VoiceProfile
import com.example.neptuno.data.local.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferencesRepository: UserPreferencesRepository,
    private val aiProviderRegistry: AiProviderRegistry
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesRepository.userPreferences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences()
        )

    val voiceProfiles: List<VoiceProfile> = VoiceProfileCatalog.profiles
    val aiProviders: List<AiProvider> = aiProviderRegistry.getAllProviders()

    fun setVoice(voiceId: String) = viewModelScope.launch {
        preferencesRepository.setSelectedVoice(voiceId)
    }

    fun setPersonality(mode: PersonalityMode) = viewModelScope.launch {
        preferencesRepository.setPersonalityMode(mode)
    }

    fun setAvatarStyle(style: AvatarStyle) = viewModelScope.launch {
        preferencesRepository.setAvatarStyle(style)
    }

    fun setLanguage(language: AppLanguage) = viewModelScope.launch {
        preferencesRepository.setLanguage(language)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        preferencesRepository.setThemeMode(mode)
    }

    fun setOfflineMode(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setOfflineMode(enabled)
    }

    fun setBargeInEnabled(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setBargeInEnabled(enabled)
    }

    fun setPreferBluetoothAudio(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setPreferBluetoothAudio(enabled)
    }

    fun setMemoryAuthorized(authorized: Boolean) = viewModelScope.launch {
        preferencesRepository.setMemoryAuthorized(authorized)
    }

    fun setPersistConversationHistory(persist: Boolean) = viewModelScope.launch {
        preferencesRepository.setPersistConversationHistory(persist)
    }

    fun setPreferredAiProvider(providerId: String) = viewModelScope.launch {
        preferencesRepository.setPreferredAiProvider(providerId)
    }

    companion object {
        fun factory(
            preferencesRepository: UserPreferencesRepository,
            aiProviderRegistry: AiProviderRegistry
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(
                    preferencesRepository = preferencesRepository,
                    aiProviderRegistry = aiProviderRegistry
                ) as T
            }
        }
    }
}
