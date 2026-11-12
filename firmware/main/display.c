#include "equimos.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "driver/spi_master.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_st7789.h"
#include "esp_timer.h"
#include "esp_heap_caps.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define LCD_HOST SPI2_HOST
#define LCD_PIN_MOSI GPIO_NUM_7
#define LCD_PIN_CLK GPIO_NUM_6
#define LCD_PIN_DC GPIO_NUM_4
#define LCD_PIN_RST GPIO_NUM_3
#define LCD_PIN_CS GPIO_NUM_5
#define DRAW_ROWS 32

static const uint32_t colors[] = {
    0x050A14, 0x111B2A, 0x00D9F5, 0x8CF5FF,
    0xF4F8FF, 0x598EFF, 0xA873FF, 0xFF4FB7,
    0xFFB547, 0xFFE39A, 0x71849D, 0x32E6A1,
    0xFF667D, 0x1B3049, 0x34465D, 0xFFFFFF
};

static uint16_t rgb565(uint32_t rgb) {
    return (uint16_t)(((rgb >> 8) & 0xF800) |
                      ((rgb >> 5) & 0x07E0) |
                      ((rgb >> 3) & 0x001F));
}

static esp_lcd_panel_handle_t panel;
static uint8_t *canvas;
static uint16_t *line_buffer;
static TaskHandle_t display_task_handle;
static portMUX_TYPE state_lock = portMUX_INITIALIZER_UNLOCKED;
static char status_text[48] = "PRONTO";
static char reminder_text[48] = "";
static float temperature_c = -999.0f;
static double external_moon_phase = -1.0;
static int page_index = 0;
static bool recording = false;
static bool blink = false;

static bool lcd_color_done(esp_lcd_panel_io_handle_t panel_io,
                           esp_lcd_panel_io_event_data_t *event_data,
                           void *user_ctx) {
    (void)panel_io;
    (void)event_data;
    TaskHandle_t *task = (TaskHandle_t *)user_ctx;
    if (task != NULL && *task != NULL) {
        BaseType_t higher_priority_task_woken = pdFALSE;
        vTaskNotifyGiveFromISR(*task, &higher_priority_task_woken);
        return higher_priority_task_woken == pdTRUE;
    }
    return false;
}

static void pixel(int x, int y, uint8_t color) {
    if (x >= 0 && x < EQUIMOS_WIDTH && y >= 0 && y < EQUIMOS_HEIGHT) {
        canvas[y * EQUIMOS_WIDTH + x] = color;
    }
}

static void fill(uint8_t color) {
    memset(canvas, color, EQUIMOS_WIDTH * EQUIMOS_HEIGHT);
}

static void rect(int x, int y, int w, int h, uint8_t color) {
    if (w <= 0 || h <= 0) return;
    int x0 = x < 0 ? 0 : x;
    int y0 = y < 0 ? 0 : y;
    int x1 = x + w > EQUIMOS_WIDTH ? EQUIMOS_WIDTH : x + w;
    int y1 = y + h > EQUIMOS_HEIGHT ? EQUIMOS_HEIGHT : y + h;
    for (int yy = y0; yy < y1; ++yy) {
        memset(&canvas[yy * EQUIMOS_WIDTH + x0], color, (size_t)(x1 - x0));
    }
}

static void circle(int cx, int cy, int radius, uint8_t color) {
    int rr = radius * radius;
    for (int y = -radius; y <= radius; ++y) {
        for (int x = -radius; x <= radius; ++x) {
            if (x * x + y * y <= rr) pixel(cx + x, cy + y, color);
        }
    }
}

static void glyph(char ch, uint8_t out[5]) {
    memset(out, 0, 5);
    if (ch >= 'a' && ch <= 'z') ch = (char)(ch - 'a' + 'A');
    switch (ch) {
        case 'A': {uint8_t a[]={0x7E,0x11,0x11,0x11,0x7E}; memcpy(out,a,5); break;}
        case 'B': {uint8_t a[]={0x7F,0x49,0x49,0x49,0x36}; memcpy(out,a,5); break;}
        case 'C': {uint8_t a[]={0x3E,0x41,0x41,0x41,0x22}; memcpy(out,a,5); break;}
        case 'D': {uint8_t a[]={0x7F,0x41,0x41,0x22,0x1C}; memcpy(out,a,5); break;}
        case 'E': {uint8_t a[]={0x7F,0x49,0x49,0x49,0x41}; memcpy(out,a,5); break;}
        case 'F': {uint8_t a[]={0x7F,0x09,0x09,0x09,0x01}; memcpy(out,a,5); break;}
        case 'G': {uint8_t a[]={0x3E,0x41,0x49,0x49,0x7A}; memcpy(out,a,5); break;}
        case 'H': {uint8_t a[]={0x7F,0x08,0x08,0x08,0x7F}; memcpy(out,a,5); break;}
        case 'I': {uint8_t a[]={0x00,0x41,0x7F,0x41,0x00}; memcpy(out,a,5); break;}
        case 'J': {uint8_t a[]={0x20,0x40,0x41,0x3F,0x01}; memcpy(out,a,5); break;}
        case 'K': {uint8_t a[]={0x7F,0x08,0x14,0x22,0x41}; memcpy(out,a,5); break;}
        case 'L': {uint8_t a[]={0x7F,0x40,0x40,0x40,0x40}; memcpy(out,a,5); break;}
        case 'M': {uint8_t a[]={0x7F,0x02,0x0C,0x02,0x7F}; memcpy(out,a,5); break;}
        case 'N': {uint8_t a[]={0x7F,0x04,0x08,0x10,0x7F}; memcpy(out,a,5); break;}
        case 'O': {uint8_t a[]={0x3E,0x41,0x41,0x41,0x3E}; memcpy(out,a,5); break;}
        case 'P': {uint8_t a[]={0x7F,0x09,0x09,0x09,0x06}; memcpy(out,a,5); break;}
        case 'Q': {uint8_t a[]={0x3E,0x41,0x51,0x21,0x5E}; memcpy(out,a,5); break;}
        case 'R': {uint8_t a[]={0x7F,0x09,0x19,0x29,0x46}; memcpy(out,a,5); break;}
        case 'S': {uint8_t a[]={0x46,0x49,0x49,0x49,0x31}; memcpy(out,a,5); break;}
        case 'T': {uint8_t a[]={0x01,0x01,0x7F,0x01,0x01}; memcpy(out,a,5); break;}
        case 'U': {uint8_t a[]={0x3F,0x40,0x40,0x40,0x3F}; memcpy(out,a,5); break;}
        case 'V': {uint8_t a[]={0x1F,0x20,0x40,0x20,0x1F}; memcpy(out,a,5); break;}
        case 'W': {uint8_t a[]={0x3F,0x40,0x38,0x40,0x3F}; memcpy(out,a,5); break;}
        case 'X': {uint8_t a[]={0x63,0x14,0x08,0x14,0x63}; memcpy(out,a,5); break;}
        case 'Y': {uint8_t a[]={0x07,0x08,0x70,0x08,0x07}; memcpy(out,a,5); break;}
        case 'Z': {uint8_t a[]={0x61,0x51,0x49,0x45,0x43}; memcpy(out,a,5); break;}
        case '0': {uint8_t a[]={0x3E,0x51,0x49,0x45,0x3E}; memcpy(out,a,5); break;}
        case '1': {uint8_t a[]={0x00,0x42,0x7F,0x40,0x00}; memcpy(out,a,5); break;}
        case '2': {uint8_t a[]={0x62,0x51,0x49,0x49,0x46}; memcpy(out,a,5); break;}
        case '3': {uint8_t a[]={0x22,0x49,0x49,0x49,0x36}; memcpy(out,a,5); break;}
        case '4': {uint8_t a[]={0x18,0x14,0x12,0x7F,0x10}; memcpy(out,a,5); break;}
        case '5': {uint8_t a[]={0x2F,0x49,0x49,0x49,0x31}; memcpy(out,a,5); break;}
        case '6': {uint8_t a[]={0x3E,0x49,0x49,0x49,0x32}; memcpy(out,a,5); break;}
        case '7': {uint8_t a[]={0x01,0x71,0x09,0x05,0x03}; memcpy(out,a,5); break;}
        case '8': {uint8_t a[]={0x36,0x49,0x49,0x49,0x36}; memcpy(out,a,5); break;}
        case '9': {uint8_t a[]={0x26,0x49,0x49,0x49,0x3E}; memcpy(out,a,5); break;}
        case ':': {uint8_t a[]={0x00,0x36,0x36,0x00,0x00}; memcpy(out,a,5); break;}
        case '.': {uint8_t a[]={0x00,0x60,0x60,0x00,0x00}; memcpy(out,a,5); break;}
        case '-': {uint8_t a[]={0x08,0x08,0x08,0x08,0x08}; memcpy(out,a,5); break;}
        case '/': {uint8_t a[]={0x20,0x10,0x08,0x04,0x02}; memcpy(out,a,5); break;}
        case '+': {uint8_t a[]={0x08,0x08,0x3E,0x08,0x08}; memcpy(out,a,5); break;}
        case '%': {uint8_t a[]={0x63,0x13,0x08,0x64,0x63}; memcpy(out,a,5); break;}
        case '!': {uint8_t a[]={0x00,0x00,0x5F,0x00,0x00}; memcpy(out,a,5); break;}
        case '?': {uint8_t a[]={0x02,0x01,0x51,0x09,0x06}; memcpy(out,a,5); break;}
        case ',': {uint8_t a[]={0x00,0x50,0x30,0x00,0x00}; memcpy(out,a,5); break;}
        case ' ': default: break;
    }
}

static void text(const char *value, int x, int y, int scale, uint8_t color) {
    if (!value) return;
    for (const char *p = value; *p; ++p) {
        uint8_t bits[5];
        glyph(*p, bits);
        for (int col = 0; col < 5; ++col) {
            for (int row = 0; row < 7; ++row) {
                if (bits[col] & (1U << row)) {
                    rect(x + col * scale, y + row * scale, scale, scale, color);
                }
            }
        }
        x += 6 * scale;
        if (x >= EQUIMOS_WIDTH - 4) break;
    }
}

static void line(int x0, int y0, int x1, int y1, uint8_t color) {
    int dx = abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
    int dy = -abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
    int err = dx + dy;
    while (true) {
        pixel(x0, y0, color);
        if (x0 == x1 && y0 == y1) break;
        int e2 = 2 * err;
        if (e2 >= dy) { err += dy; x0 += sx; }
        if (e2 <= dx) { err += dx; y0 += sy; }
    }
}

static double moon_phase(time_t now) {
    const double synodic = 29.530588853;
    const double reference = 947182440.0;
    double days = ((double)now - reference) / 86400.0;
    double phase = fmod(days / synodic, 1.0);
    return phase < 0 ? phase + 1.0 : phase;
}

static void draw_header(const char *page_name) {
    rect(0, 0, EQUIMOS_WIDTH, EQUIMOS_HEIGHT, 0);
    text("EQUIMOS", 9, 8, 2, 2);
    text(page_name, 218, 10, 1, 10);
    rect(9, 29, 302, 1, 13);
}

static void draw_home(time_t now, float temp, const char *status) {
    draw_header("C6");
    struct tm local_time = {0};
    char clock[8] = "--:--";
    if (now > 1600000000) {
        localtime_r(&now, &local_time);
        strftime(clock, sizeof(clock), "%H:%M", &local_time);
    }
    text(clock, 112, 43, 4, 4);

    text("SARANDI", 12, 50, 1, 10);
    if (temp > -100.0f) {
        char t[16];
        snprintf(t, sizeof(t), "%.1f C", (double)temp);
        text(t, 255, 49, 1, 8);
    } else {
        text("-- C", 258, 49, 1, 8);
    }

    int eye_y = 105;
    int eye_h = blink ? 5 : 34;
    int eye_w = blink ? 44 : 74;
    rect(62, eye_y, eye_w, eye_h, 2);
    rect(184, eye_y, eye_w, eye_h, 2);
    line(62, eye_y, 82, eye_y - 8, 3);
    line(184, eye_y, 204, eye_y - 8, 3);
    if (!blink) {
        circle(96, eye_y + 17, 5, 4);
        circle(218, eye_y + 17, 5, 4);
    }
    if (recording) {
        circle(302, 42, 7, 12);
        text("OUVINDO", 112, 187, 1, 2);
    } else {
        text(status && status[0] ? status : "PRONTO", 124, 187, 1, 10);
    }
    text("TOQUE PARA FALAR", 92, 218, 1, 10);
}

static void draw_moon(time_t now, double supplied_phase) {
    double phase = supplied_phase >= 0.0 ? supplied_phase : moon_phase(now);
    double angle = 6.283185307179586 * phase;
    double terminator = cos(angle);
    draw_header("LUA");
    text("FASE ATUAL", 15, 45, 1, 10);
    const char *name = phase < 0.03 || phase > 0.97 ? "NOVA" :
        phase < 0.22 ? "CRESCENTE" :
        phase < 0.28 ? "QUARTO CRESC" :
        phase < 0.47 ? "GIBOSA CRESC" :
        phase < 0.53 ? "CHEIA" :
        phase < 0.72 ? "GIBOSA MING" :
        phase < 0.78 ? "QUARTO MING" : "MINGUANTE";
    text(name, 15, 64, 1, 2);

    int cx = 222, cy = 141, r = 76;
    circle(cx, cy, r + 4, 5);
    circle(cx, cy, r, 14);
    for (int y = -r; y <= r; ++y) {
        double span = sqrt((double)(r * r - y * y));
        for (int x = -r; x <= r; ++x) {
            if ((double)(x * x + y * y) > (double)(r * r)) continue;
            bool lit = phase < 0.5 ? (x >= terminator * span)
                                   : (x <= -terminator * span);
            if (lit) pixel(cx + x, cy + y, 9);
        }
    }
    text("ILUMINACAO", 15, 113, 1, 10);
    double illumination = (1.0 - cos(angle)) / 2.0;
    char percent[12];
    snprintf(percent, sizeof(percent), "%.0f%%", illumination * 100.0);
    text(percent, 15, 132, 2, 4);
    text("CALCULADA NO EQUIMOS", 15, 188, 1, 10);
}

static void draw_agenda(const char *reminder, const char *status) {
    draw_header("AGENDA");
    text("PROXIMO AVISO", 15, 50, 1, 10);
    if (reminder && reminder[0]) {
        text(reminder, 15, 75, 2, 4);
    } else {
        text("SEM LEMBRETES", 15, 83, 2, 10);
    }
    text("APP", 15, 162, 1, 10);
    text(status && status[0] ? status : "DESCONECTADO", 15, 181, 1, 2);
    text("ALARME NO CELULAR", 15, 218, 1, 10);
}

static void flush_canvas(void) {
    for (int y = 0; y < EQUIMOS_HEIGHT; y += DRAW_ROWS) {
        int rows = EQUIMOS_HEIGHT - y;
        if (rows > DRAW_ROWS) rows = DRAW_ROWS;
        for (int row = 0; row < rows; ++row) {
            const uint8_t *src = &canvas[(y + row) * EQUIMOS_WIDTH];
            uint16_t *dst = &line_buffer[row * EQUIMOS_WIDTH];
            for (int x = 0; x < EQUIMOS_WIDTH; ++x) {
                dst[x] = rgb565(colors[src[x] & 0x0F]);
            }
        }
        ESP_ERROR_CHECK(esp_lcd_panel_draw_bitmap(
                panel, 0, y, EQUIMOS_WIDTH, y + rows, line_buffer));
        if (ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(250)) == 0) {
            ESP_LOGW("equimos_display", "Timeout na transferencia da tela");
        }
    }
}

void display_init(void) {
    canvas = heap_caps_calloc(EQUIMOS_WIDTH * EQUIMOS_HEIGHT, 1, MALLOC_CAP_INTERNAL);
    line_buffer = heap_caps_malloc(EQUIMOS_WIDTH * DRAW_ROWS * sizeof(uint16_t),
                                   MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
    if (canvas == NULL || line_buffer == NULL) {
        ESP_LOGE("equimos_display", "Sem memoria para a tela");
        abort();
    }

    spi_bus_config_t bus = {
        .sclk_io_num = LCD_PIN_CLK,
        .mosi_io_num = LCD_PIN_MOSI,
        .miso_io_num = -1,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = EQUIMOS_WIDTH * DRAW_ROWS * sizeof(uint16_t),
    };
    ESP_ERROR_CHECK(spi_bus_initialize(LCD_HOST, &bus, SPI_DMA_CH_AUTO));

    esp_lcd_panel_io_spi_config_t io_config = {
        .dc_gpio_num = LCD_PIN_DC,
        .cs_gpio_num = LCD_PIN_CS,
        .pclk_hz = 20000000,
        .spi_mode = 0,
        .trans_queue_depth = 1,
        .on_color_trans_done = lcd_color_done,
        .user_ctx = &display_task_handle,
        .lcd_cmd_bits = 8,
        .lcd_param_bits = 8,
    };
    esp_lcd_panel_io_handle_t io = NULL;
    ESP_ERROR_CHECK(esp_lcd_new_panel_io_spi((esp_lcd_spi_bus_handle_t)LCD_HOST,
                                             &io_config, &io));
    esp_lcd_panel_dev_config_t panel_config = {
        .reset_gpio_num = LCD_PIN_RST,
        .rgb_ele_order = LCD_RGB_ELEMENT_ORDER_RGB,
        .data_endian = LCD_RGB_DATA_ENDIAN_LITTLE,
        .bits_per_pixel = 16,
    };
    ESP_ERROR_CHECK(esp_lcd_new_panel_st7789(io, &panel_config, &panel));
    ESP_ERROR_CHECK(esp_lcd_panel_reset(panel));
    ESP_ERROR_CHECK(esp_lcd_panel_init(panel));
    ESP_ERROR_CHECK(esp_lcd_panel_invert_color(panel, true));
    ESP_ERROR_CHECK(esp_lcd_panel_swap_xy(panel, true));
    ESP_ERROR_CHECK(esp_lcd_panel_mirror(panel, true, false));
    ESP_ERROR_CHECK(esp_lcd_panel_disp_on_off(panel, true));
}

void display_set_status(const char *text_value) {
    if (text_value == NULL) return;
    portENTER_CRITICAL(&state_lock);
    snprintf(status_text, sizeof(status_text), "%s", text_value);
    portEXIT_CRITICAL(&state_lock);
}

void display_set_recording(bool value) {
    portENTER_CRITICAL(&state_lock);
    recording = value;
    portEXIT_CRITICAL(&state_lock);
}

void display_apply_status(const char *text_value, float temp_c, int64_t epoch,
                          double moon_phase_value, const char *reminder) {
    portENTER_CRITICAL(&state_lock);
    if (text_value) snprintf(status_text, sizeof(status_text), "%s", text_value);
    if (temp_c > -100.0f) temperature_c = temp_c;
    if (moon_phase_value >= 0.0 && moon_phase_value <= 1.0)
        external_moon_phase = moon_phase_value;
    if (reminder) snprintf(reminder_text, sizeof(reminder_text), "%s", reminder);
    portEXIT_CRITICAL(&state_lock);
    (void)epoch;
}

void display_task(void *arg) {
    (void)arg;
    display_task_handle = xTaskGetCurrentTaskHandle();
    int64_t started = esp_timer_get_time();
    int64_t last_blink = started;
    while (true) {
        char status[48], reminder[48];
        float temp;
        double phase;
        bool is_recording;
        portENTER_CRITICAL(&state_lock);
        memcpy(status, status_text, sizeof(status));
        memcpy(reminder, reminder_text, sizeof(reminder));
        temp = temperature_c;
        phase = external_moon_phase;
        is_recording = recording;
        portEXIT_CRITICAL(&state_lock);

        time_t now = time(NULL);
        int64_t ticks = esp_timer_get_time();
        if (ticks - last_blink > 4200000) {
            blink = !blink;
            last_blink = ticks;
        }
        int64_t elapsed = ticks - started;
        int next_page = (int)((elapsed / 40000000) % 3);
        if (next_page != page_index) page_index = next_page;

        if (page_index == 0) draw_home(now, temp, status);
        else if (page_index == 1) draw_moon(now, phase);
        else draw_agenda(reminder, status);
        flush_canvas();
        (void)is_recording;
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
