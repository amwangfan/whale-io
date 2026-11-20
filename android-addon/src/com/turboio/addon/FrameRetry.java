package com.turboio.addon;

/** §7 bounded self-recovery policy for low-risk content pushes (greeting/demo/card frames): bounded retry
 *  per frame, failure isolation (a dropped frame never blocks the next push), and a consecutive-drop breaker
 *  that waits for a real reconnect instead of hammering a dead link. Pure state machine, no I/O.
 *  High-risk paths (OTA / gates / result guard) never use this. */
final class FrameRetry {
    static final int MAX_ATTEMPTS=3;      // attempts per frame, first try included
    static final int BREAKER_DROPS=3;     // consecutive dropped frames that open the breaker
    static final double BREAKER_COOLDOWN_S=60;
    private int attempt,drops,totalDropped,totalRetried;
    private double openSince=Double.NaN;  // clock time when the breaker opened
    /** Start a new frame. false = breaker open, caller must refuse this push. */
    boolean begin(double now){
        if(open()){
            if(now-openSince<BREAKER_COOLDOWN_S)return false;
            close();  // cooled down: one probe frame may try
        }
        attempt=1;return true;
    }
    /** The in-flight frame failed transiently; true = one more autonomous attempt remains. */
    boolean retry(){if(attempt>=MAX_ATTEMPTS)return false;attempt++;totalRetried++;return true;}
    int attempt(){return attempt;}
    /** Frame delivered. Clears attempts and the consecutive-drop count. */
    void success(){attempt=0;drops=0;openSince=Double.NaN;}
    /** Frame exhausted its attempts: record and drop it. The next push is unaffected (isolation). */
    void drop(double now){attempt=0;totalDropped++;if(++drops>=BREAKER_DROPS)openSince=now;}
    boolean open(){return drops>=BREAKER_DROPS;}
    private void close(){drops=0;openSince=Double.NaN;}
    /** Reconnect evidence: a different/fresh peer lets a new push through immediately. */
    void reconnect(){close();}
    int consecutiveDrops(){return drops;}
    int totalDropped(){return totalDropped;}
    int totalRetried(){return totalRetried;}
}
