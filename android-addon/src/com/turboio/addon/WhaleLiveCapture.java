package com.turboio.addon;

import java.util.LinkedHashMap;
import java.util.Map;

/** Turns official always-on callbacks into outbox items. Pure: the Android hook (WhaleCaptureHook) feeds it plain values.
 * Session: a new one after nativeOnEndOfConversation, or after 10 minutes without any callback. Item identity is
 * (session, roundId, role); streaming partials (finished=false) are held back and only the latest text is offered once the
 * round finishes, the session ends, a newer round starts, or the partial has been quiet for 5 s (tick). Text is stripped of
 * control characters before hashing so the server's SHA-256 over the UTF-8 text always matches. */
final class WhaleLiveCapture {
    static final long SESSION_IDLE_MS = 10L * 60_000L, PARTIAL_QUIET_MS = 5_000L;
    static final int MAX_TEXT = 8000, MAX_PARTIALS = 16;
    private final WhaleOutbox outbox;
    private final String instance, tz;
    private String session = "";
    private long lastCallbackMs, sessionCount;
    private int syntheticRound;
    private final LinkedHashMap<String, Partial> partials = new LinkedHashMap<String, Partial>();
    private long accepted, dropped;

    private static final class Partial { String role, roundId, text; long lastMs; }

    WhaleLiveCapture(WhaleOutbox outbox, String sourceInstance, String timeZoneId) { this.outbox = outbox; this.instance = sourceInstance; this.tz = timeZoneId; }

    synchronized String session() { return session; }
    synchronized long accepted() { return accepted; }

    synchronized void onAudioStart(long now) { touch(now); }

    synchronized void onEnd(long now) {
        flushAll(now);
        session = "";
    }

    synchronized void onResponse(String role, String roundId, String text, boolean finished, long now) {
        touch(now);
        String clean = clean(text);
        if (clean.isEmpty()) return;
        String r = token(role, 16, "unknown"), round = token(roundId, 64, "");
        if (round.isEmpty()) round = "n" + (finished ? ++syntheticRound : syntheticRound + 1);
        String key = session + "|" + round + "|" + r;
        // A new round for the same role means the previous partial of that role will not grow any more.
        if (!finished) {
            for (Map.Entry<String, Partial> e : new java.util.ArrayList<Map.Entry<String, Partial>>(partials.entrySet())) {
                Partial p = e.getValue();
                if (p.role.equals(r) && !(session + "|" + p.roundId + "|" + p.role).equals(key)) { offer(p.role, p.roundId, p.text, p.lastMs); partials.remove(e.getKey()); }
            }
            Partial p = partials.get(key);
            if (p == null) { if (partials.size() >= MAX_PARTIALS) flushAll(now); p = new Partial(); p.role = r; p.roundId = round; partials.put(key, p); }
            p.text = clean; p.lastMs = now;
            return;
        }
        partials.remove(key);
        offer(r, round, clean, now);
    }

    /** Called periodically: offers partials that have been quiet for 5 s. */
    synchronized void tick(long now) {
        for (Map.Entry<String, Partial> e : new java.util.ArrayList<Map.Entry<String, Partial>>(partials.entrySet())) {
            if (now - e.getValue().lastMs >= PARTIAL_QUIET_MS) { Partial p = e.getValue(); partials.remove(e.getKey()); offer(p.role, p.roundId, p.text, p.lastMs); }
        }
    }
    synchronized boolean hasPartials() { return !partials.isEmpty(); }

    private void flushAll(long now) {
        for (Partial p : new java.util.ArrayList<Partial>(partials.values())) offer(p.role, p.roundId, p.text, p.lastMs);
        partials.clear();
    }

    private void touch(long now) {
        if (session.isEmpty() || now - lastCallbackMs > SESSION_IDLE_MS) {
            if (!session.isEmpty()) flushAll(now);
            session = "s-" + Long.toString(now / 1000, 36) + (++sessionCount);
        }
        lastCallbackMs = now;
    }

    private void offer(String role, String round, String text, long receivedMs) {
        String segment = session + ":" + round + ":" + role;
        String key = "rayneo:" + instance + ":" + session + ":" + round + ":" + role;
        if (key.length() > 256) { dropped++; return; }
        String hash = "sha256:" + RayneoContextProtocol.sha256(text);
        LinkedHashMap<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("segment_id", segment); body.put("round_id", round); body.put("role", role); body.put("session", session);
        body.put("text", text); body.put("content_hash", hash); body.put("source_time", null); body.put("source_time_unknown", Boolean.TRUE);
        body.put("received_at", RayneoContextProtocol.isoUtc(receivedMs)); body.put("tz", tz);
        String json = RayneoContextProtocol.json(body);
        outbox.offer(key, "transcript", "live", segment, hash, json.substring(1, json.length() - 1), receivedMs);
        accepted++;
    }

    static String clean(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_TEXT + 4));
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i); i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (cp == '\n' || cp == '\t') { out.append((char) cp); continue; }
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE || type == Character.UNASSIGNED) continue;
            if (out.length() + Character.charCount(cp) > MAX_TEXT) break;
            out.appendCodePoint(cp);
        }
        return out.toString().trim();
    }

    static String token(String value, int max, String fallback) {
        if (value == null) return fallback;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length() && out.length() < max; i++) { char c = value.charAt(i); if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') out.append(c); }
        return out.length() == 0 ? fallback : out.toString();
    }
}
