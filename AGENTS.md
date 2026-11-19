# AGENTS.md — Instrucciones de Arquitectura y Desarrollo para Denpa-Kun (電波くん)

## 1. Identidad y Propósito del Proyecto
- **Nombre:** Denpa-Kun (電波くん)
- **Tipo:** Transceptor Walkie-Talkie digital punto a punto (P2P), **Half-Duplex** e **Isócrono**, con capacidad adicional de recepción/transmisión de imágenes mediante audio **SSTV (Slow-Scan Television)** (referido también como SCTV/SSTV en requerimientos).
- **Objetivo Académico:** Simular y ejecutar radiocomunicación real entre 2 laptops sin router (vía Mobile Hotspot / Wi-Fi Direct / Ad-Hoc) y permitir la auditoría en tiempo real de cada trama de red mediante **Wireshark** desde una 3ra laptop.

---

## 2. Stack Tecnológico y Entorno de Compilación
- **Lenguaje:** Java 21 (JDK 21 LTS). Aprovechar características modernas como `record`, *Pattern Matching* y manejo limpio de hilos.
- **Framework UI:** JavaFX 21 (sin FXML innecesario o con FXML bien estructurado; preferir construcción programática limpia en Java + hojas de estilo CSS externas `styles.css` para máximo control visual).
- **Sistema de Construcción:** **Gradle** (Groovy DSL `build.gradle` o Kotlin DSL `build.gradle.kts`) con el plugin oficial `org.openjfx.javafxplugin` (versión `0.1.0` o compatible con Java 21).
- **IDEs Soportados:** IntelliJ IDEA y Visual Studio Code (Extension Pack for Java + Gradle for Java). Todo debe compilar y ejecutarse directamente mediante `./gradlew run` sin depender de configuraciones propietarias del IDE.
- **Dependencias:** Usar exclusivamente la biblioteca estándar del JDK (`javax.sound.sampled`, `java.net.DatagramSocket`, `java.nio.ByteBuffer`, `java.util.zip.CRC32`, `java.awt.image.BufferedImage`) y los módulos de JavaFX (`javafx.controls`, `javafx.graphics`, `javafx.swing` para conversión de imágenes). **No agregar librerías nativas externas de audio que rompan la portabilidad entre laptops.**

---

## 3. Requisitos Funcionales Obligatorios

### A. Enlace de Red Isócrono y Protocolo (Auditable en Wireshark)
- **Transporte:** `DatagramSocket` (UDP) en el puerto fijo **`50005`**.
- **Cadencia Isócrona:** Bloques de audio PCM (`16000.0f Hz`, `16-bit`, `1 canal Mono`, `Signed`, `Big-Endian`) de **640 bytes** cada uno, enviados exactamente cada **20 ms**.
- **Estructura Binaria de la Trama (21 bytes de cabecera + 640 bytes de payload = 661 bytes totales):**
  1. `Packet Type` (`1 byte`):
     - `0x00`: `START_TX` (Inicio de transmisión de voz)
     - `0x01`: `AUDIO_FRAME` (Trama isócrona de voz)
     - `0x02`: `END_TX` (Fin de transmisión / liberación de canal)
     - `0x03`: `SSTV_FRAME` (Trama de imagen codificada en tonos de audio SSTV)
  2. `Sequence Number` (`4 bytes`, `int`): Contador incremental por sesión.
  3. `Timestamp` (`8 bytes`, `long`): `System.nanoTime()` al momento de captura.
  4. `CRC32 Checksum` (`8 bytes`, `long`): Calculado mediante `java.util.zip.CRC32` sobre los primeros 13 bytes de cabecera + los 640 bytes del payload.
  5. `Payload` (`640 bytes`): Datos de audio modulado o muestras de audio SSTV.

### B. Control Half-Duplex y Prevención de Colisiones
- **Estados (`ChannelState`):** `IDLE` (Libre), `TRANSMITTING` (Transmitiendo), `RECEIVING` (Recibiendo).
- **Bloqueo Estricto:** Mientras el nodo esté en `RECEIVING`, el micrófono local y el botón *Push-to-Talk* (PTT) deben bloquearse físicamente y visualmente.
- **Anti-Colisión (CSMA básico):** Antes de pasar a `TRANSMITTING`, verificar que el estado sea `IDLE` y que no hayan llegado paquetes en los últimos 100 ms. Si ambos nodos transmiten simultáneamente, gana el paquete con el `Timestamp` menor; el otro cede el canal automáticamente.
- **Watchdog Timer (300 ms):** Si un nodo en `RECEIVING` deja de recibir tramas durante 300 ms (por ejemplo, si el simulador de fallos descarta el paquete `END_TX`), debe regresar automáticamente a `IDLE` para evitar bloqueos permanentes.

### C. Detección Real de Errores y Simulador de Fallos
- **Validación Nativa:** Al recibir cualquier paquete UDP, el receptor recalcula el `CRC32`. Si no coincide con el campo de la cabecera:
  - El paquete **NO** se reproduce en las bocinas (evitando ruido estático).
  - Se incrementa el contador de *Paquetes Corruptos* en la UI y se registra una alerta visual en el log de eventos.
- **Simulador de Fallos en Vivo (`FaultSimulator`):** Controles interactivos en la UI del emisor:
  - Slider de **Pérdida de Paquetes (`0% - 50%`)**: Descarta paquetes intencionalmente antes de `socket.send()`.
  - Botón/Toggle **Inyectar Error de Bit (Corrupción)**: Calcula el `CRC32` válido, altera un byte aleatorio del payload (`payload[idx] ^= 0xFF`) y envía el paquete alterado por red para que Wireshark lo capture y el receptor lo detecte como corrupto.
  - Slider de **Jitter / Retardo (`0 ms - 150 ms`)**: Introduce latencia variable antes del envío.

### D. Modulación de Onda Portadora
- Implementar en `SignalModulator.java` una modulación en banda base / subportadora (ej. modulación en amplitud AM o modulación por desplazamiento de frecuencia FSK/BPSK sobre las muestras PCM) antes del envío y su correspondiente demodulación al recibir, permitiendo comparar visualmente la onda de voz original frente a la señal portadora modulada.

### E. Recepción y Transmisión de Imágenes vía Audio SSTV (Slow-Scan Television)
- Implementar un módulo `SstvCodec.java` capaz de convertir una imagen pequeña (ej. `160x120` píxeles en escala de grises o RGB) en una señal de audio modulada por frecuencia (FM de audio), y viceversa:
  - **Frecuencias estándar SSTV:**
    - Pulso de sincronización horizontal/vertical: **`1200 Hz`**
    - Nivel de negro (luminancia 0): **`1500 Hz`**
    - Nivel de blanco (luminancia 255): **`2300 Hz`**
  - **Flujo:**
    1. El emisor selecciona una imagen (`.png` / `.jpg`), la redimensiona a `160x120`, genera las muestras de audio PCM con las frecuencias correspondientes por línea de píxeles y las transmite en bloques isócronos de 640 bytes (también reproducibles como sonido tipo módem/radio en las bocinas).
    2. El receptor escucha los tonos de audio SSTV (ya sea por red UDP o mediante análisis de frecuencia / cruce por cero / Goertzel sobre el buffer PCM recibido) y reconstruye la imagen línea por línea en vivo dentro de un panel visual de la interfaz (`ImageView` / `Canvas`).

---

## 4. Arquitectura de Paquetes y Clases Requerida

Mantener el código limpio, modular y bajo el paquete `com.denpakun`:

```text
src/main/java/com/denpakun/
├── Launcher.java           # Punto de entrada auxiliar para evitar errores de módulos JavaFX al ejecutar
├── MainApp.java            # Interfaz principal JavaFX + estilos + vinculación de teclado (Barra Espaciadora PTT)
├── ChannelState.java       # Enum: IDLE, TRANSMITTING, RECEIVING
├── AudioEngine.java        # Captura (TargetDataLine) y reproducción (SourceDataLine) en 16kHz/16-bit/Mono
├── SignalModulator.java    # Modulación/Demodulación de onda portadora + cálculo de muestras para osciloscopio
├── SstvCodec.java          # Codificador y decodificador de imágenes por tonos de audio SSTV (1200Hz - 2300Hz)
├── FaultSimulator.java     # Middleware de simulación: packet drop, bit-flip post-CRC y jitter
└── UdpTransceiver.java     # Sockets UDP puerto 50005, cabecera 21B, CRC32 nativo, CSMA y Watchdog 300ms