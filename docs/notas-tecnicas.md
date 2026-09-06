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

## Nota adicional: reset del ESP32 al abrir el puerto serial

La base de programación (ESP32-CAM-MB, con CH340) resetea el chip cada vez que se abre el puerto serial desde una PC (circuito de auto-programación en RTS/DTR). Esto mata cualquier conexión activa del celular al stream. Al depurar por serial, hay que recargar la página en el celular después de abrir el monitor serial, no antes.
