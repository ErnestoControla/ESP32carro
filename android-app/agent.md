# Instrucciones para el asistente de IA de Android Studio

Este archivo es para ti, asistente integrado en Android Studio. Contiene todo
el contexto necesario para continuar el desarrollo de esta app sin tener que
releer todo el repo. Es la app de control remoto de un carro RC basado en
ESP32-CAM. Antes de escribir código, lee completo este documento — el orden
de las secciones importa (hay una tarea de validación que va ANTES del
rediseño visual).

Contexto más amplio del proyecto (firmware, hardware, decisiones ya tomadas):
`../PLAN.md` y `../docs/notas-tecnicas.md`. Léelos si necesitas entender el
"por qué" de algo — no repitas la investigación que ya está documentada ahí.

## 1. Qué es esta app

Control remoto en vivo para un carro RC. El ESP32-CAM del carro crea su
propia red WiFi (`ESP32CAR`, sin internet) y expone:

- `http://192.168.4.1/` — página HTML simple servida por el firmware (puerto 80).
- `http://192.168.4.1:81/stream` — video MJPEG en vivo (streaming server aparte, puerto 81).
- `http://192.168.4.1/control?steer=<0-180>&throttle=<-255..255>` — comando de control (puerto 80).
  - `steer`: ángulo del servo de dirección. 90 = centro. Valores fuera de rango se recortan en el firmware.
  - `throttle`: velocidad/sentido del motor. Positivo = adelante, negativo = reversa, 0 = detenido.
  - El firmware tiene un **watchdog de 500ms**: si no llega un `/control` nuevo en ese tiempo, corta el motor solo. Por eso la app debe reenviar el comando actual en un loop continuo, no solo cuando el usuario mueve algo.

El celular se conecta a `ESP32CAR` como a cualquier WiFi. No hay backend,
nube, ni beacons: todo es HTTP directo entre el celular y `192.168.4.1`.

## 2. Estado actual del código (no lo reinventes)

Todo vive hoy en `app/src/main/kotlin/com/esp32carro/app/MainActivity.kt`.
Ya está resuelto y **validado funcionando**, no lo toques salvo que la tarea
lo pida explícitamente:

- **Módulo de comunicación / red** (`rememberCarConnection`, `sendControl`):
  detecta la conexión WiFi vía `ConnectivityManager` + `NetworkRequest` (se
  asume que cualquier WiFi activa es la del carro, porque la app no tiene
  otro uso) y hace `bindProcessToNetwork`. `sendControl` usa
  `network.openConnection(url)` — no `URL.openConnection()` a secas — para no
  depender de cuál sea la red "default" del sistema en ese instante. Se
  reenvía cada `CONTROL_SEND_INTERVAL_MS` (150ms) en un `LaunchedEffect` con
  `while (isActive)`. **No cambies este mecanismo** salvo que el usuario lo
  pida: costó una sesión entera de depuración (ver `notas-tecnicas.md`
  sección 4.1) y hoy funciona.
- **Controles** (`AnalogStick`): dos palancas analógicas verticales
  (Dirección y Velocidad), arrastre táctil con `detectVerticalDragGestures`,
  auto-centrado por resorte (`animate` de Compose) al soltar. Ya validado
  con el firmware por log serial. Mantén esta lógica de arrastre/auto-centrado
  intacta; en la tarea de rediseño (sección 4) solo cambia su apariencia
  visual, no su comportamiento de gestos ni las funciones
  `valueToOffset`/`offsetToValue`/`springBackToCenter`.
- **Video** (`AndroidView` con `WebView`): carga `http://192.168.4.1/` (la
  página del firmware, que a su vez muestra el stream). **Ya confirmado
  funcionando** a nivel firmware/red (video en vivo verificado en un
  navegador de escritorio conectado a `ESP32CAR`, ver
  `docs/notas-tecnicas.md` secciones 6 y 6.1) — pero todavía no se
  reconfirmó específicamente dentro del `WebView` de esta app desde que se
  resolvió. Es un mecanismo simple (`WebView` normal, sin trucos) que ya
  funcionó antes, así que debería andar, pero hacé la verificación rápida
  de la sección 3 igual antes de rediseñar encima.

No dupliques ni reescribas estas piezas desde cero. Si algo no funciona,
depura el código existente primero.

## 3. Tarea 1: confirmar el video — ✅ RESUELTA

**Estado: video funcionando end-to-end en la app**, confirmado visualmente
(imagen en movimiento, superpuesta con el HUD de cabina). La causa real de
"pantalla negra" **no era la app ni el WebView de Android**: era un bug de
CSS en el HTML que sirve el firmware (`INDEX_HTML` en `firmware/src/main.cpp`)
— el `<img>` quedaba con `clientHeight: 0` porque dependía de su tamaño
intrínseco, y el WebView/Chrome de Android (a diferencia de Chrome de
escritorio) no recalculaba el layout cuando el recurso
`multipart/x-mixed-replace` "cambiaba" de contenido. Se arregló poniendo el
`<img>` en `position:fixed` con `width/height:100%` para que dimensione
contra el viewport directamente. Detalle completo, con el procedimiento de
diagnóstico via Chrome DevTools remoto (`chrome://inspect` / WebSocket al
`webview_devtools_remote_<pid>`), en `docs/notas-tecnicas.md` secciones 6.2
a 6.4. De paso se corrigió un bug real de firmware (servidor de streaming
bloqueado para siempre por una conexión muerta sin cerrar, sección 6.2).

**Si el video vuelve a fallar en el futuro**, antes de sospechar de la app:
1. Probar `http://192.168.4.1:81/stream` directo en un navegador (celular o PC).
2. Revisar `/status` (contadores de diagnóstico del firmware).
3. Si el navegador tampoco muestra nada pese a que `/status` muestra frames
   enviándose sin fallas, inspeccionar el DOM del `<img>` (`clientWidth`/
   `clientHeight`/`naturalWidth`) antes de asumir que es un problema de red.

Lo que sigue (queda como referencia histórica de cómo se llegó a la Tarea 2,
ya implementada — ver sección 4):

1. Confirma que el ESP32 esté encendido y "descansado" (no en medio de una
   sesión de pruebas maratónica — si acaba de estar horas encendido,
   dale un power-cycle completo por USB).
2. Compila e instala la app tal cual está hoy (sin cambios de layout).
3. Conecta el celular a la red `ESP32CAR` (contraseña `carro1234`).
4. Abre la app y verifica **visualmente** que el `WebView` muestra el video
   en vivo del carro.
5. Reporta el resultado antes de continuar:
   - **Si se ve el video**: el mecanismo actual (WebView simple + bind de
     red) es correcto y definitivo. Pasa a la Tarea 2 sin tocar la lógica
     de red/video, solo su ubicación/tamaño dentro del nuevo layout.
   - **Si NO se ve** (pantalla negra, `ERR_EMPTY_RESPONSE`, etc.): revisa
     `docs/notas-tecnicas.md` sección 4 completa antes de intentar nada
     nuevo — ya se probaron y descartaron: decodificador manual con
     `BitmapFactory` (falla porque el JPEG del sensor OV3660 está mal
     formado — no es bug del código Android), `data:` URI dentro de
     WebView (mismo problema), y proxy vía `shouldInterceptRequest` (la
     respuesta llega bien pero no se renderiza, posible limitación de
     `WebResourceResponse` con streams infinitos). Si hay una IP de origen
     rara en los errores, sospecha primero de otra red compitiendo (ver
     notas-tecnicas 4.1) antes de tocar código. **Antes que nada**, probá
     `http://192.168.4.1:81/stream` directo en un navegador (celular o PC
     conectados a `ESP32CAR`) para descartar que sea el firmware y no la
     app — el endpoint `GET /capture` (foto única con flash,
     `http://192.168.4.1/capture`) sirve para aislar rápido si la cámara
     entrega imagen en absoluto. Ver notas-tecnicas.md secciones 6 y 6.1
     para el procedimiento completo de diagnóstico si esto reaparece.

## 4. Tarea 2: rediseño visual "cabina de auto" + velocímetro — ✅ IMPLEMENTADA

**Estado**: ya implementada (`CarScreen.kt`, `Speedometer.kt`, `AnalogStick.kt`,
`SteeringWheel.kt`). Una diferencia respecto a lo planeado originalmente:
la palanca de dirección se reemplazó por un `SteeringWheel` (volante que se
rota), no un re-skin de `AnalogStick` como decía el plan de abajo — el
usuario lo vio y le gustó, quedó así. La palanca de velocidad sí sigue
siendo `AnalogStick` re-skinneada. No lo cambies de vuelta sin que el
usuario lo pida.

Queda como referencia el detalle de la spec original (velocímetro, etc.):

- **Controles**: se mantienen las palancas analógicas verticales actuales
  tal cual funcionan hoy (arrastre + auto-centrado). Esta tarea es
  **solo re-skin visual** de `AnalogStick` (colores, marco, textura tipo
  panel de control), no un reemplazo por volante/pedal.
- **Video**: se mantiene el `WebView` actual. Esta tarea solo lo reubica
  dentro del nuevo layout tipo "parabrisas" (más grande, centrado, como
  si fuera el frente de la cabina). No toques `rememberCarConnection` ni
  `sendControl`.
- **Velocímetro**: un solo dial analógico de **0% a 100%**, aguja
  proporcional a `abs(throttle) / 255f * 100`. La reversa **no** se
  representa moviendo la aguja a valores negativos — se indica con un
  testigo/luz separada (por ejemplo un ícono o texto "R" que se enciende
  cuando `throttle < 0`), igual que en un auto automático real.
- **Fuera de alcance por ahora** (no las implementes en esta tarea, ya
  quedaron anotadas como pendientes futuras en `../PLAN.md` Fase 4):
  indicador de calidad de conexión/latencia, e indicador visual de ángulo
  de dirección en el tablero.

### 4.1 Especificación del velocímetro

Nuevo composable, por ejemplo `SpeedometerGauge` (archivo separado
`Speedometer.kt` sugerido, ver sección 5 sobre organización de archivos):

- Dibujado con `Canvas` de Compose (`androidx.compose.foundation.Canvas`),
  no una librería externa — es un arco simple, no vale la pena la
  dependencia.
- Arco de ~270° (como un velocímetro real), de 0% a 100%, con marcas cada
  10% y números cada 20-25%.
- Aguja animada suavemente hacia el valor objetivo (usa
  `animateFloatAsState` o `Animatable`, no un salto brusco) para que se
  sienta como una aguja física, no un salto digital.
- Color de la aguja/arco que cambie con el valor (ej. verde → amarillo →
  rojo hacia el 100%) para dar sensación de "tablero".
- Texto grande en el centro con el porcentaje actual (`"${pct}%"`).
- Testigo "R" (reversa): un pequeño indicador (círculo o texto) que se
  enciende con color (ej. rojo/naranja) solo cuando `throttle < 0`, apagado
  el resto del tiempo. Debe ser claramente visible pero no tapar el dial.
- Recibe el `throttle: Float` actual como parámetro (el mismo estado que ya
  existe en `CarScreen`) — no dupliques el estado, solo agrega esta vista
  que lo consume.

### 4.2 Layout general tipo cabina

Mantén la orientación horizontal (landscape) ya fijada en el
`AndroidManifest.xml`. Disposición sugerida (de izquierda a derecha):

```
[ Palanca Dirección ]   [ Video (parabrisas) ]   [ Palanca Velocidad ]
                         [   Velocímetro debajo o superpuesto  ]
```

- El video ocupa el centro, lo más grande posible (protagonista, como un
  parabrisas).
- El velocímetro puede ir debajo del video (como un cluster de
  instrumentos bajo el parabrisas) o superpuesto en una esquina del video
  a modo de HUD — tú decides cuál se ve mejor, pero no debe tapar el área
  donde el usuario mira para manejar.
- Estética "cabina": fondo oscuro (negro/gris carbón), acentos de color
  (rojo o naranja tipo tablero deportivo), bordes redondeados en los
  paneles, texto en fuente monoespaciada o "digital" para los números si
  es fácil de lograr con lo que ya trae Compose/Material3 (no agregues una
  fuente custom solo por esto salvo que sea trivial).
- No introduzcas una librería de UI nueva (ni Accompanist, ni gauges de
  terceros) — con `Canvas` + Compose estándar alcanza.

## 5. Organización de archivos (sugerido, no obligatorio)

`MainActivity.kt` ya tiene ~325 líneas y esta tarea le agrega un gauge
nuevo. Si al implementar se vuelve difícil de navegar, está bien dividir en
archivos por responsabilidad, por ejemplo:

- `MainActivity.kt` — solo el `ComponentActivity` y `setContent`.
- `CarScreen.kt` — el composable principal y el layout de cabina.
- `AnalogStick.kt` — la palanca (código ya existente, solo mover).
- `Speedometer.kt` — el gauge nuevo.
- `CarNetwork.kt` — `rememberCarConnection` + `sendControl` (código ya
  existente, solo mover).

No es obligatorio si prefieres mantenerlo en un solo archivo — es una
sugerencia para que no se vuelva inmanejable, no una refactorización que
haya que justificar aparte.

## 6. Cómo probar

No hay tests automatizados para UI en este proyecto (es un proyecto
personal de hobby, no se justifica la inversión). La validación es manual:

1. `./gradlew assembleDebug` (o el botón Run de Android Studio) — debe
   compilar sin errores.
2. Instalar en un celular real conectado a `ESP32CAR` (el emulador no sirve
   acá: no hay forma de unirlo a la red WiFi real del carro).
3. Verificar visualmente: video visible, ambas palancas responden y
   auto-centran al soltar, el velocímetro se mueve con la palanca de
   velocidad y el testigo "R" enciende en reversa.
4. Si es posible, confirmar por el log serial del firmware (como se hizo en
   sesiones anteriores) que los valores de `steer`/`throttle` que llegan
   coinciden con lo que muestra la app.

## 7. Reglas generales para este proyecto

- Kotlin + Jetpack Compose, sin XML de layouts (ya es 100% Compose).
- No agregues dependencias nuevas sin que sean claramente necesarias — el
  `build.gradle.kts` actual es intencionalmente mínimo.
- No cambies `minSdk`, `compileSdk` ni el paquete `com.esp32carro.app`.
- Comentarios solo donde el "por qué" no sea obvio (ver los comentarios
  existentes en `MainActivity.kt` como ejemplo de tono/nivel de detalle) —
  no documentes lo que el código ya dice por sí mismo.
- Este es software para un vehículo físico con motor real: cualquier
  cambio en la lógica de envío de comandos o en el watchdog debe seguir
  garantizando que el carro se detiene si se pierde la conexión. No
  relajes el intervalo de reenvío (150ms) sin verificar que sigue muy por
  debajo del timeout del watchdog del firmware (500ms).
