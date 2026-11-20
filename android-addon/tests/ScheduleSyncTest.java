package com.turboio.addon;
import java.util.*;

/** ScheduleSync pure parts: snapshot shape, JSON escaping, the push gate. */
public final class ScheduleSyncTest {
    static int n; static void ok(boolean b, String why){ n++; if(!b) throw new AssertionError("schedulesync "+n+" "+why); }
    public static void main(String[] a) {
        // push gate: same hash+day -> skip; changed hash or new day -> push
        ok(!ScheduleSync.due("h1","2026-11-18","h1","2026-11-18"),"same snapshot+day skipped");
        ok(ScheduleSync.due("h2","2026-11-18","h1","2026-11-18"),"changed snapshot pushes");
        ok(ScheduleSync.due("h1","2026-11-19","h1","2026-11-18"),"date rollover pushes");
        ok(ScheduleSync.due("h1","2026-11-18","",""),"first ever pushes");

        // snapshot shape (null context -> unreadable days as null per the contract)
        String snap = ScheduleSync.snapshot(null);
        ok(snap.startsWith("{\"calendar\":{\"") && snap.contains("\":null,\"") && snap.endsWith(":null}}"), "calendar-map skeleton with null days: "+snap);

        System.out.println("ScheduleSyncTest: "+n+" checks; push gate, snapshot skeleton");
    }
}
