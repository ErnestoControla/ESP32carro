# Rediseño Visual "Cabina de Auto" y Velocímetro

Este plan detalla el rediseño de la interfaz de usuario para que parezca la cabina de un auto, incluyendo un nuevo velocímetro analógico y una reorganización del código para mejorar la mantenibilidad.

## Cambios Propuestos

### 1. Organización de Archivos
Se dividirá `MainActivity.kt` en archivos separados por responsabilidad para facilitar el desarrollo y futuras actualizaciones.

- `MainActivity.kt`: Punto de entrada de la aplicación.
- `CarNetwork.kt`: Lógica de conexión WiFi y envío de comandos.
- `CarScreen.kt`: Layout principal de la cabina.
- `AnalogStick.kt`: Componente de control (re-skin visual).
- `Speedometer.kt`: Nuevo componente de velocímetro analógico.

---

### 2. Componentes UI

#### [NEW] [Speedometer.kt](file:///home/ernesto/Documentos/Proyectos/ESP32_carro/android-app/app/src/main/kotlin/com/esp32carro/app/ui/components/Speedometer.kt)
- Implementación de `SpeedometerGauge` usando `Canvas`.
- Rango de 0% a 100% basado en `abs(throttle)`.
- Aguja animada con `animateFloatAsState`.
- Indicador de reversa "R" que se activa cuando `throttle < 0`.

#### [MODIFY] [AnalogStick.kt](file:///home/ernesto/Documentos/Proyectos/ESP32_carro/android-app/app/src/main/kotlin/com/esp32carro/app/ui/components/AnalogStick.kt)
- Re-skin visual para estética de cabina:
  - Marco con bordes metálicos o texturizados.
  - Knob con diseño más industrial/deportivo.
  - Mantendrá la lógica actual de gestos y auto-centrado.

#### [MODIFY] [CarScreen.kt](file:///home/ernesto/Documentos/Proyectos/ESP32_carro/android-app/app/src/main/kotlin/com/esp32carro/app/ui/CarScreen.kt)
- Nuevo layout horizontal:
  - **Izquierda**: AnalogStick de Dirección.
  - **Centro**: WebView (Parabrisas) expandido.
  - **Debajo/Superpuesto**: Velocímetro HUD.
  - **Derecha**: AnalogStick de Velocidad.
- Fondo oscuro con acentos de color (rojo/naranja).

---

### 3. Lógica y Red

#### [NEW] [CarNetwork.kt](file:///home/ernesto/Documentos/Proyectos/ESP32_carro/android-app/app/src/main/kotlin/com/esp32carro/app/network/CarNetwork.kt)
- Traslado de `rememberCarConnection` y `sendControl`.
- Sin cambios en la lógica validada (intervalo de 150ms, bind de red).

---

## Plan de Verificación

### Pruebas Manuales
1. **Compilación**: Ejecutar `./gradlew assembleDebug` para asegurar que la refactorización no rompió nada.
2. **Interfaz**: Verificar visualmente el nuevo layout en modo landscape.
3. **Velocímetro**: Confirmar que la aguja se mueve suavemente y el indicador "R" se ilumina al ir en reversa.
4. **Controles**: Asegurar que las palancas siguen funcionando y auto-centrándose correctamente.
5. **Video**: Confirmar que el WebView sigue mostrando el stream en su nueva ubicación.
