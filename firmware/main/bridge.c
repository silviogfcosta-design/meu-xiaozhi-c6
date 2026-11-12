#include "equimos.h"

#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

#include "cJSON.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

static const char *TAG = "equimos_bridge";
static volatile int client_socket = -1;
static SemaphoreHandle_t send_lock;

static bool receive_exact(int fd, void *buffer, size_t length) {
    uint8_t *out = (uint8_t *)buffer;
    size_t offset = 0;
    while (offset < length) {
        int got = recv(fd, out + offset, length - offset, 0);
        if (got <= 0) {
            return false;
        }
        offset += (size_t)got;
    }
    return true;
}

static bool send_exact(int fd, const void *buffer, size_t length) {
    const uint8_t *data = (const uint8_t *)buffer;
    size_t offset = 0;
    while (offset < length) {
        int sent = send(fd, data + offset, length - offset, 0);
        if (sent <= 0) {
            return false;
        }
        offset += (size_t)sent;
    }
    return true;
}

bool bridge_is_connected(void) {
    return client_socket >= 0;
}

bool bridge_send_frame(uint8_t type, const void *payload, uint32_t length) {
    if (client_socket < 0 || length > 65536 || send_lock == NULL) {
        return false;
    }
    uint8_t header[5];
    header[0] = type;
    header[1] = (uint8_t)(length >> 24);
    header[2] = (uint8_t)(length >> 16);
    header[3] = (uint8_t)(length >> 8);
    header[4] = (uint8_t)length;

    bool ok = false;
    if (xSemaphoreTake(send_lock, pdMS_TO_TICKS(1000)) == pdTRUE) {
        int fd = client_socket;
        ok = fd >= 0 && send_exact(fd, header, sizeof(header)) &&
             (length == 0 || send_exact(fd, payload, length));
        xSemaphoreGive(send_lock);
    }
    return ok;
}

static void apply_status(const char *json_text) {
    cJSON *root = cJSON_Parse(json_text);
    if (root == NULL) {
        return;
    }
    const cJSON *status = cJSON_GetObjectItemCaseSensitive(root, "status");
    const cJSON *temp = cJSON_GetObjectItemCaseSensitive(root, "tempC");
    const cJSON *epoch = cJSON_GetObjectItemCaseSensitive(root, "epoch");
    const cJSON *moon = cJSON_GetObjectItemCaseSensitive(root, "moonPhase");
    const cJSON *reminder = cJSON_GetObjectItemCaseSensitive(root, "reminder");

    float temp_c = cJSON_IsNumber(temp) ? (float)temp->valuedouble : -999.0f;
    int64_t unix_time = cJSON_IsNumber(epoch) ? (int64_t)epoch->valuedouble : 0;
    double phase = cJSON_IsNumber(moon) ? moon->valuedouble : -1.0;
    const char *status_text = cJSON_IsString(status) ? status->valuestring : "";
    const char *reminder_text = cJSON_IsString(reminder) ? reminder->valuestring : "";

    if (unix_time > 1600000000) {
        struct timeval tv = {.tv_sec = (time_t)unix_time, .tv_usec = 0};
        settimeofday(&tv, NULL);
    }
    display_apply_status(status_text, temp_c, unix_time, phase, reminder_text);
    cJSON_Delete(root);
}

static void handle_client(int fd) {
    display_set_status("APP CONECTADO");
    ESP_LOGI(TAG, "Aplicativo Equimos conectado");

    while (true) {
        uint8_t header[5];
        if (!receive_exact(fd, header, sizeof(header))) {
            break;
        }
        uint8_t type = header[0];
        uint32_t length = ((uint32_t)header[1] << 24) |
                          ((uint32_t)header[2] << 16) |
                          ((uint32_t)header[3] << 8) |
                          (uint32_t)header[4];
        if (length > 65536) {
            ESP_LOGW(TAG, "Quadro maior que o limite");
            break;
        }
        if (type == EQ_FRAME_STATUS && length > 0) {
            if (length > 1024) {
                ESP_LOGW(TAG, "Status JSON maior que o limite");
                break;
            }
            char *json = malloc(length + 1);
            if (json == NULL || !receive_exact(fd, json, length)) {
                free(json);
                break;
            }
            json[length] = '\0';
            apply_status(json);
            free(json);
        } else if (type == EQ_FRAME_SPEAKER_PCM && length > 0 &&
                   length <= 4096 && (length % sizeof(int16_t)) == 0) {
            int16_t pcm[2048];
            if (!receive_exact(fd, pcm, length)) {
                break;
            }
            audio_play_mono(pcm, length / sizeof(int16_t));
        } else if (length > 0) {
            uint8_t discard[128];
            uint32_t left = length;
            while (left > 0) {
                uint32_t part = left > sizeof(discard) ? sizeof(discard) : left;
                if (!receive_exact(fd, discard, part)) {
                    left = UINT32_MAX;
                    break;
                }
                left -= part;
            }
            if (left == UINT32_MAX) {
                break;
            }
        }
    }
}

static void bridge_task(void *arg) {
    (void)arg;
    send_lock = xSemaphoreCreateMutex();
    if (send_lock == NULL) {
        vTaskDelete(NULL);
        return;
    }

    int server = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
    if (server < 0) {
        ESP_LOGE(TAG, "Nao foi possivel criar o servidor TCP");
        vTaskDelete(NULL);
        return;
    }
    int reuse = 1;
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    struct sockaddr_in address = {
        .sin_family = AF_INET,
        .sin_port = htons(EQUIMOS_TCP_PORT),
        .sin_addr.s_addr = htonl(INADDR_ANY),
    };
    if (bind(server, (struct sockaddr *)&address, sizeof(address)) != 0 ||
        listen(server, 1) != 0) {
        ESP_LOGE(TAG, "Falha ao abrir a porta %d", EQUIMOS_TCP_PORT);
        close(server);
        vTaskDelete(NULL);
        return;
    }

    while (true) {
        struct sockaddr_in peer;
        socklen_t peer_len = sizeof(peer);
        int fd = accept(server, (struct sockaddr *)&peer, &peer_len);
        if (fd < 0) {
            vTaskDelay(pdMS_TO_TICKS(250));
            continue;
        }
        client_socket = fd;
        handle_client(fd);
        if (xSemaphoreTake(send_lock, pdMS_TO_TICKS(1000)) == pdTRUE) {
            client_socket = -1;
            xSemaphoreGive(send_lock);
        } else {
            client_socket = -1;
        }
        shutdown(fd, SHUT_RDWR);
        close(fd);
        display_set_recording(false);
        display_set_status("APP DESCONECTADO");
        ESP_LOGI(TAG, "Aplicativo Equimos desconectado");
    }
}

void bridge_init(void) {
    xTaskCreate(bridge_task, "equimos_bridge", 10240, NULL, 5, NULL);
}
