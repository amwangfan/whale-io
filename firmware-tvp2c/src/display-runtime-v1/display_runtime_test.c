#include "display_runtime.h"
#include "display_client.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <stdarg.h>
/* STALE tracepoint capture (2026-11-16): display_runtime.c now logs each STALE
 * rejection; capture the format string + the 7 varargs for content assertions. */
static char last_log_fmt[96];static unsigned last_log[7];static unsigned log_count;
void native_log(unsigned a,unsigned b,unsigned c,unsigned d,const char *f,...){
 (void)a;(void)b;(void)c;(void)d;
 strncpy(last_log_fmt,f,sizeof last_log_fmt-1);last_log_fmt[sizeof last_log_fmt-1]=0;
 va_list ap;va_start(ap,f);for(unsigned i=0;i<7;i++)last_log[i]=va_arg(ap,unsigned);va_end(ap);log_count++;
}
static TDPRuntime r;
static uint8_t wire[TDP_MAX_PACKET];
static uint8_t frame[TDP_PIXELS];
static bool busy,fail;
static const uint8_t *shown;
static unsigned submits;
static bool idle(void *p){(void)p;return !busy;}
static bool submit(void *p,const uint8_t *pixels,unsigned w,unsigned h){(void)p;if(fail)return false;if(pixels)assert(w==512&&h==128);shown=pixels;submits++;return true;}
static const TDPUI ui={NULL,idle,submit};
static void put(uint8_t *p,uint32_t n){for(unsigned i=0;i<4;i++)p[i]=(uint8_t)(n>>(8*i));}
static size_t packet(unsigned op,uint32_t sid,uint32_t req,uint32_t base,unsigned len){
 memset(wire,0,sizeof wire);memcpy(wire,"TDP1",4);wire[4]=1;wire[5]=(uint8_t)op;put(wire+8,sid);put(wire+12,req);put(wire+16,base);put(wire+20,base+(op==TDP_RECT));put(wire+24,len);return 32+len;
}
static void seal(size_t n){put(wire+28,tdp_crc(wire+32,n-32));}
static size_t rect(uint32_t req,uint32_t base,unsigned w,unsigned h){size_t n=packet(TDP_RECT,7392,req,base,8+w*h);wire[36]=(uint8_t)w;wire[37]=(uint8_t)(w>>8);wire[38]=(uint8_t)h;wire[39]=(uint8_t)(h>>8);memset(wire+40,180,w*h);seal(n);return n;}
static void frame_tests(void){
 assert(tdp_open(&r,10001,0));
 for(unsigned i=0;i<sizeof frame;i++)frame[i]=(uint8_t)((i/512)^i);
 size_t n=tdp_client_begin(wire,sizeof wire,10001,10,0,1,tdp_crc(frame,sizeof frame));assert(n==44);
 assert(tdp_handle(&r,&ui,wire,n,1).result==TDP_STAGED&&r.frame_received==0);
 assert(tdp_handle(&r,&ui,wire,n,2).result==TDP_STAGED); /* lost ACK retry */
 n=tdp_client_finish(wire,sizeof wire,TDP_FRAME_COMMIT,10001,11,0,1);
 assert(tdp_handle(&r,&ui,wire,n,3).result==TDP_STALE&&shown==NULL);
 unsigned before=submits;
 for(unsigned offset=0;offset<TDP_PIXELS;offset+=TDP_MAX_RECT){
  n=tdp_client_chunk(wire,sizeof wire,10001,20+offset,0,1,offset,frame+offset,TDP_MAX_RECT);
  assert(n==TDP_MAX_PACKET);
  assert(tdp_handle(&r,&ui,wire,n,4).result==TDP_STAGED);
  assert(tdp_handle(&r,&ui,wire,n,5).result==TDP_STAGED); /* no double append */
  wire[40]^=1;seal(n);assert(tdp_handle(&r,&ui,wire,n,6).result==TDP_STALE);
  assert(submits==before&&shown==NULL&&r.revision==0);
 }
 n=tdp_client_rect(wire,sizeof wire,10001,99,0,0,0,1,1,frame,1);
 assert(tdp_handle(&r,&ui,wire,n,7).result==TDP_BUSY);
 n=tdp_client_finish(wire,sizeof wire,TDP_FRAME_COMMIT,10001,100,0,1);
 busy=true;assert(tdp_handle(&r,&ui,wire,n,8).result==TDP_BUSY);busy=false;
 fail=true;assert(tdp_handle(&r,&ui,wire,n,9).result==TDP_RENDER_ERROR);fail=false;
 assert(tdp_handle(&r,&ui,wire,n,10).result==TDP_UI_SUBMITTED&&r.revision==1);
 assert(!memcmp(shown,frame,TDP_PIXELS)&&submits==before+1);
 assert(tdp_handle(&r,&ui,wire,n,11).result==TDP_UI_SUBMITTED&&submits==before+1);
 n=tdp_client_begin(wire,sizeof wire,10001,101,1,2,0);
 assert(tdp_handle(&r,&ui,wire,n,12).result==TDP_STAGED);
 for(unsigned offset=0;offset<TDP_PIXELS;offset+=TDP_MAX_RECT){
  n=tdp_client_chunk(wire,sizeof wire,10001,200+offset,1,2,offset,frame+offset,TDP_MAX_RECT);
  assert(tdp_handle(&r,&ui,wire,n,13).result==TDP_STAGED);
 }
 n=tdp_client_finish(wire,sizeof wire,TDP_FRAME_COMMIT,10001,300,1,2);
 assert(tdp_handle(&r,&ui,wire,n,14).result==TDP_BAD_PACKET&&!r.frame_pending);
 assert(submits==before+1&&r.revision==1); /* failed total CRC keeps old frame */
 n=tdp_client_begin(wire,sizeof wire,10001,301,1,3,0);
 assert(tdp_handle(&r,&ui,wire,n,15).result==TDP_STAGED);
 n=tdp_client_finish(wire,sizeof wire,TDP_FRAME_ABORT,10001,302,1,3);
 assert(tdp_handle(&r,&ui,wire,n,16).result==TDP_ABORTED);
 n=tdp_client_begin(wire,sizeof wire,10001,303,1,3,0);
 assert(tdp_handle(&r,&ui,wire,n,17).result==TDP_STALE); /* no tx reuse */
 n=tdp_client_begin(wire,sizeof wire,10001,304,1,4,0);
 assert(tdp_handle(&r,&ui,wire,n,18).result==TDP_STAGED);
 assert(tdp_tick(&r,&ui,30018)==TDP_ALIVE&&!r.frame_pending);
 assert(tdp_client_chunk(wire,sizeof wire,10001,305,1,5,65536,frame,1)==0);
 assert(tdp_client_rect(wire,sizeof wire,10001,306,1,511,127,2,1,frame,2)==0);
 assert(tdp_client_rect(wire,sizeof wire,10001,307,UINT32_MAX,0,0,1,1,frame,1)==0);
 n=tdp_client_query(wire,sizeof wire,308);
 TDPReply q=tdp_handle(&r,&ui,wire,n,30019),copy={0};uint8_t answer[TDP_REPLY_BYTES];
 assert(q.result==TDP_CAPS&&q.sid==10001&&q.revision==1);
 assert(tdp_reply_encode(&q,answer,sizeof answer)&&tdp_reply_decode(answer,sizeof answer,&copy));
 assert(copy.sid==q.sid&&copy.request==308&&copy.revision==1&&copy.max_rect_bytes==16384);
 answer[8]^=1;assert(!tdp_reply_decode(answer,sizeof answer,&copy));
 q=tdp_handle(&r,&ui,wire,n,120010);assert(q.result==TDP_NO_SESSION&&q.sid==0);
 assert(tdp_tick(&r,&ui,120010)==TDP_CLOSED&&shown==NULL);
}
static void stale_log_tests(void){
 /* 2026-11-16: every STALE rejection must emit exactly one tracepoint with the
  * packet op/tx/at and the runtime's transaction/revision/pending/received. */
 assert(tdp_open(&r,4242,1000));
 unsigned lg0=log_count;
 size_t n=tdp_client_begin(wire,sizeof wire,4242,10,3,4,tdp_crc(frame,sizeof frame)); /* :56 wrong base */
 assert(n&&tdp_handle(&r,&ui,wire,n,1001).result==TDP_STALE);
 assert(log_count==lg0+1&&strstr(last_log_fmt,"stale op=%u"));
 assert(last_log[0]==TDP_FRAME_BEGIN&&last_log[1]==4&&last_log[2]==0);            /* op tx at */
 assert(last_log[3]==0&&last_log[4]==0&&last_log[5]==0&&last_log[6]==0);         /* rt tx rev pending recv */
 n=tdp_client_finish(wire,sizeof wire,TDP_FRAME_COMMIT,4242,11,0,1);              /* :73 no frame pending */
 assert(n&&tdp_handle(&r,&ui,wire,n,1002).result==TDP_STALE);
 assert(log_count==lg0+2&&last_log[0]==TDP_FRAME_COMMIT&&last_log[1]==1&&last_log[5]==0);
 n=tdp_client_begin(wire,sizeof wire,4242,12,0,1,tdp_crc(frame,sizeof frame));    /* proper BEGIN -> STAGED */
 assert(n&&tdp_handle(&r,&ui,wire,n,1003).result==TDP_STAGED);
 assert(log_count==lg0+2);                                                        /* no log on a normal path */
 n=tdp_client_chunk(wire,sizeof wire,4242,13,0,1,100,frame,16);                   /* :80 out-of-order offset */
 assert(n&&tdp_handle(&r,&ui,wire,n,1004).result==TDP_STALE);
 assert(log_count==lg0+3&&last_log[0]==TDP_FRAME_CHUNK&&last_log[1]==1&&last_log[2]==100);
 assert(last_log[5]==1&&last_log[6]==0);                                          /* pending=1 recv=0 */
 printf("PASS stale tracepoints: :56/:73/:80 each logged once with correct op/tx/at/rt fields; normal paths silent\n");
}
static void deadlock_tests(void){
 /* 2026-11-18 CAPS deadlock fix: the CAPS advertises bit0|bit2|bit3 UNCONDITIONALLY (no
  * fast latch needed), and feature acceptance follows the offer — a cold QUERY gets the
  * offer and a 13B BEGIN / ANIM_PLAY work immediately, before any streaming. */
 if(r.open)tdp_close(&r,&ui);
 assert(tdp_open(&r,8888,3000));
 r.fast=false;   /* cold: never streamed */
 size_t n=tdp_client_query(wire,sizeof wire,50);
 TDPReply q=tdp_handle(&r,&ui,wire,n,3001);
 assert(q.result==TDP_CAPS&&q.flags==13);          /* the offer is unconditional */
 /* ANIM_PLAY (op=9) accepted without any fast latch */
 memset(wire,0,64);memcpy(wire,"TDP1",4);wire[4]=1;wire[5]=9;
 put(wire+8,8888);put(wire+12,51);put(wire+16,0);put(wire+20,0);put(wire+24,4);
 wire[32]=0;wire[33]=0;wire[34]=1;wire[35]=60;
 put(wire+28,tdp_crc(wire+32,4));
 q=tdp_handle(&r,&ui,wire,36,3002);
 assert(q.result==TDP_STAGED);                     /* accepted: no latch needed */
 printf("PASS deadlock: cold QUERY -> CAPS flags=13; ANIM_PLAY/13B BEGIN usable before any streaming\n");
}
static void fast_tests(void){
 /* V5 FAST2: gap -> NAK(high-water) not STALE; overlap-mismatch stays fatal STALE;
  * accepted chunk + NAK renew the 30s frame deadline; CAPS advertises FAST2 once
  * negotiated; T4 idempotent re-push does not re-submit. */
 if(r.open)tdp_close(&r,&ui);   /* stale_log_tests leaves the session open */
 assert(tdp_open(&r,5555,0));
 r.fast=true;   /* negotiated (behavioral latch) */
 for(unsigned i=0;i<sizeof frame;i++)frame[i]=(uint8_t)(i*7+3);
 size_t n=tdp_client_begin(wire,sizeof wire,5555,10,0,1,tdp_crc(frame,sizeof frame));
 assert(tdp_handle(&r,&ui,wire,n,1000).result==TDP_STAGED);
 n=tdp_client_query(wire,sizeof wire,11);
 TDPReply q=tdp_handle(&r,&ui,wire,n,1001);
 assert(q.result==TDP_CAPS&&q.flags==13&&q.high_water==0);         /* FAST2+ANIM_LOCAL+FORM_FIELD once negotiated */
 uint32_t fd0=r.frame_deadline;
 n=tdp_client_chunk(wire,sizeof wire,5555,20,0,1,0,frame,472);
 q=tdp_handle(&r,&ui,wire,n,1002);
 assert(q.result==TDP_STAGED&&q.high_water==472&&r.frame_deadline>=fd0);   /* accept renews */
 n=tdp_client_chunk(wire,sizeof wire,5555,21,0,1,1000,frame+1000,472);     /* gap: at!=472 */
 q=tdp_handle(&r,&ui,wire,n,1003);
 assert(q.result==TDP_NAK&&q.high_water==472&&r.frame_pending&&r.frame_received==472);
 assert(r.frame_deadline>=fd0);                                             /* NAK renews too */
 n=tdp_client_chunk(wire,sizeof wire,5555,22,0,1,472,frame+472,472);        /* phone resumes from the watermark */
 q=tdp_handle(&r,&ui,wire,n,1004);
 assert(q.result==TDP_STAGED&&q.high_water==944&&r.frame_received==944);    /* monotone */
 n=tdp_client_chunk(wire,sizeof wire,5555,23,0,1,0,frame,472);wire[40]^=0xff;seal(n);  /* overlap + mismatch */
 q=tdp_handle(&r,&ui,wire,n,1005);
 assert(q.result==TDP_STALE);                                               /* stays fatal, never NAK */
 /* T4: full frame -> COMMIT -> UI_SUBMITTED; re-push the same COMMIT -> UI_SUBMITTED, no re-submit */
 unsigned at=0,req=100;
 while(at<65536){unsigned len=65536-at;if(len>472)len=472;
  n=tdp_client_chunk(wire,sizeof wire,5555,req++,0,1,at,frame+at,len);
  assert(tdp_handle(&r,&ui,wire,n,1100+at).result==TDP_STAGED);at+=len;}
 n=tdp_client_finish(wire,sizeof wire,TDP_FRAME_COMMIT,5555,500,0,1);
 unsigned s0=submits;
 assert(tdp_handle(&r,&ui,wire,n,1006).result==TDP_UI_SUBMITTED&&submits==s0+1);
 assert(tdp_handle(&r,&ui,wire,n,1007).result==TDP_UI_SUBMITTED&&submits==s0+1);   /* idempotent re-push */
 /* PushPolicy FORM_FIELD: 13B BEGIN (byte44=form) accepted once the capability is advertised
  * (acceptance follows the offer, not the fast latch); old phones never send 13B. */
 if(r.open)tdp_close(&r,&ui);assert(tdp_open(&r,7777,2000));
 r.fast=false;
 n=tdp_client_begin13(wire,sizeof wire,7777,10,0,1,tdp_crc(frame,sizeof frame),1);  /* 13B, form=banner */
 assert(n&&tdp_handle(&r,&ui,wire,n,2001).result==TDP_STAGED&&r.form==1);           /* accepted, form read */
 n=tdp_client_begin13(wire,sizeof wire,7777,12,0,1,tdp_crc(frame,sizeof frame),0);  /* form=0 -> page */
 assert(n&&tdp_handle(&r,&ui,wire,n,2003).result==TDP_STAGED);
 /* ANIM_PLAY (op=9): accepted (STAGED) when idle; BUSY when a frame is in flight. */
 n=tdp_client_finish(wire,sizeof wire,TDP_FRAME_ABORT,7777,30,0,1);assert(tdp_handle(&r,&ui,wire,n,3004).result==TDP_ABORTED); /* clear the FORM_FIELD frame */
 { uint8_t an[36];memset(an,0,36);memcpy(an,"TDP1",4);an[4]=1;an[5]=9;
   put(an+8,7777);put(an+12,60);put(an+24,4);an[32]=0;an[33]=0;an[34]=255;an[35]=60;put(an+28,tdp_crc(an+32,4));
   q=tdp_handle(&r,&ui,an,36,3005);assert(q.result==TDP_STAGED);   /* idle: accepted */
   /* arm a frame -> in-flight -> ANIM_PLAY must be BUSY */
   n=tdp_client_begin(wire,sizeof wire,7777,61,0,5,tdp_crc(frame,sizeof frame));   /* base=0 rev=1, tx=5 > prior txs */
   assert(tdp_handle(&r,&ui,wire,n,3006).result==TDP_STAGED);
   q=tdp_handle(&r,&ui,an,36,3007);assert(q.result==TDP_BUSY);     /* in-flight: BUSY */
 }
 printf("PASS fast: NAK gap+high-water, mismatch stays STALE, 30s renewal, CAPS FAST2, T4 idempotent, FORM_FIELD 13B gate, ANIM_PLAY idle/in-flight\n");
}
int main(void){
 size_t n=packet(TDP_QUERY,0,1,0,0);seal(n);assert(tdp_handle(&r,&ui,wire,n,0).result==TDP_NO_SESSION);
 assert(tdp_open(&r,7392,0));assert(!tdp_open(&r,8642,0));
 TDPReply q=tdp_handle(&r,&ui,wire,n,0);assert(q.result==TDP_CAPS&&q.sid==7392&&q.width==512&&q.height==128);
 n=rect(2,0,512,32);assert(tdp_handle(&r,&ui,wire,n,1).result==TDP_UI_SUBMITTED);assert(shown[0]==180&&shown[16383]==180&&shown[16384]==0);
 assert(tdp_handle(&r,&ui,wire,n,2).result==TDP_UI_SUBMITTED&&submits==1);
 wire[40]^=1;seal(n);assert(tdp_handle(&r,&ui,wire,n,3).result==TDP_STALE&&submits==1);
 n=rect(3,1,32,32);busy=true;assert(tdp_handle(&r,&ui,wire,n,4).result==TDP_BUSY&&r.revision==1);busy=false;fail=true;assert(tdp_handle(&r,&ui,wire,n,5).result==TDP_RENDER_ERROR&&r.revision==1);fail=false;
 assert(tdp_handle(&r,&ui,wire,n,6).result==TDP_UI_SUBMITTED&&r.revision==2);
 put(wire+8,999);assert(tdp_handle(&r,&ui,wire,n,7).result==TDP_STALE);
 n=rect(4,2,1,1);wire[32]=255;wire[33]=255;seal(n);assert(tdp_handle(&r,&ui,wire,n,7).result==TDP_BAD_PACKET);
 n=rect(4,2,1,1);wire[36]=255;wire[37]=255;wire[38]=255;wire[39]=255;seal(n);assert(tdp_handle(&r,&ui,wire,n,7).result==TDP_BAD_PACKET);
 /* Deterministic malformed input, no heap allocation or unbounded queue. */
 uint32_t rng=19;for(unsigned i=0;i<50000;i++){rng=rng*1664525u+1013904223u;size_t len=rng%sizeof wire;for(size_t j=0;j<len;j++){rng=rng*1664525u+1013904223u;wire[j]=(uint8_t)(rng>>24);}tdp_handle(&r,&ui,wire,len,20);}
 /* Valid headers and CRC force malformed payloads past the outer envelope. */
 for(unsigned i=0;i<5000;i++){
  rng=rng*1664525u+1013904223u;unsigned len=(rng>>24)%64;
  n=packet(1+i%8,7392,1000+i,r.revision,len);
  for(unsigned j=0;j<len;j++){rng=rng*1664525u+1013904223u;wire[32+j]=(uint8_t)(rng>>24);}
  if(i%8>=4)put(wire+20,r.revision+1);
  seal(n);tdp_handle(&r,&ui,wire,n,20);
  assert(r.frame_received<=TDP_PIXELS&&r.active<2);
  if(!r.open){assert(tdp_close(&r,&ui)==TDP_CLOSED);assert(tdp_open(&r,7392,20));}
 }
 busy=true;assert(tdp_close(&r,&ui)==TDP_BUSY);assert(!tdp_open(&r,999,21));n=rect(5,2,1,1);assert(tdp_handle(&r,&ui,wire,n,21).result==TDP_NO_SESSION);busy=false;assert(tdp_close(&r,&ui)==TDP_CLOSED&&shown==NULL);
 assert(tdp_open(&r,8642,UINT32_MAX-100));assert(tdp_tick(&r,&ui,30)==TDP_ALIVE);assert(tdp_tick(&r,&ui,120000)==TDP_CLOSED);
 frame_tests();
 stale_log_tests();
 deadlock_tests();
 fast_tests();
 printf("PASS query/SID, rect, atomic 4-chunk frame, CRC, replay, timeout, busy close, tick wrap, stale tracepoints, FAST2 NAK/watermark, 50000 malformed + 5000 structured packets; runtime=%zu bytes; no device IO\n",sizeof r);return 0;
}
