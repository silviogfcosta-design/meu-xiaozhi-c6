#include "orion_ui.h"
#include "lvgl.h"
#include <ctime>
#include <cstdio>
#include <cmath>

namespace {
lv_obj_t *root=nullptr,*clock_label=nullptr,*temp_label=nullptr,*status_label=nullptr,*eyes[2]={nullptr,nullptr};
lv_timer_t *clock_timer=nullptr,*blink_timer=nullptr;
bool closed=false;
void clock_cb(lv_timer_t*) {
    if(!clock_label) return;
    time_t t=time(nullptr);
    tm local{}; localtime_r(&t,&local);
    char b[12];
    if(t<1600000000) snprintf(b,sizeof(b),"--:--");
    else snprintf(b,sizeof(b),"%02d:%02d",local.tm_hour,local.tm_min);
    lv_label_set_text(clock_label,b);
}
void blink_cb(lv_timer_t* t) {
    closed=!closed;
    for(auto *eye:eyes) if(eye) lv_obj_set_height(eye,closed?5:38);
    lv_timer_set_period(t,closed?140:4200);
}
void clear(lv_obj_t* o) {
    lv_obj_set_style_bg_opa(o,LV_OPA_TRANSP,0);
    lv_obj_set_style_border_width(o,0,0);
    lv_obj_set_style_pad_all(o,0,0);
    lv_obj_remove_flag(o,LV_OBJ_FLAG_SCROLLABLE);
}
}
extern "C" void ui_orion_start() {
    lv_obj_t *screen=lv_screen_active();
    if(root && lv_obj_is_valid(root)) lv_obj_delete(root);
    if(clock_timer) {lv_timer_delete(clock_timer);clock_timer=nullptr;}
    if(blink_timer) {lv_timer_delete(blink_timer);blink_timer=nullptr;}
    const int w=lv_obj_get_width(screen),h=lv_obj_get_height(screen);
    root=lv_obj_create(screen); lv_obj_set_size(root,w,h);lv_obj_center(root);
    lv_obj_set_style_radius(root,0,0);
    lv_obj_set_style_bg_color(root,lv_color_hex(0x080B10),0);
    lv_obj_set_style_border_width(root,0,0);lv_obj_set_style_pad_all(root,0,0);
    lv_obj_remove_flag(root,LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_t *name=lv_label_create(root);lv_label_set_text(name,"ORION");
    lv_obj_set_style_text_color(name,lv_color_hex(0x00E5FF),0);lv_obj_align(name,LV_ALIGN_TOP_LEFT,8,8);
    clock_label=lv_label_create(root);lv_obj_set_style_text_color(clock_label,lv_color_white(),0);lv_obj_align(clock_label,LV_ALIGN_TOP_MID,0,8);
    temp_label=lv_label_create(root);lv_label_set_text(temp_label,"-- C");
    lv_obj_set_style_text_color(temp_label,lv_color_hex(0xFFB347),0);lv_obj_align(temp_label,LV_ALIGN_TOP_RIGHT,-8,8);
    lv_obj_t *group=lv_obj_create(root);lv_obj_set_size(group,w>210?210:w-12,82);lv_obj_align(group,LV_ALIGN_CENTER,0,0);clear(group);
    for(int i=0;i<2;i++) {
        eyes[i]=lv_obj_create(group);lv_obj_set_size(eyes[i],55,38);
        lv_obj_set_style_bg_color(eyes[i],lv_color_hex(0x00E5FF),0);
        lv_obj_set_style_radius(eyes[i],7,0);lv_obj_set_style_border_width(eyes[i],0,0);
        lv_obj_set_style_pad_all(eyes[i],0,0);
        lv_obj_align(eyes[i],i?LV_ALIGN_RIGHT_MID:LV_ALIGN_LEFT_MID,i?-12:12,0);
    }
    status_label=lv_label_create(root);lv_label_set_text(status_label,"PRONTO");
    lv_obj_set_style_text_color(status_label,lv_color_hex(0x91A8BB),0);lv_obj_align(status_label,LV_ALIGN_BOTTOM_MID,0,-8);
    closed=false;clock_timer=lv_timer_create(clock_cb,1000,nullptr);blink_timer=lv_timer_create(blink_cb,4200,nullptr);clock_cb(nullptr);
}
extern "C" void ui_orion_set_temperature(float c) {
    if(!temp_label || !std::isfinite(c))return;
    char b[24];snprintf(b,sizeof(b),"%.1f C",static_cast<double>(c));lv_label_set_text(temp_label,b);
}
extern "C" void ui_orion_set_status(const char *s) {if(status_label&&s)lv_label_set_text(status_label,s);}
