#include "equimos.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "driver/gpio.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define TOUCH_GPIO GPIO_NUM_2
#define BOOT_GPIO GPIO_NUM_9
#define AP_SSID "EQUIMOS-C6"
#define AP_PASSWORD "equimos2026"

static const char *TAG = "equimos";
static volatile bool recording = false;

static void start_access_point(void) {
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_ap();

    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&init));

    wifi_config_t ap = {0};
    memcpy(ap.ap.ssid, AP_SSID, sizeof(AP_SSID) - 1);
    memcpy(ap.ap.password, AP_PASSWORD, sizeof(AP_PASSWORD) - 1);
    ap.ap.ssid_len = sizeof(AP_SSID) - 1;
    ap.ap.channel = 1;
    ap.ap.max_connection = 1;
    ap.ap.authmode = WIFI_AUTH_WPA2_PSK;

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap));
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_LOGI(TAG, "Wi-Fi EQUIMOS-C6 pronto em 192.168.4.1");
}

static void toggle_recording(void) {
    if (!bridge_is_connected()) {
        display_set_status("CONECTE O APP");
        return;
    }
    recording = !recording;
    display_set_recording(recording);
    display_set_status(recording ? "OUVINDO" : "PROCESSANDO");
    bridge_send_frame(recording ? EQ_FRAME_START : EQ_FRAME_END, NULL, 0);
}

static void button_task(void *arg) {
    (void)arg;
    gpio_config_t touch = {
        .pin_bit_mask = 1ULL << TOUCH_GPIO,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_ENABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config_t boot = {
        .pin_bit_mask = 1ULL << BOOT_GPIO,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_ERROR_CHECK(gpio_config(&touch));
    ESP_ERROR_CHECK(gpio_config(&boot));

    int last_touch = 0;
    int last_boot = 1;
    int64_t last_change = 0;
    while (true) {
        const int touch_now = gpio_get_level(TOUCH_GPIO);
        const int boot_now = gpio_get_level(BOOT_GPIO);
        const int64_t now = esp_timer_get_time();
        if ((touch_now && !last_touch) || (!boot_now && last_boot)) {
            if (now - last_change > 250000) {
                toggle_recording();
                last_change = now;
            }
        }
        last_touch = touch_now;
        last_boot = boot_now;
        vTaskDelay(pdMS_TO_TICKS(25));
    }
}

static void microphone_task(void *arg) {
    (void)arg;
    int16_t frame[EQUIMOS_FRAME_SAMPLES];
    while (true) {
        if (!recording || !bridge_is_connected()) {
            vTaskDelay(pdMS_TO_TICKS(20));
            continue;
        }
        if (audio_read_mono(frame, EQUIMOS_FRAME_SAMPLES)) {
            bridge_send_frame(EQ_FRAME_MIC_PCM, frame,
                              EQUIMOS_FRAME_SAMPLES * sizeof(int16_t));
        } else {
            vTaskDelay(pdMS_TO_TICKS(5));
        }
    }
}

void app_main(void) {
    setenv("TZ", "UTC3", 1);
    tzset();
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ESP_ERROR_CHECK(nvs_flash_init());
    } else {
        ESP_ERROR_CHECK(err);
    }

    start_access_point();
    display_init();
    display_set_status("PRONTO PARA CONECTAR");
    audio_init();
    bridge_init();

    xTaskCreate(display_task, "equimos_display", 4096, NULL, 4, NULL);
    xTaskCreate(button_task, "equimos_buttons", 2048, NULL, 6, NULL);
    xTaskCreate(microphone_task, "equimos_microphone", 4096, NULL, 5, NULL);
}
