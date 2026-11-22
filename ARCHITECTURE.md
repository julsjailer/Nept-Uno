# Arquitectura de NEPTUNO (Android Nativo — Fase 1 Estabilizada)

NEPTUNO está diseñado como una base Android nativa modular en Kotlin + Jetpack Compose + Material 3, compatible a partir de **Android 10 (API 29)** hasta **Android 16 (API 36)**, preparada para crecer hacia audio bidireccional, múltiples proveedores de IA, Bluetooth avanzado y herramientas extensibles sin reconstruir el proyecto.

## 1. Separación de módulos / paquetes

Cada responsabilidad reside en un paquete independiente bajo `com.example.neptuno.*` con contratos claros y dependencias controladas:

| Paquete | Responsabilidad |
|---|---|
| `app` | `NeptunoApplication`, contenedor manual de dependencias (`NeptunoAppContainer`) y grafo de navegación adaptativo (`NeptunoNavHost`). |
| `core` | Utilidades transversales del sistema: `NetworkMonitor` (`ConnectivityManager`), `PermissionHelper` (adaptación API 29–30 vs API 31+), `DispatcherProvider`. |
| `core.model` | Modelos de dominio puros (`AssistantState`, `ChatMessage`, `ConversationSession`, `MemoryEntry`, `BluetoothDeviceInfo`, `AudioRouteInfo`, `VoiceProfile`, `UserPreferences`, `ToolDescriptor`). |
| `data.local` | Persistencia real mediante **Room** (`NeptunoDatabase`, `ConversationDao`, `MessageDao`, `MemoryDao`, repositorios) y **Jetpack DataStore** (`UserPreferencesRepository`). |
| `ai.api` | Contratos desacoplados `AiProvider`, `AiRequest`, `AiResponseEvent` y `AiProviderRegistry`, incluyendo interrupción (`interruptActiveResponse`). |
| `ai.gemini` | Contrato `GeminiLiveSessionContract` y proveedor `GeminiPendingProvider` (sin claves privadas ni respuestas simuladas). |
| `ai.local` | Proveedor `LocalEnginePendingProvider` preparado para futuros motores on-device sin conexión. |
| `audio.engine` | Contratos `AudioCaptureEngine`, `AudioPlaybackEngine`, `SpeechToTextEngine`, `TextToSpeechEngine`, `AudioRoutingController`, `BidirectionalAudioCoordinator`, catálogo extensible `VoiceProfileCatalog` e inspector real `AndroidAudioRouteManager`. |
| `voice.wake` | Contrato `WakeWordEngine` para detección futura de palabra de activación. |
| `android.services` | `BluetoothDeviceController` (estado real del adaptador + dispositivos vinculados) y `AssistantServiceContract` para futuros servicios en primer plano. |
| `feature.avatar` | Personaje 2D `NeptunoAvatar` con animaciones reactivas para los 6 estados: `IDLE`, `LISTENING`, `PROCESSING`, `SPEAKING`, `ERROR`, `OFFLINE`. |
| `feature.home` | Pantalla de Inicio con personaje 2D, conectividad real, estado de Bluetooth y accesos rápidos. |
| `feature.chat` | Interfaz de conversación con historial persistido en Room, solicitud real de permiso `RECORD_AUDIO`, canal preparado para audio bidireccional y control de interrupción. |
| `feature.bluetooth` | Gestión real del estado Bluetooth, permisos Android 12+, dispositivos vinculados del sistema y detección real de rutas de audio (`AudioManager`) con guardado explícito de preferencias locales. |
| `feature.memory` | Gestión de memoria autorizada y sesiones de historial en Room con consulta y eliminación individual o total. |
| `feature.tools` | Catálogo extensible `ToolRegistry` y contrato `NeptunoTool`, marcando con transparencia las capacidades pendientes. |
| `feature.settings` | Pantallas de Personalización y Configuración sincronizadas con DataStore. |

## 2. Persistencia local

- **Room (`neptuno_local.db`)**:
  - `conversations`: sesiones de conversación con borrado en cascada y verificación de existencia (`ensureConversationExists`) antes de insertar mensajes.
  - `messages`: mensajes de usuario y avisos del sistema con marca de tiempo e indicador de interrupción.
  - `memory_entries`: preferencias y recuerdos autorizados explícitamente por el usuario.
- **Jetpack DataStore (`neptuno_user_preferences`)**:
  - Conserva voz seleccionada, personalidad, estilo de personaje, idioma, tema, modo sin conexión, interrupción de voz (barge-in), prioridad Bluetooth, autorización de memoria, persistencia de historial, preferencias de entrada/salida de audio y proveedor de IA preferido.

## 3. Privacidad y política de respaldos

Se aplica una **política conservadora de privacidad** para proteger conversaciones y memorias locales:

- **Qué NO se respalda automáticamente**:
  - La base de datos Room (`neptuno_local.db`: conversaciones, mensajes y memorias autorizadas).
  - Los archivos de preferencias de Jetpack DataStore (`datastore/`) y `sharedpref`.
- **Implementación por versión de Android**:
  - `AndroidManifest.xml` define `android:allowBackup="false"`.
  - **Android 10–11 (API 29–30)**: `res/xml/backup_rules.xml` excluye explícitamente `database`, `file/datastore/` y `sharedpref`.
  - **Android 12+ (API 31+)**: `res/xml/data_extraction_rules.xml` excluye explícitamente `database`, `file/datastore/` y `sharedpref` tanto en `<cloud-backup>` como en `<device-transfer>`.
- **Evolución futura**: esta política mantiene intacta la capacidad de incorporar en fases futuras una exportación/restauración cifrada y controlada explícitamente por el usuario.

## 4. Bluetooth y rutas de audio

- **Operativo en esta fase**:
  - Observación real del estado del adaptador Bluetooth (`ON`, `OFF`, `UNAVAILABLE`).
  - Solicitud de permisos `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` en Android 12+ (`neverForLocation`).
  - Lectura real de dispositivos vinculados (`BluetoothAdapter.bondedDevices`).
  - Consulta real de dispositivos de entrada/salida de audio (`AudioManager.getDevices(...)`).
  - Persistencia local en DataStore de la entrada y salida preferidas.
- **Pendiente para la fase Bluetooth/Audio**:
  - Cambio activo de la ruta de audio del sistema (`AudioRoutingController` -> `AudioManager.setCommunicationDevice` / SCO / A2DP). La interfaz informa explícitamente que seleccionar una ruta guarda una preferencia local y no ejecuta aún un cambio activo de ruta.

## 5. Compilación, Gradle Wrapper y firma

- **Gradle Wrapper**: el proyecto incluye el wrapper oficial (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` y `gradle/wrapper/gradle-wrapper.properties` para Gradle 9.3.1).
- **Compilación Debug**: lista para ejecutarse con `debugConfig` (`debug.keystore` generado automáticamente por el entorno de compilación).
- **Compilación Release**: requiere configurar externamente las variables de entorno `KEYSTORE_PATH` (o el archivo `my-upload-key.jks`), `STORE_PASSWORD` y `KEY_PASSWORD` con el alias `upload`. No se incluyen contraseñas ni claves privadas en el repositorio ni en el ZIP.

## 6. Preparación para Fases 2+

1. **Conectar proveedor real de IA**: implementar `AiProvider` en `ai.gemini` (Gemini / Gemini Live) o `ai.local` y registrarlo en `NeptunoAppContainer.aiProviderRegistry` sin modificar `ChatScreen` ni `ChatViewModel`.
2. **Audio bidireccional y enrutamiento activo**: implementar `AudioCaptureEngine`, `AudioPlaybackEngine`, `SpeechToTextEngine`, `TextToSpeechEngine` y `AudioRoutingController` en `audio.engine`.
3. **Herramientas operativas**: implementar `NeptunoTool` en `feature.tools` y habilitar su ejecución cuando exista proveedor IA conectado.
