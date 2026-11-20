package com.turboio.addon;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;

/** Settings of the whale-core context integration. Everything is off until the user flips the master switch and
 * confirms the privacy notice. The bearer token lives in SecretStore ("whale_token", an independent field labeled
 * "whale 眼镜 token"); it is never stored in preferences. */
final class WhaleConfig {
    static final String PRIVACY_VERSION = "v1", TOKEN = "whale_token";
    private WhaleConfig() {}
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("turboio_whale", 0); }

    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
    static boolean acknowledged(Context c) { return PRIVACY_VERSION.equals(prefs(c).getString("privacy_ack", "")); }
    static boolean queryOn(Context c) { return prefs(c).getBoolean("query", false); }
    static boolean wifiOnly(Context c) { return prefs(c).getBoolean("wifi_only", true); }
    /** Placeholder only: official-summary upload is not implemented; the switch is stored but nothing reads it yet. */
    static boolean summaryOn(Context c) { return prefs(c).getBoolean("summary", false); }
    static String base(Context c) { String v = prefs(c).getString("base", ""); return v.isEmpty() ? WhaleEndpoint.DEFAULT_BASE : v; }
    static long pausedUntil(Context c) { return prefs(c).getLong("paused_until", 0); }
    static boolean paused(Context c, long now) { return pausedUntil(c) > now; }
    static String token(Context c) { try { return SecretStore.get(c, TOKEN); } catch (Exception e) { return ""; } }
    static boolean hasToken(Context c) { return !token(c).isEmpty(); }

    /** Stable random id of this install: rayneo-android-<16 hex>. */
    static synchronized String instance(Context c) {
        String v = prefs(c).getString("instance", "");
        if (v.isEmpty()) {
            byte[] b = new byte[8]; new SecureRandom().nextBytes(b);
            StringBuilder s = new StringBuilder("rayneo-android-");
            for (byte x : b) s.append(String.format("%02x", x & 255));
            v = s.toString(); prefs(c).edit().putString("instance", v).commit();
        }
        return v;
    }

    static boolean validBase(String v) { return WhaleEndpoint.validBase(v, false); }
    static boolean captureAllowed(Context c, long now) { return enabled(c) && acknowledged(c) && hasToken(c) && !paused(c, now) && validBase(base(c)); }
}
