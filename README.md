# Denpa-Kun (電波くん)

Transceptor digital punto a punto para dos computadoras con voz *Push-to-Talk* y envío de imágenes SSTV. Utiliza Java 21, JavaFX 21 y UDP sobre una red local directa, como un *Mobile Hotspot* o una red ad hoc.

## Ejecutar

Instala el **JDK 21** y selecciona esa instalación como `JAVA_HOME`. La primera compilación necesita internet para que el wrapper de Gradle obtenga la distribución y las dependencias.

```powershell
$env:JAVA_HOME = 'C:\ruta\al\jdk-21'
.\gradlew.bat build
.\gradlew.bat run
```

En macOS o Linux, ejecuta `sh ./gradlew build` y después `sh ./gradlew run`. En IntelliJ IDEA o VS Code, abre la carpeta como proyecto Gradle y selecciona el JDK 21.

## Enlazar dos laptops

1. Conecta las dos laptops a la misma red local. Anota la IP de la interfaz que usa cada una, por ejemplo con `ipconfig` en Windows. Si el firewall solicita acceso, autoriza Java en la red privada para el puerto UDP `50005`.
2. Abre Denpa-Kun en ambos equipos. En cada ventana escribe la IP del otro equipo y pulsa **Conectar**. El indicador muestra el estado del canal local.
3. Con el canal libre, elige **Modulación de voz**. Mantén pulsado el botón **Mantén pulsado para hablar** o la barra espaciadora con el botón enfocado. El receptor identifica automáticamente el modo del emisor. El osciloscopio muestra el PCM del micrófono y la señal modulada al transmitir, o el PCM recuperado y la señal que llegó por UDP al recibir. Durante la recepción el micrófono y el botón PTT están bloqueados.
4. Para enviar una imagen, usa **Abrir imagen** y luego **Enviar imagen**. Una imagen completa tarda aproximadamente 14,5 s y se reconstruye progresivamente en el receptor. Las franjas magenta indican bloques de píxeles perdidos o descartados por CRC en su posición original. El borde ámbar indica un pulso de sincronización ausente. Activa **Escuchar SSTV recibido** para reproducir los tonos.

La franja de recepción muestra las últimas 80 posiciones de 20 ms, con las tramas válidas en verde, las perdidas en magenta y las descartadas por CRC con secuencia identificable en ámbar. El receptor espera hasta 150 ms para resolver el reordenamiento antes de confirmar un hueco. Un `END_TX` válido delimita las pérdidas al final de la sesión. Si también falta el cierre, la cantidad de tramas posteriores a la última secuencia observada queda indeterminada.

Las pérdidas se determinan en el receptor a partir de UDP, incluso con el simulador desactivado. El contador **Descartes TX** registra los paquetes que el simulador del emisor retira antes de enviarlos. El receptor registra las tramas con CRC incorrecto y las excluye de la reproducción.

## Modulación de voz

Los tres modos modulan el PCM sobre una subportadora digital de 4 kHz. El modo predeterminado conserva el funcionamiento de la versión anterior.

| Modo | Señal transmitida |
| --- | --- |
| AM · Portadora suprimida | Doble banda lateral (DSB-SC), con mezcla coherente sobre una portadora cuadrada de 4 kHz |
| AM · Portadora completa | AM con portadora de 4 kHz presente incluso durante el silencio |
| SSB · Banda lateral superior | Traslada de 50–3400 Hz a 4050–7400 Hz por bloques de 20 ms |

Actualiza los dos equipos para usar todos los modos. SSTV mantiene su formato de 160 × 120 píxeles en escala de grises. Los pulsos se envían a 1200 Hz y la luminancia se codifica entre 1500 y 2300 Hz.

## Auditar en Wireshark

Aplica el filtro de visualización `udp.port == 50005`. Para inspeccionar solo las tramas del protocolo, añade `udp.length == 669` (661 bytes de datos más 8 de cabecera UDP). Una tercera laptop necesita visibilidad del tráfico unicast mediante una captura en modo monitor compatible con la red y su cifrado, un puerto espejo o una captura compartida desde uno de los extremos.

| Desplazamiento en los datos UDP | Bytes | Campo |
| ---: | ---: | --- |
| 0 | 1 | Tipo. `0x00` inicio, `0x01` voz, `0x02` fin, `0x03` SSTV |
| 1 | 4 | Secuencia big-endian. `START_TX` usa cero |
| 5 | 8 | Marca `System.nanoTime()` big-endian del emisor |
| 13 | 8 | Valor CRC32 en un `long` big-endian |
| 21 | 640 | PCM modulado de voz, tonos SSTV o metadatos de control |

El CRC se calcula sobre los bytes 0–12 y 21–660. Con jitter desactivado, el emisor programa una trama de 320 muestras PCM mono de 16 bits big-endian cada 20 ms a 16 kHz. Los intervalos observados en la captura dependen del planificador y de la red. El indicador **Jitter RX** estima la variación del espaciado.

En `START_TX` y `END_TX`, los primeros cuatro bytes del payload contienen `DNPK`, seguidos del tipo de trama de datos (`0x01` para voz, `0x03` para SSTV) y del identificador de modo (`0` para DSB-SC o SSTV, `1` para AM con portadora completa, `2` para SSB-USB). Para AM con portadora completa y SSB-USB, las primeras 64 muestras de cada trama de voz guardan la firma `0xA9C631F269578EBC | modo` en sus bits menos significativos, con el bit más significativo de la firma primero. Esto permite identificar el modo aunque se pierda el inicio de la sesión.

El control half-duplex comprueba que hayan pasado 100 ms sin tráfico. En una colisión se compara el valor bruto de la marca de inicio y se desempata por dirección IP. El origen de `System.nanoTime()` es local a cada JVM, por lo que el resultado arbitra el canal y no mide cuál laptop pulsó primero. Tras 300 ms sin tramas de la sesión en recepción, el watchdog libera el canal.
