# Arquitectura de NEPTUNO (Android Nativo — Fase 2: Sistema de Voz STT/TTS y Gemini)

NEPTUNO es una aplicación Android nativa modular en Kotlin + Jetpack Compose + Material 3, compatible desde **Android 10 (API 29)** hasta **Android 16 (API 36)**, con persistencia local en Room y DataStore, reconocimiento/síntesis de voz reales y proveedor online Gemini mediante Firebase AI Logic.

## 1. Separación de módulos / paquetes

| Paquete | Responsabilidad |
|---|---|
| `app` | `NeptunoApplication`, `NeptunoAppContainer` (inyección manual de repositorios, motores STT/TTS/PCM y proveedores de IA) y `NeptunoNavHost` adaptativo (`NavigationBar` / `NavigationRail`). |
| `core` | Utilidades del sistema: `NetworkMonitor` (`ConnectivityManager`), `PermissionHelper`, `DispatcherProvider`. |
| `core.model` | Modelos de dominio (`AssistantState`, `ChatMessage`, `ConversationSession`, `MemoryEntry`, `BluetoothDeviceInfo`, `AudioRouteInfo`, `VoiceProfile`, `DeviceVoiceInfo`, `UserPreferences`, `ToolDescriptor`). |
| `data.local` | Persistencia real mediante **Room** (`NeptunoDatabase`, `ConversationDao`, `MessageDao`, `MemoryDao`, repositorios) y **Jetpack DataStore** (`UserPreferencesRepository`). |
| `ai.api` | Contratos desacoplados `AiProvider`, `AiRequest`, `AiResponseEvent` (`TextDelta`, `Completed`, `Interrupted`, `IntegrationPending`, `Failure`) y `AiProviderRegistry`. |
| `ai.gemini` | `GeminiFirebaseProvider` operativo vía Firebase AI Logic (`Firebase.ai`, `generateContentStream`, App Check en `MainActivity`) más `GeminiPendingProvider` / `GeminiLiveSessionContract` para la futura sesión WebSocket de audio bidireccional. |
| `ai.local` | `LocalEnginePendingProvider` preparado para futuros motores on-device sin conexión. |
| `audio.engine` | Implementaciones reales `AndroidSpeechToTextEngine` (`SpeechRecognizer` en hilo principal), `AndroidTextToSpeechEngine` (`TextToSpeech` + `UtteranceProgressListener`), `AndroidAudioCaptureEngine` (`AudioRecord`), `AndroidAudioPlaybackEngine` (`AudioTrack`), `BidirectionalAudioCoordinator`, catálogo dinámico `VoiceProfileCatalog`, `AndroidAudioRouteManager` y contrato `AudioRoutingController`. |
| `voice.wake` | Contrato `WakeWordEngine` para detección futura de palabra de activación. |
| `android.services` | `BluetoothDeviceController` y `AssistantServiceContract`. |
| `feature.avatar` | Personaje 2D `NeptunoAvatar` reactivo a los 6 estados: `IDLE`, `LISTENING`, `PROCESSING`, `SPEAKING`, `ERROR`, `OFFLINE`. |
| `feature.home` | Pantalla de Inicio con avatar 2D, conectividad real, Bluetooth, estado del proveedor IA y accesos rápidos. |
| `feature.chat` | Conversación por texto y voz: dictado STT parcial/final en el campo de entrada, acumulación progresiva `TextDelta`, persistencia única en Room al finalizar, lectura automática TTS, reproducción por mensaje e interrupción inmediata (`barge-in`). |
| `feature.bluetooth` | Estado real de Bluetooth, permisos Android 12+, dispositivos vinculados y detección de rutas de audio (`AudioManager`) con guardado de preferencias locales. |
| `feature.memory` | Gestión de memoria autorizada e historial de conversaciones en Room. |
| `feature.tools` | Catálogo extensible `ToolRegistry` y contrato `NeptunoTool` (pendiente de ejecución activa). |
| `feature.settings` | Personalización (avatar, personalidad, perfiles de voz vinculados a Android TTS, voces instaladas, velocidad/tono, vista previa en vivo, idioma) y Configuración (proveedor IA, modo sin conexión, lectura automática, barge-in, privacidad, tema). |

## 2. Sistema de Voz (Fase 2) y flujo de interrupción

1. **Reconocimiento de voz (STT)**:
   - `AndroidSpeechToTextEngine` usa `android.speech.SpeechRecognizer` en `Dispatchers.Main.immediate`, solicita `RECORD_AUDIO`, impide sesiones simultáneas, emite resultados parciales y finales al campo de texto de `ChatScreen`, y comprueba disponibilidad del servicio y soporte on-device (`isOnDeviceRecognitionAvailable` en API 31+).
2. **Síntesis de voz (TTS) y perfiles**:
   - `AndroidTextToSpeechEngine` inicializa `android.speech.tts.TextToSpeech`, consulta voces e idiomas instalados reales, resuelve la disponibilidad real de `VoiceProfileCatalog`, aplica velocidad (`ttsSpeechRate`), tono (`ttsPitch`) y voz instalada preferida, y soporta detención inmediata.
3. **Barge-in / Interrupción**:
   - Al pulsar **Interrumpir** (o activar el micrófono con `bargeInEnabled = true`), iniciar una nueva sesión o cambiar de conversación, `ChatViewModel` cancela la generación en curso, notifica al proveedor IA, detiene `TextToSpeech`, `AudioTrack` y `SpeechRecognizer`, y conserva el fragmento parcial marcado como interrumpido.

## 3. Proveedor de IA y capacidades aún pendientes

- **Operativo**: `GeminiFirebaseProvider` (Firebase AI Logic + Firebase App Check, streaming de texto `generateContentStream`, sin claves privadas en el repositorio).
- **Pendiente para fases posteriores**:
  - Sesión en tiempo real de audio bidireccional nativo (`GeminiLiveSessionContract` / `GeminiPendingProvider`).
  - Motor de IA local sin conexión (`LocalEnginePendingProvider`).
  - Enrutamiento activo de audio Bluetooth (`AudioRoutingController` -> `AudioManager.setCommunicationDevice` / SCO / A2DP).
  - Detección continua de palabra de activación (`WakeWordEngine`) y ejecución real de herramientas (`NeptunoTool`).

## 4. Persistencia y política conservadora de respaldos

- **Room (`neptuno_local.db`)** y **Jetpack DataStore (`neptuno_user_preferences`)** están excluidos de respaldos automáticos mediante `android:allowBackup="false"`, `res/xml/backup_rules.xml` y `res/xml/data_extraction_rules.xml`.
- Se respetan los interruptores de privacidad: si `persistConversationHistory` está desactivado, los mensajes se mantienen solo en memoria de sesión y no se escriben en Room.

## 5. Compilación y firma

- Gradle Wrapper oficial incluido (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`).
- Firma `debugConfig` para compilaciones de depuración en Google AI Studio y configuración `release` mediante variables externas (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) sin credenciales en el código.
