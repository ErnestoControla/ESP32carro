# Plan MVP — Carro RC con ESP32-CAM + App Android

## Decisiones ya tomadas
- **App**: nativa en Android Studio (Kotlin).
- **Driver de motor**: hay que comprar uno (recomendado abajo).
- **Batería**: sin definir todavía (recomendación abajo).
- **GPIO**: todo en el ESP32-CAM, sin microcontrolador secundario.
- **Comunicación**: el ESP32-CAM crea su propia red WiFi (modo Access Point). El celular se conecta directo a esa red — no se necesita WiFi del estacionamiento ni internet.
- **Firmware**: PlatformIO (en vez de Arduino IDE) para poder compilar/flashear desde línea de comandos y que yo pueda ayudarte a iterar código directamente. Si prefieres Arduino IDE clásico, dime y ajusto.

## Progreso

- **Fase 0 — Identificación y entorno**: completa.
  - Chip confirmado: **ESP32-D0WDQ6 (rev v1.1)**, WiFi/BT, MAC `f8:b3:b7:a6:92:b4`.
  - Cámara confirmada: **OV3660**.
  - PlatformIO instalado (entorno conda `esp32car`), proyecto en `firmware/` (board `esp32cam`).
  - La base de programación (ESP32-CAM-MB, con CH340) flashea directo por USB sin puente manual de GPIO0.
- **Fase 1 — Firmware: solo cámara**: completa.
  - AP propio `ESP32CAR` (contraseña `carro1234`) sirviendo `http://192.168.4.1/` (página) y stream MJPEG en el puerto 81.
  - Tres bugs de hardware/software resueltos — ver detalle en `docs/notas-tecnicas.md`.
  - Resolución final: **QVGA (320x240)**, calidad JPEG 12, ~25 fps estables. Se prefirió sobre VGA porque el cuello de botella es el envío por WiFi, no la cámara, y para controlar el carro importa más la latencia que el detalle de imagen.
- **Fase 2 — Firmware: control de motor y servo**: ✅ **completa, validada end-to-end con hardware real, incluida batería.**
  - Endpoint `GET /control?steer=<0-180>&throttle=<-255..255>` en el puerto 80.
  - Validado manualmente desde el navegador del celular: valores dentro de rango se aplican tal cual, fuera de rango se recortan (ej. `steer=999` → `180`), y falta un parámetro responde 400 con mensaje claro.
  - **Bug real encontrado y corregido en la primera prueba con hardware**: el motor iba bien hacia adelante pero no hacía reversa ni dirección. Causa: en el ESP32, cada par de canales LEDC comparte un mismo timer de hardware (`timer = (canal/2) % 4`) — los canales usados para servo (4) y motor (5) caían en el mismo timer, y `ledcSetup()` del motor pisaba en silencio la configuración de frecuencia/resolución del servo. El servo quedaba corriendo a la frecuencia del motor (5000Hz/8-bit en vez de 50Hz/16-bit) y sus valores de duty (calculados para 16-bit) se saturaban al máximo, dejando el pin fijo en HIGH sin importar el ángulo pedido — confirmado con multímetro (3.34V constante en GPIO13 sin cambiar aunque se pidieran ángulos distintos). Fix: mover el motor al canal 6 (timer distinto) en `firmware/src/drive.h`. Detalle completo en `docs/notas-tecnicas.md` sección 7.
  - **Después del fix, probado end-to-end y confirmado**: dirección (servo) y reversa del motor funcionando en protoboard (fuente de banco), y luego **con batería real** (LiPo, desconectado de la fuente de banco) — el vehículo se movió correctamente y **la comunicación WiFi/`/control` no se interrumpió durante el movimiento** (buena señal para el watchdog y para que el motor en marcha no meta ruido eléctrico que tumbe el WiFi).
  - Watchdog implementado (detiene el motor si no llega un `/control` nuevo en 500ms). No se hizo todavía una prueba **deliberada** de cortar la conexión a propósito para verificar que el motor se detiene solo (lo que sí se confirmó es que *no* se interrumpe en uso normal, que es distinto).
  - Pendiente: prueba de watchdog a propósito (alejar el celular o cerrar la app y confirmar que el motor para en ≤500ms), y **prueba de alcance en exteriores** (ver "Pendiente de tu lado").
- **Fase 3 — App Android v0**: ✅ **completa**, video incluido, validada junto con el movimiento real del vehículo (Fase 2).
  - Proyecto Kotlin + Jetpack Compose en `android-app/`, compilable por CLI con `./gradlew` (Gradle 9.7.1, AGP 9.3.0 — Android Studio ya no necesita el plugin `kotlin-android` por separado desde AGP 9).
  - Estructura de archivos (hecha por el asistente de Android Studio siguiendo `android-app/agent.md`): `MainActivity.kt` (entry point), `network/CarNetwork.kt` (conexión WiFi + `/control`), `ui/CarScreen.kt` (layout de cabina), `ui/components/AnalogStick.kt`, `ui/components/SteeringWheel.kt` (volante rotable, reemplazó la palanca de dirección), `ui/components/Speedometer.kt` (velocímetro HUD).
  - **Funcionando y confirmado end-to-end**: conexión a la red del carro, diseño visual "cabina de auto" (volante rotable para dirección, palanca vertical con auto-centrado para velocidad, velocímetro HUD 0-100% con testigo "R" de reversa), envío de `/control` cada 150ms, watchdog de seguridad, video en vivo mostrándose correctamente, y **manejo real del vehículo con la app** (video + controles + movimiento físico, todo junto, sin cortes).
  - Watchdog de recarga del `WebView` cada 20s agregado como red de seguridad extra (por si el stream se cuelga en silencio como se vio en una sesión anterior) — no debería hacer falta en uso normal dado que el bug de fondo ya se identificó y arregló (ver abajo), pero se deja como salvaguarda barata.
  - **El video tuvo una investigación larga** (`docs/notas-tecnicas.md` secciones 6 a 6.4) con varias causas encontradas y corregidas en el camino:
    1. Un bug real de firmware: el servidor de streaming solo atendía un cliente a la vez y una conexión muerta sin cerrar bloqueaba a cualquier cliente nuevo para siempre — arreglado con un timeout de socket (sección 6.2).
    2. La causa final de "no se ve la imagen en el celular": un bug de CSS en el HTML que sirve el firmware — el `<img>` del stream quedaba con `clientHeight: 0` (tamaño renderizado cero) en el `WebView`/Chrome de Android, aunque la imagen estaba perfectamente decodificada. Arreglado usando `position:fixed` en vez de depender del tamaño intrínseco de la imagen (sección 6.4). No era un problema de Android, del celular, ni de la app — estaba en el HTML servido por el ESP32.
  - Endpoints de diagnóstico agregados al firmware (quedan permanentes, son útiles): `GET /capture` (foto única con flash, sin streaming) y `GET /status` (contadores de diagnóstico del streaming en texto plano).
  - Bug de conectividad corregido: `NET_CAPABILITY_INTERNET` NO sirve para detectar "esta es la red sin internet del carro" — Android la marca en casi cualquier WiFi por defecto. La app bindea a cualquier WiFi activa, ya que no tiene otro uso.
  - Instrucciones completas para el asistente de Android Studio (contexto del proyecto, contrato de red, qué no tocar) en `android-app/agent.md` — está actualizado con el estado final de esta sesión, sirve como punto de partida para la próxima.

## Pendiente de tu lado
1. **Prueba de alcance en exteriores** — es el próximo paso concreto. Sin obstáculos, medir hasta qué distancia se mantiene la conexión WiFi/control antes de que se corte, y qué tan gradual o abrupto es el corte (¿el video se degrada primero, o se corta todo de golpe?). Anotar la distancia aproximada para tenerla como referencia real (el rango típico de un AP ESP32 es 20-50m en exterior abierto, pero varía mucho según la antena del módulo).
2. Mientras estás en eso, aprovechá para probar el watchdog a propósito: alejate hasta que se corte la señal y confirmá que el motor se detiene solo (no debería seguir andando "a ciegas").
3. Confirmar el centrado físico del servo (¿90° del firmware coincide con las ruedas derechas del chasis?, si no, ajustar el brazo del servo o calibrar — ver Fase 4).
4. Cuando quieras seguir con pulido (Fase 4): indicador de conexión/latencia y de ángulo de dirección en el tablero de la app ya están anotados como pendientes futuros, no urgentes.

---

## 1. Arquitectura general

```
[Batería] ──┬─→ [Buck 5V/3A] ──┬─→ ESP32-CAM (5V/GND)
            │                  └─→ Servo MG996R (V+/GND) + capacitor 470-1000uF
            └─→ [Driver H-bridge] ──→ Motor DC 6-12V

ESP32-CAM:
  - GPIO servo (PWM 50Hz)      → Servo señal
  - 2 GPIO dirección + 1 PWM   → Driver H-bridge (IN1, IN2, ENA)
  - WiFi en modo AP            → "ESP32CAR" (SSID propio, sin internet)
  - HTTP server puerto 80      → recibe comandos de control
  - Stream MJPEG puerto 81     → video en vivo

Celular Android:
  - Se conecta a la red WiFi "ESP32CAR"
  - App Kotlin/Compose, diseño "cabina de auto": WebView con el stream,
    volante rotable (dirección) + palanca vertical auto-centrada (velocidad)
    + velocímetro HUD → HTTP requests a 192.168.4.1:80/control cada 150ms
```

**Por qué AP y no WiFi de casa/estacionamiento**: como no hay WiFi en el lugar de uso, el ESP32 debe ser el punto de acceso. El celular se conecta directo a él (rango típico 20-50m en exterior abierto, menos con obstáculos).

**Diagrama visual de cableado** (fuente de banco/batería + buck converter + L298N + servo + motor + ESP32-CAM, con mapa de pines y secuencia de encendido): `docs/cableado-puente-h.html` — abrir directo en cualquier navegador.

## 2. Riesgos técnicos a tener en cuenta (importante)

- **Pines libres limitados**: el ESP32-CAM (AI-Thinker) usa casi todos los GPIO para la cámara. Los libres reales son **2, 4, 12, 13, 14, 15** (corrección: GPIO16 NO está libre en este módulo — está conectado a la PSRAM externa, confirmado por los build flags del board `esp32cam`; usarlo como GPIO corrompería la PSRAM). El **GPIO12** es un pin de "strapping": si algo lo fuerza a HIGH durante el arranque, el ESP32 puede no bootear — hay que evitar conectarle algo que lo jale alto al encender, así que se dejó sin usar. Asignación actual (ver `firmware/src/drive.h`): GPIO13 = servo, GPIO14/15 = dirección motor (L298N IN1/IN2), GPIO2 = PWM velocidad (L298N ENA).
- **Brownouts por el servo**: el MG996R puede pedir picos de hasta ~2A al moverse. Si comparte regulador con el ESP32-CAM, causa caídas de voltaje que resetean la cámara/WiFi a media operación. Por eso el servo y el ESP32-CAM deben alimentarse desde un buck de al menos 3A, con un capacitor grande cerca del servo.
- **Tierra común obligatoria**: batería, driver, servo y ESP32-CAM deben compartir GND.
- **Flasheo del ESP32-CAM**: no tiene USB propio (por eso usas el adaptador CH340). Para subir código hay que puentear GPIO0 a GND antes de resetear/alimentar, y quitar el puente para ejecutar normal. Es el paso que más se olvida y da más dolores de cabeza.
- **Android y redes sin internet**: Android (incluido Android 16) tiende a desconectar o enrutar tráfico por datos móviles cuando detecta que el WiFi conectado no tiene internet. Hay que manejar esto en la app con `ConnectivityManager` pidiendo explícitamente la red WiFi sin capacidad de internet (`NetworkRequest` + `bindProcessToNetwork`), si no los comandos pueden fallar de forma intermitente.
- **Watchdog de seguridad**: si el celular sale de rango o se cae la conexión, el motor debe detenerse solo. El firmware debe parar el motor si no recibe un comando nuevo en, por ejemplo, 500 ms.

## 3. Lista de compras (cerrada, lista para pedir)

**Ítems 1, 2, 3 y 7 ya conseguidos y probados con hardware real** (L298N, batería LiPo, buck converter, fuente de banco) — ver Fase 2 en la sección Progreso.

| # | Ítem | Recomendación | Cant. | Motivo |
|---|---|---|---|---|
| 1 | Driver H-bridge | **L298N** (módulo con disipador) | 1 | Barato, fácil de conseguir, tolerante a errores de cableado — ideal para MVP. Alternativa más eficiente: TB6612FNG. |
| 2 | Batería | **LiPo 2S 7.4V, 1300-2200mAh** con conector XT60/JST + cargador balanceador | 1 | Estándar en chasis RC, encaja en el rango 6-12V del motor. Alternativa más segura/simple de cargar: pack de 2x18650 con protección. |
| 3 | Buck converter | Módulo step-down 5V, mínimo 3A (ej. LM2596 o MP1584) | 1 | Alimenta ESP32-CAM + servo separado del motor, evita brownouts. |
| 4 | Capacitor electrolítico | 470-1000 µF, ≥16V | 2 | Amortigua picos de corriente del servo (uno de repuesto — son baratos). |
| 5 | Interruptor on/off | Cualquiera, para el paquete de batería | 1 | Evita desconectar cables para apagar. |
| 6 | Cables Dupont (M-M, M-H, H-H) + protoboard pequeña o perfboard | — | 1 set | Cableado entre módulos. |
| 7 | Fuente de banco ajustable | 0-30V, mínimo 3A, con **límite de corriente ajustable** | 1 | Para probar motor/servo/L298N sin arriesgar el LiPo — si hay un error de cableado, el límite de corriente evita quemar componentes. El límite de corriente es la característica clave, no solo el rango de voltaje. |

Ya tienes: chasis 4WD, servo MG996R, motor DC, ESP32-CAM, adaptador CH340, celular Android, multímetro.

## 4. Fases del desarrollo

**Fase 0 — Identificación y entorno**
- Confirmar modelo exacto del ESP32-CAM (esptool).
- Instalar PlatformIO (o Arduino IDE si lo prefieres).
- Crear proyecto base en Android Studio.

**Fase 1 — Firmware: solo cámara** ✅ (ver sección Progreso)

**Fase 2 — Firmware: control de motor y servo** ✅ (ver sección Progreso)
- Agregar PWM para servo (centrado/min/max) y control de dirección+velocidad del motor vía L298N.
- Exponer endpoints HTTP simples (`/control?steer=90&throttle=120`) y probarlos manualmente desde el navegador o `curl` antes de tocar la app.
- Implementar el watchdog de seguridad (parar motor sin comandos recientes).

**Fase 3 — App Android v0 (MVP funcional)** ✅ (ver sección Progreso)
- Vista con el stream (WebView apuntando al MJPEG).
- Dos sliders (dirección y velocidad) o joystick simple enviando HTTP requests.
- Manejo de conexión a red WiFi sin internet (bindProcessToNetwork).
- Prueba end-to-end: manejar el carro viendo la imagen en vivo.

**Fase 4 — Pulido y seguridad**
- Indicador de conexión/latencia en la app (tablero de cabina).
- Indicador visual de ángulo de dirección en el tablero (redundante con la palanca, estético).
- Pantalla de calibración del servo (ajustar centro/límites sin reflashear).
- Manejo de reconexión automática.

**Fase 5 — Mejoras futuras (post-MVP)**
- Reemplazar sliders por joystick virtual táctil.
- Telemetría de voltaje de batería (divisor resistivo a un ADC libre).
- Grabación de video o snapshots.

## 5. Estructura del repo (ya creada)

```
ESP32_carro/
├── PLAN.md
├── firmware/       (proyecto PlatformIO para el ESP32-CAM)
├── android-app/    (proyecto Android Studio)
└── docs/           (diagramas de cableado, notas de pines)
```
