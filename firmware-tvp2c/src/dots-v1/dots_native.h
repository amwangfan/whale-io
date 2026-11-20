#ifndef TURBO_DOTS_NATIVE_H
#define TURBO_DOTS_NATIVE_H
#include <stdbool.h>
/* 2026-11-16 tenth-flash slimming: Dots service/wire/view/carrier physically removed
 * (AUDIT-DORMANT-MODULES-611.md §2.4: entries unreachable, slot never created).
 * Four functions stay live and keep their exact 9th-flash behavior:
 *  - dots_link_notify: patch site #33 target; forwards to the stock notify first.
 *  - dots_business_idle: consulted by every home() gate (music/apps/reader/...).
 *  - dots_native_visible / dots_native_event: mutex checks / vm_event chain stubs
 *    (the slot was never created, so these were already always-false). */
typedef struct {void *row,*label,*icon,*dot,*app;} DotsNativeSlot;
bool dots_native_visible(void *);
bool dots_native_event(void *);
void dots_link_notify(void *monitor,unsigned connected);
bool dots_business_idle(void); // for OTHER extensions, not Dots itself
#endif
