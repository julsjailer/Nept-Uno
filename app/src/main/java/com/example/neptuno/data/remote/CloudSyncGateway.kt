package com.example.neptuno.data.remote

import com.example.neptuno.core.NetworkMonitor
import com.example.neptuno.core.model.ChatMessage
import com.example.neptuno.core.model.MemoryEntry
import com.example.neptuno.core.model.UserPreferences
import com.example.neptuno.data.local.UserPreferencesRepository
import kotlinx.coroutines.flow.first

/**
 * Puente opcional de sincronización hacia Cloud Firestore.
 * Mantiene Room y DataStore operativos localmente y sincroniza cambios autorizados
 * cuando existe sesión autenticada y conectividad activa.
 */
interface CloudSyncGateway {
    suspend fun syncProfile(displayName: String, email: String?, preferences: UserPreferences)
    suspend fun syncMemorySaved(entry: MemoryEntry)
    suspend fun syncMemoryDeleted(localId: Long)
    suspend fun syncAllMemoriesCleared()
    suspend fun syncConversationTitle(localConversationId: Long, title: String)
    suspend fun syncMessageAdded(conversationId: Long, conversationTitle: String, message: ChatMessage)
    suspend fun syncConversationDeleted(localConversationId: Long)
    suspend fun syncAllConversationsCleared()
}

object NoOpCloudSyncGateway : CloudSyncGateway {
    override suspend fun syncProfile(displayName: String, email: String?, preferences: UserPreferences) = Unit
    override suspend fun syncMemorySaved(entry: MemoryEntry) = Unit
    override suspend fun syncMemoryDeleted(localId: Long) = Unit
    override suspend fun syncAllMemoriesCleared() = Unit
    override suspend fun syncConversationTitle(localConversationId: Long, title: String) = Unit
    override suspend fun syncMessageAdded(conversationId: Long, conversationTitle: String, message: ChatMessage) = Unit
    override suspend fun syncConversationDeleted(localConversationId: Long) = Unit
    override suspend fun syncAllConversationsCleared() = Unit
}

class FirestoreCloudSyncGateway(
    private val userId: String,
    private val repository: NeptunoFirestoreRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val networkMonitor: NetworkMonitor
) : CloudSyncGateway {

    private suspend fun isCloudSyncAllowed(): Boolean {
        val prefs = preferencesRepository.userPreferences.first()
        if (prefs.offlineMode) return false
        return networkMonitor.observeNetworkStatus().first().isConnected
    }

    override suspend fun syncProfile(
        displayName: String,
        email: String?,
        preferences: UserPreferences
    ) {
        if (!isCloudSyncAllowed()) return
        repository.upsertUserProfile(
            userId = userId,
            displayName = displayName,
            email = email,
            languageCode = preferences.language.code,
            personalityId = preferences.personalityMode.id,
            avatarStyleId = preferences.avatarStyle.id
        )
    }

    override suspend fun syncMemorySaved(entry: MemoryEntry) {
        if (!isCloudSyncAllowed() || !entry.authorizedByUser) return
        repository.saveMemory(
            userId = userId,
            title = entry.title,
            content = entry.content,
            category = entry.category,
            localId = entry.id.takeIf { it > 0L }
        )
    }

    override suspend fun syncMemoryDeleted(localId: Long) {
        if (!isCloudSyncAllowed() || localId <= 0L) return
        repository.deleteMemory(userId = userId, memoryDocId = "local_$localId")
    }

    override suspend fun syncAllMemoriesCleared() {
        if (!isCloudSyncAllowed()) return
        repository.clearAllMemories(userId = userId)
    }

    override suspend fun syncConversationTitle(localConversationId: Long, title: String) {
        if (!isCloudSyncAllowed()) return
        repository.ensureCloudConversation(
            userId = userId,
            localConversationId = localConversationId,
            title = title
        )
    }

    override suspend fun syncMessageAdded(
        conversationId: Long,
        conversationTitle: String,
        message: ChatMessage
    ) {
        if (!isCloudSyncAllowed()) return
        repository.appendCloudMessage(
            userId = userId,
            localConversationId = conversationId,
            conversationTitle = conversationTitle,
            sender = message.sender,
            content = message.content,
            providerId = message.providerId,
            interrupted = message.isInterrupted
        )
    }

    override suspend fun syncConversationDeleted(localConversationId: Long) {
        if (!isCloudSyncAllowed()) return
        repository.deleteCloudConversation(
            userId = userId,
            conversationDocId = "conv_$localConversationId"
        )
    }

    override suspend fun syncAllConversationsCleared() {
        if (!isCloudSyncAllowed()) return
        repository.clearAllCloudConversations(userId = userId)
    }
}
