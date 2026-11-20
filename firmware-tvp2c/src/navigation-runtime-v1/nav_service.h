#ifndef TURBO_NAV_SERVICE_H
#define TURBO_NAV_SERVICE_H
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include "../image-upload-test/native_file_bridge.h"   /* TIO* types for the service headers that include us */
/* 2026-11-16 tenth-flash slimming: navigation app half physically removed
 * (nav_runtime/nav_view/nav_visual + tn_file_receive + tn_message_* + tn_slot_open gone).
 * This header keeps ONLY the hub: the slot rack + tf_back_blocked + m8_hook_vm_event.
 * Field layout FROZEN (focus_menu_native.c asserts offsetof(quad)==44 / sizeof==68);
 * deleted-module fields stay as unused void* slots; the TIO_MUSIC_RUNTIME /
 * TD_DIAGNOSTICS_RUNTIME guards are preserved verbatim so the layout is bit-identical
 * under every flag combination. */
typedef struct {void *row,*label,*icon,*dot,*app,*control;
#if TIO_MUSIC_RUNTIME
 void *music;
 void *reader;
#if TD_DIAGNOSTICS_RUNTIME
 void *diagnostics;
 void *focus;
#endif
#endif
 uint32_t back_until;
 void *quad;
 void *ride;
 void *apps;
 void *english;
 void *dots;
 void *camera;
} TNNavSlot;
static inline bool tf_back_blocked(TNNavSlot *s,uint32_t now){return s&&s->back_until&&(int32_t)(s->back_until-now)>0;}
TNNavSlot *tn_slot_create(void *app);
void tn_slot_destroy(TNNavSlot *),tn_slot_hidden(TNNavSlot *);
bool tn_slot_visible(TNNavSlot *);
void m8_hook_vm_event(void *event);
#endif
