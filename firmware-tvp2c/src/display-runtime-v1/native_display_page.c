/* 1.0.4.12-only adapter under OFFLINE TEST. Not linked into N8W or any OTA.
 * Owns one LVGL subtree, two pixel buffers, and one retirement timer.
 * Native symbols must be bound by a SHA-pinned linker, not guessed addresses. */
#include "native_display_page.h"
#include "display_carrier.h"
#include "display_memory.h"
#include <string.h>
#include "../menu8-renderer.h"
#include "../image-upload-test/render_idle.h"
#ifdef TIO_ANIMATION_EXPERIMENT
#include "../animation-runtime-v1/animation.h"
extern const uint8_t ta_asset[TA_BYTES*TA_FRAMES];
extern void native_log(unsigned,unsigned,unsigned,unsigned,const char *,...);
extern void stream_timer_period(void *,uint32_t);
#endif
struct TDPNativePage {
 TDPRuntime runtime;
 void *parent,*root,*canvas,*label,*timer;
 TDPNativePage **owner;
 uint32_t last_activity,last_reply;
 bool retired,sent_reply,closing_notified,reply_pending;
 bool visible_pending;   /* TDP_VISIBLE queued behind the UI_SUBMITTED's gate flush */
 bool revealed;          /* 2026-11-16 ghost fix: root stays hidden until the first UI_SUBMITTED */
 uint32_t reveal_at;     /* POWER-611 candidate A: when the first frame revealed (0 = never) */
 TDPReply pending_reply;
 bool banner;            /* V5 PushPolicy: this page renders as a top notification banner */
#ifdef TIO_ANIMATION_EXPERIMENT
 TAPlayer animation;
 bool animation_mode;
 unsigned anim_loop;     /* ANIM_PLAY loop count (255 = until upload/close) */
 bool anim_playing;      /* the 12-pose loop is running (16 ms tick) */
 bool sway;              /* ANIM_PLAY asset_id 0xFFFF: zero-asset sine sway of the shown frame */
#endif
 uint32_t hold;  /* 2026-11-16: screen-hold token from nav_always_on (0 = not held) */
};
extern const M8RenderAPI m8_native_api;
extern void *stream_memalign(size_t,size_t);
extern void stream_free(void *);
extern uint32_t stream_tick(void);
extern void *stream_timer_create(void (*)(void *),uint32_t,void *);
extern void stream_timer_delete(void *),*stream_timer_next(void *),*stream_event_user(void *);
extern void *stream_add_event(void *,void (*)(void *),uint32_t,void *);
extern void *stream_canvas_create(void *);
extern void stream_canvas_set_buffer(void *,void *,int32_t,int32_t,uint8_t);
extern uint32_t stream_stride(uint32_t,uint8_t);
extern void *stream_display_next(void *);
extern void stream_invalidate(void *),native_report_activity(void);
extern uint32_t nav_always_on(const char *);
extern void nav_release_always_on(const char *,uint32_t),nav_screen_on(bool);
/* PushPolicy (2026-11-17): self-computed foreground category — pref43 (ai_memory) is
 * deliberately NOT consulted (the background-recording busy signal is excluded; only a
 * real top-app foreground counts). */
extern const char *nav_top_app(void);
extern void *nav_monitors(void),*nav_input(void *);
extern bool nav_folded(void *);
/* V5 PushPolicy banner (candidate B): form=1 renders the frame as a top notification
 * banner — native-notification semantics (top overlay, auto-dismiss, expandable later);
 * transient screen-on, NO screen hold, no top-app/page-slot occupation beyond the lease. */
#define TDP_BANNER_MS 6000u    /* auto-dismiss duration (tunable) */
static unsigned display_category(void){
 void *m=nav_monitors(),*i=m?nav_input(m):NULL;
 if(i&&nav_folded(i))return 6;                              /* folded -> defer */
 const char *top=nav_top_app();
 if(!top||!*top||!strcmp(top,"com.rayneo.liteos.launcher")||!strcmp(top,"EMPTY_PAGE"))return 0;
 if(!strcmp(top,"com.rayneo.liteos.navigation"))return 1;   /* nav */
 if(!strcmp(top,"com.rayneo.liteos.prompter"))return 2;     /* prompter */
 if(!strcmp(top,"com.rayneo.liteos.conversationAssist"))return 3; /* call */
 if(!strcmp(top,"com.rayneo.liteos.assistant"))return 4;    /* voice session */
 return 5;                                                  /* other foreground */
}
extern int32_t tio_lv_obj_get_width(void *),tio_lv_obj_get_height(void *);
extern void tio_lv_obj_set_size(void *,int32_t,int32_t);
extern void *native_label_create(void *);
extern void native_label_text(void *,const char *),native_text_color(void *,uint32_t,uint32_t);
extern void native_align(void *,int,int,int);
/* C ABI inferred and ARM-tested: business, payload, uint16 length, callback.
 * It synchronously copies before enqueue; nonzero can still mean queued. */
extern int tdp_rnlink_send(unsigned,const uint8_t *,unsigned,void *);
static bool read_word(uint32_t address,uint32_t *out){
#ifdef TDP_NATIVE_HOST_TEST
 extern bool tdp_test_read_word(uint32_t,uint32_t *);
 return tdp_test_read_word(address,out);
#else
 if((address&3)||address<0x18000000||address>0x1b600000-4)return false;
 *out=*(volatile uint32_t *)(uintptr_t)address;return true;
#endif
}
static uint32_t next_display(uint32_t p){return (uint32_t)(uintptr_t)stream_display_next((void *)(uintptr_t)p);}
static bool idle(void *ctx){(void)ctx;return tio_render_graph_idle(read_word,next_display);}
static void unlink_owner(TDPNativePage *p){if(p->owner){if(*p->owner==p)*p->owner=NULL;p->owner=NULL;}}
static void deleted(void *event){
 TDPNativePage *p=stream_event_user(event);if(!p)return;
 p->root=p->canvas=p->label=NULL;p->retired=true;unlink_owner(p);
}
static bool submit(void *ctx,const uint8_t *pixels,unsigned w,unsigned h){
 TDPNativePage *p=ctx;
 if(!pixels){
  if(p->root)m8_native_api.delete_root(p->root);
  p->root=p->canvas=p->label=NULL;return true;
 }
 if(p->retired||!p->root||!p->canvas||w!=512||h!=128)return false;
#ifdef TIO_ANIMATION_EXPERIMENT
 ta_stop(&p->animation);p->animation_mode=false;
 if(p->timer)stream_timer_period(p->timer,100);
 tio_lv_obj_set_size(p->canvas,512,128);native_align(p->canvas,5,0,0);
 tio_lv_obj_set_size(p->label,512,44);native_align(p->label,2,0,0);
#endif
 stream_canvas_set_buffer(p->canvas,(void *)pixels,w,h,6);stream_invalidate(p->canvas);return true;
}
static TDPUI ui(TDPNativePage *p){return (TDPUI){p,idle,submit};}
static char *number(char *s,uint32_t n){char b[10];unsigned k=0;do{b[k++]=(char)('0'+n%10);n/=10;}while(n);while(k)*s++=b[--k];return s;}
static void status(TDPNativePage *p){
 if(!p->label)return;char text[64]="Turbo Display SID ";char *s=text+sizeof("Turbo Display SID ")-1;
 s=number(s,p->runtime.sid);*s++=' ';*s++='#';s=number(s,p->runtime.revision);*s=0;native_label_text(p->label,text);
}
#ifdef TIO_ANIMATION_EXPERIMENT
static bool animation_submit(void *ctx,const uint8_t *pixels,unsigned w,unsigned h){
 TDPNativePage *p=ctx;
 if(p->retired||!p->root||!p->canvas||w!=TA_WIDTH||h!=TA_HEIGHT)return false;
 stream_canvas_set_buffer(p->canvas,(void *)pixels,w,h,6);stream_invalidate(p->canvas);return true;
}
static void animation_status(TDPNativePage *p){
 if(!p->label)return;
 char text[120];
 memcpy(text,"Turbo Display 192x176\n1 still image\nsent ",sizeof("Turbo Display 192x176\n1 still image\nsent ")-1);
 char *s=text+sizeof("Turbo Display 192x176\n1 still image\nsent ")-1;
 s=number(s,p->animation.submitted);*s++=' ';*s++='/';*s++=' ';
 s=number(s,p->animation.skipped);*s++='\n';*s++='g';*s++='a';*s++='p';*s++=' ';
 s=number(s,p->animation.max_gap);*s++='m';*s++='s';*s++='\n';
 const char *state=p->animation.stalled?"STALL STOP":p->animation.failed?"ERROR STOP":p->animation.running?"WAIT":"STILL";
 while(*state)*s++=*state++;*s=0;native_label_text(p->label,text);
}
/* 2026-11-17 ANIM_LOCAL: page stays still until an explicit ANIM_PLAY (bit2 negotiated).
 * During playback the tick is 16 ms (FM precedent) and the page lease renews per accepted
 * pose (same discipline as the frame's 30 s renewal); on stop it falls back to 100 ms. */
static void sway_step(TDPNativePage *p,uint32_t now){
 /* ① zero-asset fallback: sine row-shift of the currently shown frame (±4 px, 2 s). */
 const uint8_t *src=p->runtime.pixels[p->runtime.active];
 uint8_t *dst=p->runtime.pixels[p->runtime.active^1u];
 /* quarter-wave sine table 0..90 deg scaled x4 (fixed point); phase from time. */
 static const int8_t qsin[26]={0,1,1,2,3,3,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4};
 uint32_t ph=(now/40)%176;   /* ~2 s spatial period across the 176 rows */
 for(unsigned y=0;y<TA_HEIGHT;y++){
  unsigned a=(y+ph)%176;unsigned quad=a/44,frac=a%44;
  int mag=qsin[frac>22?22-(frac-22):frac];int dx=(quad&1)?-mag:mag;if(quad>=2)mag=0,dx=0;
  const uint8_t *row=src+y*TA_WIDTH;uint8_t *o=dst+y*TA_WIDTH;
  for(unsigned x=0;x<TA_WIDTH;x++)o[x]=(int)x+dx>=0&&(int)x+dx<TA_WIDTH?row[x+dx]:0;
 }
 if(animation_submit(p,dst,TA_WIDTH,TA_HEIGHT))p->runtime.active^=1u;
}
static void animation_step(TDPNativePage *p,uint32_t now){
 TAUI a={p,idle,animation_submit};
 bool was_running=p->animation.running;
 if(p->sway){
  sway_step(p,now);p->runtime.deadline=now+120000u;   /* sway keeps the page alive */
  if(p->anim_loop!=255&&(uint32_t)(now-p->animation.start)>=TA_DURATION*(uint32_t)(p->anim_loop?p->anim_loop:1)){
   p->sway=false;p->anim_playing=false;stream_timer_period(p->timer,100);
  }
  return;
 }
 TAResult r=ta_step(&p->animation,&a,now);p->runtime.active=(uint8_t)p->animation.active;
 if(r==TA_SUBMITTED)p->runtime.deadline=now+120000u;  /* renew the page lease per pose */
 if(was_running&&!p->animation.running){
  /* FINISHED: loop again if requested (255 = until upload/close), else back to still. */
  if(p->anim_loop&&(p->anim_loop==255||--p->anim_loop)){
   ta_start(&p->animation,p->animation.asset,TA_BYTES*TA_FRAMES,p->runtime.pixels[0],p->runtime.pixels[1],TDP_PIXELS,now,p->runtime.active);
  }else{p->anim_playing=false;stream_timer_period(p->timer,100);}
 }
}
static void animation_play(TDPNativePage *p,unsigned asset_id,unsigned loop,unsigned fps_cap,uint32_t now){
 (void)fps_cap;   /* fps_cap reserved (V1 fixed 60 target) */
 p->animation_mode=true;   /* re-enable the tick's animation_step (an upload may have cleared it) */
 if(asset_id==0xFFFFu){   /* ① sway: zero-asset fallback on the shown frame */
  memset(&p->animation,0,sizeof p->animation);p->animation.start=now;
  p->sway=true;p->anim_playing=true;p->anim_loop=loop;
  p->runtime.deadline=now+120000u;stream_timer_period(p->timer,16);return;
 }
 if(asset_id!=0)return;   /* V1 single embedded pack (asset 0) */
 if(ta_start(&p->animation,ta_asset,sizeof ta_asset,p->runtime.pixels[0],p->runtime.pixels[1],TDP_PIXELS,now,p->runtime.active)){
  p->sway=false;p->anim_playing=true;p->anim_loop=loop;
  p->runtime.deadline=now+120000u;stream_timer_period(p->timer,16);   /* 16 ms during playback */
 }
}
#endif
static void emit(TDPReply result){
 result.max_rect_bytes=TDP_NATIVE_PIXELS_MAX;
 uint8_t bytes[TDP_UPLINK_MAX];size_t n=tdp_carrier_reply(&result,bytes,sizeof bytes);
 if(!n)return;
 /* Pageful path: the page's pending slot + tick flush backstop it. */
 (void)tdp_rnlink_send(TDP_UPLINK_BUSINESS,bytes,(unsigned)n,NULL);
}
/* 2026-11-16 Case A (Tob-approved): the SESSIONLESS path had no such backstop — a busy
 * stock send state machine (tdp_rnlink_send != 0) silently dropped NO_SESSION replies
 * (three QUERYs lost on 2026-11-16). One borrowed 100 ms stream timer carries the
 * pending reply (no BSS: the link script asserts zero data/bss); retried per tick,
 * bounded at 20 ticks (2 s), then dropped with a native_log trace as evidence.
 * The "offer exactly once" discipline becomes "offer until accepted, bounded" — an
 * accepted reply is still never re-sent. */
typedef struct { TDPReply reply; unsigned tries; } TDPPageless;
extern void native_log(unsigned,unsigned,unsigned,unsigned,const char *,...);
static void pageless_flush(void *timer){
#ifdef TDP_NATIVE_HOST_TEST
 extern void *tdp_test_timer_user(void *);
 TDPPageless *pg=tdp_test_timer_user(timer);
#else
 TDPPageless *pg=*(TDPPageless **)((uint8_t *)timer+12);
#endif
 if(!pg)return;
 uint8_t bytes[TDP_UPLINK_MAX];size_t n=tdp_carrier_reply(&pg->reply,bytes,sizeof bytes);
 bool sent=n&&tdp_rnlink_send(TDP_UPLINK_BUSINESS,bytes,(unsigned)n,NULL)==0;
 if(sent||++pg->tries>=40u){   /* 4 s bound (2026-11-17: raised from 2 s for long SM busy windows) */
  if(!sent)native_log(1,0,0,0,"[TurboDisplay] pageless reply dropped after 4s bound (result=%d)",(int)pg->reply.result);
  stream_timer_delete(timer);stream_free(pg);
 }
}
static TDPPageless *pageless_slot(bool create){
 unsigned visited=0;
 for(void *t=stream_timer_next(NULL);t;t=stream_timer_next(t)){
  if(++visited>512)break;
  bool ours;
#ifdef TDP_NATIVE_HOST_TEST
  extern bool tdp_test_timer_callback(void *,void (*)(void *));
  ours=tdp_test_timer_callback(t,pageless_flush);
#else
  ours=*(void (**)(void *))((uint8_t *)t+8)==pageless_flush;
#endif
  if(ours){
#ifdef TDP_NATIVE_HOST_TEST
   extern void *tdp_test_timer_user(void *);
   return tdp_test_timer_user(t);
#else
   return *(TDPPageless **)((uint8_t *)t+12);
#endif
  }
 }
 if(!create)return NULL;
 TDPPageless *pg=stream_memalign(8,sizeof *pg);if(!pg)return NULL;
 memset(pg,0,sizeof *pg);
 if(!stream_timer_create(pageless_flush,100,pg)){stream_free(pg);return NULL;}
 return pg;
}
static void pageless_emit(TDPReply result){
 result.max_rect_bytes=TDP_NATIVE_PIXELS_MAX;
 result.flags=0x0d;   /* CAPS bits are a firmware offer, independent of a page existing:
  * advertise FAST2|ANIM_LOCAL|FORM_FIELD even on the no-page reply so the host negotiates */
 uint8_t bytes[TDP_UPLINK_MAX];size_t n=tdp_carrier_reply(&result,bytes,sizeof bytes);
 if(!n)return;
 if(!tdp_rnlink_send(TDP_UPLINK_BUSINESS,bytes,(unsigned)n,NULL))return;   /* accepted at once */
 TDPPageless *pg=pageless_slot(true);
 if(!pg)return;                          /* no timer/mem available: bounded drop, as before */
 pg->reply=result;pg->tries=0;           /* newest sessionless answer supersedes the parked one */
}
/* 2026-11-16: orphan reply for paths that must answer without opening a page
 * (e.g. greeting's other-page-occupied STALE defer). Rides the same bounded chain. */
void tdp_native_reply_now(TDPResult result,uint32_t request){
 pageless_emit((TDPReply){result,0,request,0,512,128,TDP_NATIVE_PIXELS_MAX,120000,0,0});
}
/* Offer-once sessionless reply (V5 guard receipts): no parked retry — a garbage-packet
 * answer is best-effort; the phone's own timeout/retry covers a busy drop. No timer. */
static void pageless_offer(TDPReply result){
 result.max_rect_bytes=TDP_NATIVE_PIXELS_MAX;
 result.flags=0x0d;   /* pageless capability advertisement (same rationale as pageless_emit) */
 uint8_t bytes[TDP_UPLINK_MAX];size_t n=tdp_carrier_reply(&result,bytes,sizeof bytes);
 if(n)(void)tdp_rnlink_send(TDP_UPLINK_BUSINESS,bytes,(unsigned)n,NULL);
}
void tdp_native_offer_now(TDPResult result,uint32_t request){
 pageless_offer((TDPReply){result,0,request,0,512,128,TDP_NATIVE_PIXELS_MAX,120000,0,0});
}
static void flush_reply(TDPNativePage *p){
 uint32_t now=stream_tick();
 if(!p->reply_pending||(p->sent_reply&&(uint32_t)(now-p->last_reply)<100u))return;
 TDPReply value=p->pending_reply;p->reply_pending=false;p->sent_reply=true;p->last_reply=now;emit(value);
 /* TDP_VISIBLE: exactly one unsolicited follow-up right AFTER a UI_SUBMITTED, only
  * when the screen hold is held. Same gate as the ACK — order preserved under load. */
 if(value.result==TDP_UI_SUBMITTED&&p->visible_pending){p->visible_pending=false;
  if(p->hold){
   native_log(1,0,0,0,"[TurboDisplay] visible sid=%u rev=%u",p->runtime.sid,p->runtime.revision);
   emit((TDPReply){TDP_VISIBLE,p->runtime.sid,0,p->runtime.revision,512,128,TDP_NATIVE_PIXELS_MAX,120000,0,0});
  }
 }
}
/* V5 FAST2 merge priority: CLOSED > UI_SUBMITTED > NAK > STAGED > others. A strictly
 * higher-priority reply preempts the parked one; equal/lower is dropped (keep parked).
 * STAGED merges: the later one carries the higher frame_received high-water, so
 * drop-old-keep-new is lossless and the phone sees a monotone non-decreasing watermark. */
static unsigned reply_prio(TDPResult r){
 return r==TDP_CLOSED?4u:r==TDP_UI_SUBMITTED?3u:r==TDP_NAK?2u:r==TDP_STAGED?1u:0u;
}
static void reply(TDPNativePage *p,TDPReply result){
 /* One fixed pending slot; the 100ms timer delays a too-early reply instead of
  * silently dropping it. Lock-step behavior is unchanged (the slot is rarely pending). */
 if(p->reply_pending&&reply_prio(result.result)<=reply_prio(p->pending_reply.result))return;
 p->pending_reply=result;p->reply_pending=true;flush_reply(p);
}
static void notify_closed(TDPNativePage *p){
 if(p->closing_notified)return;p->closing_notified=true;
 /* request=0 denotes an unsolicited event; retain SID until this offer. */
 reply(p,(TDPReply){TDP_CLOSED,p->runtime.sid,0,p->runtime.revision,512,128,TDP_NATIVE_PIXELS_MAX,120000,0,0});
}
static void release_hold(TDPNativePage *p){
 if(p->hold){nav_release_always_on("turbo_display_v1",p->hold);p->hold=0;}
}
#ifdef TDP_NATIVE_HOST_TEST
uint32_t tdp_test_hold(const TDPNativePage *p){return p?p->hold:0;}   /* host-test accessor */
#endif
static void tick(void *timer){
#ifdef TDP_NATIVE_HOST_TEST
 extern void *tdp_test_timer_user(void *);
 TDPNativePage *p=tdp_test_timer_user(timer);
#else
 TDPNativePage *p=*(TDPNativePage **)((uint8_t *)timer+12);
#endif
 if(!p)return;TDPUI callbacks=ui(p);uint32_t now=stream_tick();
 if(p->retired||!p->runtime.open||(int32_t)(now-p->runtime.deadline)>=0){
  notify_closed(p);p->retired=true;unlink_owner(p);release_hold(p);
  if(p->root)m8_native_api.hidden(p->root,true);
  if(tdp_close(&p->runtime,&callbacks)!=TDP_CLOSED)return;
  flush_reply(p);if(p->reply_pending)return; /* keep one small ACK until due */
  stream_timer_delete(timer);stream_free(p);return;
 }
 flush_reply(p);
 (void)tdp_tick(&p->runtime,&callbacks,now);
 /* POWER-611 candidate A (Tob-approved): release the screen hold 3 s after the first
  * frame revealed — the page lease stays (the page can still be re-fed within 120 s),
  * but the screen is handed back to stock's normal sleep discipline. */
 if(p->hold&&p->reveal_at&&(uint32_t)(now-p->reveal_at)>=3000u)release_hold(p);
#ifdef TIO_ANIMATION_EXPERIMENT
 if(p->animation_mode)animation_step(p,now);
#endif
 if((uint32_t)(now-p->last_activity)>=1000u){p->last_activity=now;native_report_activity();
#ifdef TIO_ANIMATION_EXPERIMENT
  if(p->animation_mode){
   animation_status(p);
   native_log(1,0,0,0,"[TurboAnim60] elapsed=%u sent=%u skip=%u busy=%u gap=%u fail=%u running=%u",
    now-p->animation.start,p->animation.submitted,p->animation.skipped,p->animation.busy_polls,
    p->animation.max_gap,p->animation.failed,p->animation.running);
  }
#endif
 }
}
TDPNativePage *tdp_native_open(void *parent,uint32_t sid,TDPNativePage **owner){
 if(!parent||!sid||!owner||*owner)return NULL;
 unsigned visited=0;for(void *t=stream_timer_next(NULL);t;t=stream_timer_next(t)){
  if(++visited>512)return NULL;
#ifdef TDP_NATIVE_HOST_TEST
  extern bool tdp_test_timer_callback(void *,void (*)(void *));
  if(tdp_test_timer_callback(t,tick))return NULL;
#else
  if(*(void (**)(void *))((uint8_t *)t+8)==tick)return NULL;
#endif
 }
 if(!idle(NULL)||stream_stride(512,6)!=512||tio_lv_obj_get_width(parent)<540||tio_lv_obj_get_height(parent)<180)return NULL;
 TDPNativePage *p=stream_memalign(64,sizeof *p);if(!p)return NULL;memset(p,0,sizeof *p);p->parent=parent;p->owner=owner;
 if(!tdp_open(&p->runtime,sid,stream_tick())){stream_free(p);return NULL;}
#ifdef TIO_ANIMATION_EXPERIMENT
 if(stream_stride(TA_WIDTH,6)!=TA_WIDTH){stream_free(p);return NULL;}
 p->timer=stream_timer_create(tick,100,p);
#else
 p->timer=stream_timer_create(tick,100,p);
#endif
 if(!p->timer){stream_free(p);return NULL;}
 p->root=m8_native_api.create_root(parent);
 if(p->root){
  m8_native_api.hidden(p->root,true);
  if(!m8_native_api.configure_root(p->root,parent))goto fail;
  tio_lv_obj_set_size(p->root,540,180);native_align(p->root,9,0,0);
  if(!stream_add_event(p->root,deleted,0x24,p))goto fail;
  p->canvas=stream_canvas_create(p->root);p->label=native_label_create(p->root);
  if(!p->canvas||!p->label)goto fail;
  tio_lv_obj_set_size(p->canvas,512,128);native_align(p->canvas,5,0,0);
  tio_lv_obj_set_size(p->label,512,44);native_align(p->label,2,0,0);native_text_color(p->label,0x00ffffff,0);
  stream_canvas_set_buffer(p->canvas,p->runtime.pixels[0],512,128,6);
  status(p);
#ifdef TIO_ANIMATION_EXPERIMENT
  /* 2026-11-17 ANIM_LOCAL: show pose 0 as a STILL at open (the loop only runs on an
   * explicit ANIM_PLAY). The player is NOT started here; the lease stays the default
   * 120 s (tdp_open) — playback renews it per accepted pose. */
  p->animation_mode=true;
  memcpy(p->runtime.pixels[p->runtime.active],ta_asset,TA_BYTES);   /* pose 0 still */
  animation_submit(p,p->runtime.pixels[p->runtime.active],TA_WIDTH,TA_HEIGHT);
  tio_lv_obj_set_size(p->canvas,TA_WIDTH,TA_HEIGHT);native_align(p->canvas,7,8,0);
  tio_lv_obj_set_size(p->label,300,164);native_align(p->label,8,-8,0);
  animation_status(p);
#endif
  /* 2026-11-16 no-pop fix: the auto-opened page must take the screen (all sibling
   * modules do nav_always_on + nav_screen_on — camera/app/reader/music). Hold taken
   * only after every failure point, so the fail: path never leaks it. */
  p->hold=nav_always_on("turbo_display_v1");nav_screen_on(true);
  *owner=p;native_report_activity();   /* stay hidden: reveal only on the first UI_SUBMITTED */
  reply(p,(TDPReply){TDP_CAPS,sid,0,0,512,128,TDP_NATIVE_PIXELS_MAX,120000,0x0d,0});return p;   /* flags=bit0|bit2|bit3: the OPEN CAPS advertises unconditionally (the deadlock fix) */
 }
fail:
 if(p->root)m8_native_api.delete_root(p->root);
 stream_timer_delete(p->timer);stream_free(p);return NULL;
}
void tdp_native_retire(TDPNativePage *p){
 if(!p)return;notify_closed(p);p->retired=true;unlink_owner(p);release_hold(p);
 if(p->root)m8_native_api.hidden(p->root,true);
}
void tdp_native_link_lease(TDPNativePage *p,bool connected,uint32_t now){
 if(!p||p->retired)return;
 if(connected){p->runtime.deadline=now+120000u;return;}  /* link-up: full lease (same renewal as KEEPALIVE) */
 p->runtime.deadline=now+3000u;  /* every link-down (re)arms the 3 s grace from now */
}
TDPResult tdp_native_receive(TDPNativePage *p,const uint8_t *bytes,size_t n){
 /* TDP_STATUS (op=10): foreground-category query, page-agnostic — intercept before the
  * page gate so it answers with or without an open page. Valid envelope: magic/ver/op,
  * sid=0, request!=0, length=0, CRC. Replies ALIVE with byte33=category. */
 if(bytes&&n>=32&&n<=TDP_NATIVE_PACKET_MAX&&bytes[5]==TDP_STATUS){
  uint32_t request=0,len=0,crc=0;
  for(unsigned i=0;i<4;i++){request|=(uint32_t)bytes[12+i]<<(8*i);len|=(uint32_t)bytes[24+i]<<(8*i);crc|=(uint32_t)bytes[28+i]<<(8*i);}
  if(!memcmp(bytes,"TDP1",4)&&bytes[4]==1&&!bytes[6]&&!bytes[7]&&!bytes[8]&&!bytes[9]&&!bytes[10]&&!bytes[11]
     &&request&&len==0&&crc==tdp_crc(bytes+32,n-32)&&n==32){
   pageless_offer((TDPReply){TDP_ALIVE,0,request,0,512,128,TDP_NATIVE_PIXELS_MAX,120000,0,display_category()});
   return TDP_ALIVE;
  }
 }
 if(!p||p->retired||!p->root){
  /* Called only after native_display_file validated the owned outer envelope.
   * Still revalidate it here; no SID guessing and no page allocation. */
  if(!bytes||n<32||n>TDP_NATIVE_PACKET_MAX||memcmp(bytes,"TDP1",4)||bytes[4]!=1||bytes[6]||bytes[7]||bytes[5]<TDP_QUERY||bytes[5]>TDP_ANIM_PLAY){pageless_offer((TDPReply){TDP_BAD_PACKET,0,0,0,512,128,TDP_NATIVE_PIXELS_MAX,120000,0,0});return TDP_BAD_PACKET;}
  uint32_t request=0,len=0,crc=0;for(unsigned i=0;i<4;i++){request|=(uint32_t)bytes[12+i]<<(8*i);len|=(uint32_t)bytes[24+i]<<(8*i);crc|=(uint32_t)bytes[28+i]<<(8*i);}
  if(!request||len!=n-32||crc!=tdp_crc(bytes+32,len)){pageless_offer((TDPReply){TDP_BAD_PACKET,0,request,0,512,128,TDP_NATIVE_PIXELS_MAX,120000,0,0});return TDP_BAD_PACKET;}
  pageless_emit((TDPReply){TDP_NO_SESSION,0,request,0,512,128,TDP_NATIVE_PIXELS_MAX,120000,0,0});return TDP_NO_SESSION;
 }
 /* V5: over-limit guards answer the phone instead of silently dropping (kills the
  * phone's 5s UNCERTAIN + 60s gate-quarantine chain). */
 if(n>TDP_NATIVE_PACKET_MAX){uint32_t req=0;for(unsigned i=0;i<4&&12+i<n;i++)req|=(uint32_t)bytes[12+i]<<(8*i);reply(p,(TDPReply){TDP_BAD_PACKET,p->runtime.sid,req,p->runtime.revision,512,128,TDP_NATIVE_PIXELS_MAX,120000,0,0});return TDP_BAD_PACKET;}
 flush_reply(p);
 /* V5 FAST2 M3: a packet arriving while a reply is pending is the streaming signature
  * (lock-step phones never pipeline) — latch fast and proceed; the reply merges via
  * reply()'s priority slot. fast never latches for lock-step phones -> byte-identical. */
 if(p->reply_pending)p->runtime.fast=true;
 TDPUI callbacks=ui(p);TDPReply r=tdp_handle(&p->runtime,&callbacks,bytes,n,stream_tick());
#ifdef TIO_ANIMATION_EXPERIMENT
 if(bytes[5]==TDP_ANIM_PLAY){
  /* ANIM_LOCAL: {asset_id u16, loop u8, fps_cap u8}; the runtime already validated it
   * (session + fast-negotiated). STAGED = accepted -> start the loop / sway. */
  if(r.result==TDP_STAGED){
   unsigned asset_id=bytes[32]|((unsigned)bytes[33]<<8),loop=bytes[34],fps_cap=bytes[35];
   animation_play(p,asset_id,loop,fps_cap,stream_tick());
  }
 }else if(r.result==TDP_STAGED||r.result==TDP_UI_SUBMITTED||r.result==TDP_CLOSED){
  /* Valid uploads take ownership of the SAME staging buffers. QUERY/invalid
   * packets do not interrupt playback. Never animate during a transaction. */
  ta_stop(&p->animation);p->animation_mode=false;p->sway=false;p->anim_playing=false;
  stream_timer_period(p->timer,100);
 }
#endif
 if(r.result==TDP_UI_SUBMITTED){
  if(p->runtime.form==1){
   /* banner: top overlay, transient screen-on, no hold; auto-dismiss via the lease. */
   p->banner=true;p->runtime.deadline=stream_tick()+TDP_BANNER_MS;
   tio_lv_obj_set_size(p->canvas,512,64);native_align(p->canvas,1,0,8);   /* top banner geometry */
   if(!p->revealed){m8_native_api.hidden(p->root,false);p->revealed=true;}
   nav_screen_on(true);   /* wake to show the banner; no nav_always_on (transient) */
  }else{
   status(p);p->visible_pending=true;
   if(!p->revealed){m8_native_api.hidden(p->root,false);p->revealed=true;p->reveal_at=stream_tick();}   /* ghost fix + power: stamp the reveal */
  }
 }
 if(r.result==TDP_CLOSED){p->closing_notified=true;release_hold(p);}
 reply(p,r);
 return r.result;
}
