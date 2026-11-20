/* 2026-11-16 tenth-flash slimming: navigation APP half physically removed
 * (Control/tick/enter/render/power/flush/emit/tn_slot_open/tn_file_receive
 * and tn_message_* — they were unreachable since the 8th flash, proven by
 * AUDIT-DORMANT-MODULES-611.md §1.4/§2.2: no callers, emu slot fields zero).
 * This file keeps ONLY the live hub: the slot rack used by every module, and
 * m8_hook_vm_event (patch target "Entry trampoline vm_event").
 * Behavior-identical to the 9th flash: s->control could never exist (control()
 * was unreachable), so the removed nav-view key handling was already dead code. */
#include "../camera-v1/camera_native.h"
#include "../dots-v1/dots_native.h"
#include "../context-english-v1/ce_stubs.h"
#include "../app-runtime-v1/app_service.h"
#include "nav_service.h"
#include "../weread-v1/reader_service.h"
#if TIO_MUSIC_RUNTIME
#include "../music-runtime-v1/music_service.h"
#endif
#include <string.h>
extern void *stream_memalign(size_t,size_t),stream_free(void *);
extern void stock_vm_event(void *);
TNNavSlot *tn_slot_create(void *app){TNNavSlot *s=stream_memalign(8,sizeof *s);if(s){memset(s,0,sizeof *s);s->app=app;}return s;}
void tn_slot_hidden(TNNavSlot *s){(void)s;}           /* nav app removed: no control to retire */
void tn_slot_destroy(TNNavSlot *s){stream_free(s);}    /* s->control never exists post-removal */
bool tn_slot_visible(TNNavSlot *s){(void)s;return false;} /* nav app removed: never visible */
void m8_hook_vm_event(void *event){
#if TIO_MUSIC_RUNTIME
 if(tc_native_event(event)||dots_native_event(event)||ce_native_handle_event(event)||tap_service_event(event)||wr_handle_event(event)||tm_handle_event(event))return;
#endif
 stock_vm_event(event);
}
