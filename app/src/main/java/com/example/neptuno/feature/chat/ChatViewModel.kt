package com.example.neptuno.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.neptuno.ai.api.AiProviderRegistry
import com.example.neptuno.ai.api.AiRequest
import com.example.neptuno.ai.api.AiResponseEvent
import com.example.neptuno.audio.engine.BidirectionalAudioCoordinator
import com.example.neptuno.core.NetworkMonitor
import com.example.neptuno.core.model.AiProviderStatus
import com.example.neptuno.core.model.AssistantState
import com.example.neptuno.core.model.AvatarStyle
import com.example.neptuno.core.model.ChatMessage
import com.example.neptuno.core.model.MessageSender
import com.example.neptuno.data.local.ConversationRepository
import com.example.neptuno.data.local.MemoryRepository
import com.example.neptuno.data.local.UserPreferencesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val activeConversationId: Long? = null,
    val messages: List<ChatMessage> = emptyList(),
    val assistantState: AssistantState = AssistantState.IDLE,
    val avatarStyle: AvatarStyle = AvatarStyle.ABYSSAL_CORE,
    val activeProviderName: String = "Gemini / Gemini Live",
    val isProviderPending: Boolean = true,
    val isOffline: Boolean = false,
    val bargeInEnabled: Boolean = true,
    val persistHistoryEnabled: Boolean = true,
    val microphoneStatusBanner: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val conversationRepository: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val aiProviderRegistry: AiProviderRegistry,
    private val audioCoordinator: BidirectionalAudioCoordinator,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    private val _transientState = MutableStateFlow<AssistantState?>(null)
    private val _micStatusBanner = MutableStateFlow<String?>(null)
    private val _inMemoryMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    private var activeTurnJob: Job? = null

    private val persistedMessagesFlow = _activeConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else conversationRepository.observeMessages(id)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        _activeConversationId,
        persistedMessagesFlow,
        _inMemoryMessages,
        preferencesRepository.userPreferences,
        networkMonitor.observeNetworkStatus(),
        _transientState,
        _micStatusBanner
    ) { values ->
        val convId = values[0] as Long?
        @Suppress("UNCHECKED_CAST")
        val persisted = values[1] as List<ChatMessage>
        @Suppress("UNCHECKED_CAST")
        val ephemeral = values[2] as List<ChatMessage>
        val prefs = values[3] as com.example.neptuno.core.model.UserPreferences
        val network = values[4] as com.example.neptuno.core.NetworkStatus
        val transient = values[5] as AssistantState?
        val micBanner = values[6] as String?

        val provider = aiProviderRegistry.getActiveProvider(prefs.preferredAiProviderId)
        val offline = prefs.offlineMode || !network.isConnected
        val computedState = transient ?: if (offline) AssistantState.OFFLINE else AssistantState.IDLE

        ChatUiState(
            activeConversationId = convId,
            messages = if (prefs.persistConversationHistory) persisted else ephemeral,
            assistantState = computedState,
            avatarStyle = prefs.avatarStyle,
            activeProviderName = provider.displayName,
            isProviderPending = provider.status is AiProviderStatus.PendingIntegration,
            isOffline = offline,
            bargeInEnabled = prefs.bargeInEnabled,
            persistHistoryEnabled = prefs.persistConversationHistory,
            microphoneStatusBanner = micBanner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState()
    )

    private val _draftInput = MutableStateFlow("")
    val draftInput: StateFlow<String> = _draftInput.asStateFlow()

    init {
        viewModelScope.launch {
            val id = conversationRepository.getOrCreateActiveConversation("Sesión principal")
            _activeConversationId.value = id
        }
    }

    fun onDraftChanged(value: String) {
        _draftInput.value = value
    }

    fun selectConversation(conversationId: Long) {
        _activeConversationId.value = conversationId
        _micStatusBanner.value = null
    }

    fun startNewSession() {
        viewModelScope.launch {
            interruptActiveResponse()
            val prefs = preferencesRepository.userPreferences.first()
            if (prefs.persistConversationHistory) {
                val id = conversationRepository.createConversation("Nueva conversación")
                _activeConversationId.value = id
            } else {
                _inMemoryMessages.value = emptyList()
            }
            _micStatusBanner.value = null
        }
    }

    fun sendMessage() {
        val trimmed = _draftInput.value.trim()
        if (trimmed.isEmpty()) return
        _draftInput.value = ""

        activeTurnJob?.cancel()
        activeTurnJob = viewModelScope.launch {
            val prefs = preferencesRepository.userPreferences.first()
            val conversationId = if (prefs.persistConversationHistory) {
                conversationRepository.ensureConversationExists(
                    conversationId = _activeConversationId.value,
                    defaultTitle = "Sesión principal"
                ).also { _activeConversationId.value = it }
            } else {
                _activeConversationId.value ?: 0L
            }

            val userMsg = ChatMessage(
                conversationId = conversationId,
                sender = MessageSender.USER,
                content = trimmed,
                timestampMillis = System.currentTimeMillis()
            )

            if (prefs.persistConversationHistory) {
                val currentCount = uiState.value.messages.size
                conversationRepository.addMessage(userMsg)
                if (currentCount == 0) {
                    val autoTitle = trimmed.take(42).let { if (trimmed.length > 42) "$it…" else it }
                    conversationRepository.updateConversationTitle(conversationId, autoTitle)
                }
            } else {
                _inMemoryMessages.value = _inMemoryMessages.value + userMsg.copy(id = System.nanoTime())
            }

            _transientState.value = AssistantState.PROCESSING

            val provider = aiProviderRegistry.getActiveProvider(prefs.preferredAiProviderId)
            val memories = if (prefs.memoryAuthorized) {
                memoryRepository.observeMemories().first()
            } else {
                emptyList()
            }

            val request = AiRequest(
                conversationId = conversationId,
                userInput = trimmed,
                recentHistory = uiState.value.messages.takeLast(10),
                authorizedMemories = memories,
                personality = prefs.personalityMode,
                languageCode = prefs.language.code
            )

            try {
                provider.streamResponse(request).collect { event ->
                    when (event) {
                        is AiResponseEvent.IntegrationPending -> {
                            appendSystemOrAssistantMessage(
                                persist = prefs.persistConversationHistory,
                                message = ChatMessage(
                                    conversationId = conversationId,
                                    sender = MessageSender.SYSTEM_NOTICE,
                                    content = event.message,
                                    providerId = provider.id
                                )
                            )
                            _transientState.value = null
                        }

                        is AiResponseEvent.TextDelta -> {
                            _transientState.value = AssistantState.SPEAKING
                        }

                        is AiResponseEvent.Completed -> {
                            _transientState.value = null
                        }

                        is AiResponseEvent.Interrupted -> {
                            appendSystemOrAssistantMessage(
                                persist = prefs.persistConversationHistory,
                                message = ChatMessage(
                                    conversationId = conversationId,
                                    sender = MessageSender.SYSTEM_NOTICE,
                                    content = "Respuesta interrumpida: ${event.reason}",
                                    isInterrupted = true
                                )
                            )
                            _transientState.value = null
                        }

                        is AiResponseEvent.Failure -> {
                            _transientState.value = AssistantState.ERROR
                            appendSystemOrAssistantMessage(
                                persist = prefs.persistConversationHistory,
                                message = ChatMessage(
                                    conversationId = conversationId,
                                    sender = MessageSender.SYSTEM_NOTICE,
                                    content = "Error del proveedor: ${event.errorMessage}"
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                _transientState.value = AssistantState.ERROR
            }
        }
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        if (!granted) {
            _micStatusBanner.value =
                "Permiso de micrófono denegado. Puedes concederlo para habilitar la captura de audio en futuras fases."
            _transientState.value = null
            return
        }

        // Alterna el estado de escucha preparada sin simular transcripciones falsas
        if (_transientState.value == AssistantState.LISTENING) {
            _transientState.value = null
            _micStatusBanner.value = null
        } else {
            _transientState.value = AssistantState.LISTENING
            _micStatusBanner.value =
                "Micrófono autorizado y canal bidireccional listo. El motor STT/voz en tiempo real aún está pendiente de integración."
        }
    }

    fun interruptActiveResponse() {
        activeTurnJob?.cancel()
        activeTurnJob = null
        audioCoordinator.interruptCurrentOutput()
        viewModelScope.launch {
            val prefs = preferencesRepository.userPreferences.first()
            aiProviderRegistry.getActiveProvider(prefs.preferredAiProviderId).interruptActiveResponse()
        }
        _transientState.value = null
        _micStatusBanner.value = null
    }

    fun dismissMicBanner() {
        _micStatusBanner.value = null
        if (_transientState.value == AssistantState.LISTENING) {
            _transientState.value = null
        }
    }

    private suspend fun appendSystemOrAssistantMessage(persist: Boolean, message: ChatMessage) {
        if (persist) {
            conversationRepository.addMessage(message)
        } else {
            _inMemoryMessages.value = _inMemoryMessages.value + message.copy(id = System.nanoTime())
        }
    }

    companion object {
        fun factory(
            conversationRepository: ConversationRepository,
            memoryRepository: MemoryRepository,
            preferencesRepository: UserPreferencesRepository,
            aiProviderRegistry: AiProviderRegistry,
            audioCoordinator: BidirectionalAudioCoordinator,
            networkMonitor: NetworkMonitor
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(
                    conversationRepository = conversationRepository,
                    memoryRepository = memoryRepository,
                    preferencesRepository = preferencesRepository,
                    aiProviderRegistry = aiProviderRegistry,
                    audioCoordinator = audioCoordinator,
                    networkMonitor = networkMonitor
                ) as T
            }
        }
    }
}
