#pragma once

// Control de motor DC (via L298N) y servo de direccion para el ESP32-CAM.
//
// Pines usados (libres en el AI-Thinker sin SD card):
//   GPIO13 -> señal servo (PWM 50Hz)
//   GPIO14 -> L298N IN1
//   GPIO15 -> L298N IN2
//   GPIO2  -> L298N ENA (PWM velocidad)
//
// GPIO12 se evita a proposito: es pin de "strapping" (selecciona voltaje de
// flash al boot) y algunos drivers/modulos externos traen pull-ups en sus
// entradas que podrian forzarlo alto durante el arranque e impedir bootear.
// GPIO16/17 tambien se evitan: en este modulo estan conectados a la PSRAM
// externa (confirmado por los build flags -mfix-esp32-psram-cache-issue del
// board esp32cam) y no son GPIO de proposito general.

#include <Arduino.h>

static const int SERVO_PIN = 13;
static const int MOTOR_IN1_PIN = 14;
static const int MOTOR_IN2_PIN = 15;
static const int MOTOR_ENA_PIN = 2;

// Canales LEDC: el driver esp32-camera usa el canal 0 internamente para el
// XCLK de la camara (ver init_camera() en main.cpp), asi que evitamos ese
// canal aqui para no pisarlo.
//
// IMPORTANTE: en el ESP32 original, cada PAR de canales comparte un mismo
// timer de hardware (formula real del driver: timer = (canal/2) % 4). Los
// canales 4 y 5 caen en el MISMO timer (timer 2) — si se configuran con
// frecuencia/resolucion distintas (como aqui: servo 50Hz/16-bit vs motor
// 5000Hz/8-bit), la segunda llamada a ledcSetup() pisa la configuracion de
// la primera sin avisar. Resultado real observado: el canal del servo
// terminaba corriendo a 5000Hz/8-bit, y como el codigo le seguia mandando
// valores de duty calculados para 16-bit, se saturaban al maximo (255) y
// el pin quedaba fijo en HIGH sin importar el angulo pedido (confirmado
// con multimetro: 3.34V constante en GPIO13 para cualquier steer).
// Fix: usar canales de PARES distintos para servo y motor. Canal 4 -> timer
// 2, canal 6 -> timer 3 (no comparten). Se evitan tambien los canales 0/1
// (timer 0, usado por la camara).
static const int SERVO_LEDC_CHANNEL = 4;
static const int MOTOR_LEDC_CHANNEL = 6;

static const int SERVO_FREQ_HZ = 50;
static const int SERVO_PWM_BITS = 16;
static const int SERVO_MIN_US = 500;   // 0 grados
static const int SERVO_MAX_US = 2500;  // 180 grados
static const int SERVO_CENTER_DEG = 90;

static const int MOTOR_PWM_FREQ_HZ = 5000;
static const int MOTOR_PWM_BITS = 8; // duty 0-255

static void drive_init() {
    ledcSetup(SERVO_LEDC_CHANNEL, SERVO_FREQ_HZ, SERVO_PWM_BITS);
    ledcAttachPin(SERVO_PIN, SERVO_LEDC_CHANNEL);
    ledcSetup(MOTOR_LEDC_CHANNEL, MOTOR_PWM_FREQ_HZ, MOTOR_PWM_BITS);
    ledcAttachPin(MOTOR_ENA_PIN, MOTOR_LEDC_CHANNEL);
    pinMode(MOTOR_IN1_PIN, OUTPUT);
    pinMode(MOTOR_IN2_PIN, OUTPUT);
    digitalWrite(MOTOR_IN1_PIN, LOW);
    digitalWrite(MOTOR_IN2_PIN, LOW);
}

// angle_deg: 0-180, donde 90 es centro.
static void drive_set_steering(int angle_deg) {
    angle_deg = constrain(angle_deg, 0, 180);
    long pulse_us = map(angle_deg, 0, 180, SERVO_MIN_US, SERVO_MAX_US);
    uint32_t max_duty = (1UL << SERVO_PWM_BITS) - 1;
    uint32_t duty = (uint32_t)((pulse_us * max_duty) / (1000000UL / SERVO_FREQ_HZ));
    ledcWrite(SERVO_LEDC_CHANNEL, duty);
}

// throttle: -255..255. Positivo = adelante, negativo = reversa, 0 = detenido.
static void drive_set_throttle(int throttle) {
    throttle = constrain(throttle, -255, 255);
    if (throttle > 0) {
        digitalWrite(MOTOR_IN1_PIN, HIGH);
        digitalWrite(MOTOR_IN2_PIN, LOW);
    } else if (throttle < 0) {
        digitalWrite(MOTOR_IN1_PIN, LOW);
        digitalWrite(MOTOR_IN2_PIN, HIGH);
    } else {
        digitalWrite(MOTOR_IN1_PIN, LOW);
        digitalWrite(MOTOR_IN2_PIN, LOW);
    }
    ledcWrite(MOTOR_LEDC_CHANNEL, abs(throttle));
}

static void drive_stop() {
    drive_set_throttle(0);
}
