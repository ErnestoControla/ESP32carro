// Fase 1 — ESP32-CAM en modo Access Point con stream MJPEG.
// El celular se conecta a la red WiFi "ESP32CAR" (sin internet) y abre
// http://192.168.4.1/ para ver el video en vivo servido en /stream (puerto 81).

#include <Arduino.h>
#include <WiFi.h>
#include <esp_camera.h>
#include <esp_http_server.h>
#include <lwip/sockets.h>
#include "camera_pins.h"
#include "drive.h"

static const char *AP_SSID = "ESP32CAR";
static const char *AP_PASSWORD = "carro1234"; // min. 8 caracteres para WPA2

// Si no llega un /control nuevo en este tiempo, se detiene el motor.
// El servo se queda en su ultima posicion (no es peligroso que no se mueva).
static const uint32_t COMMAND_TIMEOUT_MS = 500;
static volatile uint32_t last_command_ms = 0;
static volatile bool motor_running = false;

static httpd_handle_t stream_httpd = nullptr;
static httpd_handle_t index_httpd = nullptr;

// Contadores de diagnostico expuestos por /status. Existen para poder
// investigar fallas intermitentes de /stream sin depender del monitor
// serial: el adaptador CH340 de esta placa resetea el ESP32 cada vez que
// se abre el puerto serial desde esta PC (ver notas-tecnicas.md), asi que
// leer el log serial destruye el estado que se queria observar. Por HTTP
// se puede consultar sin tocar la placa para nada.
static volatile uint32_t diag_stream_attempts = 0;
static volatile uint32_t diag_stream_fb_fail = 0;
static volatile uint32_t diag_stream_frames_sent = 0;
static volatile uint32_t diag_last_fb_get_ms = 0;
static volatile uint32_t diag_max_fb_get_ms = 0;

static const char INDEX_HTML[] = R"HTML(
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>ESP32 Carro</title>
<style>
  /* El <img> necesita un tamaño resuelto ANTES de tener datos: con solo
     max-width/max-height (dependiente del tamaño intrinseco de la imagen)
     algunos WebView de Android nunca recalculan el layout cuando el
     recurso multipart/x-mixed-replace "cambia" de contenido, y el <img>
     queda con clientWidth/clientHeight en 0 para siempre aunque
     naturalWidth/naturalHeight sean correctos (confirmado con Chrome
     DevTools remoto, ver docs/notas-tecnicas.md). Fix: que el tamaño del
     <img> dependa del contenedor (width/height:100%), no de si misma. */
  /* position:fixed dimensiona contra el viewport directamente (el
     "initial containing block"), sin depender de que html/body resuelvan
     bien height:100% en cascada -- eso fue lo que fallaba: bodyClientHeight
     quedaba en 0 en este WebView aunque html.clientHeight si reportaba el
     alto real del viewport (confirmado con Chrome DevTools remoto). */
  html, body { margin:0; padding:0; background:#111; }
  img { position:fixed; top:0; left:0; width:100%; height:100%; object-fit:contain; }
</style>
</head>
<body>
  <img src="http://192.168.4.1:81/stream" />
</body>
</html>
)HTML";

static esp_err_t index_handler(httpd_req_t *req) {
    httpd_resp_set_type(req, "text/html");
    return httpd_resp_send(req, INDEX_HTML, HTTPD_RESP_USE_STRLEN);
}

#define PART_BOUNDARY "frame"
static const char *STREAM_CONTENT_TYPE = "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char *STREAM_BOUNDARY = "\r\n--" PART_BOUNDARY "\r\n";
static const char *STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

// El servidor de streaming solo procesa un cliente a la vez (un unico task
// bloqueado en el while(true) de este handler). Si un cliente se cae sin
// cerrar prolijamente la conexion TCP (ej. corte breve de WiFi, celular
// bloqueado), el socket queda "vivo" del lado del ESP32 y bloquea a
// cualquier cliente nuevo para siempre, sin ningun error visible, hasta
// reiniciar el ESP32 a mano. Fix: timeout de envio en el socket para que un
// cliente muerto se detecte y libere solo.
static const int STREAM_SOCKET_TIMEOUT_SEC = 3;

static esp_err_t stream_handler(httpd_req_t *req) {
    Serial.println("[stream] cliente conectado, handler iniciado");
    diag_stream_attempts++;

    int sockfd = httpd_req_to_sockfd(req);
    if (sockfd >= 0) {
        struct timeval tv;
        tv.tv_sec = STREAM_SOCKET_TIMEOUT_SEC;
        tv.tv_usec = 0;
        setsockopt(sockfd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        setsockopt(sockfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    }

    camera_fb_t *fb = nullptr;
    esp_err_t res = httpd_resp_set_type(req, STREAM_CONTENT_TYPE);
    if (res != ESP_OK) {
        Serial.printf("[stream] httpd_resp_set_type fallo: 0x%x\n", res);
        return res;
    }

    char part_buf[64];
    uint32_t frame_n = 0;

    while (true) {
        uint32_t t0 = millis();
        fb = esp_camera_fb_get();
        uint32_t dt = millis() - t0;
        frame_n++;
        diag_last_fb_get_ms = dt;
        if (dt > diag_max_fb_get_ms) diag_max_fb_get_ms = dt;
        if (dt > 200) {
            Serial.printf("[stream] fb_get #%u tardo %ums\n", frame_n, dt);
        }
        if (!fb) {
            Serial.println("Fallo al capturar frame");
            diag_stream_fb_fail++;
            res = ESP_FAIL;
        } else {
            if (fb->format != PIXFORMAT_JPEG) {
                Serial.println("Formato de frame no es JPEG, se descarta");
                esp_camera_fb_return(fb);
                res = ESP_FAIL;
            } else {
                res = httpd_resp_send_chunk(req, STREAM_BOUNDARY, strlen(STREAM_BOUNDARY));
                if (res == ESP_OK) {
                    size_t hlen = snprintf(part_buf, sizeof(part_buf), STREAM_PART, fb->len);
                    res = httpd_resp_send_chunk(req, part_buf, hlen);
                }
                if (res == ESP_OK) {
                    res = httpd_resp_send_chunk(req, (const char *)fb->buf, fb->len);
                }
                if (res == ESP_OK) diag_stream_frames_sent++;
                esp_camera_fb_return(fb);
            }
        }
        if (res != ESP_OK) {
            Serial.printf("[stream] saliendo del handler en frame #%u, res=0x%x\n", frame_n, res);
            break;
        }
    }
    return res;
}

// GET /capture — foto unica (no streaming) con el flash prendido durante la
// toma. Sirve para aislar si el problema es el loop continuo de /stream o
// algo mas basico en esp_camera_fb_get(), y si la falta de luz influye.
static esp_err_t capture_handler(httpd_req_t *req) {
    digitalWrite(FLASH_GPIO_NUM, HIGH);
    delay(150); // deja que el sensor reajuste exposicion con el flash prendido

    uint32_t t0 = millis();
    camera_fb_t *fb = esp_camera_fb_get();
    uint32_t dt = millis() - t0;

    digitalWrite(FLASH_GPIO_NUM, LOW);

    Serial.printf("[capture] fb_get tardo %ums, fb=%s\n", dt, fb ? "OK" : "NULL");

    if (!fb) {
        httpd_resp_set_status(req, "500 Internal Server Error");
        return httpd_resp_send(req, "Fallo al capturar frame", HTTPD_RESP_USE_STRLEN);
    }
    if (fb->format != PIXFORMAT_JPEG) {
        esp_camera_fb_return(fb);
        httpd_resp_set_status(req, "500 Internal Server Error");
        return httpd_resp_send(req, "Formato de frame no es JPEG", HTTPD_RESP_USE_STRLEN);
    }

    httpd_resp_set_type(req, "image/jpeg");
    esp_err_t res = httpd_resp_send(req, (const char *)fb->buf, fb->len);
    esp_camera_fb_return(fb);
    return res;
}

// GET /status — contadores de diagnostico en texto plano, para investigar
// fallas intermitentes de /stream sin necesidad de abrir el monitor serial
// (que resetea esta placa al abrirse, ver comentario junto a los diag_*).
static esp_err_t status_handler(httpd_req_t *req) {
    char buf[256];
    int len = snprintf(buf, sizeof(buf),
        "stream_attempts=%u\n"
        "stream_fb_fail=%u\n"
        "stream_frames_sent=%u\n"
        "last_fb_get_ms=%u\n"
        "max_fb_get_ms=%u\n"
        "uptime_ms=%lu\n",
        diag_stream_attempts, diag_stream_fb_fail, diag_stream_frames_sent,
        diag_last_fb_get_ms, diag_max_fb_get_ms, millis());
    httpd_resp_set_type(req, "text/plain");
    return httpd_resp_send(req, buf, len);
}

// GET /control?steer=<0-180>&throttle=<-255..255>
// Se exige mandar ambos parametros en cada llamada: asi no hay que
// recordar estado entre requests y el watchdog solo necesita vigilar
// "cuando fue el ultimo /control", sin importar que contenia.
static esp_err_t control_handler(httpd_req_t *req) {
    char query[64];
    char val[16];

    if (httpd_req_get_url_query_str(req, query, sizeof(query)) != ESP_OK ||
        httpd_query_key_value(query, "steer", val, sizeof(val)) != ESP_OK) {
        httpd_resp_set_status(req, "400 Bad Request");
        return httpd_resp_send(req, "Falta el parametro steer", HTTPD_RESP_USE_STRLEN);
    }
    int steer = constrain(atoi(val), 0, 180);

    if (httpd_query_key_value(query, "throttle", val, sizeof(val)) != ESP_OK) {
        httpd_resp_set_status(req, "400 Bad Request");
        return httpd_resp_send(req, "Falta el parametro throttle", HTTPD_RESP_USE_STRLEN);
    }
    int throttle = constrain(atoi(val), -255, 255);

    drive_set_steering(steer);
    drive_set_throttle(throttle);
    motor_running = (throttle != 0);
    last_command_ms = millis();

    Serial.printf("[control] steer=%d throttle=%d\n", steer, throttle);

    char resp[48];
    int len = snprintf(resp, sizeof(resp), "OK steer=%d throttle=%d\n", steer, throttle);
    httpd_resp_set_type(req, "text/plain");
    return httpd_resp_send(req, resp, len);
}

static void start_camera_server() {
    httpd_config_t index_config = HTTPD_DEFAULT_CONFIG();
    index_config.server_port = 80;
    index_config.ctrl_port = 32080;

    httpd_uri_t index_uri = {
        .uri = "/",
        .method = HTTP_GET,
        .handler = index_handler,
        .user_ctx = nullptr,
    };

    httpd_uri_t control_uri = {
        .uri = "/control",
        .method = HTTP_GET,
        .handler = control_handler,
        .user_ctx = nullptr,
    };

    httpd_uri_t capture_uri = {
        .uri = "/capture",
        .method = HTTP_GET,
        .handler = capture_handler,
        .user_ctx = nullptr,
    };

    httpd_uri_t status_uri = {
        .uri = "/status",
        .method = HTTP_GET,
        .handler = status_handler,
        .user_ctx = nullptr,
    };

    esp_err_t index_start_res = httpd_start(&index_httpd, &index_config);
    Serial.printf("[http] index_httpd (puerto %d) httpd_start: 0x%x\n", index_config.server_port, index_start_res);
    if (index_start_res == ESP_OK) {
        httpd_register_uri_handler(index_httpd, &index_uri);
        httpd_register_uri_handler(index_httpd, &control_uri);
        httpd_register_uri_handler(index_httpd, &capture_uri);
        httpd_register_uri_handler(index_httpd, &status_uri);
    }

    httpd_config_t stream_config = HTTPD_DEFAULT_CONFIG();
    stream_config.server_port = 81;
    stream_config.ctrl_port = 32081;

    httpd_uri_t stream_uri = {
        .uri = "/stream",
        .method = HTTP_GET,
        .handler = stream_handler,
        .user_ctx = nullptr,
    };

    esp_err_t stream_start_res = httpd_start(&stream_httpd, &stream_config);
    Serial.printf("[http] stream_httpd (puerto %d) httpd_start: 0x%x\n", stream_config.server_port, stream_start_res);
    if (stream_start_res == ESP_OK) {
        esp_err_t reg_res = httpd_register_uri_handler(stream_httpd, &stream_uri);
        Serial.printf("[http] registro de /stream: 0x%x\n", reg_res);
    }
}

static bool init_camera() {
    // Workaround: en el ESP32-CAM AI-Thinker el pin RESET de la camara no
    // esta conectado (pin_reset = -1). El driver esp32-camera solo baja
    // PWDN a LOW dentro de la secuencia de reset, asi que si no hay pin de
    // reset, PWDN se queda flotando en HIGH y la camara nunca enciende.
    // Forzamos PWDN a LOW manualmente antes de inicializar.
    pinMode(PWDN_GPIO_NUM, OUTPUT);
    digitalWrite(PWDN_GPIO_NUM, LOW);
    delay(10);

    camera_config_t config = {};
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer = LEDC_TIMER_0;
    config.pin_d0 = Y2_GPIO_NUM;
    config.pin_d1 = Y3_GPIO_NUM;
    config.pin_d2 = Y4_GPIO_NUM;
    config.pin_d3 = Y5_GPIO_NUM;
    config.pin_d4 = Y6_GPIO_NUM;
    config.pin_d5 = Y7_GPIO_NUM;
    config.pin_d6 = Y8_GPIO_NUM;
    config.pin_d7 = Y9_GPIO_NUM;
    config.pin_xclk = XCLK_GPIO_NUM;
    config.pin_pclk = PCLK_GPIO_NUM;
    config.pin_vsync = VSYNC_GPIO_NUM;
    config.pin_href = HREF_GPIO_NUM;
    config.pin_sccb_sda = SIOD_GPIO_NUM;
    config.pin_sccb_scl = SIOC_GPIO_NUM;
    config.pin_pwdn = PWDN_GPIO_NUM;
    config.pin_reset = RESET_GPIO_NUM;
    config.xclk_freq_hz = 20000000;
    config.pixel_format = PIXFORMAT_JPEG;

    if (psramFound()) {
        // Medimos que el cuello de botella real es el envio por WiFi
        // (cientos de ms por frame en VGA), no la captura. Para
        // control en vivo importa mas la latencia que la resolucion,
        // asi que usamos QVGA para que cada frame pese mucho menos.
        config.frame_size = FRAMESIZE_QVGA;
        config.jpeg_quality = 12;
        config.fb_count = 2;
        config.fb_location = CAMERA_FB_IN_PSRAM;
        config.grab_mode = CAMERA_GRAB_LATEST;
    } else {
        config.frame_size = FRAMESIZE_QVGA;
        config.jpeg_quality = 15;
        config.fb_count = 1;
        config.fb_location = CAMERA_FB_IN_DRAM;
        config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
    }

    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) {
        Serial.printf("Fallo al iniciar la camara: 0x%x\n", err);
        return false;
    }

    // Ajuste post-init recomendado por el ejemplo oficial CameraWebServer
    // de Espressif para el sensor OV3660 (el que trae este modulo). No
    // arregla el problema de frames JPEG con estructura no estandar que
    // documentamos en docs/notas-tecnicas.md (confirmado con y sin este
    // bloque, los bytes salen identicos) — se deja de todas formas porque
    // es la calibracion recomendada para este sensor especifico.
    sensor_t *sensor = esp_camera_sensor_get();
    if (sensor->id.PID == OV3660_PID) {
        sensor->set_vflip(sensor, 1);
        sensor->set_brightness(sensor, 1);
        sensor->set_saturation(sensor, -2);
    }

    return true;
}

void setup() {
    Serial.begin(115200);
    Serial.setDebugOutput(true);

    Serial.printf("PSRAM: %s\n", psramFound() ? "detectada" : "NO detectada");

    if (!init_camera()) {
        Serial.println("No se pudo iniciar la camara, deteniendo.");
        return;
    }

    pinMode(FLASH_GPIO_NUM, OUTPUT);
    digitalWrite(FLASH_GPIO_NUM, LOW);

    drive_init();
    drive_set_steering(SERVO_CENTER_DEG);
    drive_stop();

    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASSWORD);
    // El ahorro de energia (modem sleep) del WiFi introduce pausas
    // periodicas que se ven como tirones en streams continuos como MJPEG.
    // Lo desactivamos porque el carro siempre esta alimentado, no hay
    // necesidad de ahorrar bateria del WiFi.
    WiFi.setSleep(false);
    IPAddress ip = WiFi.softAPIP();
    Serial.printf("AP '%s' listo. IP: %s\n", AP_SSID, ip.toString().c_str());

    start_camera_server();
    Serial.println("Servidor listo. Abrir http://192.168.4.1/ desde el celular conectado al AP.");
}

void loop() {
    if (motor_running && millis() - last_command_ms > COMMAND_TIMEOUT_MS) {
        drive_stop();
        motor_running = false;
        Serial.println("[watchdog] sin comandos recientes, motor detenido");
    }
    delay(50);
}
