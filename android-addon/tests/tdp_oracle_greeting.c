#include "display_runtime.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif
/* Greeting-channel oracle: the unchanged 611 display_runtime + the greeting.c auto-open model
 * (greeting.c:36-40 — no page open && BEGIN to GREETING_SID => tdp_open on GREETING_SID first).
 * argv[1] = pre-opened sid (models a manually opened display-test page occupying the slot); 0 = closed.
 * In: [now_ms u32][len u32][len bytes]. Out: 40-byte TDR1 reply, CRC-32 of shown pixels, revision. */
static bool idle(void *c){(void)c;return true;}
static bool submit(void *c,const uint8_t *p,unsigned w,unsigned h){(void)c;(void)p;(void)w;(void)h;return true;}
/* The 611 runtime's STALE-instrumentation calls native_log; the oracle has no logging backend. */
void native_log(const char *fmt,...){(void)fmt;}
static uint32_t rd(void){uint8_t b[4];if(fread(b,1,4,stdin)!=4)exit(0);return b[0]|((uint32_t)b[1]<<8)|((uint32_t)b[2]<<16)|((uint32_t)b[3]<<24);}
static void wr(uint32_t v){uint8_t b[4]={(uint8_t)v,(uint8_t)(v>>8),(uint8_t)(v>>16),(uint8_t)(v>>24)};fwrite(b,1,4,stdout);}
#define GREETING_SID 0x47525400u
static void p32le(uint8_t *p,uint32_t v){for(int i=0;i<4;i++)p[i]=(uint8_t)(v>>(8*i));}
static uint32_t u32le(const uint8_t *p){return p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);}
int main(int argc,char **argv){
 static TDPRuntime r;static uint8_t p[TDP_MAX_PACKET];TDPUI ui={NULL,idle,submit};
#ifdef _WIN32
 setmode(fileno(stdin),O_BINARY);setmode(fileno(stdout),O_BINARY);
#endif
 if(argc!=2)return 2;
 /* argv[1] = pre-opened sid, optionally "sid@ttlMs" (occupier self-closes when now>ttl) and/or flag 'i'
  * (idle-retire on: models F1-B — the firmware retires the display page after ~3 s of link silence).
  * 0 = start closed. */
 uint32_t presid=0,closeAt=0,lastPacket=0;int idleRetire=strchr(argv[1],'i')!=NULL;const char*at=strchr(argv[1],'@');
 int silentQ=strchr(argv[1],'q')!=NULL;   /* 'q': no page => no runtime => QUERY is never answered (case-A truth) */
 /* 'f': fast mode (V5) — CAPS carries FEATURE_STREAM; BEGIN/CHUNK replies are suppressed (merged-reply model);
  * 'k<N>': drop chunk N entirely (no processing, no reply); the NEXT chunk's gap answers with NAK{highwater}. */
 int fastM=strchr(argv[1],'f')!=NULL;int dropAt=-1;const char*kp=strchr(argv[1],'k');if(kp)dropAt=atoi(kp+1);
 /* 'S<N>': answer TDP_STATUS (op=10) with the foreground category N at byte33 (per-op semantics, not highwater). */
 int statusCat=0;const char*sp=strchr(argv[1],'S');if(sp)statusCat=atoi(sp+1);
 int formF=strchr(argv[1],'F')!=NULL;   /* 'F': CAPS advertises FORM_FIELD (bit3) — the phone may send 13B BEGIN */
 int formSeen=-1;
 /* 'L': LEGACY firmware model (pre-611-featured build) — CAPS byte32 forced to 0 (strips the runtime's
  * unconditional FAST2/ANIM/FORM advertisement) and a 13-byte BEGIN is rejected outright (the old fixed
  * length==12 gate). Reverse-gate cases run in this mode. */
 int legacy=strchr(argv[1],'L')!=NULL;
 static uint8_t vextra[40];int extraPending=0;long chunkIdx=0;int negotiated=0;
 if(at){presid=(uint32_t)strtoul(argv[1],NULL,10);closeAt=(uint32_t)strtoul(at+1,NULL,10);}else presid=(uint32_t)strtoul(argv[1],NULL,10);
 if(presid&&!tdp_open(&r,presid,1000))return 2;
 for(;;){uint32_t now=rd(),n=rd();uint8_t out[TDP_REPLY_BYTES]={0};
  if(n>sizeof p)return 3;if(n&&fread(p,1,n,stdin)!=n)return 0;
  if(idleRetire&&r.open&&lastPacket&&now-lastPacket>3000)tdp_close(&r,&ui);
  lastPacket=now;
  if(closeAt&&r.open&&now>closeAt&&r.sid!=GREETING_SID)tdp_close(&r,&ui);
  if(n){
   if(!r.open&&!r.closing&&n>=12&&p[5]==TDP_FRAME_BEGIN&&u32le(p+8)==GREETING_SID)tdp_open(&r,GREETING_SID,now);
   /* fast mode: BEGIN/CHUNK processed with no reply (merged model); a dropped chunk makes the NEXT chunk gap →
    * NAK{sid, highwater=frame_received} instead of the classic STALE. */
   if(fastM&&negotiated&&(p[5]==TDP_FRAME_BEGIN||p[5]==TDP_FRAME_CHUNK)){
    if(p[5]==TDP_FRAME_CHUNK){chunkIdx++;if(chunkIdx==dropAt)continue;
     if(u32le(p+36)!=r.frame_received){memset(out,0,sizeof out);memcpy(out,"TDR1",4);out[4]=1;out[5]=13;
      p32le(out+8,r.sid);p32le(out+12,u32le(p+12));p32le(out+16,r.revision);out[20]=512&255;out[21]=512>>8;out[22]=128;out[23]=0;
      p32le(out+24,TDP_MAX_RECT);p32le(out+28,120000);p32le(out+33,(uint32_t)r.frame_received);p32le(out+36,tdp_crc(out,36));
      fwrite(out,1,sizeof out,stdout);wr(tdp_crc(r.pixels[r.active],TDP_PIXELS));wr(r.revision);fflush(stdout);continue;}}
    tdp_handle(&r,&ui,p,n,now);continue;}
   if(formF&&p[5]==10){   /* TDP_STATUS: answer ALIVE with the foreground category at byte33 (form-capable firmware) */
    memset(out,0,sizeof out);memcpy(out,"TDR1",4);out[4]=1;out[5]=TDP_ALIVE;
    p32le(out+8,r.open?r.sid:0);p32le(out+12,u32le(p+12));p32le(out+16,r.revision);out[20]=512&255;out[21]=512>>8;out[22]=128;out[23]=0;
    p32le(out+24,TDP_MAX_RECT);p32le(out+28,120000);out[33]=(uint8_t)statusCat;p32le(out+36,tdp_crc(out,36));
    fwrite(out,1,sizeof out,stdout);wr(tdp_crc(r.pixels[r.active],TDP_PIXELS));wr(r.revision);fflush(stdout);continue;}
   if(!(silentQ&&p[5]==TDP_QUERY)){TDPReply q;
    /* form-carrying BEGIN (13-byte body, byte44=form): validate + run as a plain BEGIN (the runtime's fixed
     *  length==12 check would reject it); the form byte is read and logged but does not alter the runtime. */
    static uint8_t p12[TDP_MAX_PACKET];
    if(legacy&&p[5]==TDP_FRAME_BEGIN&&u32le(p+24)!=12){   /* legacy gate: BEGIN body must be exactly 12 */
     memset(out,0,sizeof out);memcpy(out,"TDR1",4);out[4]=1;out[5]=TDP_BAD_PACKET;
     p32le(out+8,r.open?r.sid:0);p32le(out+12,u32le(p+12));p32le(out+16,r.revision);out[20]=512&255;out[21]=512>>8;out[22]=128;
     p32le(out+24,TDP_MAX_RECT);p32le(out+28,120000);p32le(out+36,tdp_crc(out,36));
     fwrite(out,1,sizeof out,stdout);wr(tdp_crc(r.pixels[r.active],TDP_PIXELS));wr(r.revision);fflush(stdout);continue;}
    if(p[5]==TDP_FRAME_BEGIN)formSeen=(u32le(p+24)==13)?p[44]:0;   /* the VISIBLE model honors the committed form */
    if(formF&&p[5]==TDP_FRAME_BEGIN&&u32le(p+24)==13){memcpy(p12,p,n);p12[24]=12;p32le(p12+28,tdp_crc(p12+32,12));q=tdp_handle(&r,&ui,p12,n-1,now);}
    else q=tdp_handle(&r,&ui,p,n,now);
    if(!tdp_reply_encode(&q,out,sizeof out))return 4;
    if(legacy&&q.result==TDP_CAPS){out[32]=0;p32le(out+36,tdp_crc(out,36));}   /* legacy: strip feature flags */
    if((fastM||formF)&&q.result==TDP_CAPS){out[32]=(uint8_t)((fastM?1:0)|(formF?8:0));p32le(out+36,tdp_crc(out,36));negotiated=1;}   /* FAST2 and/or FORM_FIELD bits */
    /* TDP_VISIBLE model (firmware fix, additive): right after a COMMIT's UI_SUBMITTED, emit the unsolicited
     * event (request=0, result=11) when submit+screen-take both succeeded. The banner branch of the real page
     * (native_display_page.c:447-458) takes no hold and emits NO VISIBLE — form==1 suppresses the extra. */
    if(p[5]==TDP_FRAME_COMMIT&&q.result==TDP_UI_SUBMITTED&&formSeen!=1){
     memset(vextra,0,sizeof vextra);memcpy(vextra,"TDR1",4);vextra[4]=1;vextra[5]=11;
     p32le(vextra+8,r.sid);p32le(vextra+12,0);p32le(vextra+16,r.revision);vextra[20]=512&255;vextra[21]=512>>8;vextra[22]=128;vextra[23]=0;
     p32le(vextra+24,TDP_MAX_RECT);p32le(vextra+28,120000);p32le(vextra+36,tdp_crc(vextra,36));
     extraPending=1;}}
  fwrite(out,1,sizeof out,stdout);wr(tdp_crc(r.pixels[r.active],TDP_PIXELS));wr(r.revision);fflush(stdout);
  if(extraPending){extraPending=0;fwrite(vextra,1,sizeof vextra,stdout);wr(tdp_crc(r.pixels[r.active],TDP_PIXELS));wr(r.revision);fflush(stdout);}}
 }
}
