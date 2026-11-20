package com.turboio.addon;
import java.util.*;

/** PushPolicy v2: foreground classification → push form, and the BEGIN form byte is negotiation-gated. */
public final class PushPolicyTest {
    static int n; static void ok(boolean b, String why){ n++; if(!b) throw new AssertionError("pushpolicy "+n+" "+why); }
    public static void main(String[] a){
        // classification → form
        ok(PushPolicy.decide(PushPolicy.Foreground.NAVIGATION)==PushPolicy.Form.NOTIFICATION_BANNER,"nav → banner");
        ok(PushPolicy.decide(PushPolicy.Foreground.TELEPROMPTER)==PushPolicy.Form.NOTIFICATION_BANNER,"teleprompter → banner");
        ok(PushPolicy.decide(PushPolicy.Foreground.CALL)==PushPolicy.Form.NOTIFICATION_BANNER,"call → banner");
        ok(PushPolicy.decide(PushPolicy.Foreground.IDLE)==PushPolicy.Form.PAGE,"idle → page");
        ok(PushPolicy.decide(PushPolicy.Foreground.OTHER)==PushPolicy.Form.PAGE,"other → page");
        ok(PushPolicy.formWire(PushPolicy.Form.NOTIFICATION_BANNER)==Tdp.FORM_BANNER&&PushPolicy.formWire(PushPolicy.Form.PAGE)==Tdp.FORM_PAGE,"wire values");
        // foreground setter guards null
        PushPolicy.foreground(null);ok(PushPolicy.foreground()==PushPolicy.Foreground.IDLE,"null → idle");
        PushPolicy.foreground(PushPolicy.Foreground.NAVIGATION);ok(PushPolicy.decide()==PushPolicy.Form.NOTIFICATION_BANNER,"live foreground drives the decision");
        PushPolicy.foreground(PushPolicy.Foreground.IDLE);
        // the form-byte BEGIN: 13-byte body, form at byte 44, CRC over the full body
        byte[] plain=Tdp.begin(7392,7,0,9,0);
        byte[] formed=Tdp.beginForm(7392,7,0,9,0,Tdp.FORM_BANNER);
        ok(plain!=null&&formed!=null&&plain.length==44&&formed.length==45&&formed[44]==1&&Tdp.u32(formed,24)==13,"13-byte BEGIN with form byte");
        ok(Tdp.beginForm(7392,7,0,9,0,9)==null,"unknown form refused");
        // ANIM_PLAY (op=9, negotiation-gated on CAPS bit2): wire fixed 2026-11-19 per ANIM-PLAY-611 — the body
        // is EXACTLY 4 bytes {asset_id u16, loop u8, fps_cap u8}; the firmware length gate rejects length!=4.
        byte[] anim=Tdp.animPlay(7392,7,0,42,true,30);
        ok(anim!=null&&anim.length==36&&anim[5]==9&&Tdp.u32(anim,24)==4&&Tdp.u16(anim,32)==42&&anim[34]==1&&anim[35]==30,"ANIM_PLAY wire: 4-byte body {u16,u8,u8}");
        ok(Tdp.animPlay(7392,7,0,42,true,0)==null,"fps 0 refused");
        ok(Tdp.animPlay(7392,7,0,65536,true,30)==null,"asset beyond u16 refused");
        ok(Tdp.valid(anim),"ANIM_PLAY passes canonical validation");
        ok(!Tdp.valid(Arrays.copyOf(anim,40)),"the legacy 8-byte body shape refused by canonical validation");
        System.out.println("PushPolicyTest: "+n+" checks; classification, wire values, negotiation-gated form byte, ANIM_PLAY (4B body)");
    }
}
