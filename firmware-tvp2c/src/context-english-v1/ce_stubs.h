#ifndef TURBO_CE_STUBS_H
#define TURBO_CE_STUBS_H
#include <stdbool.h>
#include <stdint.h>
/* 2026-11-16 tenth-flash slimming: the context-english module is physically removed.
 * This header carries ONLY what retained live code still references:
 *  - CE session types + ce_wants_gesture: head_gate.c's surviving dependency
 *    (migrated verbatim from english_session.h/.c).
 *  - CENativeSlot: migrated from english_native.h (TNNavSlot.english stays void*,
 *    the slot is never created; type kept for source compatibility).
 *  - three inert stubs whose call sites are live: ce_native_visible (mutex checks),
 *    ce_native_handle_event (m8_hook_vm_event chain), ce_native_head_observe
 *    (bl target of head_observer.S = patch site #28 ce_head_notify_entry).
 * All three stubs preserve the 9th-flash runtime behavior exactly: the english slot
 * was never created, so the originals always returned false / did nothing. */
#define CE_MAX_CARDS 10u
enum CEPhase { CE_CLOSED, CE_INVITE, CE_QUESTION, CE_ANSWER, CE_DONE };
enum CEResult { CE_UNSEEN, CE_REMEMBERED, CE_FORGOTTEN, CE_SKIPPED };
enum CEAction { CE_YES=1, CE_NO=2, CE_STOP=3 };
typedef struct {
    uint32_t epoch, since, now;
    uint8_t phase, count, index, results[CE_MAX_CARDS];
    bool available;
} CESession;
bool ce_wants_gesture(const CESession *s);
typedef struct {void *row,*label,*icon,*dot,*app;} CENativeSlot;
bool ce_native_visible(void *app),ce_native_handle_event(void *);
void ce_native_head_observe(void *monitor,unsigned mapped,unsigned raw);
#endif
