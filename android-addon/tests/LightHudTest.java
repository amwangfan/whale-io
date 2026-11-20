package com.turboio.addon;

/** Offline checks for LightHud (non-navigation greeting text only) — no device, no HostAbi. */
public final class LightHudTest {
    static int n;
    static void ok(boolean b,String why){n++;if(!b)throw new AssertionError("LightHud "+n+" "+why);}

    public static void main(String[] a) throws Exception{
        String greet=LightHud.greetingLine("你好","凡望");
        ok(greet.contains("你好")&&greet.contains("凡望")&&greet.getBytes("UTF-8").length<=384,"greetingLine");
        ok(LightHud.greetingLine(""," ").equals("你好")&&LightHud.greetingLine(null,null).equals("你好"),"greeting fallback");
        ok(LightHud.greetingLine("仅标题",null).equals("仅标题"),"greeting title only");
        ok(LightHud.greetingLine(null,"仅副标题").equals("仅副标题"),"greeting subtitle only");
        StringBuilder big=new StringBuilder();for(int i=0;i<400;i++)big.append("字");
        ok(LightHud.greetingLine(big.toString(),"x").getBytes("UTF-8").length<=384,"greeting clipped");
        System.out.println("LightHudTest: "+n+" checks; greeting line OK");
    }
}
