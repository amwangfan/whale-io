package com.turboio.addon;

import android.content.Context;
import android.database.Cursor;
import android.provider.CalendarContract;
import java.text.SimpleDateFormat;
import java.util.*;

/** Real-time facts for the Enxi (whale) glasses chat: current time + today/tomorrow schedule (Android
 *  calendar) + weather (cached glasses-dashboard read). Injected as a context prefix on the chat text
 *  so the persona always "knows" the近况 without per-intent detection. All reads are local, cheap, and
 *  fail-soft (a missing piece is simply omitted from the context). */
final class ChatContext {
    private ChatContext() {}

    private static volatile String weatherCache = "";        // last-read glasses weather, "" = unknown
    private static volatile long weatherAt = 0;              // when weatherCache was last refreshed

    /** Build the context block to prepend to the user's glasses-chat text. */
    static String build(Context c) {
        StringBuilder s = new StringBuilder();
        s.append("[现在] ").append(nowText()).append('\n');
        String today = schedule(c, 0), tomorrow = schedule(c, 1);
        s.append("[今日日程] ").append(today.isEmpty() ? "（无）" : today).append('\n');
        s.append("[明日日程] ").append(tomorrow.isEmpty() ? "（无）" : tomorrow).append('\n');
        String w = weatherCache;
        if (!w.isEmpty()) s.append("[天气] ").append(w).append('\n');
        return s.toString();
    }

    /** The full text to send to whale-core: context + the user's utterance. */
    static String wrap(Context c, String userText) {
        return build(c) + "[用户] " + (userText == null ? "" : userText);
    }

    private static String nowText() {
        Date d = new Date();
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(d);
        String hm = new SimpleDateFormat("HH:mm", Locale.ROOT).format(d);
        String[] wd = {"日","一","二","三","四","五","六"};
        Calendar cal = Calendar.getInstance(); cal.setTime(d);
        return day + " 周" + wd[cal.get(Calendar.DAY_OF_WEEK)-1] + " " + hm;
    }

    /** Schedule for a day offset (0=today, 1=tomorrow): "HH:mm 标题" joined by "、". Local calendar read. */
    static String schedule(Context c, int dayOffset) {
        List<String[]> evs = events(c, dayOffset);
        if (evs == null || evs.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        for (String[] e : evs) out.add(e[2].equals("1") ? "全天 " + e[1] : e[0] + " " + e[1]);
        return String.join("、", out);
    }

    /** Structured events for a day offset: each row [HH:mm|"", title, allDay? "1":"0"]. null = unreadable
     *  (no permission / provider absent); empty list = genuinely no events. */
    static List<String[]> events(Context c, int dayOffset) {
        if (c == null) return null;
        List<String[]> evs = new ArrayList<>();
        try {
            Calendar cal = Calendar.getInstance(); cal.set(Calendar.HOUR_OF_DAY,0); cal.set(Calendar.MINUTE,0); cal.set(Calendar.SECOND,0); cal.set(Calendar.MILLISECOND,0); cal.add(Calendar.DAY_OF_YEAR, dayOffset);
            long begin = cal.getTimeInMillis(); cal.add(Calendar.DAY_OF_YEAR, 1); long end = cal.getTimeInMillis();
            android.net.Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            android.content.ContentUris.appendId(b, begin); android.content.ContentUris.appendId(b, end);
            String[] proj = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY};
            try (Cursor cur = c.getContentResolver().query(b.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")) {
                if (cur == null) return null;
                SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT);
                while (cur.moveToNext() && evs.size() < 12) {
                    String title = cur.getString(0); long start = cur.getLong(1); boolean allday = cur.getInt(2) != 0;
                    if (title == null || title.trim().isEmpty()) continue;
                    String t = title.trim(); if (t.length() > 120) t = t.substring(0, 120);
                    evs.add(new String[]{allday ? "" : hm.format(new Date(start)), t, allday ? "1" : "0"});
                }
            }
            return evs;
        } catch (Throwable t) { return null; }
    }

    /** Inject the last-known glasses weather (refreshed in the background). */
    static void setWeather(String w) { weatherCache = w == null ? "" : w; weatherAt = System.currentTimeMillis(); }
    static long weatherAge() { return weatherAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - weatherAt; }
}
