#pragma once
#ifdef __cplusplus
extern "C" {
#endif
void ui_orion_start(void);
void ui_orion_set_temperature(float celsius);
void ui_orion_set_status(const char *status);
#ifdef __cplusplus
}
#endif
