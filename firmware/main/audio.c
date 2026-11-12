#include "equimos.h"

#include <stdlib.h>

#include "driver/i2s_std.h"
#include "esp_check.h"
#include "esp_log.h"

static const char *TAG = "equimos_audio";
static i2s_chan_handle_t tx_channel;
static i2s_chan_handle_t rx_channel;

void audio_init(void) {
    i2s_chan_config_t channel_config =
        I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    channel_config.dma_desc_num = 6;
    channel_config.dma_frame_num = 320;

    ESP_ERROR_CHECK(i2s_new_channel(&channel_config, &tx_channel, &rx_channel));

    i2s_std_config_t std_config = {
        .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(EQUIMOS_SAMPLE_RATE),
        .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(
            I2S_DATA_BIT_WIDTH_32BIT, I2S_SLOT_MODE_STEREO),
        .gpio_cfg = {
            .mclk = I2S_GPIO_UNUSED,
            .bclk = GPIO_NUM_18,
            .ws = GPIO_NUM_19,
            .dout = GPIO_NUM_14,
            .din = GPIO_NUM_20,
        },
    };
    std_config.slot_cfg.slot_mask = I2S_STD_SLOT_BOTH;

    ESP_ERROR_CHECK(i2s_channel_init_std_mode(tx_channel, &std_config));
    ESP_ERROR_CHECK(i2s_channel_init_std_mode(rx_channel, &std_config));
    ESP_ERROR_CHECK(i2s_channel_enable(tx_channel));
    ESP_ERROR_CHECK(i2s_channel_enable(rx_channel));
    ESP_LOGI(TAG, "I2S full duplex inicializado em 16 kHz");
}

bool audio_read_mono(int16_t *samples, size_t requested) {
    if (samples == NULL || requested == 0) {
        return false;
    }

    int32_t raw[EQUIMOS_FRAME_SAMPLES * 2];
    size_t bytes_read = 0;
    esp_err_t err = i2s_channel_read(rx_channel, raw, sizeof(raw), &bytes_read,
                                     60);
    if (err != ESP_OK || bytes_read < sizeof(int32_t) * 2) {
        return false;
    }

    size_t frames = bytes_read / (sizeof(int32_t) * 2);
    if (frames > requested) {
        frames = requested;
    }
    for (size_t i = 0; i < frames; ++i) {
        int32_t left = raw[i * 2];
        int32_t right = raw[i * 2 + 1];
        int64_t abs_left = llabs((long long)left);
        int64_t abs_right = llabs((long long)right);
        int32_t active = abs_left >= abs_right ? left : right;
        samples[i] = (int16_t)(active >> 16);
    }
    for (size_t i = frames; i < requested; ++i) {
        samples[i] = 0;
    }
    return true;
}

void audio_play_mono(const int16_t *samples, size_t count) {
    if (samples == NULL || count == 0) {
        return;
    }

    int32_t stereo[512];
    size_t offset = 0;
    while (offset < count) {
        size_t n = count - offset;
        if (n > 256) {
            n = 256;
        }
        for (size_t i = 0; i < n; ++i) {
            int32_t value = ((int32_t)samples[offset + i]) << 16;
            stereo[i * 2] = value;
            stereo[i * 2 + 1] = value;
        }
        size_t written = 0;
        if (i2s_channel_write(tx_channel, stereo,
                              n * 2 * sizeof(int32_t), &written,
                              300) != ESP_OK) {
            break;
        }
        offset += n;
    }
}
