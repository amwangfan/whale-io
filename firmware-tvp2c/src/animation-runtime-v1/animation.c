#include "animation.h"
#include "../display-runtime-v1/display_memory.h"
/* 2026-11-17 ANIM_LOCAL: the 12-pose sprite-loop player is restored to the pre-clamp
 * design (animation_test.c is the oracle: 1800 submissions/30s, fps==60, max_gap==20
 * at 5 ms ticks, skipped==89 busy case, stall-stop, FINISHED). Same guards/discipline
 * as the shipped still-clamped version: dual buffers, never allocates, skip-not-queue,
 * 2 s busy stall-stop. Only invoked on an explicit ANIM_PLAY (the page stays still by
 * default — the 9th-flash behavior is bit-identical unless点播). */
bool ta_start(TAPlayer *p,const uint8_t *asset,size_t n,uint8_t *a,uint8_t *b,size_t capacity,uint32_t now,unsigned active){
 if(!p||p->running||!asset||n!=(size_t)TA_BYTES*TA_FRAMES||!a||!b||capacity<TA_BYTES||active>1)return false;
 uintptr_t ap=(uintptr_t)a,bp=(uintptr_t)b;
 if((ap<bp?bp-ap:ap-bp)<TA_BYTES)return false;
 memset(p,0,sizeof *p);p->asset=asset;p->buffers[0]=a;p->buffers[1]=b;
 p->start=p->last_success=now;p->active=active;p->fps=TA_TARGET_FPS;p->running=true;return true;
}
void ta_stop(TAPlayer *p){if(p)p->running=false;}
TAResult ta_step(TAPlayer *p,const TAUI *ui,uint32_t now){
 if(!p||!ui||!ui->idle||!ui->submit)return TA_ERROR;
 if(!p->running)return TA_NOOP;
 uint32_t elapsed=now-p->start;
 /* Unsigned subtraction handles one tick wrap; a backwards/huge jump stops. */
 if(elapsed>=TA_DURATION){ta_stop(p);return TA_FINISHED;}
 uint32_t due=(uint32_t)((uint64_t)elapsed*TA_TARGET_FPS/1000);
 if(p->has_frame&&due==p->last_due)return TA_NOOP;
 if(!ui->idle(ui->ctx)){
  p->busy_polls++;
  if((uint32_t)(now-p->last_success)>=2000){p->stalled=true;ta_stop(p);return TA_ERROR;}
  return TA_BUSY;
 }
 /* due frame = due % TA_FRAMES (pose loop); submit the back buffer. */
 unsigned back=p->active^1u,frame=due%TA_FRAMES;
 memcpy(p->buffers[back],p->asset+(size_t)frame*TA_BYTES,TA_BYTES);
 if(!ui->submit(ui->ctx,p->buffers[back],TA_WIDTH,TA_HEIGHT)){
  p->failed++;ta_stop(p);return TA_ERROR;
 }
 p->skipped+=p->has_frame?due-p->last_due-1:due;
 p->has_frame=true;p->last_due=due;p->frame=frame;p->active=back;
 uint32_t gap=now-p->last_success;if(p->has_frame&&gap>p->max_gap)p->max_gap=gap;
 p->submitted++;p->last_success=now;return TA_SUBMITTED;
}
