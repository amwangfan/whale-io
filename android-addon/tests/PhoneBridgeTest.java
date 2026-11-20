package com.turboio.addon;

import java.util.Map;

/** Offline PhoneBridge policy checks — no Android / Bluetooth / HTTP. */
public final class PhoneBridgeTest {
    private static int n;
    private static void check(boolean c,String m){if(!c)throw new AssertionError(m);n++;}
    public static void main(String[] a){
        check(PhoneBridge.validate(null,"x")!=null,"null title");
        check(PhoneBridge.validate("","x")!=null,"empty title");
        check(PhoneBridge.validate("ok",null)==null,"null body ok");
        String longTitle=new String(new char[PhoneBridge.TITLE_MAX+1]).replace('\0','a');
        check(PhoneBridge.validate(longTitle,"b")!=null,"title max");
        String longBody=new String(new char[PhoneBridge.BODY_MAX+1]).replace('\0','b');
        check(PhoneBridge.validate("t",longBody)!=null,"body max");
        Map<String,Object> d=PhoneBridge.draftPayload("提醒","会议 10 分钟后");
        check(PhoneBridge.CARD_ID_ASSISTANT.equals(d.get("id")),"assistant id");
        check("提醒".equals(d.get("title")),"title");
        check("assistant".equals(d.get("channel")),"channel");
        Map<String,Object> local=PhoneBridge.draftLocalPayload("本地","旁路");
        check(PhoneBridge.CARD_ID_LOCAL.equals(local.get("id")),"local id");
        check(PhoneBridge.JOB_NOTIFY.equals("主动通知"),"job notify");
        check(PhoneBridge.JOB_AUDIO.equals("音频会话"),"job audio");
        check(PhoneBridge.clip("  hi  ",10).equals("hi"),"clip trim");
        try{PhoneBridge.draftPayload("","");check(false,"should throw");}catch(IllegalArgumentException e){check(true,"reject empty");}
        System.out.println("PhoneBridgeTest: "+n+" checks; phone-bridge skeleton offline OK");
    }
}
