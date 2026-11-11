#include "orion_ui.h"
#include "lvgl.h"

#include <cmath>
#include <cstdio>
#include <ctime>

namespace {
lv_obj_t* root = nullptr;
lv_obj_t* clock_label = nullptr;
lv_obj_t* temp_label = nullptr;
lv_obj_t* status_label = nullptr;
lv_obj_t* eyes[2] = {nullptr, nullptr};
lv_timer_t* clock_timer = nullptr;
lv_timer_t* blink_timer = nullptr;
bool eyes_closed = false;

void clear_object(lv_obj_t* object) {
    lv_obj_set_style_bg_opa(object, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(object, 0, 0);
    lv_obj_set_style_pad_all(object, 0, 0);
    lv_obj_remove_flag(object, LV_OBJ_FLAG_SCROLLABLE);
}

void set_large_font(lv_obj_t* object) {
#if defined(LV_FONT_MONTSERRAT_28) && LV_FONT_MONTSERRAT_28
    lv_obj_set_style_text_font(object, &lv_font_montserrat_28, 0);
#elif defined(LV_FONT_MONTSERRAT_20) && LV_FONT_MONTSERRAT_20
    lv_obj_set_style_text_font(object, &lv_font_montserrat_20, 0);
#endif
}

void set_small_font(lv_obj_t* object) {
#if defined(LV_FONT_MONTSERRAT_14) && LV_FONT_MONTSERRAT_14
    lv_obj_set_style_text_font(object, &lv_font_montserrat_14, 0);
#endif
}

void update_clock(lv_timer_t*) {
    if (clock_label == nullptr) {
        return;
    }

    const time_t now = time(nullptr);
    tm local_time{};
    localtime_r(&now, &local_time);

    char text[12];
    if (now < 1600000000) {
        std::snprintf(text, sizeof(text), "--:--");
    } else {
        std::snprintf(text, sizeof(text), "%02d:%02d",
                      local_time.tm_hour, local_time.tm_min);
    }
    lv_label_set_text(clock_label, text);
}

void blink(lv_timer_t* timer) {
    eyes_closed = !eyes_closed;
    for (lv_obj_t* eye : eyes) {
        if (eye != nullptr) {
            lv_obj_set_height(eye, eyes_closed ? 5 : 38);
            lv_obj_set_y(eye, eyes_closed ? 39 : 22);
        }
    }
    lv_timer_set_period(timer, eyes_closed ? 150 : 3900);
}
}  // namespace

extern "C" void ui_orion_start() {
    lv_obj_t* screen = lv_screen_active();

    if (root != nullptr && lv_obj_is_valid(root)) {
        lv_obj_delete(root);
    }
    if (clock_timer != nullptr) {
        lv_timer_delete(clock_timer);
        clock_timer = nullptr;
    }
    if (blink_timer != nullptr) {
        lv_timer_delete(blink_timer);
        blink_timer = nullptr;
    }

    const int width = lv_obj_get_width(screen);
    const int height = lv_obj_get_height(screen);

    root = lv_obj_create(screen);
    lv_obj_set_size(root, width, height);
    lv_obj_center(root);
    lv_obj_set_style_radius(root, 0, 0);
    lv_obj_set_style_bg_color(root, lv_color_hex(0x070D16), 0);
    lv_obj_set_style_border_width(root, 0, 0);
    lv_obj_set_style_pad_all(root, 0, 0);
    lv_obj_remove_flag(root, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t* top_line = lv_obj_create(root);
    lv_obj_set_size(top_line, width - 16, 1);
    lv_obj_align(top_line, LV_ALIGN_TOP_MID, 0, 32);
    lv_obj_set_style_bg_color(top_line, lv_color_hex(0x164258), 0);
    lv_obj_set_style_bg_opa(top_line, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(top_line, 0, 0);

    lv_obj_t* name = lv_label_create(root);
    lv_label_set_text(name, "ORION");
    lv_obj_set_style_text_color(name, lv_color_hex(0x00E5FF), 0);
    set_small_font(name);
    lv_obj_align(name, LV_ALIGN_TOP_LEFT, 9, 8);

    clock_label = lv_label_create(root);
    lv_label_set_text(clock_label, "--:--");
    lv_obj_set_style_text_color(clock_label, lv_color_white(), 0);
    set_large_font(clock_label);
    lv_obj_align(clock_label, LV_ALIGN_TOP_MID, 0, 4);

    temp_label = lv_label_create(root);
    lv_label_set_text(temp_label, "-- C");
    lv_obj_set_style_text_color(temp_label, lv_color_hex(0xFFB347), 0);
    set_small_font(temp_label);
    lv_obj_align(temp_label, LV_ALIGN_TOP_RIGHT, -9, 9);

    lv_obj_t* face = lv_obj_create(root);
    lv_obj_set_size(face, width > 230 ? 224 : width - 20, 92);
    lv_obj_align(face, LV_ALIGN_CENTER, 0, 4);
    clear_object(face);

    for (int i = 0; i < 2; ++i) {
        eyes[i] = lv_obj_create(face);
        lv_obj_set_size(eyes[i], 64, 38);
        lv_obj_set_style_bg_color(eyes[i], lv_color_hex(0x00D9F5), 0);
        lv_obj_set_style_bg_opa(eyes[i], LV_OPA_COVER, 0);
        lv_obj_set_style_radius(eyes[i], 10, 0);
        lv_obj_set_style_border_width(eyes[i], 2, 0);
        lv_obj_set_style_border_color(eyes[i], lv_color_hex(0x8CF5FF), 0);
        lv_obj_set_style_shadow_width(eyes[i], 15, 0);
        lv_obj_set_style_shadow_color(eyes[i], lv_color_hex(0x00A8C7), 0);
        lv_obj_align(eyes[i], i == 0 ? LV_ALIGN_LEFT_MID : LV_ALIGN_RIGHT_MID,
                     i == 0 ? 15 : -15, 0);
    }

    status_label = lv_label_create(root);
    lv_label_set_text(status_label, "PRONTO");
    lv_obj_set_style_text_color(status_label, lv_color_hex(0x91A8BB), 0);
    set_small_font(status_label);
    lv_obj_align(status_label, LV_ALIGN_BOTTOM_MID, 0, -8);

    eyes_closed = false;
    clock_timer = lv_timer_create(update_clock, 1000, nullptr);
    blink_timer = lv_timer_create(blink, 3900, nullptr);
    update_clock(nullptr);
}

extern "C" void ui_orion_set_temperature(float celsius) {
    if (temp_label == nullptr || !std::isfinite(celsius)) {
        return;
    }

    char text[24];
    std::snprintf(text, sizeof(text), "%.1f C", static_cast<double>(celsius));
    lv_label_set_text(temp_label, text);
}

extern "C" void ui_orion_set_status(const char* status) {
    if (status_label != nullptr && status != nullptr) {
        lv_label_set_text(status_label, status);
    }
}
