#include "display_runtime.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif
/* The unchanged TVP2 display runtime as a stdin/stdout oracle for the Java session test.
 * In: [now_ms u32][len u32][len bytes]. Out: 40-byte TDR1 reply (zeros for len 0), CRC-32 of the shown pixels, revision.
 * Windows: stdin/stdout must be binary — text mode truncates reads at 0x1A (Ctrl+Z) and mangles 0x0A on write. */
static bool idle(void *c){(void)c;return true;}
static bool submit(void *c,const uint8_t *p,unsigned w,unsigned h){(void)c;(void)p;(void)w;(void)h;return true;}
/* The 611 runtime's STALE-instrumentation calls native_log; the oracle has no logging backend. */
void native_log(const char *fmt,...){(void)fmt;}
static uint32_t rd(void){uint8_t b[4];if(fread(b,1,4,stdin)!=4)exit(0);return b[0]|((uint32_t)b[1]<<8)|((uint32_t)b[2]<<16)|((uint32_t)b[3]<<24);}
static void wr(uint32_t v){uint8_t b[4]={(uint8_t)v,(uint8_t)(v>>8),(uint8_t)(v>>16),(uint8_t)(v>>24)};fwrite(b,1,4,stdout);}
static void p32le(uint8_t *p,uint32_t v){for(int i=0;i<4;i++)p[i]=(uint8_t)(v>>(8*i));}
static uint32_t u32le(const uint8_t *p){return p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);}
int main(int argc,char **argv){
 static TDPRuntime r;static uint8_t p[TDP_MAX_PACKET];TDPUI ui={NULL,idle,submit};
#ifdef _WIN32
 setmode(fileno(stdin),O_BINARY);setmode(fileno(stdout),O_BINARY);
#endif
 if(argc!=2)return 2;
 /* 'f': fast mode (V5) — CAPS carries FEATURE_STREAM; once negotiated, BEGIN/CHUNK replies are suppressed
  * (merged-reply model); 'k<N>': drop chunk N entirely; the NEXT chunk's gap answers with NAK{highwater}.
  * 'L': LEGACY firmware model — CAPS byte32 forced to 0, stripping the 611 runtime's unconditional
  * FAST2/ANIM/FORM advertisement so the classic per-packet cases exercise the old-firmware path. */
 int fastM=strchr(argv[1],'f')!=NULL;int dropAt=-1;const char*kp=strchr(argv[1],'k');if(kp)dropAt=atoi(kp+1);
 int legacy=strchr(argv[1],'L')!=NULL;
 long chunkIdx=0;int negotiated=0;
 if(!tdp_open(&r,(uint32_t)strtoul(argv[1],NULL,10),1000))return 2;
 for(;;){uint32_t now=rd(),n=rd();uint8_t out[TDP_REPLY_BYTES]={0};
  if(n>sizeof p)return 3;if(n&&fread(p,1,n,stdin)!=n)return 0;
  if(n){
   if(fastM&&negotiated&&(p[5]==TDP_FRAME_BEGIN||p[5]==TDP_FRAME_CHUNK)){
    if(p[5]==TDP_FRAME_CHUNK){chunkIdx++;if(chunkIdx==dropAt)continue;
     if(u32le(p+36)!=r.frame_received){memset(out,0,sizeof out);memcpy(out,"TDR1",4);out[4]=1;out[5]=13;
      p32le(out+8,r.sid);p32le(out+12,u32le(p+12));p32le(out+16,r.revision);out[20]=512&255;out[21]=512>>8;out[22]=128;out[23]=0;
      p32le(out+24,TDP_MAX_RECT);p32le(out+28,120000);p32le(out+33,(uint32_t)r.frame_received);p32le(out+36,tdp_crc(out,36));
      fwrite(out,1,sizeof out,stdout);wr(tdp_crc(r.pixels[r.active],TDP_PIXELS));wr(r.revision);fflush(stdout);continue;}}
    tdp_handle(&r,&ui,p,n,now);continue;}
   TDPReply q=tdp_handle(&r,&ui,p,n,now);if(!tdp_reply_encode(&q,out,sizeof out))return 4;
   if(legacy&&q.result==TDP_CAPS){out[32]=0;p32le(out+36,tdp_crc(out,36));}   /* legacy: strip feature flags */
   if(fastM&&q.result==TDP_CAPS){out[32]=1;p32le(out+36,tdp_crc(out,36));negotiated=1;}}
  fwrite(out,1,sizeof out,stdout);wr(tdp_crc(r.pixels[r.active],TDP_PIXELS));wr(r.revision);fflush(stdout);}
}
