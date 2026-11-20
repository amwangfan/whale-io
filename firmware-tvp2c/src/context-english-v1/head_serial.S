.syntax unified
.thumb
.fpu fpv5-sp-d16
.macro command name,op
.section .text.\name,"ax",%progbits
.global \name
.type \name,%function
.thumb_func
\name:
    push {r4,lr}
    mov r1,lr
    movs r0,#\op
    bl ce_serial_foreign
    pop {r4,pc}
.size \name,.-\name
.endm
command ce_serial_start_entry,1
command ce_serial_stop_entry,2
.macro body name,literal,resume
.section .text.\name,"ax",%progbits
.global \name
.type \name,%function
.thumb_func
\name:
    push {r4,r5,r7,lr}
    ldr r2,=\literal
    b.w \resume
.size \name,.-\name
.endm
/* 2026-11-12 重导：literal=6.11 站点被位移 ldr r2 的池字（REVIEW-head-serial 证据链） */
body ce_stock_head_start_body,0x1820fee3,ce_stock_head_start_resume
body ce_stock_head_stop_body,0x1820fd3c,ce_stock_head_stop_resume
.macro save
    push.w {r0-r3,r12,lr}
    mrs r3,apsr
    vmrs r12,fpscr
    push.w {r3,r12}
    vpush {s0-s15}
.endm
.macro restore
    vpop {s0-s15}
    pop.w {r3,r12}
    vmsr fpscr,r12
    msr APSR_nzcvqg,r3
    pop.w {r0-r3,r12,lr}
.endm
.section .text.ce_serial_publish_entry,"ax",%progbits
.global ce_serial_publish_entry
.type ce_serial_publish_entry,%function
.thumb_func
ce_serial_publish_entry:
    save
    mov r0,r4
    /* 6.11 stock keeps the new monitor in r8 at this site (str.w r8,[r4,#0x18]);
     * 5.12 used r6 (str r6,[r4,#0x18]). Passing r6 on 6.11 published NULL. */
    mov r1,r8
    bl ce_serial_publish
    str r0,[sp,#72]
    restore
    bx lr
.size ce_serial_publish_entry,.-ce_serial_publish_entry
.section .text.ce_serial_retire_entry,"ax",%progbits
.global ce_serial_retire_entry
.type ce_serial_retire_entry,%function
.thumb_func
ce_serial_retire_entry:
    save
    mov r0,r4
    bl ce_serial_retire
    str r0,[sp,#72]
    restore
    bx lr
.size ce_serial_retire_entry,.-ce_serial_retire_entry
.section .text.ce_serial_destroy_entry,"ax",%progbits
.global ce_serial_destroy_entry
.type ce_serial_destroy_entry,%function
.thumb_func
ce_serial_destroy_entry:
    save
    bl ce_serial_before_destroy
    restore
    push {r3,r4,r7,lr}
    /* 2026-11-12 重导：0x18200f48 = 6.11 head 族 vtable（与 ctor 0x105ab460 写入值配对；
       旧值 0x181e757c 为 5.12 vtable）。钩点已迁 0x41a84c（真 dtor 入口，复刻字节逐字匹配）。 */
    ldr r3,=0x18200f48
    b.w ce_stock_sys_destroy_resume
.size ce_serial_destroy_entry,.-ce_serial_destroy_entry
