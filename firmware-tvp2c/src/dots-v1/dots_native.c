/* 2026-11-16 tenth-flash slimming: Dots module physically removed; this file keeps
 * only the four functions whose call sites stay live (see dots_native.h).
 * 9th-flash behavior is preserved exactly: the DotsNative control could never be
 * created (dots_slot_open unreachable), so every control(false) lookup provably
 * returned NULL and every gate below already evaluated to false / no-op. */
#include "../camera-v1/camera_native.h"
#include "dots_native.h"
#include <stdint.h>
static void *ptr(void *p,unsigned offset){return p?*(void **)((uint8_t *)p+offset):NULL;}
static void *manager(void){return ptr(*(void **)(uintptr_t)0x199595d4,0x15c);}
extern bool ce_extension_idle(void);
extern void dots_stock_notify(void *,unsigned);
bool dots_native_visible(void *app){(void)app;return false;}   /* slot never created */
bool dots_native_event(void *event){(void)event;return false;} /* vm_event chain stub */
void dots_link_notify(void *monitor,unsigned connected){
    dots_stock_notify(monitor,connected); // never replace/unregister native callbacks
    /* 9th flash then scanned the timer list for a DotsNative control that could
     * never exist (emu-proven zero allocations) — the scan was a no-op. */
}
bool dots_business_idle(void){return ce_extension_idle()&&!tc_native_visible(ptr(manager(),0x10))&&!dots_native_visible(ptr(manager(),0x10));}
