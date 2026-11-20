#ifndef TURBO_DISPLAY_CARRIER_H
#define TURBO_DISPLAY_CARRIER_H
#include "display_runtime.h"
/* Existing Launcher business / generic command-reply PB envelope. The private
 * command is OUR extension, not an official firmware command. No new bus ID. */

/* 2026-11-16 big-chunk gradient: the downlink TDP packet cap is a COMPILE-TIME
 * tier, injected by build611_module.py --tier N as -DTDP_PACKET_TIER=N.
 * Default 512 is the only hardware-proven value (stock file-receive service
 * buffer constraint, see tmp/business15-buffer-1025.md). Tiers above 512 exist
 * ONLY for the on-device 2K/4K/8K/16K gradient test; a phone packet larger
 * than the flashed tier is rejected by the native guards. */
#ifndef TDP_PACKET_TIER
#define TDP_PACKET_TIER 512
#endif
#if TDP_PACKET_TIER!=512&&TDP_PACKET_TIER!=2048&&TDP_PACKET_TIER!=4096&&TDP_PACKET_TIER!=8192&&TDP_PACKET_TIER!=16384
#error "TDP_PACKET_TIER must be one of 512/2048/4096/8192/16384"
#endif
enum { TDP_UPLINK_BUSINESS=15, TDP_UPLINK_TYPE=6, TDP_UPLINK_MAX=192,
       TDP_NATIVE_PACKET_MAX=TDP_PACKET_TIER,
       /* CHUNK payload = packet - 32B TDP header - 8B CHUNK header (tx+offset).
        * Derived by compile-time expression only; hand-written values forbidden. */
       TDP_NATIVE_PIXELS_MAX=TDP_NATIVE_PACKET_MAX-TDP_HEADER-8 };
_Static_assert(TDP_NATIVE_PIXELS_MAX+TDP_HEADER+8==TDP_NATIVE_PACKET_MAX,"payload = packet - 40 (32B TDP + 8B CHUNK)");
_Static_assert(TDP_NATIVE_PACKET_MAX>=32+8+1&&TDP_NATIVE_PACKET_MAX<=(int)TDP_MAX_PACKET,"tier must fit the protocol envelope");
_Static_assert(TDP_NATIVE_PIXELS_MAX>=1&&TDP_NATIVE_PIXELS_MAX<=(int)TDP_MAX_RECT,"tier payload must fit the core CHUNK cap");
size_t tdp_carrier_reply(const TDPReply *,uint8_t *,size_t);
/* Strict, canonical decode of our encoder only. Foreign events pass through. */
bool tdp_carrier_decode(unsigned business,const uint8_t *,size_t,TDPReply *);
#endif
