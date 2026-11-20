#ifndef TURBO_DISPLAY_RUNTIME_H
#define TURBO_DISPLAY_RUNTIME_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
/* OFFLINE CORE. No RNLink ABI, firmware addresses, or device writes here.
 * Caller zero-initializes storage and owns a single authenticated peer lease.
 * Execute only on UI executor. Storage must outlive renderer references. */
enum { TDP_WIDTH=512, TDP_HEIGHT=128, TDP_PIXELS=65536,
       TDP_HEADER=32, TDP_MAX_RECT=16384, TDP_MAX_PACKET=16424 };
enum { TDP_QUERY=1, TDP_RECT=2, TDP_KEEPALIVE=3, TDP_CLOSE=4,
 TDP_FRAME_BEGIN=5, TDP_FRAME_CHUNK=6, TDP_FRAME_COMMIT=7, TDP_FRAME_ABORT=8,
 TDP_ANIM_PLAY=9 /* 2026-11-17 ANIM_LOCAL: {asset_id u16, loop u8, fps_cap u8} control op —
                  * start the local 12-pose loop on the open page. Only honored once the
                  * feature is negotiated (fast latched); old phones never send it. */,
 TDP_STATUS=10 /* 2026-11-17 PushPolicy: length=0 control op -> TDR1{ALIVE, byte33=category}.
                * Category = self-computed foreground class (0 idle/1 nav/2 prompter/3 call/
                * 4 voice/5 other-fg/6 fold-defer); pref43 (ai_memory) deliberately excluded. */ };
typedef enum { TDP_CAPS, TDP_UI_SUBMITTED, TDP_ALIVE, TDP_CLOSED,
 TDP_BAD_PACKET, TDP_NO_SESSION, TDP_STALE, TDP_BUSY, TDP_RENDER_ERROR,
 TDP_STAGED, TDP_ABORTED,
 TDP_VISIBLE /* 2026-11-16: frame submitted AND screen held — one unsolicited
              * follow-up (request=0) right after UI_SUBMITTED. Old hosts ignore
              * unknown result codes; old firmware never sends it. */,
 /* 12 = reserved (MS0: phone side coded NAK=13; 12 stays reserved). */
 TDP_NAK=13 /* 2026-11-17 V5 FAST2: recoverable chunk gap — carries the frame_received
             * high-water in TDR1 bytes33..35; phone resumes from it. Never replaces
             * the fatal STALE on an overlapping-content mismatch. */ } TDPResult;
typedef struct { TDPResult result; uint32_t sid,request,revision;
 uint16_t width,height; uint32_t max_rect_bytes,lease_ms;
 /* V5 FAST2 (TDR1 bytes32..35): byte32 = feature flags (bit0=FAST2 offer, set on CAPS
  * only once negotiated); bytes33..35 = u24 high-water (frame_received), set on NAK and
  * merged STAGED under fast mode, else 0. Both 0 -> byte-identical with the old wire. */
 uint32_t flags,high_water; } TDPReply;
typedef struct { void *ctx; bool (*idle)(void *);
 /* On false MUST leave old buffer/root intact. On true retains new buffer.
  * NULL detaches owned canvas; do not claim this is physical PRESENTED. */
 bool (*submit)(void *,const uint8_t *,unsigned,unsigned); } TDPUI;
typedef struct { bool open,closing,frame_pending; uint8_t active;
 uint32_t sid,revision,last_request,last_digest,deadline;
 uint32_t transaction,last_transaction,frame_crc,frame_received,frame_deadline;
 /* fast (offset 44): V5 FAST2 latched when the phone streams (a packet arrives while a
  * reply is pending — lock-step phones never do). Placed after the uint32 block so the
  * existing field offsets and pixels' 64-alignment are untouched. 0 -> lock-step. */
 bool fast;
 uint8_t form;   /* V5 FORM_FIELD: BEGIN byte44 (0=page default, 1=notification-banner),
                  * read only when negotiated (fast latched); unnegotiated 13B BEGIN is
                  * rejected byte-identically (the length!=12 gate stays for lock-step). */
 _Alignas(64) uint8_t pixels[2][TDP_PIXELS]; } TDPRuntime;
uint32_t tdp_crc(const uint8_t *,size_t);
bool tdp_open(TDPRuntime *,uint32_t sid,uint32_t now);
TDPReply tdp_handle(TDPRuntime *,const TDPUI *,const uint8_t *,size_t,uint32_t now);
/* Called periodically and on physical close; BUSY retains storage for retry. */
TDPResult tdp_close(TDPRuntime *,const TDPUI *);
TDPResult tdp_tick(TDPRuntime *,const TDPUI *,uint32_t now);
/* Fixed 40-byte LE response; CRC covers bytes 0..35. No native struct casting. */
enum { TDP_REPLY_BYTES=40 };
bool tdp_reply_encode(const TDPReply *,uint8_t *,size_t);
bool tdp_reply_decode(const uint8_t *,size_t,TDPReply *);
#endif
