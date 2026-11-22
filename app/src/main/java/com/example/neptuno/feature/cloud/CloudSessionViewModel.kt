package com.example.neptuno.feature.cloud

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.neptuno.core.NetworkMonitor
import com.example.neptuno.data.local.ConversationRepository
import com.example.neptuno.data.local.MemoryRepository
import com.example.neptuno.data.local.UserPreferencesRepository
import com.example.neptuno.data.remote.CloudSyncGateway
import com.example.neptuno.data.remote.CloudUiState
import com.example.neptuno.data.remote.FirestoreCloudSyncGateway
import com.example.neptuno.data.remote.FirestoreConversation
import com.example.neptuno.data.remote.FirestoreMemoryEntry
import com.example.neptuno.data.remote.FirestoreUserProfile
import com.example.neptuno.data.remote.NeptunoFirestoreRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CloudAccountSummary(
    val userId: String,
    val displayName: String,
    val email: String?,
    val isSyncing: Boolean = false,
    val lastSyncMessage: String? = null
)

class CloudSessionViewModel(
    private val currentUserId: String,
    private val displayName: String,
    private val email: String?,
    private val firestoreRepository: NeptunoFirestoreRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    val cloudSyncGateway: CloudSyncGateway = FirestoreCloudSyncGateway(
        userId = currentUserId,
        repository = firestoreRepository,
        preferencesRepository = preferencesRepository,
        networkMonitor = networkMonitor
    )

    private val _accountSummary = MutableStateFlow(
        CloudAccountSummary(
            userId = currentUserId,
            displayName = displayName.ifBlank { "Usuario Neptuno" },
            email = email
        )
    )
    val accountSummary: StateFlow<CloudAccountSummary> = _accountSummary.asStateFlow()

    val profileState: StateFlow<CloudUiState<FirestoreUserProfile?>> =
        firestoreRepository.observeUserProfile(currentUserId)
            .map<FirestoreUserProfile?, CloudUiState<FirestoreUserProfile?>> { CloudUiState.Success(it) }
            .catch { error ->
                val firestoreError = NeptunoFirestoreRepository.unwrapFirestoreException(error)
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing user profile", rootError)
                emit(CloudUiState.Error(rootError.localizedMessage ?: "No se pudo sincronizar el perfil."))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = CloudUiState.Loading
            )

    val cloudMemoriesState: StateFlow<CloudUiState<List<FirestoreMemoryEntry>>> =
        firestoreRepository.observeMemories(currentUserId)
            .map<List<FirestoreMemoryEntry>, CloudUiState<List<FirestoreMemoryEntry>>> { CloudUiState.Success(it) }
            .catch { error ->
                val firestoreError = NeptunoFirestoreRepository.unwrapFirestoreException(error)
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing cloud memories", rootError)
                emit(CloudUiState.Error(rootError.localizedMessage ?: "No se pudieron leer las memorias en la nube."))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = CloudUiState.Loading
            )

    val cloudConversationsState: StateFlow<CloudUiState<List<FirestoreConversation>>> =
        firestoreRepository.observeConversations(currentUserId)
            .map<List<FirestoreConversation>, CloudUiState<List<FirestoreConversation>>> { CloudUiState.Success(it) }
            .catch { error ->
                val firestoreError = NeptunoFirestoreRepository.unwrapFirestoreException(error)
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing cloud conversations", rootError)
                emit(CloudUiState.Error(rootError.localizedMessage ?: "No se pudieron leer las conversaciones en la nube."))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = CloudUiState.Loading
            )

    init {
        syncProfileAndLocalState()
    }

    fun syncProfileAndLocalState() {
        viewModelScope.launch {
            _accountSummary.value = _accountSummary.value.copy(
                isSyncing = true,
                lastSyncMessage = null
            )
            val prefs = preferencesRepository.userPreferences.first()
            val network = networkMonitor.observeNetworkStatus().first()
            if (prefs.offlineMode || !network.isConnected) {
                _accountSummary.value = _accountSummary.value.copy(
                    isSyncing = false,
                    lastSyncMessage = "Sin conexión o modo offline activo: usando caché local Room."
                )
                return@launch
            }

            val profileResult = firestoreRepository.upsertUserProfile(
                userId = currentUserId,
                displayName = displayName,
                email = email,
                languageCode = prefs.language.code,
                personalityId = prefs.personalityMode.id,
                avatarStyleId = prefs.avatarStyle.id
            )

            var syncErrors = 0
            if (profileResult.isFailure) syncErrors += 1

            if (prefs.memoryAuthorized) {
                val localMemories = memoryRepository.observeMemories().first()
                for (memory in localMemories) {
                    if (memory.authorizedByUser) {
                        val res = firestoreRepository.saveMemory(
                            userId = currentUserId,
                            title = memory.title,
                            content = memory.content,
                            category = memory.category,
                            localId = memory.id
                        )
                        if (res.isFailure) syncErrors += 1
                    }
                }
            }

            if (prefs.persistConversationHistory) {
                val localConversations = conversationRepository.observeConversations().first()
                for (session in localConversations) {
                    val res = firestoreRepository.ensureCloudConversation(
                        userId = currentUserId,
                        localConversationId = session.id,
                        title = session.title
                    )
                    if (res.isFailure) syncErrors += 1
                }
            }

            _accountSummary.value = _accountSummary.value.copy(
                isSyncing = false,
                lastSyncMessage = if (syncErrors == 0) {
                    "Sincronización con Cloud Firestore completada."
                } else {
                    "Sincronización parcial ($syncErrors error(es) de red o permisos)."
                }
            )
        }
    }

    fun deleteCloudMemory(memoryDocId: String) {
        viewModelScope.launch {
            firestoreRepository.deleteMemory(currentUserId, memoryDocId)
        }
    }

    fun deleteCloudConversation(conversationDocId: String) {
        viewModelScope.launch {
            firestoreRepository.deleteCloudConversation(currentUserId, conversationDocId)
        }
    }

    companion object {
        private const val TAG = "CloudSessionVM"
        private const val STOP_TIMEOUT_MILLIS = 5000L

        fun factory(
            currentUserId: String,
            displayName: String,
            email: String?,
            preferencesRepository: UserPreferencesRepository,
            memoryRepository: MemoryRepository,
            conversationRepository: ConversationRepository,
            networkMonitor: NetworkMonitor
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as Application
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                CloudSessionViewModel(
                    currentUserId = currentUserId,
                    displayName = displayName,
                    email = email,
                    firestoreRepository = NeptunoFirestoreRepository(db),
                    preferencesRepository = preferencesRepository,
                    memoryRepository = memoryRepository,
                    conversationRepository = conversationRepository,
                    networkMonitor = networkMonitor
                )
            }
        }
    }
}
