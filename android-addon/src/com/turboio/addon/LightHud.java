package com.turboio.addon;

/**
 * Lightweight non-navigation HUD text helpers for {@link NavGlasses} (business-19 text channel).
 * The navigation arrow / TNV1 scene / L8 preview layers were removed in the 1.0.20 dedup branch:
 * the official 1.0.20 host ships its own AMap NAVI (libAMapSDK_NAVI_v11_2_000.so, features/navigation).
 * Pure formatting only — no Bluetooth, no OTA, no HostAbi.
 */
public final class LightHud {
    private LightHud(){}

    /**
     * One-line greeting for NavGlasses text formatting (≤384 UTF-8). Does not send Bluetooth.
     * Example: "你好 · 凡望" or title alone when subtitle empty.
     */
    public static String greetingLine(String title, String subtitle){
        String t=title==null?"":title.trim();
        String s=subtitle==null?"":subtitle.trim();
        if(t.isEmpty()&&s.isEmpty())return NavCore.clip("你好",384);
        if(s.isEmpty())return NavCore.clip(t,384);
        if(t.isEmpty())return NavCore.clip(s,384);
        return NavCore.clip(t+" · "+s,384);
    }
}
