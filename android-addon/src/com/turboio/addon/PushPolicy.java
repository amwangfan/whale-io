package com.turboio.addon;
import java.util.*;

/** Push strategy v2 (2026-11-18, user ruling): content pushes pick a form — page (take the display page) or a
 *  firmware-native notification banner (never takes the page). The decision keys off the glasses' current
 *  foreground app class; the carrier is the BEGIN form byte, negotiation-gated (FORM_FIELD capability bit):
 *  the extended BEGIN is never sent before negotiation, so old firmware sees byte-identical packets.
 *  Background-resident activities (全天智记 / whale sync / background work) never count as blocking here. */
final class PushPolicy {
    enum Occupied{
        CLOSE_REOPEN,   /* legacy: close the occupier and reopen (kept for reference; assigned to nothing) */
        NOTIFY_DEFER,   /* tell the user once (notification), keep the frame queued, retry with spacing */
        DEFER_QUIET     /* same defer, no notification */
    }
    static final Occupied DEFAULT=Occupied.DEFER_QUIET;
    private static final Map<String,Occupied> TABLE;
    static{Map<String,Occupied> m=new HashMap<>();m.put("greeting",Occupied.NOTIFY_DEFER);m.put("sticker",Occupied.NOTIFY_DEFER);TABLE=Collections.unmodifiableMap(m);}
    static Occupied occupied(String contentKind){Occupied o=TABLE.get(contentKind);return o==null?DEFAULT:o;}

    /* ---- v2: foreground classification → push form ---- */
    /** The glasses' current foreground app class, as reported by the firmware (TDP_STATUS op=10; enum per
     *  ANIM-LOCAL-611 §8.7: 0 idle / 1 navigation / 2 teleprompter / 3 call / 4 voice session / 5 other / 6 folded). */
    enum Foreground{IDLE,NAVIGATION,TELEPROMPTER,CALL,VOICE_SESSION,OTHER,FOLDED}
    /** The push form on the wire: page (take the display page) or a notification banner (never takes it). */
    enum Form{PAGE,NOTIFICATION_BANNER}
    private static volatile Foreground foreground=Foreground.IDLE;
    /** Map the wire category (TDP_STATUS byte33) to the enum. */
    static Foreground foregroundOf(long cat){Foreground[] v=Foreground.values();return cat>=0&&cat<v.length?v[(int)cat]:Foreground.OTHER;}
    static void foreground(Foreground f){foreground=f==null?Foreground.IDLE:f;}
    static Foreground foreground(){return foreground;}
    /** The form for the next push, from the current glasses foreground class. (Test-entry determinism rides the
     *  per-push pin in GreetingSession — 2026-11-19 ruling; no shared override lives here.) */
    static Form decide(){return decide(foreground);}
    static Form decide(Foreground fg){
        switch(fg==null?Foreground.IDLE:fg){
            case NAVIGATION: case TELEPROMPTER: case CALL: case VOICE_SESSION: return Form.NOTIFICATION_BANNER;
            default: return Form.PAGE;
        }
    }
    static int formWire(Form f){return f==Form.NOTIFICATION_BANNER?Tdp.FORM_BANNER:Tdp.FORM_PAGE;}
}
