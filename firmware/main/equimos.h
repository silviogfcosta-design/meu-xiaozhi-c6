#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define EQUIMOS_WIDTH 320
#define EQUIMOS_HEIGHT 240
#define EQUIMOS_SAMPLE_RATE 16000
#define EQUIMOS_FRAME_SAMPLES 320
#define EQUIMOS_TCP_PORT 3761

enum {
    EQ_FRAME_START = 0x01,
    EQ_FRAME_MIC_PCM = 0x02,
    EQ_FRAME_END = 0x03,
    EQ_FRAME_STATUS = 0x20,
    EQ_FRAME_SPEAKER_PCM = 0x21
};

void display_init(void);
void display_task(void *arg);
void display_set_status(const char *text);
void display_set_recording(bool recording);
void display_apply_status(const char *text, float temp_c, int64_t epoch,
                          double moon_phase, const char *reminder);

void audio_init(void);
bool audio_read_mono(int16_t *samples, size_t requested);
void audio_play_mono(const int16_t *samples, size_t count);

void bridge_init(void);
bool bridge_is_connected(void);
bool bridge_send_frame(uint8_t type, const void *payload, uint32_t length);
