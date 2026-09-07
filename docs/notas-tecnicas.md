# Notas técnicas — hallazgos que costó tiempo diagnosticar

## 1. Cámara OV3660 no inicializaba (`Camera probe failed`)

**Síntoma**: `esp_camera_init()` fallaba (`0xffffffff` o `0x1`) y un escáner I2C manual sobre los pines SCCB (GPIO26/27) tampoco encontraba ningún dispositivo.

**Causa**: el ESP32-CAM AI-Thinker no tiene el pin RESET de la cámara conectado (`pin_reset = -1`). El driver `esp32-camera` solo baja el pin PWDN (GPIO32) a LOW como parte de la secuencia de reset — si no hay pin de reset configurado, ese paso se salta, PWDN se queda flotando en HIGH y el sensor nunca enciende.

**Fix**: forzar PWDN a LOW manualmente antes de llamar a `esp_camera_init()`:
```cpp
pinMode(PWDN_GPIO_NUM, OUTPUT);
digitalWrite(PWDN_GPIO_NUM, LOW);
delay(10);
```
Ver `firmware/src/main.cpp`, función `init_camera()`.

## 2. Stream sin imagen en el navegador

**Síntoma**: la página cargaba pero el `<img>` nunca mostraba nada.

**Causa**: `<img src="/stream">` se resuelve al mismo origen (puerto 80), pero el servidor de streaming MJPEG corre en un `httpd` separado en el puerto 81.

**Fix**: usar la URL absoluta con puerto explícito: `http://192.168.4.1:81/stream`.

## 3. Video con tirones y retardo creciente

**Síntoma**: stream visible pero con tirones y un delay que se sentía cada vez mayor, incluso con el celular a corta distancia del ESP32.

**Diagnóstico**: se instrumentó temporalmente `stream_handler` para medir tiempo de captura vs. tiempo de envío por frame. Resultado con VGA:

```
capture_ms/f = 0.4–12 ms
send_ms/f    = 107–421 ms
KB/s         = 44–151
```

El cuello de botella era el envío por WiFi (frames VGA de ~18KB), no la captura de la cámara. El delay creciente es backlog acumulándose entre el ESP32 y el navegador porque el throughput real del AP no alcanza a mover frames tan grandes al ritmo que se generan.

**Fix**: bajar de VGA a **QVGA** (320x240, ~6.5KB/frame). Resultado: ~25 fps estables, `capture_ms/f` y `send_ms/f` balanceados (~20ms cada uno), sin acumulación de retardo.

**Lección**: para control en vivo, la latencia importa más que la resolución. Si en el futuro se necesita más detalle de imagen, hay que evaluarlo contra el presupuesto real de throughput del AP (medido, no asumido) — no subir la resolución a ciegas.

## 4. App Android: WebView no muestra el stream MJPEG

**Síntoma**: el `WebView` embebido en la app mostraba un recuadro negro; el navegador Chrome normal del celular sí mostraba el stream. El `WebViewClient.onReceivedError` reportaba `net::ERR_EMPTY_RESPONSE` para `http://192.168.4.1:81/stream`.

**Causa real (dos problemas apilados)**:
1. Android no permite que apps sin *bind* explícito (Chrome normal, y el proceso separado que usa `WebView` internamente) enruten tráfico por una red WiFi sin "internet validado", aunque la tabla de rutas del sistema se vea correcta. `ConnectivityManager.bindProcessToNetwork()` solo afecta al proceso que lo llama — el renderer/red de `WebView` corre en un **proceso separado** y no hereda ese bind. Por eso las llamadas `/control` (hechas con `HttpURLConnection` dentro del proceso de la app) funcionaban, pero el `WebView` no.
2. Durante la depuración, el celular se reconectó solo a la red de casa (WiFi conocida) en algún momento y nunca fue evidente porque la app seguía mostrando "Conectado" — el bug real terminó siendo simplemente que el celular no estaba en `ESP32CAR`. Se confirmó viendo el log de `sendControl`, que mostraba la IP de origen real de la conexión fallida (`192.168.100.x`, la red de casa, no `192.168.4.x`).

**Fix**: se eliminó el `WebView` y se reemplazó por un decodificador MJPEG propio (`streamMjpeg` en `MainActivity.kt`) que:
- Usa `network.openConnection(url)` (el objeto `Network` específico, no la conexión "por defecto" del proceso) para cada request — más robusto que `bindProcessToNetwork`, porque ata la petición a una red concreta sin depender de qué proceso la ejecute.
- Parsea a mano el `multipart/x-mixed-replace` leyendo el header `Content-Length` de cada parte (evita tener que buscar el boundary byte a byte).
- Decodifica cada JPEG con `BitmapFactory` y lo muestra en un `Image` de Compose.

**Lección para depurar esto en el futuro**: cuando algo falla "porque la red está mal", verificar primero, con evidencia (no suposición), la IP de origen real de la conexión que falla — ahí se ve inmediato si el celular está en la red equivocada.

## Nota adicional: reset del ESP32 al abrir el puerto serial

La base de programación (ESP32-CAM-MB, con CH340) resetea el chip cada vez que se abre el puerto serial desde una PC (circuito de auto-programación en RTS/DTR). Esto mata cualquier conexión activa del celular al stream. Al depurar por serial, hay que recargar la página en el celular después de abrir el monitor serial, no antes.
