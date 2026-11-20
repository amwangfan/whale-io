#define _POSIX_C_SOURCE 200112L
#include "native_display_page.h"
#include "native_display_file.h"
#include "display_carrier.h"
#include "display_client.h"
#include "../menu8-renderer.h"
#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <stdarg.h>
#ifdef TIO_ANIMATION_EXPERIMENT
#include "../animation-runtime-v1/animation.h"
uint8_t ta_asset[TA_BYTES*TA_FRAMES];   /* all-zero: the main flow's shown[0]==0 depends on it */
static unsigned log_args[7];static const char *last_stale_fmt;
void native_log(unsigned a,unsigned b,unsigned c,unsigned d,const char *f,...){(void)a;(void)b;(void)c;(void)d;assert(f);
 va_list ap;va_start(ap,f);for(unsigned i=0;i<7;i++)log_args[i]=va_arg(ap,unsigned);va_end(ap);
 if(strstr(f,"stale"))last_stale_fmt=f;}
static unsigned animation_draws;
static unsigned last_period;   /* V5 ANIM_LOCAL: capture the tick period switches */
#endif
/* V5 PushPolicy stubs: nav_top_app returns a settable package; fold state settable. */
static const char *top_app_name;static bool folded_now;
const char *nav_top_app(void){return top_app_name;}
void *nav_monitors(void){return (void *)1;}
void *nav_input(void *m){(void)m;return (void *)1;}
bool nav_folded(void *i){(void)i;return folded_now;}
typedef struct Obj {struct Obj *parent;void (*ondelete)(void *);void *user;bool hidden;int w,h;} Obj;
typedef struct Timer {void (*callback)(void *);void *user;} Timer;
static Obj *objects[16];static unsigned object_count,creates,fail_create;
static bool busy,fail_timer,fail_event,fail_config,fail_alloc;
static Timer *timer;static uint32_t now=1000;static unsigned allocs,frees,sends;
static const uint8_t *shown;static TDPReply latest;
static size_t allocation_size;
static void *new_object(void *parent){
 creates++;if(creates==fail_create)return NULL;
 Obj *o=calloc(1,sizeof *o);assert(o&&object_count<16);o->parent=parent;objects[object_count++]=o;return o;
}
static void delete_object(void *p){
 Obj *o=p;assert(o&&object_count);
 for(unsigned i=0;i<object_count;){if(objects[i]->parent==o)delete_object(objects[i]);else i++;}
 if(o->ondelete)o->ondelete(o);
 unsigned i=0;while(i<object_count&&objects[i]!=o)i++;assert(i<object_count);
 objects[i]=objects[--object_count];shown=NULL;free(o);
}
static void hidden(void *p,bool h){((Obj *)p)->hidden=h;}
static bool configure(void *p,void *parent){assert(p&&parent==(void *)1);return !fail_config;}
const M8RenderAPI m8_native_api={.create_root=new_object,.delete_root=delete_object,.hidden=hidden,.configure_root=configure};
void *stream_memalign(size_t align,size_t n){if(fail_alloc)return NULL;void *p=NULL;assert(!posix_memalign(&p,align,n));allocs++;allocation_size=n;return p;}
void stream_free(void *p){assert(!busy&&shown==NULL);frees++;free(p);}
uint32_t stream_tick(void){return now;}
void *stream_timer_create(void (*fn)(void *),uint32_t period,void *user){
#ifdef TIO_ANIMATION_EXPERIMENT
 assert(!timer&&period==100);
#else
 assert(!timer&&period==100);
#endif
 if(fail_timer)return NULL;timer=calloc(1,sizeof *timer);assert(timer);timer->callback=fn;timer->user=user;return timer;
}
void stream_timer_delete(void *t){assert(t==timer);free(timer);timer=NULL;}
void *stream_timer_next(void *previous){return previous?NULL:timer;}
#ifdef TIO_ANIMATION_EXPERIMENT
void stream_timer_period(void *t,uint32_t ms){assert(t==timer&&(ms==100||ms==16));last_period=ms;}
#endif
void *tdp_test_timer_user(void *t){return ((Timer *)t)->user;}
bool tdp_test_timer_callback(void *t,void (*fn)(void *)){return ((Timer *)t)->callback==fn;}
void *stream_event_user(void *event){return ((Obj *)event)->user;}
void *stream_add_event(void *o,void (*fn)(void *),uint32_t event,void *user){
 assert(event==0x24);if(fail_event)return NULL;((Obj *)o)->ondelete=fn;((Obj *)o)->user=user;return o;
}
void *stream_canvas_create(void *p){return new_object(p);}
void *native_label_create(void *p){return new_object(p);}
void stream_canvas_set_buffer(void *p,void *b,int32_t w,int32_t h,uint8_t color){
 assert(p&&b&&color==6);
#ifdef TIO_ANIMATION_EXPERIMENT
 assert((w==512&&h==128)||(w==192&&h==176));
 if(w==192)animation_draws++;
#else
 assert(w==512&&h==128);
#endif
 shown=b;
}
uint32_t stream_stride(uint32_t w,uint8_t color){assert(color==6);return w;}
void *stream_display_next(void *p){return p?NULL:(void *)(uintptr_t)0x18002000;}
bool tdp_test_read_word(uint32_t a,uint32_t *v){
 if(a==0x1850ce1c+0x124){*v=0x18001000;return true;} /* 6.11 render-graph root (render_idle.c:7); was stale 1.0.4.12 0x184d4440 */
 if(a==0x18001000+12){*v=0x10462225;return true;} /* 6.11 render dispatch fn (render_idle.c:11); was stale 0x10428d89 */
 if(a==0x18001000+24){*v=busy?1:0;return true;}
 if(a==0x18001000||a==0x18002000+0x2a0){*v=0;return true;}
 assert(0);return false;
}
void stream_invalidate(void *p){assert(p);}
void native_report_activity(void){}
/* 2026-11-16 no-pop fix: the page takes the screen on open — capture the calls. */
static unsigned always_on_calls,release_calls,screen_on_calls;
uint32_t nav_always_on(const char *tag){always_on_calls++;return 1;}
void nav_release_always_on(const char *tag,uint32_t t){(void)tag;(void)t;release_calls++;}
void nav_screen_on(bool on){if(on)screen_on_calls++;}
int32_t tio_lv_obj_get_width(void *p){assert(p==(void *)1);return 540;}
int32_t tio_lv_obj_get_height(void *p){assert(p==(void *)1);return 280;}
void tio_lv_obj_set_size(void *p,int32_t w,int32_t h){assert(p);((Obj *)p)->w=w;((Obj *)p)->h=h;}
void native_label_text(void *p,const char *s){assert(p&&s&&strlen(s)<120);}
void native_text_color(void *p,uint32_t c,uint32_t style){assert(p&&c==0xffffff&&!style);}
void native_align(void *p,int align,int x,int y){assert(p);(void)align;(void)x;(void)y;}
int tdp_rnlink_send(unsigned bus,const uint8_t *p,unsigned n,void *cb){assert(!cb);assert(tdp_carrier_decode(bus,p,n,&latest));sends++;return 0;}
static uint8_t queue_data[TDP_NATIVE_PACKET_MAX];static TIONativeMessage queued;static unsigned queue_calls;
static int enqueue(unsigned module,const TIONativeMessage *m){assert(module==1&&m->mode==0&&m->bytes<=TDP_NATIVE_PACKET_MAX);memcpy(queue_data,m->data,m->bytes);queued=*m;queued.data=queue_data;queue_calls++;return 0;}
static TDPResult deliver(TDPNativePage *page,uint8_t *data,size_t len){
 TIONativeFile file={.data=data,.declared=(uint32_t)len,.complete=1,.received=(uint32_t)len};
 strcpy(file.filename,"turbo-display.tdp");assert(tdp_native_file_receive(&file,enqueue)==TIO_OK);
 memset(data,0,len); /* caller data expires after callback */
 now+=120;return tdp_native_dispatch(page,&queued);
}
static void pulse(void){assert(timer);timer->callback(timer);}
static void failure(unsigned which){
 assert(!timer&&!object_count);TDPNativePage *owner=NULL;creates=0;fail_create=which<=3?which:0;
 fail_timer=which==4;fail_event=which==5;fail_config=which==6;fail_alloc=which==7;
 assert(!tdp_native_open((void *)1,7392,&owner));assert(!owner&&!timer&&!object_count&&allocs==frees);
 fail_create=0;fail_timer=fail_event=fail_config=fail_alloc=false;
}
int main(void){
 for(unsigned i=1;i<=7;i++)failure(i);
 TDPNativePage *owner=NULL;creates=0;
 TDPNativePage *page=tdp_native_open((void *)1,7392,&owner);assert(page&&page==owner&&shown);
 assert(latest.sid==7392&&latest.request==0&&latest.result==TDP_CAPS&&latest.max_rect_bytes==TDP_NATIVE_PIXELS_MAX);
#ifdef TIO_ANIMATION_EXPERIMENT
 assert(animation_draws>0&&allocation_size<132000); /* reused TDP buffers */
 unsigned before_draws;
#endif
 uint8_t wire[TDP_MAX_PACKET],frame[TDP_PIXELS];memset(frame,192,sizeof frame);
 // QUERY immediately after HELLO used to lose its reply to rate limiting.
 size_t early=tdp_client_query(wire,sizeof wire,777);unsigned sent=sends;
 assert(tdp_native_receive(page,wire,early)==TDP_CAPS&&sends==sent);
 now+=100;pulse();assert(sends==sent+1&&latest.request==777);
#ifdef TIO_ANIMATION_EXPERIMENT
 /* Settle the intentionally coarse 100ms pulse, then measure a full second. */
 before_draws=animation_draws;
 for(unsigned i=0;i<200;i++){now+=5;pulse();}
 assert(animation_draws-before_draws==0); /* still image is not continuously repainted */
#endif
 size_t n=tdp_client_query(wire,sizeof wire,1);assert(deliver(page,wire,n)==TDP_CAPS&&latest.request==1&&latest.max_rect_bytes==TDP_NATIVE_PIXELS_MAX);
 n=tdp_client_begin(wire,sizeof wire,7392,2,0,1,tdp_crc(frame,sizeof frame));assert(deliver(page,wire,n)==TDP_STAGED);
#ifdef TIO_ANIMATION_EXPERIMENT
 before_draws=animation_draws;now+=25;pulse();assert(animation_draws==before_draws);
#endif
 unsigned request=3,full_tier_chunks=0;
 for(unsigned at=0;at<sizeof frame;){unsigned len=sizeof frame-at;if(len>TDP_NATIVE_PIXELS_MAX)len=TDP_NATIVE_PIXELS_MAX;
  n=tdp_client_chunk(wire,sizeof wire,7392,request++,0,1,at,frame+at,len);
  if(n==TDP_NATIVE_PACKET_MAX)full_tier_chunks++; /* wire packet exactly at the tier cap */
  assert(deliver(page,wire,n)==TDP_STAGED&&shown[0]==0);at+=len;
 }
 n=tdp_client_finish(wire,sizeof wire,TDP_FRAME_COMMIT,7392,request++,0,1);
 assert(deliver(page,wire,n)==TDP_UI_SUBMITTED&&latest.revision==1&&!memcmp(shown,frame,sizeof frame));
 /* Tier boundary: every full-sized CHUNK above ran a wire packet of exactly the tier
  * cap through file-receive -> dispatch -> page (<= tier accepted); one byte over the
  * tier must be rejected by the page guard before any parsing or reply. */
 assert(full_tier_chunks>0);
 assert(tdp_native_receive(page,wire,TDP_NATIVE_PACKET_MAX+1)==TDP_BAD_PACKET);
 unsigned before=queue_calls;
 TIONativeFile bad={.data=frame,.declared=TDP_NATIVE_PACKET_MAX+1,.received=TDP_NATIVE_PACKET_MAX+1,.complete=1};strcpy(bad.filename,"turbo-display.tdp");
 assert(tdp_native_file_receive(&bad,enqueue)==TIO_BAD_SIZE&&queue_calls==before&&latest.result==TDP_BAD_PACKET); /* V5 file guard receipt */
 strcpy(bad.filename,"turbo-photo.timg");assert(tdp_native_file_receive(&bad,enqueue)==TIO_FOREIGN);
 busy=true;tdp_native_retire(page);assert(!owner);unsigned old_frees=frees;pulse();assert(frees==old_frees);
 assert(!tdp_native_open((void *)1,8642,&owner)); /* old timer pins buffers */
 busy=false;now+=200;pulse();assert(!timer&&!object_count&&allocs==frees);
 creates=0;page=tdp_native_open((void *)1,8642,&owner);assert(page);
 busy=true;delete_object(objects[0]);assert(!owner);pulse();assert(timer);
 busy=false;now+=200;pulse();assert(!timer&&allocs==frees); /* parent deletion while DMA busy */
 creates=0;page=tdp_native_open((void *)1,9264,&owner);assert(page);
 now+=120001;pulse();assert(!owner&&!timer&&!object_count&&allocs==frees);
 creates=0;page=tdp_native_open((void *)1,28285,&owner);assert(page);
 n=tdp_client_control(wire,sizeof wire,TDP_CLOSE,28285,999,0);assert(deliver(page,wire,n)==TDP_CLOSED);
 pulse();assert(!timer&&!owner&&allocs==frees);
 // No open page: return a correlated negative reply, never silently time out.
 n=tdp_client_query(wire,sizeof wire,1000);sent=sends;
 assert(tdp_native_receive(NULL,wire,n)==TDP_NO_SESSION&&sends==sent+1&&latest.request==1000&&latest.sid==0);
 wire[28]^=1;sent=sends;assert(tdp_native_receive(NULL,wire,n)==TDP_BAD_PACKET&&sends==sent+1&&latest.result==TDP_BAD_PACKET); /* V5 guard receipt */
 sent=sends;assert(tdp_native_receive(NULL,wire,TDP_NATIVE_PACKET_MAX+1)==TDP_BAD_PACKET&&sends==sent+1&&latest.result==TDP_BAD_PACKET); /* over-tier sessionless receipt */
 // Closing within rate window preserves the negative event until the timer,
 // with bounded memory, rather than freeing before it can be offered.
 creates=0;page=tdp_native_open((void *)1,34567,&owner);assert(page);sent=sends;
 tdp_native_retire(page);pulse();assert(timer&&sends==sent);
 now+=100;pulse();assert(!timer&&sends==sent+1&&latest.result==TDP_CLOSED&&latest.sid==34567&&allocs==frees);
 /* V5 FAST2 page level: a packet with a pending reply is the streaming signature -> the
  * gate latches fast instead of bouncing BUSY; then a gap chunk -> NAK with the watermark. */
 creates=0;page=tdp_native_open((void *)1,60001,&owner);assert(page);
 n=tdp_client_begin(wire,sizeof wire,60001,2,0,1,tdp_crc(frame,sizeof frame));assert(deliver(page,wire,n)==TDP_STAGED);
 /* deliver() advanced now past the 100ms gate, so the BEGIN's reply went out; drive the
  * next two chunks WITHOUT advancing now so the first one's reply stays pending. */
 n=tdp_client_chunk(wire,sizeof wire,60001,3,0,1,0,frame,472);
 assert(tdp_native_receive(page,wire,n)==TDP_STAGED);   /* reply parked by the 100ms gate */
 n=tdp_client_chunk(wire,sizeof wire,60001,4,0,1,472,frame+472,472);
 assert(tdp_native_receive(page,wire,n)==TDP_STAGED);   /* gate fired -> FAST2 latched, no BUSY */
 n=tdp_client_chunk(wire,sizeof wire,60001,5,0,1,2000,frame+2000,472);   /* gap: at != 944 */
 assert(tdp_native_receive(page,wire,n)==TDP_NAK);      /* NAK (not STALE) once fast */
 now+=100;pulse();assert(latest.result==TDP_NAK&&latest.high_water==944); /* watermark rides bytes33..35 */
 n=tdp_client_chunk(wire,sizeof wire,60001,6,0,1,944,frame+944,472);     /* resume from the watermark */
 assert(tdp_native_receive(page,wire,n)==TDP_STAGED);
 now+=100;pulse();assert(latest.result==TDP_STAGED&&latest.high_water==1416);
 busy=true;tdp_native_retire(page);pulse();busy=false;now+=200;pulse();assert(!timer&&allocs==frees);
#ifdef TIO_ANIMATION_EXPERIMENT
 /* V5 ANIM_LOCAL: open shows pose 0 still; ANIM_PLAY starts the 12-pose loop at 16ms;
  * an upload preempts back to still. */
 creates=0;page=tdp_native_open((void *)1,60002,&owner);assert(page);
 assert(last_period==0||last_period==100);   /* still: no 16ms tick yet */
 /* latch FAST2 with NO frame in flight: two back-to-back QUERYs (reply pending) */
 n=tdp_client_query(wire,sizeof wire,60);tdp_native_receive(page,wire,n);   /* CAPS parks (within 100ms of open) */
 n=tdp_client_query(wire,sizeof wire,61);tdp_native_receive(page,wire,n);   /* gate fires -> latch fast */
 /* ANIM_PLAY op=9 {asset_id=0, loop=255, fps_cap=60} */
 memset(wire,0,64);memcpy(wire,"TDP1",4);wire[4]=1;wire[5]=9;
 {unsigned v;v=60002;for(unsigned i=0;i<4;i++)wire[8+i]=(uint8_t)(v>>(8*i));v=50;for(unsigned i=0;i<4;i++)wire[12+i]=(uint8_t)(v>>(8*i));
  v=4;for(unsigned i=0;i<4;i++)wire[24+i]=(uint8_t)(v>>(8*i));
  wire[32]=0;wire[33]=0;wire[34]=255;wire[35]=60;   /* asset_id=0, loop=255, fps_cap=60 */
  unsigned c=~0u;for(unsigned i=32;i<36;i++){c^=wire[i];for(unsigned b=0;b<8;b++)c=(c>>1)^(0xedb88320u&-(c&1));}
  c=~c;for(unsigned i=0;i<4;i++)wire[28+i]=(uint8_t)(c>>(8*i));}
 assert(tdp_native_receive(page,wire,36)==TDP_STAGED);   /* ANIM_PLAY accepted */
 assert(last_period==16);                                  /* tick raised to 16ms */
 unsigned ad0=animation_draws;
 for(unsigned i=0;i<20;i++){now+=16;pulse();}              /* ~320ms of playback */
 assert(animation_draws>ad0);                              /* poses are being submitted */
 /* a full frame upload preempts the loop back to still: BEGIN stops it, then chunks stage clean */
 n=tdp_client_begin(wire,sizeof wire,60002,70,0,1,tdp_crc(frame,sizeof frame));   /* fresh runtime: base=0 rev=1 */
 TDPResult ur=deliver(page,wire,n);
 if(!(ur==TDP_STAGED&&last_period==100))printf("  [probe] upload preempt: result=%d last_period=%u stale_args=[%u %u %u | %u %u %u %u]\n",ur,last_period,log_args[0],log_args[1],log_args[2],log_args[3],log_args[4],log_args[5],log_args[6]);
 assert(ur==TDP_STAGED&&last_period==100);   /* upload BEGIN preempted -> 100ms */
 busy=true;tdp_native_retire(page);pulse();busy=false;now+=200;pulse();assert(!timer&&allocs==frees);
#endif
 /* pageless-path capability advertisement (the host-side deadlock fix): a sessionless
  * QUERY -> NO_SESSION must still carry flags=0x0d so the host sees ANIM_LOCAL offered. */
 { unsigned n0=sends;
   n=tdp_client_query(wire,sizeof wire,900);
   assert(tdp_native_receive(NULL,wire,n)==TDP_NO_SESSION);
   assert(sends>n0);
   unsigned fl0=latest.flags; assert(fl0==0x0d);   /* the pageless NO_SESSION advertises the capabilities */
   /* then open a page -> the animation is usable (ANIM_PLAY accepted) */
   creates=0;page=tdp_native_open((void*)1,88801,&owner);assert(page);
   n=tdp_client_begin(wire,sizeof wire,88801,2,0,1,tdp_crc(frame,sizeof frame));assert(deliver(page,wire,n)==TDP_STAGED);
   { static uint8_t anp[36];memset(anp,0,36);memcpy(anp,"TDP1",4);anp[4]=1;anp[5]=9;   /* ANIM_PLAY */
     unsigned v;
     v=88801;for(unsigned i=0;i<4;i++)anp[8+i]=(uint8_t)(v>>(8*i));   /* sid */
     v=901;for(unsigned i=0;i<4;i++)anp[12+i]=(uint8_t)(v>>(8*i));    /* request */
     v=4;for(unsigned i=0;i<4;i++)anp[24+i]=(uint8_t)(v>>(8*i));      /* length=4 */
     anp[32]=0;anp[33]=0;anp[34]=255;anp[35]=60;                       /* {asset_id=0, loop=255, fps_cap=60} */
     v=tdp_crc(anp+32,4);for(unsigned i=0;i<4;i++)anp[28+i]=(uint8_t)(v>>(8*i));
     /* the frame is in-flight -> ANIM_PLAY must be BUSY (not BAD_PACKET); abort first -> STAGED */
     assert(tdp_native_receive(page,anp,36)==TDP_BUSY);
     n=tdp_client_finish(wire,sizeof wire,TDP_FRAME_ABORT,88801,request++,0,1);assert(deliver(page,wire,n)==TDP_ABORTED);
     assert(tdp_native_receive(page,anp,36)==TDP_STAGED);   /* idle now -> accepted */ }
   busy=true;tdp_native_retire(page);pulse();busy=false;now+=200;pulse();assert(!timer&&allocs==frees);
 }
 /* V5 PushPolicy: TDP_STATUS category query (page-agnostic, sessionless) + banner. */
 { static uint8_t spkt[40];unsigned v;
   memset(spkt,0,40);memcpy(spkt,"TDP1",4);spkt[4]=1;spkt[5]=10;   /* TDP_STATUS */
   v=0xCAFE;for(unsigned i=0;i<4;i++)spkt[12+i]=(uint8_t)(v>>(8*i));   /* request */
   unsigned c=~0u;for(unsigned i=32;i<32;i++){}   /* length=0 -> CRC over empty body */
   for(unsigned i=0;i<0;i++){}c=tdp_crc(spkt+32,0);for(unsigned i=0;i<4;i++)spkt[28+i]=(uint8_t)(c>>(8*i));
   top_app_name="com.rayneo.liteos.launcher";folded_now=false;sent=sends;
   assert(tdp_native_receive(NULL,spkt,32)==TDP_ALIVE&&latest.result==TDP_ALIVE&&latest.high_water==0); /* idle */
   top_app_name="com.rayneo.liteos.navigation";assert(tdp_native_receive(NULL,spkt,32)==TDP_ALIVE&&latest.high_water==1); /* nav */
   top_app_name="com.rayneo.liteos.assistant";assert(tdp_native_receive(NULL,spkt,32)==TDP_ALIVE&&latest.high_water==4);  /* voice */
   folded_now=true;top_app_name="com.rayneo.liteos.launcher";assert(tdp_native_receive(NULL,spkt,32)==TDP_ALIVE&&latest.high_water==6); /* fold->defer */
   folded_now=false;
 }
 /* POWER-611 candidate A: after the first frame reveals, the hold is released ~3s later
  * (page stays alive; frames keep landing within the lease). */
 creates=0;page=tdp_native_open((void *)1,71001,&owner);assert(page);
 n=tdp_client_begin(wire,sizeof wire,71001,2,0,1,tdp_crc(frame,sizeof frame));assert(deliver(page,wire,n)==TDP_STAGED);
 unsigned at2=0;while(at2<65536){unsigned len=65536-at2;if(len>472)len=472;
  n=tdp_client_chunk(wire,sizeof wire,71001,request++,0,1,at2,frame+at2,len);assert(deliver(page,wire,n)==TDP_STAGED);at2+=len;}
 n=tdp_client_finish(wire,sizeof wire,TDP_FRAME_COMMIT,71001,request++,0,1);
 assert(deliver(page,wire,n)==TDP_UI_SUBMITTED);       /* revealed, hold taken */
 unsigned rel0=release_calls;
 for(unsigned i=0;i<29;i++){now+=100;pulse();}         /* 2.9s: not yet */
 assert(release_calls==rel0);                          /* hold still held before 3s */
 for(unsigned i=0;i<3;i++){now+=100;pulse();}          /* cross 3s */
 assert(release_calls==rel0+1);                        /* hold released at ~3s */
 assert(owner);                                        /* page still alive after release */
 /* a second frame within the lease still lands */
 n=tdp_client_begin(wire,sizeof wire,71001,request++,1,2,tdp_crc(frame,sizeof frame));assert(deliver(page,wire,n)==TDP_STAGED);
 busy=true;tdp_native_retire(page);pulse();busy=false;now+=200;pulse();assert(!timer&&allocs==frees);
 /* banner: form=1 BEGIN (13B) transfers the frame; COMMIT renders a top banner that
  * auto-dismisses via the lease, no screen hold. */
 creates=0;page=tdp_native_open((void *)1,70001,&owner);assert(page);
 n=tdp_client_query(wire,sizeof wire,60);tdp_native_receive(page,wire,n);   /* latch fast */
 n=tdp_client_query(wire,sizeof wire,61);tdp_native_receive(page,wire,n);
 n=tdp_client_begin13(wire,sizeof wire,70001,2,0,1,tdp_crc(frame,sizeof frame),1);assert(deliver(page,wire,n)==TDP_STAGED);
 at2=0;while(at2<65536){unsigned len=65536-at2;if(len>472)len=472;
  n=tdp_client_chunk(wire,sizeof wire,70001,request++,0,1,at2,frame+at2,len);assert(deliver(page,wire,n)==TDP_STAGED);at2+=len;}
 unsigned holds0=always_on_calls;
 n=tdp_client_finish(wire,sizeof wire,TDP_FRAME_COMMIT,70001,request++,0,1);
 assert(deliver(page,wire,n)==TDP_UI_SUBMITTED);
 assert(always_on_calls==holds0);   /* banner: NO new screen hold (transient) */
 busy=true;tdp_native_retire(page);pulse();busy=false;now+=200;pulse();assert(!timer&&allocs==frees);
 printf("PASS native adapter mocks: 7 allocation/setup failures, SID/PB reply, %u-chunk atomic frame (%u full-tier), tier %d gate (payload %d, tier+1 rejected), FAST2 latch+NAK, ANIM_LOCAL play/preempt, PushPolicy STATUS+banner, physical/parent/timeout/protocol close; page=%zu bytes; allocations=%u frees=%u; NO real LVGL/radio\n",request-4,full_tier_chunks,TDP_NATIVE_PACKET_MAX,TDP_NATIVE_PIXELS_MAX,allocation_size,allocs,frees);
}
