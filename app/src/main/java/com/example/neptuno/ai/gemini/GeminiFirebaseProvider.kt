package com.example.neptuno.ai.gemini

import android.util.Log
import com.example.neptuno.ai.api.AiProvider
import com.example.neptuno.ai.api.AiProviderCapabilities
import com.example.neptuno.ai.api.AiRequest
import com.example.neptuno.ai.api.AiResponseEvent
import com.example.neptuno.core.model.AiProviderStatus
import com.example.neptuno.core.model.MessageSender
import com.example.neptuno.core.model.PersonalityMode
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

private const val TAG = "GeminiFirebaseProvider"
private const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"
private const val STREAM_TIMEOUT_MS = 60_000L

/**
 * Proveedor online oficial de Gemini mediante Firebase AI Logic (`Firebase.ai`).
 * Implementa streaming de texto (`generateContentStream`), respetando:
 * - Cero API keys privadas en el cliente (autenticado vía Firebase App Check).
 * - Diferenciación clara respecto a Gemini Live (audio bidireccional aún pendiente).
 * - Cancelación cooperativa para barge-in y timeouts realistas de 60s.
 */
class GeminiFirebaseProvider : AiProvider {

    override val id: String = "gemini_firebase"
    override val displayName: String = "Gemini (Texto y Streaming)"
    override val capabilities: AiProviderCapabilities = AiProviderCapabilities(
        supportsStreamingText = true,
        supportsBidirectionalAudio = false,
        supportsOfflineExecution = false,
        supportsToolCalling = false
    )
    override val status: AiProviderStatus = AiProviderStatus.Ready

    @Volatile
    private var interruptedByUser: Boolean = false

    override fun streamResponse(request: AiRequest): Flow<AiResponseEvent> = flow {
        interruptedByUser = false
        val systemText = buildSystemInstruction(request)
        val model = Firebase.ai.generativeModel(
            modelName = DEFAULT_GEMINI_MODEL,
            systemInstruction = content { text(systemText) }
        )

        val promptContents = buildConversationContents(request)
        var receivedAnyChunk = false

        try {
            withTimeout(STREAM_TIMEOUT_MS) {
                model.generateContentStream(promptContents).collect { chunk ->
                    if (interruptedByUser) {
                        throw CancellationException("Interrupción solicitada por el usuario.")
                    }
                    val delta = chunk.text.orEmpty()
                    if (delta.isNotEmpty()) {
                        receivedAnyChunk = true
                        emit(AiResponseEvent.TextDelta(delta))
                    }
                }
            }

            if (interruptedByUser) {
                emit(AiResponseEvent.Interrupted("Respuesta detenida por el usuario."))
            } else if (!receivedAnyChunk) {
                emit(
                    AiResponseEvent.Failure(
                        "Gemini devolvió una respuesta vacía o incompleta. Intenta reformular tu mensaje."
                    )
                )
            } else {
                emit(AiResponseEvent.Completed)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini streaming timed out", e)
            emit(
                AiResponseEvent.Failure(
                    "La conexión con Gemini agotó el tiempo de espera (60 s). Verifica tu red e inténtalo de nuevo."
                )
            )
        } catch (e: CancellationException) {
            if (interruptedByUser) {
                emit(AiResponseEvent.Interrupted("Respuesta interrumpida por el usuario."))
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini streaming failed", e)
            emit(AiResponseEvent.Failure(mapProviderException(e)))
        }
    }

    override suspend fun interruptActiveResponse() {
        interruptedByUser = true
    }

    private fun buildSystemInstruction(request: AiRequest): String {
        val languageName = when (request.languageCode.lowercase()) {
            "es" -> "español"
            "en" -> "inglés"
            "pt" -> "portugués"
            else -> request.languageCode
        }

        val personaRule = when (request.personality) {
            PersonalityMode.ANALYTICAL ->
                "Mantén un tono analítico, preciso y estructurado, ideal para conversación hablada clara."
            PersonalityMode.CALM_GUIDE ->
                "Mantén un tono sereno, cercano y pausado para el acompañamiento diario."
            PersonalityMode.CONCISE ->
                "Responde de forma breve, directa y accionable, optimizada para lectura en voz alta."
            PersonalityMode.EXPLORER ->
                "Mantén un tono curioso y creativo que proponga ideas útiles sin ser verboso."
        }

        val memoriesSection = if (request.authorizedMemories.isNotEmpty()) {
            val formatted = request.authorizedMemories.take(10).joinToString("\n") {
                "- [${it.category.displayName}] ${it.title}: ${it.content}"
            }
            "\n\nMemorias autorizadas por el usuario (úsalas solo si son relevantes):\n$formatted"
        } else {
            ""
        }

        return """
            Eres NEPTUNO, un asistente personal de inteligencia artificial con identidad tecnológica oceánica.
            Responde siempre en $languageName salvo que el usuario pida otro idioma.
            $personaRule
            Tu respuesta puede reproducirse mediante síntesis de voz (TTS), así que evita listas excesivamente largas o formato Markdown innecesario cuando una explicación natural sea más clara.
            No afirmes tener acceso a herramientas del dispositivo ni a audio bidireccional nativo si no han sido ejecutados.$memoriesSection
        """.trimIndent()
    }

    private fun buildConversationContents(request: AiRequest): List<Content> {
        val contents = mutableListOf<Content>()
        request.recentHistory
            .filter { it.sender == MessageSender.USER || it.sender == MessageSender.ASSISTANT }
            .takeLast(12)
            .forEach { msg ->
                val role = if (msg.sender == MessageSender.USER) "user" else "model"
                if (msg.content.isNotBlank()) {
                    contents += content(role) { text(msg.content) }
                }
            }

        val lastIsSameUserInput = request.recentHistory.lastOrNull()?.let {
            it.sender == MessageSender.USER && it.content.trim() == request.userInput.trim()
        } ?: false

        if (!lastIsSameUserInput && request.userInput.isNotBlank()) {
            contents += content("user") { text(request.userInput.trim()) }
        }
        return contents
    }

    private fun mapProviderException(e: Exception): String {
        val raw = e.localizedMessage.orEmpty()
        return when {
            raw.contains("403") || raw.contains("App Check", ignoreCase = true) ->
                "Acceso rechazado por Firebase App Check (HTTP 403). Verifica la configuración de App Check en AI Studio."
            raw.contains("429") || raw.contains("quota", ignoreCase = true) || raw.contains("rate", ignoreCase = true) ->
                "Se alcanzó temporalmente el límite de solicitudes de Gemini. Espera un momento antes de reintentar."
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("network", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) ->
                "Error de red al conectar con Gemini. Comprueba tu conexión a Internet."
            raw.isNotBlank() ->
                "Error de Gemini (Firebase AI): $raw"
            else ->
                "No se pudo completar la solicitud con Gemini."
        }
    }
}
