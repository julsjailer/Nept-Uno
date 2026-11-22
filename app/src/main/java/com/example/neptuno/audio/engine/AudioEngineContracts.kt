package com.example.neptuno.audio.engine

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.example.neptuno.core.model.AudioRouteDirection
import com.example.neptuno.core.model.AudioRouteInfo
import com.example.neptuno.core.model.VoiceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Contratos de la capa de audio bidireccional de Neptuno.
 * Separa captura PCM, reproducción, reconocimiento de voz (STT) y síntesis de voz (TTS).
 */
interface AudioCaptureEngine {
    val isCapturing: StateFlow<Boolean>
    suspend fun startCapture(): Result<Unit>
    suspend fun stopCapture()
}

interface AudioPlaybackEngine {
    val isPlaying: StateFlow<Boolean>
    suspend fun playPcmChunk(pcmData: ByteArray)
    suspend fun stopImmediately()
}

interface SpeechToTextEngine {
    val isIntegrationReady: Boolean
    fun observePartialTranscripts(): Flow<String>
    suspend fun startListening(): Result<Unit>
    suspend fun stopListening()
}

interface TextToSpeechEngine {
    val isIntegrationReady: Boolean
    suspend fun speak(text: String, voiceId: String): Result<Unit>
    suspend fun interrupt()
}

/**
 * Coordinador preparado para audio bidireccional e interrupción inmediata (barge-in).
 */
class BidirectionalAudioCoordinator {
    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _lastInterruptionMillis = MutableStateFlow<Long?>(null)
    val lastInterruptionMillis: StateFlow<Long?> = _lastInterruptionMillis.asStateFlow()

    fun interruptCurrentOutput() {
        _isSessionActive.value = false
        _lastInterruptionMillis.value = System.currentTimeMillis()
    }
}

/**
 * Catálogo extensible de perfiles de voz. Permite añadir motores TTS locales o remotos sin cambiar la UI.
 */
object VoiceProfileCatalog {
    val profiles: List<VoiceProfile> = listOf(
        VoiceProfile(
            id = "neptuno_ocean_neutral",
            displayName = "Neptuno · Base Oceánica",
            description = "Timbre sereno y claro. Reservado para el motor TTS principal.",
            engineType = "Contrato TTS · Pendiente",
            isAvailableNow = false
        ),
        VoiceProfile(
            id = "neptuno_abyssal_deep",
            displayName = "Abisal · Profunda",
            description = "Registro grave y pausado para sesiones técnicas prolongadas.",
            engineType = "Contrato TTS · Pendiente",
            isAvailableNow = false
        ),
        VoiceProfile(
            id = "neptuno_sonar_crisp",
            displayName = "Sonar · Precisión",
            description = "Articulación rápida pensada para respuestas concisas y conducción.",
            engineType = "Contrato Gemini Live · Pendiente",
            isAvailableNow = false
        ),
        VoiceProfile(
            id = "android_system_tts",
            displayName = "Voz del sistema Android",
            description = "Preparado para enlazarse con Android TextToSpeech en la siguiente fase.",
            engineType = "Android SDK · Preparado",
            isAvailableNow = false
        )
    )
}

/**
 * Contrato preparado para la fase de enrutamiento activo de audio (AudioManager.setCommunicationDevice / SCO / A2DP).
 * En la Fase 1 el cambio activo aún no se ejecuta; solo se detectan dispositivos y se guarda la preferencia local.
 */
interface AudioRoutingController {
    val isActiveRoutingSupported: Boolean get() = false
    suspend fun applyPreferredInputRoute(deviceId: Int): Result<Unit>
    suspend fun applyPreferredOutputRoute(deviceId: Int): Result<Unit>
}

class PendingAudioRoutingController : AudioRoutingController {
    override suspend fun applyPreferredInputRoute(deviceId: Int): Result<Unit> {
        return Result.failure(
            UnsupportedOperationException("El cambio activo de ruta de entrada se implementará en la fase de Bluetooth y audio.")
        )
    }

    override suspend fun applyPreferredOutputRoute(deviceId: Int): Result<Unit> {
        return Result.failure(
            UnsupportedOperationException("El cambio activo de ruta de salida se implementará en la fase de Bluetooth y audio.")
        )
    }
}

/**
 * Inspector real de rutas de entrada y salida de audio del sistema mediante AudioManager (API 29+).
 * No inventa dispositivos: solo reporta los que Android expone realmente.
 */
class AndroidAudioRouteManager(context: Context) {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun getAvailableInputRoutes(): List<AudioRouteInfo> {
        val manager = audioManager ?: return emptyList()
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .map { device -> device.toAudioRouteInfo(AudioRouteDirection.INPUT) }
            .distinctBy { it.id }
    }

    fun getAvailableOutputRoutes(): List<AudioRouteInfo> {
        val manager = audioManager ?: return emptyList()
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { device -> device.toAudioRouteInfo(AudioRouteDirection.OUTPUT) }
            .distinctBy { it.id }
    }

    private fun AudioDeviceInfo.toAudioRouteInfo(direction: AudioRouteDirection): AudioRouteInfo {
        val isBt = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                ))

        val label = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micrófono integrado"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Altavoz integrado"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Auricular interno"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Auriculares con cable"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Audífonos con cable"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (Voz SCO)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (Audio A2DP)"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "Audio USB"
            else -> if (isBt) "Audio Bluetooth LE" else "Dispositivo de sistema"
        }

        val name = productName?.toString()?.takeIf { it.isNotBlank() } ?: label
        return AudioRouteInfo(
            id = id,
            productName = name,
            typeLabel = label,
            direction = direction,
            isBluetooth = isBt
        )
    }
}
