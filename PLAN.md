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
- **Fase 2 — Firmware: control de motor y servo**: lógica implementada y validada por HTTP; **falta la prueba física** (sin fuente/driver/multímetro todavía).
  - Endpoint `GET /control?steer=<0-180>&throttle=<-255..255>` en el puerto 80.
  - Validado manualmente desde el navegador del celular: valores dentro de rango se aplican tal cual, fuera de rango se recortan (ej. `steer=999` → `180`), y falta un parámetro responde 400 con mensaje claro.
  - Watchdog implementado (detiene el motor si no llega un `/control` nuevo en 500ms) — no probado aún con motor real conectado.
  - Pendiente cuando haya hardware: confirmar sentido de giro del motor (IN1/IN2), centrar el servo físicamente (puede no coincidir con 90° según el brazo), y verificar que el watchdog sí corta la corriente al motor.
- **Fase 3 — App Android v0**: adelantada mientras llega el hardware de Fase 2.
  - Proyecto Kotlin + Jetpack Compose en `android-app/`, compilable por CLI con `./gradlew` (Gradle 9.7.1, AGP 9.4.0 — Android Studio ya no necesita el plugin `kotlin-android` por separado desde AGP 9).
  - **Funcionando y confirmado**: conexión a la red del carro, layout horizontal (video arriba al centro, palancas analógicas de Dirección/Velocidad a los lados con resorte al centro), envío de `/control` cada 150ms (confirmado llegando al firmware por log serial), watchdog de seguridad.
  - **Sin confirmar al cierre de esta sesión**: el video en el `WebView`. Se vio funcionando en la primerísima prueba (antes de tocar nada de redes), pero durante la depuración de un bug posterior no se pudo re-confirmar porque el ESP32 dejó de responder tras una sesión muy larga (ver `docs/notas-tecnicas.md`, secciones 4 y 5). El código quedó en la versión más simple (`WebView` normal sin trucos), que es la que sí funcionó la primera vez.
  - **Primer paso de la próxima sesión**: con el ESP32 descansado, confirmar visualmente que el video se ve en la app.
  - Bug de conectividad corregido: `NET_CAPABILITY_INTERNET` NO sirve para detectar "esta es la red sin internet del carro" — Android la marca en casi cualquier WiFi por defecto. La app bindea a cualquier WiFi activa, ya que no tiene otro uso.

## Pendiente de tu lado
1. Pedir los componentes de la lista de compras cerrada (sección 3).
2. Al iniciar la próxima sesión: confirmar que el video se vea en la app (con el ESP32 ya descansado).
3. Cuando lleguen los componentes: avisar para hacer la prueba física de Fase 2 (wiring según `firmware/src/drive.h`).

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
  - App Kotlin: WebView/vista con el stream (http://192.168.4.1:81/stream)
  - Controles (sliders o joystick) → HTTP requests a 192.168.4.1:80/control
```

**Por qué AP y no WiFi de casa/estacionamiento**: como no hay WiFi en el lugar de uso, el ESP32 debe ser el punto de acceso. El celular se conecta directo a él (rango típico 20-50m en exterior abierto, menos con obstáculos).

## 2. Riesgos técnicos a tener en cuenta (importante)

- **Pines libres limitados**: el ESP32-CAM (AI-Thinker) usa casi todos los GPIO para la cámara. Los libres reales son **2, 4, 12, 13, 14, 15** (corrección: GPIO16 NO está libre en este módulo — está conectado a la PSRAM externa, confirmado por los build flags del board `esp32cam`; usarlo como GPIO corrompería la PSRAM). El **GPIO12** es un pin de "strapping": si algo lo fuerza a HIGH durante el arranque, el ESP32 puede no bootear — hay que evitar conectarle algo que lo jale alto al encender, así que se dejó sin usar. Asignación actual (ver `firmware/src/drive.h`): GPIO13 = servo, GPIO14/15 = dirección motor (L298N IN1/IN2), GPIO2 = PWM velocidad (L298N ENA).
- **Brownouts por el servo**: el MG996R puede pedir picos de hasta ~2A al moverse. Si comparte regulador con el ESP32-CAM, causa caídas de voltaje que resetean la cámara/WiFi a media operación. Por eso el servo y el ESP32-CAM deben alimentarse desde un buck de al menos 3A, con un capacitor grande cerca del servo.
- **Tierra común obligatoria**: batería, driver, servo y ESP32-CAM deben compartir GND.
- **Flasheo del ESP32-CAM**: no tiene USB propio (por eso usas el adaptador CH340). Para subir código hay que puentear GPIO0 a GND antes de resetear/alimentar, y quitar el puente para ejecutar normal. Es el paso que más se olvida y da más dolores de cabeza.
- **Android y redes sin internet**: Android (incluido Android 16) tiende a desconectar o enrutar tráfico por datos móviles cuando detecta que el WiFi conectado no tiene internet. Hay que manejar esto en la app con `ConnectivityManager` pidiendo explícitamente la red WiFi sin capacidad de internet (`NetworkRequest` + `bindProcessToNetwork`), si no los comandos pueden fallar de forma intermitente.
- **Watchdog de seguridad**: si el celular sale de rango o se cae la conexión, el motor debe detenerse solo. El firmware debe parar el motor si no recibe un comando nuevo en, por ejemplo, 500 ms.

## 3. Lista de compras (cerrada, lista para pedir)

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

**Fase 2 — Firmware: control de motor y servo**
- Agregar PWM para servo (centrado/min/max) y control de dirección+velocidad del motor vía L298N.
- Exponer endpoints HTTP simples (`/control?steer=90&throttle=120`) y probarlos manualmente desde el navegador o `curl` antes de tocar la app.
- Implementar el watchdog de seguridad (parar motor sin comandos recientes).

**Fase 3 — App Android v0 (MVP funcional)**
- Vista con el stream (WebView apuntando al MJPEG).
- Dos sliders (dirección y velocidad) o joystick simple enviando HTTP requests.
- Manejo de conexión a red WiFi sin internet (bindProcessToNetwork).
- Prueba end-to-end: manejar el carro viendo la imagen en vivo.

**Fase 4 — Pulido y seguridad**
- Indicador de conexión/latencia en la app.
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
