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

## 4. App Android: video del WebView (saga larga, sin cierre 100% confirmado)

**Estado al cierre de esta sesión**: el código quedó en la versión que consideramos correcta (`WebView` normal, sin trucos), pero **no se pudo confirmar visualmente al final** porque el ESP32 dejó de responder por completo (ver sección 5). Documentado aquí para no repetir el camino recorrido.

### 4.1 Primer síntoma: recuadro negro, `ERR_EMPTY_RESPONSE`

El `WebView` embebido mostraba negro; Chrome normal del celular sí mostraba el stream. `WebViewClient.onReceivedError` reportaba `net::ERR_EMPTY_RESPONSE` para `http://192.168.4.1:81/stream`.

**Causas encontradas (dos apiladas)**:
1. Android no siempre enruta el tráfico de un proceso sin *bind* explícito por una red WiFi sin "internet validado", aunque la tabla de rutas se vea correcta. `ConnectivityManager.bindProcessToNetwork()` solo afecta al proceso que lo llama — el `WebView` corre en un **proceso separado** y no siempre hereda ese bind.
2. Durante la depuración, el celular (o la laptop, usada para pruebas de control) se reconectaban solos a la red de casa sin que fuera obvio en la UI. Se confirmó viendo la IP de origen real en los errores de `sendControl` (`192.168.100.x` = casa, no `192.168.4.x` = carro).

**Lección para depurar esto en el futuro**: cuando algo falla "porque la red está mal", verificar primero, con evidencia (no suposición), la IP de origen real de la conexión que falla.

### 4.2 Intento: decodificar los frames a mano (`BitmapFactory`)

Se reemplazó el `WebView` por un socket crudo (`network.openConnection` explícito, evita el problema de bind) que parsea el `multipart/x-mixed-replace` y decodifica cada JPEG con `BitmapFactory.decodeByteArray`. Los frames llegaban completos (bytes exactos, verificado byte a byte contra una captura de `curl`), pero **`BitmapFactory` siempre devolvía `null`**.

Investigando con un walker manual de marcadores JPEG y con `PIL`/`libjpeg` en la laptop: el JPEG que produce el sensor **no es válido según el estándar** — el segundo marcador tras `SOI` (`FFD8`) no es un `DQT`/`APPn` reconocible (`FFF0` en vez de `FFDB`/`FFE0`), y su campo de longitud implícito excede el tamaño real del frame. `PIL` falla con `Truncated File Read`, igual que Android. Es casi seguro un bug del encoder de hardware del OV3660 (falta byte-stuffing correcto de bytes `0xFF` sueltos en la data). **Esto es un problema del propio sensor, no del código Android ni del firmware que escribimos** — los bytes son idénticos vengan de `curl` o de la app.

### 4.3 Intento: `<img src="data:...">` dentro del WebView (sin red)

Hipótesis: quizás el decodificador de WebView es más tolerante que `BitmapFactory`. Se probó pasarle los mismos bytes crudos como `data:` URI a un `<img>` dentro de un WebView sin red (sin el problema de bind, porque no hace ninguna petición). **Tampoco se vio nada** — el WebView tampoco decodifica el JPEG malformado por esta vía. Conclusión: no es que "WebView tenga un decodificador de imagen más tolerante"; es que el navegador usa una **ruta de renderizado nativa específica para respuestas `multipart/x-mixed-replace`**, distinta a la de decodificar una imagen suelta.

### 4.4 Intento: `shouldInterceptRequest` (proxy de red hacia el WebView)

Para aprovechar esa ruta nativa, se hizo que el `WebView` cargara el stream por HTTP normal (como en la prueba original que sí funcionó), pero interceptando sus peticiones vía `WebViewClient.shouldInterceptRequest` para resolverlas nosotros con un socket atado a la red correcta (evita el problema de bind entre procesos) y devolver la respuesta cruda (incluyendo el `Content-Type: multipart/x-mixed-replace` correcto) a través de `WebResourceResponse`. La parte de red funcionó perfecto (confirmado por log: status 200, content-type correcto, chunked bien detectado), **pero tampoco se vio imagen**. Hipótesis no confirmada: `WebResourceResponse` puede no soportar bien streams verdaderamente infinitos (el multipart nunca termina), a diferencia de una respuesta real de red que Chromium sí sabe procesar incrementalmente.

### 4.5 Estado final: revertido a WebView simple

Dado que la app **sí mostró el video correctamente en la primerísima prueba** (antes de tocar `bindProcessToNetwork`, cuando `ESP32CAR` era la única red activa del celular), se revirtió todo a la versión más simple: `WebView` normal cargando `http://192.168.4.1/` por su cuenta, con `bindProcessToNetwork` como respaldo pero sin interceptar nada. La hipótesis de cierre es que **en uso real (celular solo en `ESP32CAR`, sin otra red compitiendo) esto funciona sin trucos**, y todos los problemas de red vistos durante la sesión fueron artefactos de la propia depuración (laptop y celular saltando entre redes constantemente). Esto **no se pudo re-confirmar** en esta sesión porque el ESP32 dejó de responder (sección 5). **Primer paso de la próxima sesión: verificar esto con el ESP32 descansado.**

## 5. El ESP32 dejó de responder al final de una sesión muy larga

Tras varias horas de pruebas continuas (cientos de conexiones/desconexiones, muchos resets vía RTS y algunos power-cycles), el ESP32 empezó a fallar hasta en lo más básico: `/control` (puerto 80), que había sido perfectamente estable durante toda la sesión, empezó a dar timeout — incluso recién reiniciado (power-cycle completo por USB, no solo reset por RTS). No hay backtrace de crash en el log serial; simplemente deja de responder.

**No se confirmó la causa exacta.** Hipótesis más probable: agotamiento de algún recurso en el stack WiFi/lwIP del ESP32 por la enorme cantidad de conexiones/desconexiones acumuladas en la sesión (softAP con múltiples clientes uniéndose y saliendo repetidamente), o calentamiento por tiempo de operación extendido. **Siguiente paso**: dejar descansar el ESP32 (desconectado de USB) un rato largo antes de la próxima sesión, y si el problema reaparece rápido bajo uso normal (no una maratón de pruebas), investigar más a fondo.

## Nota adicional: reset del ESP32 al abrir el puerto serial

La base de programación (ESP32-CAM-MB, con CH340) resetea el chip cada vez que se abre el puerto serial desde una PC (circuito de auto-programación en RTS/DTR). Esto mata cualquier conexión activa del celular al stream. Al depurar por serial, hay que recargar la página en el celular después de abrir el monitor serial, no antes.
