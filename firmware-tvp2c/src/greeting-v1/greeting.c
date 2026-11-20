/* Proactive greeting display (F2), minimal viable: the phone pushes a TDP frame to the
 * well-known GREETING_SID and the glasses auto-open the display page (existing TDP runtime)
 * into the menu's own page slot — no manual RX-page open, no hand-typed SID. The page shows
 * the whale image (TDP channel) + a text line, and self-closes on its lease. No-BSS idiom:
 * the page lives in the menu tail's page slot, never in a module global. */
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include "../navigation-runtime-v1/menu9.h"        /* M8NativeTail (with navigation), TDPNativePage */
#include "../navigation-runtime-v1/nav_service.h"   /* TNNavSlot (nav slot rack in the tail) */

#define GREETING_SID 0x47525400u    /* "GRT" — phone always greets on this SID */

extern int tdp_native_dispatch(TDPNativePage *, const void *);
extern int tdp_native_message_is_ours(const void *);
extern void tdp_native_reply_now(TDPResult, uint32_t request);
extern void native_report_activity(void);
/* 2026-11-16 no-pop foregrounding (NOPOP-FOREGROUND-611.md): pins already in link611.ld. */
extern void *nav_ensure_menu(void *), nav_set_state(void *, unsigned);
extern const char *nav_top_app(void);
extern void native_log(unsigned, unsigned, unsigned, unsigned, const char *, ...);
/* other-page mutex set (same as menu10's eligible()): never preempt a page the user watches. */
extern bool tc_native_visible(void *), tap_service_visible(void *);
extern bool tm_slot_visible(void *), wr_slot_visible(void *);

/* menu app via the launcher chain (6.11: launcher singleton 0x199595d4, manager at +0x15c).
 * The stock menu ctor publishes the menu app instance at 0x199595e0 (proved by disasm:
 * stock_ctor `str r4,[0x199595e0]`). */
static void *vm(void) {
    void *launcher = *(void **)(uintptr_t)0x199595d4;
    return launcher ? *(void **)((uint8_t *)launcher + 0x15c) : NULL;
}

bool greeting_message(const void *message) {
    if (!tdp_native_message_is_ours(message)) return false;
    /* TIONativeMessage: id(0) data(4) bytes(8) — must have the 32B TDP header to read SID. */
    uint32_t bytes = *(const uint32_t *)((const uint8_t *)message + 8);
    const uint8_t *d = *(const uint8_t **)((const uint8_t *)message + 4);
    if (!d || bytes < 12) return false;   /* length guard before reading d[8..11] */
    uint32_t sid = (uint32_t)d[8] | ((uint32_t)d[9] << 8) | ((uint32_t)d[10] << 16) | ((uint32_t)d[11] << 24);
    if (sid != GREETING_SID) return false;
    uint32_t request = (uint32_t)d[12] | ((uint32_t)d[13] << 8) | ((uint32_t)d[14] << 16) | ((uint32_t)d[15] << 24);

    void *m = vm();
    void *app = m ? *(void **)((uint8_t *)m + 0x10) : NULL;   /* ViewManager current app */
    /* 2026-11-17 regression fix: judge "is the launcher foreground" by nav_top_app()
     * (the four reference modules' proven home check) — NOT the 0x199595e0 singleton,
     * which is only written by the fnA menu-ctor path and is invalid before that. */
    const char *top = nav_top_app();
    bool on_launcher = top && !strcmp(top, "com.rayneo.liteos.launcher");
    M8NativeTail *t = (app && on_launcher) ? (M8NativeTail *)((uint8_t *)app + TIO_STOCK_MENU_BYTES) : NULL;

    if (t && t->page) {                       /* our display page already open -> dispatch into it */
        native_log(1,0,0,0,"[TurboDisplay] greet state=2 page=%p",t->page);
        tdp_native_dispatch(t->page, message);
        native_report_activity();
        return true;
    }
    /* 他页占用 = a non-launcher app foreground OR a module page up -> no foregrounding,
     * no open, STALE (the phone defers); never preempt the page the user is watching. */
    if (!on_launcher) {
        native_log(1,0,0,0,"[TurboDisplay] greet state=1 app=%p (occupied: stock app)",app);
        tdp_native_reply_now(TDP_STALE, request); return true;
    }
    if (t) {
        TNNavSlot *n = (TNNavSlot *)t->navigation;
        if (tc_native_visible(app) || tap_service_visible(app) ||
            (n && (tm_slot_visible(n->music) || wr_slot_visible(n->reader)))) {
            native_log(1,0,0,0,"[TurboDisplay] greet state=1 app=%p (occupied: module page)",app);
            tdp_native_reply_now(TDP_STALE, request); return true;
        }
    }
    if (!app) { app = nav_ensure_menu(m); if (!app) return false; }   /* create+install the launcher app */
    t = (M8NativeTail *)((uint8_t *)app + TIO_STOCK_MENU_BYTES);
    if (!t->page) {
        /* 2026-11-17 cold-first-push fix: set_state(vm,1) BEFORE creating the page tree.
         * The state switch rebuilds the view — all four reference modules do set_state
         * first, then create (camera:119/123, reader:70/74, music:79/80, apps:67/72).
         * Opening first and switching after wipes the just-created root (the frame then
         * commits into a dead tree: first cold push enters the menu but shows nothing). */
        nav_set_state(m, 1);
        void *parent = *(void **)((uint8_t *)app + 4);
        if (!parent || !tdp_native_open(parent, GREETING_SID, &t->page)) return false;
    }
    native_log(1,0,0,0,"[TurboDisplay] greet state=0 app=%p page=%p",app,t->page);
    tdp_native_dispatch(t->page, message);
    native_report_activity();
    return true;
}
