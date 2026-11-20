/* DORMANT / NOT in the 6.11 build (build-inputs.json sources). 2026-11-14:
 * the vtable constant below (0x181e757c) is the 5.12 value; 6.11 needs 0x18200f48
 * (see head_serial.S:85 which has it right). Do NOT re-add this file to a 6.11
 * build without fixing that constant first — it would crash the destroy path. */
/* Non-deleting SysStateMonitor destructor entry. Requires the expanded
 * allocation AND constructor-call patch. Not installed by the test runner. */
.syntax unified
.thumb
.fpu fpv5-sp-d16
.section .text.ce_sys_destroy_entry,"ax",%progbits
.global ce_sys_destroy_entry
.type ce_sys_destroy_entry,%function
.thumb_func
ce_sys_destroy_entry:
    push.w {r0-r3,r12,lr}
    mrs r3,apsr
    vmrs r12,fpscr
    push.w {r3,r12}
    vpush {s0-s15}
    bl ce_native_sys_before_destroy
    vpop {s0-s15}
    pop.w {r3,r12}
    vmsr fpscr,r12
    msr APSR_nzcvqg,r3
    pop.w {r0-r3,r12,lr}
    /* Exact displaced bytes: 98 b5 08 4b. Replay push + relocated literal. */
    push {r3,r4,r7,lr}
    ldr r3,=0x181e757c
    b.w ce_stock_sys_destroy_resume
.size ce_sys_destroy_entry,.-ce_sys_destroy_entry
