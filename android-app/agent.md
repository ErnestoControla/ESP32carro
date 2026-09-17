# Instrucciones para el asistente de IA de Android Studio

Este archivo es para vos, asistente integrado en Android Studio. Contiene el
contexto necesario para seguir desarrollando esta app sin releer todo el
repo. Es la app de control remoto de un carro RC basado en ESP32-CAM.

Contexto más amplio del proyecto (firmware, hardware, decisiones ya tomadas,
historial de bugs encontrados y resueltos): `../PLAN.md` y
`../docs/notas-tecnicas.md`. Leelos si necesitás entender el "por qué" de
algo — no repitas investigación que ya está documentada ahí.

## 1. Estado del proyecto: MVP funcionando end-to-end

**Todo lo de esta app está validado con hardware real**: video en vivo,
controles, y el vehículo moviéndose de verdad (motor + servo) con batería,
sin que se corte la comunicación. Lo único pendiente es una prueba de
alcance en exteriores (no es tarea de la app, es de uso). Si te piden
trabajar acá, es sobre esta base ya sólida — no hace falta re-derivar ni
re-confirmar lo que sigue, solo lo que la tarea puntual pida.

## 2. Qué es esta app

Control remoto en vivo para un carro RC. El ESP32-CAM del carro crea su
propia red WiFi (`ESP32CAR`, contraseña `carro1234`, sin internet) y expone:

- `http://192.168.4.1/` — página HTML con el `<img>` del stream (puerto 80).
- `http://192.168.4.1:81/stream` — video MJPEG en vivo, servidor aparte (puerto 81).
- `http://192.168.4.1/control?steer=<0-180>&throttle=<-255..255>` — comando de control (puerto 80).
  - `steer`: ángulo del servo de dirección. 90 = centro. Fuera de rango se recorta en el firmware.
  - `throttle`: velocidad/sentido del motor. Positivo = adelante, negativo = reversa, 0 = detenido.
  - Watchdog de **500ms** en el firmware: si no llega un `/control` nuevo en ese tiempo, corta el motor solo. La app reenvía el comando actual en loop continuo (cada 150ms), no solo cuando el usuario mueve algo.
- `http://192.168.4.1/status` — contadores de diagnóstico del streaming (texto plano). Útil para descartar problemas de firmware sin tocar la app.
- `http://192.168.4.1/capture` — foto única con flash, sin streaming. Útil para diagnóstico rápido de la cámara.

El celular se conecta a `ESP32CAR` como a cualquier WiFi. No hay backend,
nube, ni beacons: todo es HTTP directo entre el celular y `192.168.4.1`.

## 3. Estructura del código actual

```
app/src/main/kotlin/com/esp32carro/app/
├── MainActivity.kt              — entry point, solo setContent { CarScreen() }
├── network/CarNetwork.kt        — conexión WiFi (bind de red) + sendControl()
└── ui/
    ├── CarScreen.kt             — layout principal de cabina, WebView del video
    └── components/
        ├── AnalogStick.kt       — palanca vertical con auto-centrado (velocidad)
        ├── SteeringWheel.kt     — volante rotable (dirección; reemplazó la palanca original)
        └── Speedometer.kt       — velocímetro HUD (dial 0-100% + testigo "R" de reversa)
```

Todo **validado funcionando**, no lo reescribas desde cero. Si algo falla,
depurá el código existente primero.

- **Red** (`CarNetwork.kt`): detecta la conexión WiFi vía `ConnectivityManager`
  + `NetworkRequest` (se asume que cualquier WiFi activa es la del carro,
  porque la app no tiene otro uso) y hace `bindProcessToNetwork`.
  `sendControl` usa `network.openConnection(url)` — no `URL.openConnection()`
  a secas — para no depender de cuál sea la red "default" del sistema en ese
  instante. Se reenvía cada `CONTROL_SEND_INTERVAL_MS` (150ms). **No cambies
  este mecanismo** salvo que se pida explícitamente: costó una sesión entera
  de depuración (`notas-tecnicas.md` sección 4.1) y hoy funciona, incluso con
  el motor real andando (confirmado: la comunicación no se corta con el
  vehículo en movimiento).
- **Controles**: `SteeringWheel` (dirección, rotación) y `AnalogStick`
  (velocidad, arrastre vertical con auto-centrado por resorte). Ambos ya
  validados enviando valores correctos al firmware — mantené la lógica de
  gestos intacta salvo pedido explícito.
- **Video** (`CarScreen.kt`, `AndroidView` con `WebView`): carga
  `http://192.168.4.1/`. Watchdog agregado que llama a `webView.reload()`
  cada 20s como red de seguridad (por si el stream se cuelga en silencio).
  **La causa de fondo de por qué el video no se veía ya se identificó y
  arregló — no era un problema de esta app ni de Android**: era CSS en el
  HTML que sirve el firmware (`INDEX_HTML` en `firmware/src/main.cpp`), el
  `<img>` quedaba con tamaño renderizado cero. Detalle completo,
  con el procedimiento de diagnóstico vía Chrome DevTools remoto conectado al
  `WebView` (`adb forward` al socket `webview_devtools_remote_<pid>` +
  WebSocket, sin necesidad de abrir Chrome), en `docs/notas-tecnicas.md`
  secciones 6.2 a 6.4.
- **Velocímetro** (`Speedometer.kt`): dial 0-100% dibujado con `Canvas`,
  aguja animada, proporcional a `abs(throttle)/255`. Testigo "R" se enciende
  con `throttle < 0` (no se representa reversa con la aguja en negativo).

## 4. Si el video vuelve a fallar

No es lo más probable (la causa de fondo está resuelta), pero si pasa:

1. Probá `http://192.168.4.1:81/stream` directo en un navegador (celular o PC
   conectados a `ESP32CAR`) para descartar que sea el firmware y no la app.
2. Revisá `http://192.168.4.1/status` — si `stream_fb_fail` sube o los
   frames no aumentan, es un problema de cámara/firmware, no de la app.
3. Si el navegador tampoco muestra nada pese a que `/status` muestra frames
   enviándose sin fallas, inspeccioná el DOM del `<img>`
   (`clientWidth`/`clientHeight`/`naturalWidth`) antes de asumir que es un
   problema de red — ver notas-tecnicas.md 6.2-6.4 para el procedimiento
   completo con Chrome DevTools remoto.

## 5. Cómo probar

No hay tests automatizados de UI en este proyecto (hobby, no se justifica
la inversión). Validación manual:

1. `./gradlew assembleDebug` (o Run de Android Studio) — debe compilar sin errores.
2. Instalar en un celular real conectado a `ESP32CAR` (el emulador no sirve:
   no hay forma de unirlo a la red WiFi real del carro).
3. Verificar visualmente: video visible, volante y palanca responden y la
   palanca auto-centra al soltar, el velocímetro se mueve con la velocidad y
   el testigo "R" enciende en reversa.
4. Si hay motor/servo conectados: confirmar que el vehículo responde a los
   controles y que la app sigue funcionando (video + control) durante el
   movimiento, sin cortes.

## 6. Pendientes conocidos (Fase 4 de `../PLAN.md`, no urgentes)

Si te piden seguir avanzando la app y no hay una tarea más específica, estas
son las próximas mejoras ya anotadas (en orden sugerido, ninguna bloquea a
otra):

- **Indicador de calidad de conexión/latencia** en el tablero de cabina —
  la app ya sabe cuándo `sendControl` falla (`SocketTimeoutException` etc.),
  solo falta exponerlo visualmente (ej. un testigo de señal).
- **Indicador visual de ángulo de dirección** en el tablero — redundante con
  el volante, es estético (tipo "las ruedas giradas X°").
- **Pantalla de calibración del servo** — ajustar el centro/límites del
  servo (`steer` min/max/centro) sin tener que reflashear el firmware. Hoy
  el centro está fijo en 90° por firmware (`SERVO_CENTER_DEG` en
  `firmware/src/drive.h`); si el brazo del servo no coincide exactamente con
  ruedas derechas en el chasis real, hoy la única forma de ajustar es
  reflashear. Esta pantalla evitaría eso.
- **Manejo de reconexión automática** — qué pasa hoy si se pierde la red
  brevemente y vuelve; confirmar que la app se recupera sola sin reabrir.

## 7. Reglas generales para este proyecto

- Kotlin + Jetpack Compose, sin XML de layouts (100% Compose).
- No agregues dependencias nuevas sin que sean claramente necesarias — el
  `build.gradle.kts` actual es intencionalmente mínimo.
- No cambies `minSdk`, `compileSdk` ni el paquete `com.esp32carro.app`.
- Comentarios solo donde el "por qué" no sea obvio — no documentes lo que
  el código ya dice por sí mismo.
- Este es software para un vehículo físico con motor real: cualquier cambio
  en la lógica de envío de comandos o en el watchdog debe seguir
  garantizando que el carro se detiene si se pierde la conexión. No relajes
  el intervalo de reenvío (150ms) sin verificar que sigue muy por debajo del
  timeout del watchdog del firmware (500ms).
- No introduzcas una librería de UI nueva para gauges/dials — con `Canvas` +
  Compose estándar alcanza (ya se usó así para el velocímetro).
