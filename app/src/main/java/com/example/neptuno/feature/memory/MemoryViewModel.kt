package com.example.neptuno.feature.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.neptuno.core.model.ConversationSession
import com.example.neptuno.core.model.MemoryCategory
import com.example.neptuno.core.model.MemoryEntry
import com.example.neptuno.data.local.ConversationRepository
import com.example.neptuno.data.local.MemoryRepository
import com.example.neptuno.data.local.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MemoryUiState(
    val memories: List<MemoryEntry> = emptyList(),
    val conversations: List<ConversationSession> = emptyList(),
    val isMemoryAuthorized: Boolean = true
)

class MemoryViewModel(
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    val uiState: StateFlow<MemoryUiState> = combine(
        memoryRepository.observeMemories(),
        conversationRepository.observeConversations(),
        preferencesRepository.userPreferences
    ) { memories, conversations, prefs ->
        MemoryUiState(
            memories = memories,
            conversations = conversations,
            isMemoryAuthorized = prefs.memoryAuthorized
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MemoryUiState()
    )

    fun saveAuthorizedMemory(title: String, content: String, category: MemoryCategory) {
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            memoryRepository.saveMemory(
                MemoryEntry(
                    title = title.trim(),
                    content = content.trim(),
                    category = category,
                    authorizedByUser = true
                )
            )
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id)
        }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            memoryRepository.clearAllMemories()
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(id)
        }
    }

    fun clearAllConversations() {
        viewModelScope.launch {
            conversationRepository.clearAllConversations()
        }
    }

    companion object {
        fun factory(
            memoryRepository: MemoryRepository,
            conversationRepository: ConversationRepository,
            preferencesRepository: UserPreferencesRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MemoryViewModel(
                    memoryRepository = memoryRepository,
                    conversationRepository = conversationRepository,
                    preferencesRepository = preferencesRepository
                ) as T
            }
        }
    }
}
