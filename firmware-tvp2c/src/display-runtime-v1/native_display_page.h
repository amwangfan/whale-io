#ifndef TDP_NATIVE_DISPLAY_PAGE_H
#define TDP_NATIVE_DISPLAY_PAGE_H
#include "display_runtime.h"
typedef struct TDPNativePage TDPNativePage;
/* UI executor only. Caller must authenticate/pin the paired source BEFORE
 * dispatching. No receive-thread access to this object is permitted. */
TDPNativePage *tdp_native_open(void *parent,uint32_t sid,TDPNativePage **owner);
void tdp_native_retire(TDPNativePage *);
TDPResult tdp_native_receive(TDPNativePage *,const uint8_t *,size_t);
/* F1-B (2026-11-16): link-status lease control, called from the menu10.c message
 * peek. Link-down shrinks the lease to a 3 s grace (never EXTENDS a shorter
 * remainder); link-up restores the full 120 s lease. Grace expiry is closed by the
 * page's own 100 ms tick through the normal lease path (notify_closed -> close ->
 * free + owner unlink) — same endpoint as tdp_native_retire, via existing machinery. */
void tdp_native_link_lease(TDPNativePage *,bool connected,uint32_t now);
/* Orphan reply without a page (bounded retry chain). E.g. greeting's STALE defer. */
void tdp_native_reply_now(TDPResult result,uint32_t request);
/* Offer-once sessionless reply (no parked retry). E.g. the over-limit guard receipts. */
void tdp_native_offer_now(TDPResult result,uint32_t request);
/* Never call after retire; timer owns deferred destruction. */
#endif
