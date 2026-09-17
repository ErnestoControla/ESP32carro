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

**Ojo al depurar con scripts propios (no `pio device monitor`/Arduino IDE)**: cualquier apertura "ingenua" del puerto (ej. `pyserial` con `Serial(port, baud)` sin fijar `dtr`/`rts` antes de abrir, o `cat /dev/ttyUSB0`) puede togear DTR/RTS al abrir y resetear el ESP32 sin que sea obvio — pasó varias veces en la sesión de la sección 6 y generó falsos síntomas ("la red desapareció", "no responde") que en realidad eran resets autoinducidos por la propia herramienta de diagnóstico. Para leer el serial sin resetear: `ser = serial.Serial(); ser.port=...; ser.dtr=False; ser.rts=False; ser.open()` (fijar las líneas ANTES de abrir).

## 6. Root cause real del video negro: la cámara no entrega frames (`esp_camera_fb_get()` cuelga/falla), no es un bug de red ni de Android

Sesión posterior a las secciones 4 y 5, con el ESP32 ya descansado. Se investigó de nuevo el "recuadro negro" del video, esta vez con evidencia mucho más sólida (log serial capturado correctamente + pruebas directas por `curl` desde una PC conectada a `ESP32CAR`, sin depender del celular ni del WebView).

**Hallazgo 1 — no es un problema de Android/WebView**: `curl http://192.168.4.1:81/stream` desde una PC (nada de WebView, nada de bind de red de Android) devuelve exactamente el mismo síntoma que la app: conexión TCP aceptada, cero bytes de respuesta. Esto descarta de una vez todas las hipótesis de la sección 4 relacionadas con Android/WebView/bind de proceso para este síntoma puntual.

**Hallazgo 2 — no es agotamiento de sockets por reconexiones repetidas (se descarta la hipótesis de la sección 5 para este síntoma)**: el mismo fallo ocurre en el primerísimo intento tras un flasheo limpio (cero clientes previos). `/` y `/control` (puerto 80) siguen respondiendo perfectamente durante y después de que `/stream` falla — el dispositivo no se cuelga ni se reinicia, solo el endpoint de streaming no entrega nada.

**Hallazgo 3 — causa real, confirmada por log serial** (agregado temporalmente un `Serial.printf` con el resultado de `httpd_start()` para ambos servidores, ver `firmware/src/main.cpp`):
```
[http] index_httpd (puerto 80) httpd_start: 0x0     (OK)
[http] stream_httpd (puerto 81) httpd_start: 0x0    (OK)
[http] registro de /stream: 0x0                      (OK)
[stream] cliente conectado, handler iniciado
[stream] fb_get #1 tardo 4000ms
Fallo al capturar frame
[stream] saliendo del handler en frame #1, res=0xffffffff
```
Los dos servidores HTTP arrancan bien y el handler de `/stream` sí se ejecuta. El problema es que `esp_camera_fb_get()` (pedirle un frame al sensor OV3660) tarda ~4 segundos y falla. Como falla en el primer frame, `stream_handler` nunca llega a mandar ni el header HTTP (la respuesta es "lazy": no se manda nada hasta el primer `httpd_resp_send_chunk` exitoso) — por eso el cliente ve "conexión vacía": el servidor está bien, pero no tiene imagen que mandar. En un intento posterior (misma sesión), el comportamiento fue todavía peor: la conexión ni siquiera llegó a fallar en 10s (se quedó colgada sin responder), sugiriendo que `esp_camera_fb_get()` puede bloquearse indefinidamente esperando un frame que nunca llega, no solo tardar 4s.

**Se probó reconectar el cable flex de la cámara** (con el ESP32 desconectado de alimentación antes de tocarlo, forma correcta) y el síntoma **persistió** después de eso.

**Conclusión al momento**: esto ya no es un problema de software (firmware, red, o app) — es un problema físico/del sensor de cámara en tiempo de captura, distinto del bug de inicialización de la sección 1 (ese era al arrancar; este es en cada intento de captura, con la cámara ya inicializada correctamente). **No tiene sentido seguir iterando en el código de la app Android hasta que la cámara entregue frames de forma confiable** — el resto del sistema (WiFi AP, `/control`, watchdog) funciona correctamente.

### 6.1 Resuelto (¿solo?): dejó de fallar después de agregar `/capture` y volver a flashear

Se agregó un endpoint nuevo `GET /capture` (puerto 80, ver `firmware/src/main.cpp`) que prende el LED flash integrado (GPIO4), espera 150ms, pide **un solo frame** (`esp_camera_fb_get()`, sin loop) y lo devuelve como `image/jpeg` normal (no streaming). Pensado para aislar si el problema era del loop continuo de `/stream` o algo más básico.

Tras reflashear con este cambio: `/capture` funcionó a la primera (foto real, nítida, 320x240, ~3KB). Se probó `/stream` a continuación con el mismo firmware ya corriendo — **también funcionó**, confirmado visualmente en Chrome de la laptop (video en vivo, se vio una mano moviéndose frente a la cámara en tiempo real).

Para descartar que hiciera falta "calentar" la cámara con `/capture` antes de `/stream` (teoría: el sensor necesita un frame de descarte tras el init), se hicieron **dos reinicios limpios adicionales** (`esptool chip_id`, que hace un hard-reset prolijo) probando `/stream` **directo**, sin pasar por `/capture` primero — funcionó las dos veces (191KB y 375KB recibidos en 5s de captura con `curl`, sin ningún fallo).

**Conclusión honesta (en su momento)**: el video quedó funcionando de forma estable y reproducible (3 arranques limpios seguidos, todos OK), pero no se identificó con certeza qué acción específica lo arregló. La explicación real se encontró en la sección 6.2.

### 6.2 La causa real: el servidor de streaming solo atiende UN cliente a la vez, y una conexión muerta sin cerrar bloquea a todas las siguientes para siempre

Sesión posterior, con la app Android ya modificada (rediseño de cabina en curso). El usuario reportó: "se pudo ver la imagen pero se volvió a perder" (pantalla de video se puso negra de golpe, el resto de la app — palancas, `/control` — seguía funcionando).

**Herramienta de diagnóstico agregada**: endpoint `GET /status` (puerto 80) con contadores en texto plano (`stream_attempts`, `stream_fb_fail`, `stream_frames_sent`, `last_fb_get_ms`, `max_fb_get_ms`, `uptime_ms`). Se agregó porque **abrir el puerto serial desde esta PC resetea el ESP32** (el adaptador CH340 dispara el circuito de auto-programación al abrir el puerto, incluso con librerías que intentan evitarlo fijando DTR/RTS antes de abrir) — cualquier intento de "solo mirar el log" durante una sesión larga destruye el estado que se quiere observar. `/status` permite diagnosticar sin tocar la placa para nada.

**Con `/status` se pudo medir en limpio**: 8 intentos cortos seguidos de `/stream` → 8/8 exitosos. Un stream continuo de 45s → 0 fallas, ~20fps sostenidos. Un stream de 5 minutos → 0 fallas, `stream_frames_sent` subiendo de forma constante y saludable todo el tiempo.

**El hallazgo clave**: durante la prueba de 5 minutos, con la app del celular abierta (conectada pero *sin mostrar video* — pantalla negra), se intentó abrir una **segunda** conexión a `/stream` desde la laptop. Esa segunda conexión se quedó colgada **sin recibir ni un byte durante los 295 segundos completos de la prueba**, mientras `stream_attempts` nunca subió de 10 (nunca se aceptó como cliente nuevo) y `stream_frames_sent` seguía creciendo sin parar — es decir, **el ESP32 nunca dejó de mandar frames**, se los seguía mandando a la conexión que ya tenía abierta (la del celular). El celular simplemente dejó de renderizarlos.

**Causa raíz confirmada**: `stream_handler` corre en un único task de FreeRTOS bloqueado dentro de su propio `while(true)` mientras dura la conexión — el servidor HTTP de streaming (puerto 81) solo puede atender **un cliente a la vez** por diseño. Si un cliente se desconecta sin cerrar la conexión TCP de forma prolija (corte breve de WiFi, celular con pantalla bloqueada que deja de leer el socket, WebView que se cuelga sin cerrar), el socket queda "vivo" del lado del ESP32 — la función de envío (`httpd_resp_send_chunk`) no tenía ningún timeout configurado, así que si el par no lee, `send()` puede bloquearse indefinidamente. El resultado: **ningún cliente nuevo puede conectarse nunca más** (ni el mismo celular reintentando, ni otro dispositivo) hasta reiniciar el ESP32 a mano — sin ningún error visible ni en el celular ni en el log.

Esto explica retroactivamente casi todos los "video negro" documentados en este archivo (secciones 4, 5 y 6): probablemente casi siempre había una conexión zombie de un intento anterior (mío, del celular, o de la propia sesión de pruebas) ocupando el único cupo disponible.

**Fix aplicado** (`firmware/src/main.cpp`, `stream_handler`): al aceptar la conexión, se configura un timeout de envío/recepción en el socket subyacente (`setsockopt(SO_SNDTIMEO/SO_RCVTIMEO, 3s)` vía `httpd_req_to_sockfd()` + `<lwip/sockets.h>`). Si el cliente deja de leer, `send()` falla tras 3s en vez de bloquear para siempre, el handler sale del loop y libera la conexión.

**Fix validado con un cliente "zombi" simulado** (socket Python crudo que manda el `GET /stream` y después nunca lee ni cierra, ver `zombie_client.py`): con el zombi todavía "conectado", una segunda conexión nueva entró y streameó 408KB sin fallas en menos de 3 segundos. Antes del fix, este mismo escenario se quedaba colgado para siempre (confirmado en la prueba de 5 minutos de arriba).

**Pendiente / a tener en cuenta**: el límite de "un solo cliente a la vez" sigue existiendo — el fix solo evita que una conexión MUERTA bloquee para siempre, pero dos clientes *vivos* simultáneos (ej. el celular y alguien viendo desde el navegador al mismo tiempo) van a seguir compitiendo por el único cupo. Para este proyecto (un solo celular controlando el carro) no hace falta resolver eso.

**Sobre el `WebView` de Android específicamente**: en la prueba de 5 minutos, el celular tenía una conexión *viva* y *exitosa* (el ESP32 le mandaba frames sin fallar) pero la app no mostraba nada. Esto apunta a un problema de renderizado del propio `WebView` con streams `multipart/x-mixed-replace` de duración larga (deja de repintar aunque los datos sigan llegando), no a la red ni al firmware. Ver `android-app/agent.md` para el fix del lado de la app (recarga automática del `WebView` si no se detecta actividad).

### 6.3 El problema es del celular/navegador Android, no del ESP32 (confirmado con `/status`)

Sesión siguiente, tras agregar el watchdog de recarga automática al `WebView` (ver `CarScreen.kt`) y el fix de timeout de socket (sección 6.2). Se probó la app en el celular: video seguía en negro. Se repitió el aislamiento con `/status`:

- El ESP32 estaba perfectamente vivo y respondiendo (`/control` OK, `/status` mostrando `stream_fb_fail=0` de forma sostenida).
- Cada vez que el `WebView` de la app reintentaba (por el watchdog nuevo) o que se abría `http://192.168.4.1:81/stream` en el **Chrome normal del propio celular** (no la app), `stream_attempts` en `/status` **subía** y `stream_frames_sent` **seguía creciendo sin ninguna falla** — es decir, el ESP32 aceptaba la conexión nueva y le mandaba frames exitosamente cada vez.
- Pese a eso, ni el `WebView` ni el Chrome normal *de este celular* mostraron nunca la imagen (pantalla negra o `net::ERR_FAILED`).
- La misma URL, en Chrome de una laptop (sesión anterior, sección 6.1), sí mostró video en vivo sin problemas.

**Conclusión**: el ESP32 entrega el stream correctamente — confirmado de forma exhaustiva y repetida (single-shot, streams cortos, streams de varios minutos, con contador de fallas en 0 todo el tiempo). El problema de "no se ve la imagen" que persistía era del lado del **celular/navegador Android**, no del firmware ni de la red — pero la causa real, encontrada en la sección 6.4, resultó ser mucho más simple y estar en el propio HTML del firmware, no en el dispositivo.

### 6.4 Causa raíz final: el `<img>` quedaba con `clientHeight: 0` por un problema de CSS — nada que ver con WebView ni con el celular

El HTML embebido en el firmware (`INDEX_HTML` en `main.cpp`) era:
```html
<body style="margin:0;background:#111;display:flex;justify-content:center;align-items:center;height:100vh;">
  <img src="http://192.168.4.1:81/stream" style="max-width:100%;max-height:100%;" />
</body>
```
El `<img>` no tenía ancho/alto propio — dependía de su tamaño intrínseco (`max-width`/`max-height` como techo, no como valor). Con Chrome de escritorio esto funcionaba porque el motor recalcula el layout cuando el `<img>` recibe sus primeros bytes reales y conoce su tamaño natural. **El WebView de este celular (y su Chrome normal) nunca hicieron ese recálculo** para un recurso `multipart/x-mixed-replace` que sigue "cambiando" dentro de la misma carga.

**Diagnóstico exacto**, usando Chrome DevTools remoto conectado al `WebView` de la app vía `chrome://inspect` (forwardeando el socket `webview_devtools_remote_<pid>` con `adb forward` y hablando el protocolo directamente por WebSocket, sin necesidad de una ventana de Chrome — ver `inspect_webview2.py` y `check_img.py` en el historial de la sesión):

- `Network.dataReceived` mostraba **miles de eventos** de datos MJPEG llegando de forma continua y sana — la red y el firmware estaban perfectos (esto ya se sabía, pero terminó de confirmar que el problema no era de datos).
- `Runtime.evaluate` sobre el DOM mostró la causa exacta:
  ```json
  {"complete": true, "naturalWidth": 320, "naturalHeight": 240,
   "clientWidth": 0, "clientHeight": 0}
  ```
  La imagen **sí estaba decodificada** (`naturalWidth`/`naturalHeight` correctos) pero el elemento tenía **tamaño renderizado cero** — por eso no se veía nada, no por falta de datos ni de decodificación.

**Primer intento de fix (parcial)**: cambiar a `width:100%;height:100%` con `html,body{height:100%}`. Esto resolvió el ancho (`clientWidth` pasó a un valor real) pero la altura seguía en 0 — `document.body.clientHeight` daba `0` pese a `height:100%`, mientras `document.documentElement.clientHeight` (el alto real del viewport) sí era correcto. Es decir, el porcentaje de altura no se resolvía bien en la cascada `html → body → img` en este motor.

**Fix definitivo**: `position:fixed` en el `<img>` con `top:0;left:0;width:100%;height:100%;object-fit:contain`. `position:fixed` dimensiona contra el viewport directamente (el "initial containing block"), sin pasar por la cadena de porcentajes de `html`/`body` que fallaba. Confirmado funcionando en la app real (video en vivo visible, con el diseño de cabina completo superpuesto).

**Lección importante**: se había agregado como fix intermedio `webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)` bajo la hipótesis de un bug de invalidación de capa acelerada por hardware. **Esa hipótesis era incorrecta** — se sacó después de confirmar (quitándola y volviendo a probar) que el video sigue funcionando perfecto sin ella. El problema nunca fue de repintado/compositing, era 100% de layout/CSS. No dejar código "por las dudas" basado en una hipótesis que no se terminó de confirmar — costó una vuelta extra de diagnóstico que se pudo evitar verificando antes de asumir.

## 7. Primera prueba física de motor y servo: dos canales LEDC compartiendo timer rompían el servo

Primera vez que se probó el motor (vía L298N) y el servo con hardware real conectado a una protoboard, alimentado por fuente de banco + buck converter (ver diagrama de cableado). Resultado inicial: **el motor iba bien hacia adelante, pero no hacía reversa ni respondía la dirección (servo) en absoluto.**

**Diagnóstico**: se midió con multímetro (en modo VDC) directamente en el pin `GPIO13` del header del ESP32-CAM (confirmado con foto del pinout que era el pin correcto — el orden real de ese lado es `5V, GND, IO12, IO13, IO15, IO14, IO2, IO4`) mientras se mandaban comandos `/control?steer=170...` y `/control?steer=10...`. En ambos casos el multímetro marcó **3.34V fijo**, sin cambiar entre ángulos — para un pulso PWM angosto (steer=10 ≈ 3% de duty) se esperaba un promedio bajo (~0.1-0.4V), no un valor fijo cercano al voltaje lógico completo. Eso indicaba que el pin no estaba pulsando: se quedaba en HIGH constante sin importar el ángulo pedido.

**Causa raíz, confirmada leyendo el código fuente del framework** (`esp32-hal-ledc.c` de `framework-arduinoespressif32 @ 3.20017`): en el ESP32 original, cada **par** de canales LEDC comparte un mismo timer de hardware — la fórmula real es `timer = (canal/2) % 4`. `firmware/src/drive.h` usaba **canal 4 para el servo** (50Hz, 16-bit) y **canal 5 para el motor** (5000Hz, 8-bit) — ambos caen en `timer = 2`, el mismo timer. `drive_init()` configuraba primero el servo y después el motor; la segunda llamada a `ledcSetup()` pisaba en silencio la frecuencia/resolución que acababa de configurar la primera, dejando el timer 2 corriendo realmente a 5000Hz/8-bit (los valores del motor). El código del servo seguía calculando el duty asumiendo 16-bit (valores en el orden de miles), muy por encima del máximo real de un registro de 8-bit (255) — esos valores se saturaban al tope, y el pin quedaba fijo en ~100% duty (HIGH constante), coincidiendo exactamente con los 3.34V medidos.

**Fix** (`firmware/src/drive.h`): mover el motor del canal 5 al **canal 6** (`timer = (6/2)%4 = 3`, distinto del timer 2 del servo). Cambio de una línea. Se evitan además los canales 0 y 1 (timer 0, usado internamente por la librería de la cámara para el XCLK).

**Resultado**: tras reflashear, **tanto la dirección (servo) como la reversa del motor quedaron funcionando correctamente** en la misma sesión de pruebas. No se investigó a fondo el mecanismo exacto por el cual el bug del timer también afectaba la reversa del motor (`IN1`/`IN2` son pines digitales simples, sin PWM/LEDC de por medio, así que en teoría no deberían haberse visto afectados por este bug específico) — es posible que el estado inconsistente del timer compartido haya causado algún efecto secundario más amplio, o que haya sido una coincidencia con otro ajuste de cableado hecho en el camino. Si la reversa vuelve a fallar de forma aislada (sin que el servo también falle), investigar por separado — probablemente cableado de `GPIO15`/`IN2`, no este bug.

**Lección para el futuro**: al usar `ledcSetup()`/`ledcAttachPin()` (API vieja de Arduino-ESP32) con más de dos canales manuales, verificar siempre qué canales comparten timer (`canal/2`) antes de asignarles frecuencias/resoluciones distintas — es un error fácil de cometer y el framework no avisa cuando un timer se pisa.
