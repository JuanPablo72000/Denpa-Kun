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
3. Mantén pulsado el botón **Mantén pulsado para hablar** o la barra espaciadora con el botón enfocado. Suelta el control al terminar. Durante la recepción el micrófono está cerrado y el botón está bloqueado.
4. Para enviar una imagen, usa **Abrir imagen** y luego **Enviar imagen**. La transmisión de una imagen completa tarda aproximadamente 14,5 s. La imagen aparece progresivamente en el equipo receptor. Activa **Escuchar SSTV recibido** para reproducir los tonos.

El simulador del emisor aplica pérdida, jitter o corrupción después de calcular el CRC. El receptor descarta las tramas corruptas, incrementa el contador y registra su secuencia. Para ver el watchdog, prueba con pérdida simulada y observa el registro cuando se descarte `END_TX`.

## Auditar en Wireshark

Aplica el filtro de visualización `udp.port == 50005`. Para inspeccionar solo las tramas del protocolo, añade `udp.length == 669` (661 bytes de datos más 8 de cabecera UDP). Una tercera laptop necesita visibilidad del tráfico unicast mediante una captura en modo monitor compatible con la red y su cifrado, un puerto espejo o una captura compartida desde uno de los extremos.

| Desplazamiento en los datos UDP | Bytes | Campo |
| ---: | ---: | --- |
| 0 | 1 | Tipo. `0x00` inicio, `0x01` voz, `0x02` fin, `0x03` SSTV |
| 1 | 4 | Secuencia big-endian. `START_TX` usa cero |
| 5 | 8 | Marca `System.nanoTime()` big-endian del emisor |
| 13 | 8 | Valor CRC32 en un `long` big-endian |
| 21 | 640 | PCM de voz o SSTV. Los paquetes de control llevan relleno |

El CRC se calcula sobre los bytes 0–12 y 21–660. Con jitter desactivado, el emisor programa una trama de 320 muestras PCM mono de 16 bits big-endian cada 20 ms a 16 kHz. Los intervalos observados en la captura dependen del planificador y de la red. El indicador **Jitter RX** estima la variación del espaciado.

El control half-duplex comprueba que hayan pasado 100 ms sin tráfico. En una colisión se compara el valor bruto de la marca de inicio y se desempata por dirección IP. El origen de `System.nanoTime()` es local a cada JVM, por lo que el resultado arbitra el canal y no mide cuál laptop pulsó primero. Tras 300 ms sin tramas válidas en recepción, el watchdog libera el canal.

La voz usa modulación AM coherente con una subportadora cuadrada de 4 kHz. SSTV usa un formato de Denpa-Kun de 160 × 120 píxeles en escala de grises. El códec estima las frecuencias PCM de cada línea, con sincronización a 1200 Hz y luminancia entre 1500 y 2300 Hz.
