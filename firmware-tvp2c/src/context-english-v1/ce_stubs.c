#include "ce_stubs.h"
/* See ce_stubs.h for why these exist. Inert by construction: no session was ever
 * created on the 9th flash, so the deleted originals provably returned false/did
 * nothing at every call site (AUDIT-DORMANT-MODULES-611.md §2.3, emu-verified). */
bool ce_wants_gesture(const CESession *s) {
    return s && s->available && (s->phase==CE_INVITE || s->phase==CE_QUESTION)
        && (uint32_t)(s->now-s->since)>=1000;
}
bool ce_native_visible(void *app){(void)app;return false;}
bool ce_native_handle_event(void *e){(void)e;return false;}
void ce_native_head_observe(void *monitor,unsigned mapped,unsigned raw){(void)monitor;(void)mapped;(void)raw;}
