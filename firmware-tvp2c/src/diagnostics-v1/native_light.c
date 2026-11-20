/* TDG1 offline candidate provider; requires verified UI-owner binding.
 * This first provider never calls mallinfo/lv_mem_monitor/logger_log, walks
 * blocks, takes locks, allocates, reads arbitrary memory, or writes NAND. */
#include "native_light.h"
#include "stock_abi.h"
#if !defined(TD_STOCK_1055) || !TD_STOCK_1055
#error Native provider requires the hash-pinned Strix 1.0.5.5 profile
#endif
extern int td_gettid(void); /* Native gettid, hash-pinned by candidate builder. */
void td_stock_light(void *context,TDDiagnostics *d){
 TDLightOwner *owner=context;
 td_metric(d,TD_LV_TOTAL,0,false);td_metric(d,TD_LV_USED,0,false);td_metric(d,TD_LV_PEAK,0,false);
 if(!owner||owner->owner_tid<0||td_gettid()!=owner->owner_tid)return;
 /* 2026-11-12 重导：6.11 LVGL 堆统计 = render-root(0x1850ce1c)+0x1bc/+0x1c0
  * （写入者 lv_mem_alloc/free 双版对照；旧值 0x1856ccf0/ccfc 为 5.12 残留，
  * 且 ccfc=+0x1c8 自 5.12 起就偏了 8 字节——真实 peak 在 +0x1c0）。fail-closed 守卫不变。 */
 const volatile uint32_t *current=(const volatile uint32_t *)(uintptr_t)0x1850cfd8;
 const volatile uint32_t *peak=(const volatile uint32_t *)(uintptr_t)0x1850cfdc;
 uint32_t a=*current,b=*peak;
 if(a>b||b>0x1000000u)return; /* fail closed; no capacity guesses */
 td_metric(d,TD_LV_TOTAL,0x1000000u,true); /* configured capacity, not free */
 td_metric(d,TD_LV_USED,a,true);td_metric(d,TD_LV_PEAK,b,true);
 /* System heap/free/largest block/CPU/boot count intentionally unavailable. */
}
