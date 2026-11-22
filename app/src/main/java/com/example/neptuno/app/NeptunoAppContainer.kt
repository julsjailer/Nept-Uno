package com.example.neptuno.app

import android.content.Context
import com.example.neptuno.ai.api.AiProviderRegistry
import com.example.neptuno.ai.gemini.GeminiPendingProvider
import com.example.neptuno.ai.local.LocalEnginePendingProvider
import com.example.neptuno.android.services.BluetoothDeviceController
import com.example.neptuno.audio.engine.AndroidAudioRouteManager
import com.example.neptuno.audio.engine.BidirectionalAudioCoordinator
import com.example.neptuno.core.DefaultDispatcherProvider
import com.example.neptuno.core.DispatcherProvider
import com.example.neptuno.core.NetworkMonitor
import com.example.neptuno.data.local.ConversationRepository
import com.example.neptuno.data.local.LocalConversationRepository
import com.example.neptuno.data.local.LocalMemoryRepository
import com.example.neptuno.data.local.MemoryRepository
import com.example.neptuno.data.local.NeptunoDatabase
import com.example.neptuno.data.local.UserPreferencesRepository
import com.example.neptuno.feature.tools.ToolRegistry
import com.example.neptuno.voice.wake.PendingWakeWordEngine
import com.example.neptuno.voice.wake.WakeWordEngine

/**
 * Contenedor manual de dependencias de NEPTUNO.
 * Centraliza la creación de repositorios, controladores del sistema y contratos de futuras integraciones.
 */
class NeptunoAppContainer(context: Context) {

    private val appContext = context.applicationContext

    val dispatchers: DispatcherProvider = DefaultDispatcherProvider()

    val database: NeptunoDatabase by lazy {
        NeptunoDatabase.getInstance(appContext)
    }

    val conversationRepository: ConversationRepository by lazy {
        LocalConversationRepository(
            conversationDao = database.conversationDao(),
            messageDao = database.messageDao()
        )
    }

    val memoryRepository: MemoryRepository by lazy {
        LocalMemoryRepository(
            memoryDao = database.memoryDao()
        )
    }

    val preferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(appContext)
    }

    val networkMonitor: NetworkMonitor by lazy {
        NetworkMonitor(appContext)
    }

    val bluetoothController: BluetoothDeviceController by lazy {
        BluetoothDeviceController(appContext)
    }

    val audioRouteManager: AndroidAudioRouteManager by lazy {
        AndroidAudioRouteManager(appContext)
    }

    val audioRoutingController: com.example.neptuno.audio.engine.AudioRoutingController by lazy {
        com.example.neptuno.audio.engine.PendingAudioRoutingController()
    }

    val audioCoordinator: BidirectionalAudioCoordinator by lazy {
        BidirectionalAudioCoordinator()
    }

    val aiProviderRegistry: AiProviderRegistry by lazy {
        AiProviderRegistry(
            providers = listOf(
                GeminiPendingProvider(),
                LocalEnginePendingProvider()
            )
        )
    }

    val wakeWordEngine: WakeWordEngine by lazy {
        PendingWakeWordEngine()
    }

    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry()
    }
}
