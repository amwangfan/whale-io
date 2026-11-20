#include "display_runtime.h"
#include "display_memory.h"
/* 2026-11-16 STALE tracepoints (Tob-approved): one line per STALE rejection, never on
 * a normal path. Native call inside the offline core is deliberate and scoped to
 * reject paths only (triages which of the rejection sites fired on-device). */
extern void native_log(unsigned,unsigned,unsigned,unsigned,const char *,...);
static void stale_note(const TDPRuntime *r,unsigned op,uint32_t tx,uint32_t at){
 native_log(1,0,0,0,"[TurboDisplay] stale op=%u tx=%u at=%u | rt tx=%u rev=%u pending=%u recv=%u",
  op,tx,at,r?r->transaction:0,r?r->revision:0,r?(unsigned)r->frame_pending:0u,r?r->frame_received:0u);
}
static uint16_t u16(const uint8_t *p){return p[0]|((uint16_t)p[1]<<8);}
static uint32_t u32(const uint8_t *p){return p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);}
static void p32(uint8_t *p,uint32_t v){for(unsigned i=0;i<4;i++)p[i]=(uint8_t)(v>>(8*i));}
uint32_t tdp_crc(const uint8_t *p,size_t n){uint32_t c=UINT32_MAX;for(size_t i=0;i<n;i++){c^=p[i];for(unsigned b=0;b<8;b++)c=(c>>1)^(0xedb88320u&(0u-(c&1u)));}return ~c;}
static bool expired(uint32_t now,uint32_t deadline){return (int32_t)(now-deadline)>=0;}
static TDPReply reply(const TDPRuntime *r,TDPResult result,uint32_t request){
 TDPReply q={result,(r&&r->open&&!r->closing)?r->sid:0,request,r?r->revision:0,TDP_WIDTH,TDP_HEIGHT,TDP_MAX_RECT,120000,0,0};
 /* V5 FAST2: CAPS advertises bit0 once streaming is negotiated (behavioral latch);
  * NAK and merged STAGED carry the frame_received high-water. Both stay 0 for lock-step. */
 /* CAPS advertises FAST2(bit0)+ANIM_LOCAL(bit2)+FORM_FIELD(bit3) UNCONDITIONALLY —
  * capability advertisement is NOT behavior activation (the 2026-11-18 deadlock: gating
  * the advertisement on the behavioral latch meant no latch -> no offer -> no latch).
  * The fast latch only drives the streaming merge/NAK BEHAVIOR, not acceptance. */
 if(result==TDP_CAPS)q.flags=1u|4u|8u;
 if(r&&r->fast&&(result==TDP_NAK||result==TDP_STAGED))q.high_water=r->frame_received;
 return q;
}
bool tdp_open(TDPRuntime *r,uint32_t sid,uint32_t now){
 if(!r||!sid||r->open||r->closing)return false;
 memset(r,0,sizeof *r);r->sid=sid;r->open=true;r->deadline=now+120000u;return true;
}
TDPResult tdp_close(TDPRuntime *r,const TDPUI *ui){
 if(!r||!ui||!ui->idle||!ui->submit)return TDP_BAD_PACKET;
 if(!r->open&&!r->closing)return TDP_CLOSED;
 r->open=false;r->closing=true;r->frame_pending=false;
 if(!ui->idle(ui->ctx))return TDP_BUSY;
 if(!ui->submit(ui->ctx,NULL,0,0))return TDP_RENDER_ERROR;
 r->closing=false;r->sid=0;return TDP_CLOSED;
}
TDPResult tdp_tick(TDPRuntime *r,const TDPUI *ui,uint32_t now){
 if(!r)return TDP_BAD_PACKET;
 if(r->closing||(r->open&&expired(now,r->deadline)))return tdp_close(r,ui);
 if(r->frame_pending&&expired(now,r->frame_deadline))r->frame_pending=false;
 return r->open?TDP_ALIVE:TDP_NO_SESSION;
}
TDPReply tdp_handle(TDPRuntime *r,const TDPUI *ui,const uint8_t *p,size_t n,uint32_t now){
 if(!r||!ui||!ui->idle||!ui->submit||!p||n<TDP_HEADER||n>TDP_MAX_PACKET)return reply(r,TDP_BAD_PACKET,0);
 uint32_t request=u32(p+12),base=u32(p+16),revision=u32(p+20),length=u32(p+24),sid=u32(p+8);
 if(memcmp(p,"TDP1",4)||p[4]!=1||p[6]||p[7]||!request||length!=n-TDP_HEADER||tdp_crc(p+32,length)!=u32(p+28))return reply(r,TDP_BAD_PACKET,request);
 unsigned op=p[5];
 if(op<TDP_QUERY||op>TDP_STATUS)return reply(r,TDP_BAD_PACKET,request);
 /* TDP_STATUS (op=10) is intercepted by native_display_page.c before tdp_handle (it is a
  * page-agnostic foreground query answered from stock state, not a session op). */
 if(op==TDP_ANIM_PLAY){
  /* ANIM_LOCAL control op: {asset_id u16, loop u8, fps_cap u8}. Only honored once the
   * feature is negotiated (fast latched); the page decodes the payload and starts the
   * loop. No frame/revision state touched. */
  if(!r->open||r->closing||expired(now,r->deadline)){TDPReply q=reply(r,TDP_NO_SESSION,request);q.sid=0;return q;}
  if(sid!=r->sid){stale_note(r,op,0,0);return reply(r,TDP_STALE,request);}
  /* Acceptance follows the ADVERTISED capability (CAPS always offers ANIM_LOCAL), not the
   * behavioral fast latch — a phone playing by the offer must not be refused. */
  if(length!=4)return reply(r,TDP_BAD_PACKET,request);
  /* Never animate during a transaction: the loop reuses the SAME staging buffers, so a
   * mid-transfer ANIM_PLAY would clobber the in-flight frame -> COMMIT CRC/corruption. */
  if(r->frame_pending)return reply(r,TDP_BUSY,request);
  r->deadline=now+120000u;return reply(r,TDP_STAGED,request);
 }
 if(op==TDP_QUERY){
  if(sid||base||revision||length)return reply(r,TDP_BAD_PACKET,request);
  /* QUERY is not a keepalive, cannot open a UI or resurrect a stale session. */
  if(r->open&&expired(now,r->deadline)){TDPReply q=reply(r,TDP_NO_SESSION,request);q.sid=0;return q;}
  return reply(r,(r->open&&!r->closing)?TDP_CAPS:TDP_NO_SESSION,request);
 }
 if(!r->open||r->closing||expired(now,r->deadline)){TDPReply q=reply(r,TDP_NO_SESSION,request);q.sid=0;return q;}
 if(sid!=r->sid){stale_note(r,op,0,0);return reply(r,TDP_STALE,request);}
 if(r->frame_pending&&expired(now,r->frame_deadline))r->frame_pending=false;
 if(op==TDP_KEEPALIVE||op==TDP_CLOSE){
  if(length||revision!=base||base!=r->revision)return reply(r,TDP_BAD_PACKET,request);
  if(op==TDP_CLOSE)return reply(r,tdp_close(r,ui),request);
  r->deadline=now+120000u;return reply(r,TDP_ALIVE,request);
 }
 uint32_t digest=tdp_crc(p,n);
 if(op>=TDP_FRAME_BEGIN){
  /* FORM_FIELD: a 13-byte BEGIN body (byte44=form) is legal once the capability is advertised
   * (CAPS always offers it) — acceptance follows the offer, not the fast latch. Old phones
   * never send 13B, so the 12-byte path stays byte-identical. */
  if((op==TDP_FRAME_BEGIN&&length!=12&&length!=13)||(op==TDP_FRAME_CHUNK&&(length<9||length>8+TDP_MAX_RECT))||
     ((op==TDP_FRAME_COMMIT||op==TDP_FRAME_ABORT)&&length!=4))return reply(r,TDP_BAD_PACKET,request);
  if(op==TDP_FRAME_BEGIN)r->form=(length>=13)?p[44]:0;   /* FORM_FIELD: 0=page,1=banner */
  uint32_t tx=u32(p+32);
  if(!tx)return reply(r,TDP_BAD_PACKET,request);
  if(op==TDP_FRAME_COMMIT&&request==r->last_request&&digest==r->last_digest)return reply(r,TDP_UI_SUBMITTED,request);
  if(base==UINT32_MAX||base!=r->revision||revision!=base+1){stale_note(r,op,tx,0);return reply(r,TDP_STALE,request);}
  if(op==TDP_FRAME_BEGIN){
   if(u32(p+36)!=TDP_PIXELS)return reply(r,TDP_BAD_PACKET,request);
   if(r->frame_pending){
     if(tx==r->transaction&&u32(p+40)==r->frame_crc)return reply(r,TDP_STAGED,request);  /* idempotent retry of the same in-flight frame */
     if(tx<=r->transaction)return reply(r,TDP_BUSY,request);  /* a genuinely older conflicting frame */
     /* 2026-11-15: a NEWER transaction means the previous frame was abandoned (e.g. an interrupted
      * greeting). Reset the stuck frame_pending so a fresh frame isn't stuck BUSY for the whole
      * 30s frame_deadline — self-heals interrupted pushes. */
     r->frame_pending=false;
    }
   if(tx<=r->last_transaction){stale_note(r,op,tx,0);return reply(r,TDP_STALE,request);}
   if(!ui->idle(ui->ctx))return reply(r,TDP_BUSY,request);
   r->frame_pending=true;r->transaction=tx;r->last_transaction=tx;r->frame_crc=u32(p+40);
   r->frame_received=0;r->frame_deadline=now+30000u;
   return reply(r,TDP_STAGED,request);
  }
  if(!r->frame_pending||tx!=r->transaction){stale_note(r,op,tx,0);return reply(r,TDP_STALE,request);}
  if(op==TDP_FRAME_ABORT){r->frame_pending=false;return reply(r,TDP_ABORTED,request);}
  uint8_t *staging=r->pixels[r->active^1u];
  if(op==TDP_FRAME_CHUNK){
   uint32_t at=u32(p+36),count=length-8;
   if(at>TDP_PIXELS||count>TDP_PIXELS-at)return reply(r,TDP_BAD_PACKET,request);
   /* :87 overlap: content-identical retransmit is benign (STAGED); a mismatch is a
    * protocol violation -> STAY fatal STALE, never degrade to NAK (a NAK would mask it
    * as an endless retransmit). */
   if(at<r->frame_received){bool ok=at+count<=r->frame_received&&!memcmp(staging+at,p+40,count);if(!ok)stale_note(r,op,tx,at);return reply(r,ok?TDP_STAGED:TDP_STALE,request);}
   /* :88 gap: under FAST2 -> NAK with the high-water and keep frame_pending (the phone
    * resumes from it); lock-step -> fatal STALE as before. NAK renews the 30s frame
    * deadline (any sign of life). */
   if(at!=r->frame_received){
    if(r->fast){r->frame_deadline=now+30000u;return reply(r,TDP_NAK,request);}
    stale_note(r,op,tx,at);return reply(r,TDP_STALE,request);
   }
   memcpy(staging+at,p+40,count);r->frame_received+=count;r->frame_deadline=now+30000u;return reply(r,TDP_STAGED,request);
  }
  if(r->frame_received!=TDP_PIXELS){stale_note(r,op,tx,0);return reply(r,TDP_STALE,request);}
  if(tdp_crc(staging,TDP_PIXELS)!=r->frame_crc){r->frame_pending=false;return reply(r,TDP_BAD_PACKET,request);}
  if(!ui->idle(ui->ctx))return reply(r,TDP_BUSY,request);
  if(!ui->submit(ui->ctx,staging,TDP_WIDTH,TDP_HEIGHT))return reply(r,TDP_RENDER_ERROR,request);
  r->active^=1u;r->frame_pending=false;r->revision=revision;r->last_request=request;r->last_digest=digest;
  r->deadline=now+120000u;return reply(r,TDP_UI_SUBMITTED,request);
 }
 if(length<9)return reply(r,TDP_BAD_PACKET,request);
 unsigned x=u16(p+32),y=u16(p+34),w=u16(p+36),h=u16(p+38);
 if(!w||!h||x>=TDP_WIDTH||y>=TDP_HEIGHT||w>TDP_WIDTH-x||h>TDP_HEIGHT-y||w*h>TDP_MAX_RECT||length!=8+w*h)return reply(r,TDP_BAD_PACKET,request);
 if(request==r->last_request){bool same=digest==r->last_digest;if(!same)stale_note(r,op,0,0);return reply(r,same?TDP_UI_SUBMITTED:TDP_STALE,request);}
 if(base==UINT32_MAX||base!=r->revision||revision!=base+1){stale_note(r,op,0,0);return reply(r,TDP_STALE,request);}
 if(r->frame_pending)return reply(r,TDP_BUSY,request);
 if(!ui->idle(ui->ctx))return reply(r,TDP_BUSY,request);
 unsigned staging=r->active^1u;memcpy(r->pixels[staging],r->pixels[r->active],TDP_PIXELS);
 for(unsigned row=0;row<h;row++)memcpy(r->pixels[staging]+(y+row)*TDP_WIDTH+x,p+40+row*w,w);
 if(!ui->submit(ui->ctx,r->pixels[staging],TDP_WIDTH,TDP_HEIGHT))return reply(r,TDP_RENDER_ERROR,request);
 r->active=(uint8_t)staging;r->revision=revision;r->last_request=request;r->last_digest=digest;
 r->deadline=now+120000u;return reply(r,TDP_UI_SUBMITTED,request);
}
bool tdp_reply_encode(const TDPReply *r,uint8_t *out,size_t size){
 if(!r||!out||size<TDP_REPLY_BYTES||(unsigned)r->result>TDP_NAK)return false;
 memset(out,0,TDP_REPLY_BYTES);memcpy(out,"TDR1",4);out[4]=1;out[5]=(uint8_t)r->result;
 p32(out+8,r->sid);p32(out+12,r->request);p32(out+16,r->revision);
 out[20]=(uint8_t)r->width;out[21]=(uint8_t)(r->width>>8);out[22]=(uint8_t)r->height;out[23]=(uint8_t)(r->height>>8);
 p32(out+24,r->max_rect_bytes);p32(out+28,r->lease_ms);
 out[32]=(uint8_t)r->flags;                     /* V5 FAST2: byte32 feature flags */
 out[33]=(uint8_t)r->high_water;out[34]=(uint8_t)(r->high_water>>8);out[35]=(uint8_t)(r->high_water>>16); /* u24 */
 p32(out+36,tdp_crc(out,36));return true;
}
bool tdp_reply_decode(const uint8_t *p,size_t size,TDPReply *out){
 /* V5 FAST2: bytes32..35 now carry flags+high-water -> the old "must be zero" check is
  * relaxed (our mirror decoder; the phone-side relaxation is an app-dev task). */
 if(!p||!out||size!=TDP_REPLY_BYTES||memcmp(p,"TDR1",4)||p[4]!=1||p[5]>TDP_NAK||p[6]||p[7]||tdp_crc(p,36)!=u32(p+36))return false;
 *out=(TDPReply){(TDPResult)p[5],u32(p+8),u32(p+12),u32(p+16),u16(p+20),u16(p+22),u32(p+24),u32(p+28),
  p[32],(uint32_t)p[33]|((uint32_t)p[34]<<8)|((uint32_t)p[35]<<16)};return true;
}
