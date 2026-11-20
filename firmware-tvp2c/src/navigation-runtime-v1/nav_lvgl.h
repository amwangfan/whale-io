#ifndef TURBO_NAV_LVGL_H
#define TURBO_NAV_LVGL_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
/* 2026-11-16 tenth-flash slimming: TNWidgets migrated here from the removed
 * nav_view.h (verbatim). This is the shared LVGL widget-injection contract used by
 * camera/reader/music/apps/FM. enum TNPower migrated verbatim from nav_runtime.h
 * (tn_native_power stays in nav_lvgl.c, which is otherwise untouched). */
typedef struct {
 void *ctx;
 bool (*idle)(void *);
 void *(*root)(void *,void *parent);
 void *(*canvas)(void *,void *parent);
 void *(*label)(void *,void *parent,unsigned font_px);
 void (*place)(void *,void *,int x,int y,int w,int h);
 void (*buffer)(void *,void *,const uint8_t *,unsigned w,unsigned h);
 void (*text_static)(void *,void *,const char *);
 void (*visible)(void *,void *,bool);
 void (*destroy)(void *,void *);
} TNWidgets;
enum TNPower { TN_POWER_RELEASE=0,TN_POWER_WAKE_HOLD=1,TN_POWER_SLEEP=2 };
TNWidgets tn_lvgl_widgets(void);
typedef struct {uint32_t token;} TNNativePower;
/* owns_page must be checked against native launcher/page manager, not merely
 * a stale pointer. When another page takes focus, release only; never force off. */
bool tn_native_power(TNNativePower *,enum TNPower,bool owns_page);
#endif
