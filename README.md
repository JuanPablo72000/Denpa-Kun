# Denpa-Kun (電波くん)

Transceptor digital punto a punto para dos laptops. Envía voz con *Push-to-Talk* e imágenes en escala de grises mediante tonos SSTV. Funciona en una red local directa, por ejemplo con un punto de acceso móvil, y usa UDP en el puerto fijo `50005`.

## Compilar y ejecutar

Instala el **JDK 21** en las dos laptops. El proyecto incluye el wrapper de Gradle, que descarga Gradle y JavaFX durante la primera compilación. Una vez descargadas las dependencias, el enlace entre las laptops usa la red local.

En Windows, abre PowerShell en la raíz del proyecto. Ajusta la ruta de `JAVA_HOME` a tu instalación.

```powershell
$env:JAVA_HOME = 'C:\ruta\al\jdk-21'
& "$env:JAVA_HOME\bin\java.exe" -version
.\gradlew.bat build
.\gradlew.bat run
```

En macOS o Linux, configura `JAVA_HOME` para el JDK 21 y ejecuta `sh ./gradlew build` y `sh ./gradlew run`. En IntelliJ IDEA o VS Code, abre el proyecto como Gradle y selecciona el JDK 21. Usa la misma versión de Denpa-Kun en ambas laptops para que puedan intercambiar los tres modos de voz.

## Conectar dos laptops

1. **Crea la red local.** En la laptop A, abre **Configuración → Red e Internet → Zona con cobertura inalámbrica móvil** en Windows y activa la función. En la laptop B, conéctate por Wi-Fi al nombre de red que muestra A. También puedes usar una red local existente que permita tráfico entre los equipos.
2. **Identifica las dos IPv4.** Ejecuta `ipconfig` en ambas laptops y busca la dirección IPv4 del adaptador asociado a esa red. En A puede aparecer bajo un adaptador virtual. En B, la puerta de enlace de la conexión al punto de acceso suele ser la IPv4 de A. Usa las direcciones que muestran los equipos.
3. **Autoriza el puerto.** Si el firewall solicita acceso al iniciar la aplicación, permite Java en el perfil de red que usa el enlace o habilita una regla de entrada UDP para el puerto local `50005` en ese perfil.
4. **Configura el destino en cada laptop.** En **IP o nombre del equipo remoto**, escribe la IP de la otra laptop y pulsa **Conectar**. Por ejemplo, si A tiene `192.168.137.1` y B tiene `192.168.137.24`, escribe `192.168.137.24` en A y `192.168.137.1` en B. El puerto ya está configurado en la aplicación.
5. **Prueba en ambos sentidos.** Deja los controles del simulador en cero y la corrupción desactivada. Mantén pulsado **Mantén pulsado para hablar** en A, comprueba que B muestre **Recibiendo** y escuche la voz, y luego repite de B a A. **Canal libre** confirma que se abrió el socket local. El primer intercambio comprueba que el tráfico atraviesa la red.

### Si el enlace falla

| Síntoma | Qué comprobar |
| --- | --- |
| Ambas laptops muestran **Canal libre** y nunca aparece **Recibiendo** en el destino | Comprueba que cada una tenga la IPv4 de la otra, que sigan en la misma red y que Windows permita la entrada UDP en `50005`. Algunas redes de invitados aíslan a sus clientes. |
| Aparece **No se pudo abrir el enlace** | Revisa el mensaje en **Eventos**. Si el puerto está ocupado, cierra la segunda instancia o consulta qué proceso lo usa con `Get-NetUDPEndpoint -LocalPort 50005` en PowerShell. |
| **Válidos** aumenta y los altavoces siguen en silencio | Revisa el volumen y el dispositivo de salida en el receptor, los permisos del micrófono en el emisor y el panel **Eventos** en ambos. |
| Wireshark registra la llegada y **Válidos** sigue en cero | Comprueba el firewall y que la IP remota configurada coincida con la IP de origen de los datagramas. |

## Enviar voz e imágenes

**Voz.** Selecciona **Modulación de voz** con el canal libre. Mantén pulsado el botón PTT o la barra espaciadora con el botón enfocado y suéltalo al terminar. El receptor identifica automáticamente el modo anunciado por el emisor. El enlace es half-duplex. Durante la recepción, el micrófono local y el PTT quedan bloqueados.

| Modo de voz | Señal transmitida |
| --- | --- |
| AM · Portadora suprimida (DSB-SC) | Modo predeterminado. Usa mezcla coherente con una subportadora cuadrada de 4 kHz. |
| AM · Portadora completa | Mantiene la portadora de 4 kHz presente durante el silencio. |
| SSB · Banda lateral superior (SSB-USB) | Traslada la banda de 50–3400 Hz a 4050–7400 Hz en bloques de 20 ms. |

El **Osciloscopio** muestra el PCM del micrófono y la señal modulada al transmitir. Al recibir muestra la señal que llegó por UDP y el PCM demodulado. Para comparar los modos, transmite unos segundos en cada uno y observa la gráfica de la señal modulada.

**Imágenes.** Pulsa **Abrir imagen**, elige un PNG o JPG y luego pulsa **Enviar imagen**. La aplicación adapta la imagen a 160 × 120 píxeles en escala de grises y la transmite en aproximadamente 14,5 s. El receptor la reconstruye por líneas. Activa **Escuchar SSTV recibido** para oír los tonos. SSTV usa pulsos de sincronización a 1200 Hz y tonos de 1500 a 2300 Hz para representar la escala de negro a blanco.

En la imagen recibida, cada bloque de píxeles confirmado como perdido o descartado por CRC se marca en **magenta** en su posición original. Un marcador **ámbar** indica la falta de un pulso de sincronización. El estado bajo la imagen indica cuántas líneas tienen datos y cuántas están afectadas.

## Interpretar los contadores y probar fallos

| Indicador | Qué mide |
| --- | --- |
| **Enviados** | Datagramas que el emisor entregó al socket UDP, incluidos inicio y cierre. |
| **Válidos** | Datagramas recibidos con cabecera y CRC válidos, incluidos inicio y cierre. |
| **Descartes TX** | Datagramas que el simulador retiró en el emisor antes de enviarlos. Incluye paquetes de control. |
| **Perdidos** | Tramas de voz o SSTV ausentes cuando venció la espera de reordenamiento. Los errores CRC que pueden asociarse a una secuencia se cuentan por separado. |
| **Corruptos** | Datagramas recibidos cuyo CRC32 de Denpa-Kun falló. Se registran en **Eventos** y se excluyen de la reproducción. |
| **Jitter RX** | Estimación de cuánto varía el espaciado de llegada respecto al de captura. |

La franja de recepción muestra las últimas 80 posiciones de 20 ms de la sesión. Verde indica trama válida, magenta indica pérdida y ámbar indica un rechazo por CRC con secuencia identificable. El receptor espera hasta 150 ms para resolver el reordenamiento. Una trama demasiado tardía puede aparecer en Wireshark después de que la app ya marcó su posición como perdida. Si llega un `END_TX` válido, su secuencia también permite detectar huecos al final. Si falta el cierre, la cantidad de tramas posteriores a la última posición comprobable queda indeterminada.

Para medir la red, mantén el simulador apagado. Para reproducir un fallo de forma controlada, cambia **un control a la vez** en el emisor durante un envío SSTV.

| Prueba | Qué observar |
| --- | --- |
| **Pérdida de paquetes (0–50 %)** | Con un valor intermedio sube **Descartes TX**. Las secuencias de las tramas descartadas faltan en la captura del emisor y pueden aparecer como huecos magenta en el receptor. |
| **Inyectar corrupción después del CRC** | Actívala unos segundos a mitad del envío y desactívala antes de que termine. Los datagramas de ese tramo aparecen en Wireshark y el receptor incrementa **Corruptos**. La casilla también afecta a los paquetes de inicio y cierre. |
| **Jitter máximo (0–150 ms)** | Observa cómo cambian los intervalos entre paquetes en Wireshark y el **Jitter RX** en el receptor. Varias tramas pueden llegar juntas. |

## Auditar en Wireshark

### Capturar una sesión

1. Abre Wireshark en una de las laptops y selecciona la **interfaz que tiene la IPv4 del enlace**. En Windows, instala Npcap si Wireshark lo solicita. Antes de iniciar la captura puedes usar el filtro de captura `udp port 50005`.
2. Inicia la captura antes de pulsar PTT o **Enviar imagen**. Para la primera prueba, deja el simulador apagado y transmite en un solo sentido. Suelta PTT o deja terminar la imagen antes de detener la captura.
3. Aplica el filtro de visualización `udp.port == 50005 && udp.length == 669`. Los 669 bytes incluyen la cabecera UDP de 8 bytes y los 661 bytes de Denpa-Kun. Para aislar A → B con las IPv4 de ejemplo, añade `&& ip.src == 192.168.137.1 && ip.dst == 192.168.137.24`.
4. Guarda la evidencia en formato `.pcapng`. Puedes abrirla en una tercera laptop. Para ver el enlace **en vivo** desde un tercer equipo, necesitas que la interfaz de captura tenga acceso a ese tráfico unicast, por ejemplo mediante modo monitor compatible y descifrado de la red propia, o un puerto espejo cuando la topología lo permita. Estar conectado al mismo punto de acceso normalmente solo deja ver el tráfico destinado al tercer equipo y el tráfico de difusión.

### Leer la secuencia y la cadencia

Expande **User Datagram Protocol** y selecciona el campo **UDP payload**. Los desplazamientos de esta tabla empiezan en el primer byte de los datos UDP.

| Bytes | Campo | Cómo interpretarlo |
| ---: | --- | --- |
| 0 | Tipo | `0x00` inicio, `0x01` voz, `0x02` cierre, `0x03` SSTV. |
| 1–4 | Secuencia | Entero big-endian. `START_TX` tiene secuencia 0, las tramas de datos empiezan en 1 y `END_TX` usa la secuencia siguiente a la última generada. |
| 5–12 | Marca de tiempo | `System.nanoTime()` del emisor en big-endian. Sirve para medir intervalos entre tramas del mismo emisor. |
| 13–20 | CRC32 de Denpa-Kun | Valor CRC32 almacenado en un entero de 8 bytes big-endian. |
| 21–660 | Carga útil | Bloque PCM modulado de voz, tonos SSTV o metadatos de control. |

Para filtrar por tipo, añade `&& udp.payload[0] == 0x01` para voz o `&& udp.payload[0] == 0x03` para SSTV. Para verificar la cadencia, filtra un solo sentido y tipo de datos y selecciona **View → Time Display Format → Seconds Since Previous Displayed Packet**. Durante un tramo continuo, sin jitter ni pérdida, los intervalos deberían agruparse alrededor de **0,020 s**. Cada trama contiene 320 muestras PCM mono de 16 bits big-endian a 16 kHz. La planificación del sistema, la red y la captura pueden alterar el intervalo observado.

En una imagen completa, espera **722 tramas SSTV**, con secuencias 1–722, y un cierre con secuencia 723. Si ves un salto, revisa si la trama apareció desordenada. Compara capturas simultáneas de los dos extremos para localizar el hueco. Si la trama figura en el emisor y falta en el receptor, comprueba la interfaz y los descartes de captura antes de atribuirla al enlace. Si falta en ambas y subió **Descartes TX**, el emisor la retiró antes de enviarla.

### Comprobar el CRC32 de un datagrama

Selecciona un paquete de 661 bytes de datos y copia en hexadecimal el valor de **UDP payload**. Si tienes TShark, también puedes extraer ese valor desde una captura. Cambia `123` por el número de la trama seleccionada.

```powershell
tshark -r receptor.pcapng -Y "frame.number == 123 && udp.port == 50005 && udp.length == 669" -T fields -e udp.payload
```

Con Python 3 puedes comparar el CRC almacenado en la trama con el recalculado. Guarda este bloque como `verificar_crc.py`, ejecútalo con `python verificar_crc.py` y pega el valor hexadecimal cuando se solicite.

```python
from zlib import crc32

datos = bytes.fromhex(input("UDP payload en hexadecimal: ").replace(":", ""))
if len(datos) != 661:
    raise SystemExit("Se esperaban los 661 bytes de datos UDP.")

guardado = int.from_bytes(datos[13:21], "big")
calculado = crc32(datos[:13] + datos[21:])
print(f"Guardado: 0x{guardado:016x}  Calculado: 0x{calculado:016x}")
print("CRC válido" if guardado == calculado else "CRC incorrecto")
```

Wireshark muestra también el checksum del protocolo UDP. Cuando se inyecta una alteración después de calcular el CRC de Denpa-Kun, el sistema todavía puede calcular un checksum UDP válido para los bytes alterados. La comprobación de arriba reproduce la validación que hace la aplicación.

### Identificar el modo y el cierre del canal

En `START_TX` y `END_TX`, los bytes 21–24 contienen `DNPK` en ASCII. El byte 25 indica el tipo de datos (`0x01` para voz, `0x03` para SSTV) y el byte 26 identifica el modo (`0` para DSB-SC o SSTV, `1` para AM completa, `2` para SSB-USB). Las tramas de voz AM completa y SSB-USB repiten ese identificador en los bits menos significativos de las primeras 64 muestras mediante la firma `0xA9C631F269578EBC | modo`, con el bit más significativo de la firma primero. El receptor puede determinar el modo aunque falte el inicio.

Para tomar el canal, deben haber pasado al menos 100 ms desde el último datagrama recibido. Si ambos equipos reclaman el canal a la vez, se arbitra con el valor bruto de la marca de inicio y se desempata por IP. Cada JVM tiene su propio origen de `System.nanoTime()`. Para determinar el orden de llegada de los paquetes desde distintos equipos, usa las marcas de una misma captura. Tras 300 ms sin tramas de la sesión, el watchdog libera el canal de recepción y registra el evento.

## Licencia

[MIT](LICENSE)
