package com.turboio.addon;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Unit tests for the whale ingest/query pipeline. The server side is tests/whale/whale_mock_server.py: a MOCK written
 * from the handoff contract, not whale-core. Run: java -Dwhale.mock=<path to mock> WhaleSyncTest */
public final class WhaleSyncTest {
    static int checks;
    static final String TOKEN = "mock-token-not-real";
    static final long T0 = 1_800_000_000_000L;
    static Process proc;
    static String root;   // http://127.0.0.1:port

    static void ok(boolean c, String m) { checks++; if (!c) throw new AssertionError(m); }

    // ---------- mock plumbing ----------
    static void startMock() throws Exception {
        proc = new ProcessBuilder("python3", System.getProperty("whale.mock")).redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        String line = r.readLine();
        ok(line != null && line.startsWith("PORT "), "mock server announces its port");
        root = "http://127.0.0.1:" + line.substring(5).trim();
    }
    static String raw(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(root + path).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(3000); c.setReadTimeout(10000);
        if (body != null) { c.setDoOutput(true); c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8)); }
        try (java.io.InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }
    static void reset() throws Exception { raw("POST", "/__mock/reset", "{}"); }
    static void mode(String json) throws Exception { raw("POST", "/__mock/mode", json); }
    @SuppressWarnings("unchecked") static Map<String, Object> state() throws Exception { return (Map<String, Object>) BoundedJson.readLarge(raw("GET", "/__mock/state", null).getBytes(StandardCharsets.UTF_8), 1 << 20, 100000); }
    static long calls(String name) throws Exception { return ((Number) ((Map<?, ?>) state().get("calls")).get(name)).longValue(); }

    static WhaleSyncCore.Transport transport(final String base, final String token) {
        return (path, json) -> WhaleHttp.post(WhaleEndpoint.join(base, path), token, json, 3000, 8000, true);
    }
    static WhaleOutbox newOutbox() { return new WhaleOutbox(new MemoryStore()); }
    static WhaleSyncCore core(WhaleOutbox ob, String base, String token, long seed) { return new WhaleSyncCore(ob, transport(base, token), "rayneo-android-0123456789abcdef", new Random(seed)); }

    static String text(String prefix, int chars) { StringBuilder s = new StringBuilder(prefix); while (s.length() < chars) s.append('字'); return s.toString(); }

    static void put(WhaleOutbox ob, String key, String text, long now) {
        String hash = "sha256:" + RayneoContextProtocol.sha256(text);
        String body = "\"segment_id\":" + RayneoContextProtocol.quote(key) + ",\"text\":" + RayneoContextProtocol.quote(text) + ",\"content_hash\":" + RayneoContextProtocol.quote(hash) + ",\"received_at\":\"2026-11-10T02:15:00.123Z\"";
        ob.offer(key, "transcript", "live", key, hash, body, now);
    }
    static void putBadHash(WhaleOutbox ob, String key, String text, long now) {
        String body = "\"segment_id\":" + RayneoContextProtocol.quote(key) + ",\"text\":" + RayneoContextProtocol.quote(text) + ",\"content_hash\":\"sha256:" + "0".repeat(64) + "\",\"received_at\":\"2026-11-10T02:15:00.123Z\"";
        ob.offer(key, "transcript", "live", key, "sha256:" + "0".repeat(64), body, now);
    }

    public static void main(String[] args) throws Exception {
        startMock();
        try {
            endpointAndDiagnostics();
            outboxRules();
            liveCapture();
            ingestHappyPathAndRevision();
            batchSplitting();
            terminalVerdicts();
            backoffAndOfflineResume();
            authPause();
            tooLarge();
            rateLimitDisabledProtocol();
            gaps();
            queryPath();
            toolTurnRules();
        } finally { proc.destroy(); }
        System.out.println("Whale sync tests passed (" + checks + " checks).");
    }

    // ---------- tests ----------
    static void endpointAndDiagnostics() {
        ok(WhaleEndpoint.validBase("https://your-core-host.example.com/turbo", false), "default base is valid");
        ok(WhaleEndpoint.validBase("https://host:8443/a/b", false), "path prefix and port allowed");
        ok(!WhaleEndpoint.validBase("http://host/turbo", false), "plain http refused");
        ok(!WhaleEndpoint.validBase("http://127.0.0.1:1/turbo", false), "loopback http refused in production");
        ok(WhaleEndpoint.validBase("http://127.0.0.1:1/turbo", true), "loopback http only with the test flag");
        ok(!WhaleEndpoint.validBase("http://evil.example/x", true), "non-loopback http refused even with the test flag");
        ok(!WhaleEndpoint.validBase("https://u:p@host/", false) && !WhaleEndpoint.validBase("https://host/?q=1", false) && !WhaleEndpoint.validBase("https://host/#f", false) && !WhaleEndpoint.validBase("https://host/a/../b", false) && !WhaleEndpoint.validBase("", false), "userinfo/query/fragment/dotdot/empty refused");
        ok(WhaleEndpoint.join("https://h/turbo/", "/v1/rayneo/ingest").equals("https://h/turbo/v1/rayneo/ingest"), "join strips the trailing slash");
        ok(RayneoAuthDiagnostic.code(401, "unauthorized").equals("HTTP 401 unauthorized") && RayneoAuthDiagnostic.code(401, "x").equals("HTTP 401 permission") && RayneoAuthDiagnostic.code(500, "unauthorized").equals("HTTP 500"), "auth diagnostic allow-list");
        ok(RayneoAuthDiagnostic.isAuth("HTTP 403 permission") && !RayneoAuthDiagnostic.isAuth("HTTP 500") && RayneoAuthDiagnostic.safe("secret text").isEmpty(), "auth diagnostic never echoes free text");
        ok(WhaleBackoff.delayMs(0, new Random(1)) >= 24_000 && WhaleBackoff.delayMs(0, new Random(1)) <= 36_000, "first backoff ~30 s +-20%");
        long prev = 0; for (int a = 0; a <= 5; a++) { long d = WhaleBackoff.delayMs(a, new Random(7)); ok(d >= prev, "backoff grows"); prev = d; }
        ok(WhaleBackoff.delayMs(50, new Random(3)) <= WhaleBackoff.maxPossibleMs() && WhaleBackoff.delayMs(50, new Random(3)) >= (long) (WhaleBackoff.CAP_MS * 0.8), "backoff capped at 15 min with jitter");
        ok(WhaleBackoff.delayMs(3, new Random(1)) != WhaleBackoff.delayMs(3, new Random(2)), "jitter differs by seed");
    }

    static void outboxRules() {
        WhaleOutbox ob = newOutbox();
        put(ob, "k1", "hello", T0);
        ok(ob.pending() == 1, "first offer queued");
        ok(ob.offer("k1", "transcript", "live", "k1", "sha256:" + RayneoContextProtocol.sha256("hello"), "\"x\":1", T0) == WhaleOutbox.Offer.DUPLICATE, "same content is a duplicate");
        ok(ob.pending() == 1 && ob.store().byKey("k1").revision == 1, "duplicate changes nothing");
        put(ob, "k1", "hello world", T0 + 1);
        ok(ob.store().byKey("k1").revision == 2 && ob.pending() == 1, "changed text raises the revision on the same row");
        long seq = ob.store().byKey("k1").seq;
        ok(!ob.ack(seq, 1, T0 + 2) && ob.pending() == 1, "ack of a superseded revision is ignored");
        ok(ob.ack(seq, 2, T0 + 3) && ob.pending() == 0 && ob.acked() == 1, "ack of the current revision completes the row");
        ok(ob.store().byKey("k1").body.isEmpty(), "acked row drops its text");
        ok(ob.offer("k1", "transcript", "live", "k1", "sha256:" + RayneoContextProtocol.sha256("hello world"), "\"x\":1", T0 + 4) == WhaleOutbox.Offer.DUPLICATE, "replay after ack stays deduplicated");
        put(ob, "k1", "hello again", T0 + 5);
        ok(ob.store().byKey("k1").revision == 3 && ob.pending() == 1, "a later edit after ack re-queues as revision 3");
        ob.dead(ob.store().byKey("k1").seq, 3, "stale_ignored", T0 + 6);
        ok(ob.dead() == 1 && ob.store().byKey("k1").reason.equals("stale_ignored"), "dead verdict recorded");
        ob.prune(T0 + WhaleOutbox.RETAIN_MS + 100);
        ok(ob.dead() == 0 && ob.acked() == 0, "finished rows are pruned after 24 h");
        WhaleOutbox big = newOutbox();
        for (int i = 0; i < WhaleOutbox.MAX_ROWS + 25; i++) put(big, "r" + i, "t" + i, T0);
        ok(big.pending() == WhaleOutbox.MAX_ROWS && big.droppedUnreported() == 25, "row cap drops the oldest 25 and counts them");
        ok(big.store().byKey("r0") == null && big.store().byKey("r26") != null, "the oldest rows are the ones dropped");
    }

    static void liveCapture() {
        WhaleOutbox ob = newOutbox();
        WhaleLiveCapture cap = new WhaleLiveCapture(ob, "rayneo-android-0123456789abcdef", "Asia/Shanghai");
        cap.onAudioStart(T0);
        String session = cap.session();
        ok(session.startsWith("s-"), "session opened on audio start");
        cap.onResponse("user", "r-7", "今天下午", false, T0 + 100);
        cap.onResponse("user", "r-7", "今天下午和同事讨论接口", false, T0 + 400);
        ok(ob.pending() == 0 && cap.hasPartials(), "streaming partials are held back");
        cap.onResponse("user", "r-7", "今天下午和同事讨论接口方案。", true, T0 + 900);
        ok(ob.pending() == 1 && !cap.hasPartials(), "finished round is offered once");
        WhaleOutbox.Row row = ob.store().byKey("rayneo:rayneo-android-0123456789abcdef:" + session + ":r-7:user");
        ok(row != null && row.revision == 1 && row.body.contains("今天下午和同事讨论接口方案。") && row.body.contains("\"role\":\"user\"") && row.body.contains("\"tz\":\"Asia/Shanghai\"") && row.body.contains("\"source_time_unknown\":true"), "item carries role/round/session/tz and unknown source time");
        ok(row.body.contains("sha256:" + RayneoContextProtocol.sha256("今天下午和同事讨论接口方案。")), "content_hash is SHA-256 of the UTF-8 text");
        cap.onResponse("user", "r-7", "今天下午和同事讨论接口方案。", true, T0 + 1000);
        ok(ob.pending() == 1 && ob.store().byKey(row.dedupKey).revision == 1, "the same finished text again is a duplicate, not a new revision");
        cap.onResponse("user", "r-7", "今天下午和同事讨论接口方案，改成批量。", true, T0 + 1500);
        ok(ob.store().byKey(row.dedupKey).revision == 2, "a corrected final text becomes revision 2");
        cap.onResponse("assistant", "r-7", "好的\u0007\u200b记下了", true, T0 + 1600);
        ok(ob.pending() == 2 && ob.store().byKey("rayneo:rayneo-android-0123456789abcdef:" + session + ":r-7:assistant").body.contains("好的记下了"), "control/format characters are stripped before hashing");
        cap.onResponse("user", "r-8", "   ", true, T0 + 1700);
        ok(ob.pending() == 2, "blank text is ignored");
        cap.onResponse("user", "r-9", "半句话", false, T0 + 2000);
        cap.tick(T0 + 3000);
        ok(ob.pending() == 2, "a partial is not flushed before 5 s of quiet");
        cap.tick(T0 + 2000 + WhaleLiveCapture.PARTIAL_QUIET_MS);
        ok(ob.pending() == 3, "a quiet partial is flushed by tick");
        cap.onResponse("user", "r-10", "没说完", false, T0 + 9000);
        cap.onEnd(T0 + 9100);
        ok(ob.pending() == 4 && cap.session().isEmpty(), "end of conversation flushes partials and closes the session");
        cap.onAudioStart(T0 + 9200);
        ok(!cap.session().equals(session), "a new conversation gets a new session");
        String s2 = cap.session();
        cap.onResponse("user", "r-1", "第二段", true, T0 + 9300 + WhaleLiveCapture.SESSION_IDLE_MS + 5);
        ok(!cap.session().equals(s2), "10 minutes of silence rotates the session");
        ok(ob.store().byKey("rayneo:rayneo-android-0123456789abcdef:" + s2 + ":r-1:user") == null, "same round id in a new session is a different item");
        ok(WhaleLiveCapture.clean(text("", 9000)).length() <= 8000, "text is capped at 8000 characters");
        ok(WhaleLiveCapture.token("a:b c/d", 16, "x").equals("abcd"), "ids keep only safe ASCII (no ':' that would break dedup keys)");
    }

    static void ingestHappyPathAndRevision() throws Exception {
        reset();
        WhaleOutbox ob = newOutbox();
        WhaleSyncCore core = core(ob, root + "/turbo", TOKEN, 1);   // path prefix
        for (int i = 0; i < 3; i++) put(ob, "k" + i, "文本" + i, T0);
        WhaleSyncCore.Report r = core.runOnce(T0, 5);
        ok(r.status.equals("ok") && r.acked == 3 && ob.pending() == 0 && ob.acked() == 3, "3 items acked via a /turbo path prefix");
        ok(((Number) state().get("segments")).intValue() == 3, "server stored 3 segments");
        r = core.runOnce(T0 + 10, 5);
        ok(r.status.equals("idle") && calls("ingest") == 1, "nothing due -> no request");
        put(ob, "k1", "文本1（已更正）", T0 + 20);
        r = core.runOnce(T0 + 30, 5);
        ok(r.acked == 1 && ob.store().byKey("k1").revision == 2 && ((Map<?, ?>) state().get("revisions")).get("k1").toString().equals("2"), "revision 2 reaches the server (revision_applied) and is acked");
        // replay_idempotent: drop local memory (e.g. after pruning) and resend the identical content
        WhaleOutbox fresh = newOutbox(); WhaleSyncCore c2 = core(fresh, root + "/turbo", TOKEN, 2);
        put(fresh, "k0", "文本0", T0 + 40);
        r = c2.runOnce(T0 + 50, 5);
        ok(r.acked == 1 && r.status.equals("ok"), "replay_idempotent counts as an ack");
        ok(((Number) ((List<?>) state().get("batches")).get(0)).intValue() == 3, "first batch carried 3 items");
    }

    static void batchSplitting() throws Exception {
        reset();
        WhaleOutbox ob = newOutbox(); WhaleSyncCore core = core(ob, root, TOKEN, 3);
        for (int i = 0; i < 120; i++) put(ob, "b" + i, "条目" + i, T0);
        WhaleSyncCore.Report r = core.runOnce(T0, 10);
        ok(r.acked == 120 && ob.pending() == 0, "120 items all acked");
        List<?> batches = (List<?>) state().get("batches");
        ok(batches.size() == 3 && ((Number) batches.get(0)).intValue() == 50 && ((Number) batches.get(1)).intValue() == 50 && ((Number) batches.get(2)).intValue() == 20, "batches of 50/50/20");
        reset();
        WhaleOutbox ob2 = newOutbox(); WhaleSyncCore c2 = core(ob2, root, TOKEN, 4);
        for (int i = 0; i < 14; i++) put(ob2, "L" + i, text("L" + i, 7900), T0);   // ~24 KB each in UTF-8
        r = c2.runOnce(T0, 10);
        ok(r.acked == 14 && ob2.pending() == 0, "14 large items acked");
        ok(((Number) state().get("max_body_seen")).longValue() <= WhaleSyncCore.MAX_BATCH_BYTES, "no request body exceeds 256 KiB");
        ok(((List<?>) state().get("batches")).size() >= 2, "large items were split into several batches by size");
    }

    static void terminalVerdicts() throws Exception {
        reset();
        WhaleOutbox ob = newOutbox(); WhaleSyncCore core = core(ob, root, TOKEN, 5);
        put(ob, "good", "正常", T0);
        put(ob, "long", text("长", 9000), T0);          // > 8000 chars: injected directly (live capture caps at 8000)
        putBadHash(ob, "hash", "哈希不对", T0);
        WhaleSyncCore.Report r = core.runOnce(T0, 3);
        ok(r.acked == 1 && r.dead == 2 && ob.pending() == 0, "one ack, two per-item rejections, whole batch not failed");
        ok(ob.store().byKey("long").reason.equals("text_too_long") && ob.store().byKey("hash").reason.equals("hash_mismatch"), "rejection reasons kept (allow-listed characters only)");
        ok(ob.store().byKey("long").body.isEmpty(), "rejected row drops its text");
        mode("{\"ingest\":\"stale\"}");
        put(ob, "s1", "旧版", T0 + 10);
        r = core.runOnce(T0 + 20, 3);
        ok(r.dead == 1 && r.acked == 0 && ob.store().byKey("s1").reason.equals("stale_ignored"), "stale_ignored is a terminal conflict, never an ack");
        mode("{\"ingest\":\"ok\"}");
        WhaleHttp.Result ctl = WhaleHttp.post(root + WhaleEndpoint.CONTROL, TOKEN, RayneoContextProtocol.controlJson("purge_all", null, null), 3000, 8000, true);
        ok(ctl.ok() && ctl.body.contains("deleted_segments"), "control purge_all reaches the server");
        put(ob, "good", "正常（再发）", T0 + 30);
        r = core.runOnce(T0 + 40, 3);
        ok(r.dead == 1 && ob.store().byKey("good").reason.equals("tombstoned"), "tombstoned key is rejected as terminal after a purge");
        // second effect shape: "effect":"rejected" with a "reason" member (handled by the client even though the mock uses 'rejected: x')
        WhaleOutbox ob2 = newOutbox();
        WhaleSyncCore c2 = new WhaleSyncCore(ob2, (path, json) -> {
            String key = json.substring(json.indexOf("\"dedup_key\":\"") + 13); key = key.substring(0, key.indexOf('"'));
            return new WhaleHttp.Result(200, "{\"ok\":true,\"source\":\"rayneo\",\"batch_id\":\"" + batchId(json) + "\",\"results\":[{\"dedup_key\":\"" + key + "\",\"effect\":\"rejected\",\"reason\":\"text_too_long\"}]}", null, "", 0);
        }, "rayneo-android-0123456789abcdef", new Random(1));
        put(ob2, "q", "x", T0);
        r = c2.runOnce(T0, 2);
        ok(r.dead == 1 && ob2.store().byKey("q").reason.equals("text_too_long"), "effect=rejected + reason member is also terminal");
    }
    static String batchId(String json) { String k = "\"batch_id\":\""; int i = json.indexOf(k) + k.length(); return json.substring(i, json.indexOf('"', i)); }

    static void backoffAndOfflineResume() throws Exception {
        reset();
        WhaleOutbox ob = newOutbox(); WhaleSyncCore core = core(ob, root, TOKEN, 6);
        put(ob, "o1", "离线一", T0); put(ob, "o2", "离线二", T0);
        mode("{\"ingest\":\"500\"}");
        WhaleSyncCore.Report r = core.runOnce(T0, 3);
        ok(r.status.equals("server_error") && r.error.equals("HTTP 500") && ob.pending() == 2 && r.acked == 0, "HTTP 500 keeps rows pending");
        long retry = core.retryAt();
        ok(retry >= T0 + 24_000 && retry <= T0 + 36_000, "first retry is scheduled ~30 s out (jitter 0.8-1.2x)");
        long before = calls("ingest");
        r = core.runOnce(T0 + 5_000, 3);
        ok(r.status.equals("waiting") && calls("ingest") == before, "no request while backing off");
        mode("{\"ingest\":\"500\"}");
        r = core.runOnce(retry + 1, 3);
        ok(r.status.equals("server_error") && core.retryAt() - (retry + 1) >= 48_000 - 1 && core.retryAt() - (retry + 1) <= 72_000, "second failure backs off ~60 s");
        ok(ob.store().byKey("o1").attempts == 2, "attempt counter increases per row");
        // offline: nothing listening on this port -> pending preserved
        WhaleOutbox off = newOutbox(); WhaleSyncCore c = core(off, "http://127.0.0.1:1", TOKEN, 8);
        put(off, "x1", "断网一", T0); put(off, "x2", "断网二", T0);
        r = c.runOnce(T0, 3);
        ok(r.status.equals("offline") && off.pending() == 2, "connection refused is offline; rows stay queued");
        // "process restart": a new core over the same store (meta persisted), server is back, time passed
        mode("{\"ingest\":\"ok\"}");
        WhaleSyncCore restarted = core(off, root, TOKEN, 9);
        r = restarted.runOnce(T0 + 60_000, 3);
        ok(r.status.equals("ok") && r.acked == 2 && off.pending() == 0, "after restart and reconnect the queue resumes and drains");
        r = core.runOnce(retry + 10 * 60_000, 3);
        ok(r.status.equals("ok") && r.acked == 2 && ob.pending() == 0, "the earlier 500 backlog drains once the server recovers");
        ok(core.lastError().isEmpty() && core.retryAt() == 0, "success clears error and retry time");
    }

    static void authPause() throws Exception {
        reset();
        WhaleOutbox ob = newOutbox(); WhaleSyncCore bad = core(ob, root, "wrong-token", 10);
        put(ob, "a1", "鉴权", T0);
        WhaleSyncCore.Report r = bad.runOnce(T0, 3);
        ok(r.status.equals("auth_paused") && r.error.equals("HTTP 401 unauthorized") && ob.pending() == 1 && bad.authPaused(), "401 pauses upload with a content-free label; rows kept");
        long unauth = calls("unauthorized");
        r = bad.runOnce(T0 + 1000, 3);
        ok(r.status.equals("auth_paused") && calls("unauthorized") == unauth, "while paused no further requests are made");
        WhaleSyncCore good = core(ob, root, TOKEN, 11);
        ok(good.authPaused(), "the pause survives a restart (stored in the outbox)");
        good.resumeAuth();
        r = good.runOnce(T0 + 2000, 3);
        ok(r.status.equals("ok") && r.acked == 1 && !good.authPaused(), "resume with the right token uploads");
    }

    static void tooLarge() throws Exception {
        reset();
        mode("{\"max_body\":6000}");
        WhaleOutbox ob = newOutbox(); WhaleSyncCore core = core(ob, root, TOKEN, 12);
        for (int i = 0; i < 16; i++) put(ob, "m" + i, text("M" + i, 300), T0);
        WhaleSyncCore.Report r = core.runOnce(T0, 30);
        ok(r.acked == 16 && ob.pending() == 0 && ob.dead() == 0, "HTTP 413 halves the batch until it fits and everything is delivered");
        ok(core.batchLimit() < WhaleSyncCore.MAX_ITEMS, "batch limit stays reduced after 413");
        ok(((Number) state().get("max_body_seen")).longValue() > 6000, "the server did see an oversize body first");
        reset(); mode("{\"max_body\":500}");
        WhaleOutbox ob2 = newOutbox(); WhaleSyncCore c2 = core(ob2, root, TOKEN, 13);
        put(ob2, "huge", text("H", 3000), T0); put(ob2, "tiny", "t", T0);
        r = c2.runOnce(T0, 10);
        ok(ob2.store().byKey("huge").reason.equals("too_large") && ob2.store().byKey("tiny").state == WhaleOutbox.ACKED, "a single item that is too large becomes terminal; the rest still goes");
    }

    static void rateLimitDisabledProtocol() throws Exception {
        reset(); mode("{\"ingest\":\"429\",\"retry_after_s\":42}");
        WhaleOutbox ob = newOutbox(); WhaleSyncCore core = core(ob, root, TOKEN, 14);
        put(ob, "z", "限速", T0);
        WhaleSyncCore.Report r = core.runOnce(T0, 3);
        ok(r.status.equals("rate_limited") && core.retryAt() == T0 + 42_000 && ob.pending() == 1, "429 honours retry_after_s");
        reset(); mode("{\"ingest\":\"503_disabled\"}");
        WhaleOutbox ob2 = newOutbox(); WhaleSyncCore c2 = core(ob2, root, TOKEN, 15);
        put(ob2, "z", "关闭", T0);
        r = c2.runOnce(T0, 3);
        ok(r.status.equals("server_disabled") && c2.retryAt() == T0 + 3_600_000L && ob2.pending() == 1, "ingest_disabled waits one hour");
        reset(); mode("{\"ingest\":\"garbage\"}");
        WhaleOutbox ob3 = newOutbox(); WhaleSyncCore c3 = core(ob3, root, TOKEN, 16);
        put(ob3, "z", "乱码", T0);
        r = c3.runOnce(T0, 3);
        ok(r.status.equals("protocol_error") && r.acked == 0 && ob3.pending() == 1, "a 200 that is not the contract is never an ack");
        reset(); mode("{\"ingest\":\"bad_ack\"}");
        WhaleOutbox ob4 = newOutbox(); WhaleSyncCore c4 = core(ob4, root, TOKEN, 17);
        put(ob4, "z", "缺raw_id", T0);
        r = c4.runOnce(T0, 3);
        ok(r.status.equals("protocol_error") && r.acked == 0 && ob4.pending() == 1 && ob4.store().byKey("z").attempts == 1, "an ack without raw_id/canonical_id is refused");
    }

    static void gaps() throws Exception {
        reset();
        WhaleOutbox ob = newOutbox(); WhaleSyncCore core = core(ob, root, TOKEN, 18);
        for (int i = 0; i < WhaleOutbox.MAX_ROWS + 3; i++) put(ob, "g" + i, "t", T0);
        ok(ob.droppedUnreported() == 3, "3 rows were dropped by the cap");
        core.runOnce(T0, 1);
        ok(state().get("gaps_seen").toString().contains("client_dropped") && ob.droppedUnreported() == 0, "the drop count is reported once as a client_dropped gap, then cleared");
    }

    static void queryPath() throws Exception {
        reset();
        WhaleOutbox ob = newOutbox(); WhaleSyncCore core = core(ob, root, TOKEN, 19);
        put(ob, "rayneo:i:s-1:r-1:user", "今天下午讨论接口方案", T0);
        core.runOnce(T0, 2);
        RayneoContextProtocol.Poster poster = (path, body) -> RayneoContextProtocol.fromTransport(WhaleHttp.post(WhaleEndpoint.join(root + "/turbo", path), TOKEN, body, WhaleEndpoint.QUERY_CONNECT_MS, WhaleEndpoint.QUERY_READ_MS, true));
        Map<String, Object> e = RayneoQueryBridge.invoke("rayneo_context_query", "接口", poster, true, 8, T0);
        ok("ok".equals(e.get("status")) && RayneoQueryBridge.hasUsableResults(e), "query returns usable results");
        ok(Boolean.FALSE.equals(e.get("instruction_eligible")) && Boolean.TRUE.equals(e.get("untrusted_data")), "envelope is labeled non-instruction untrusted data");
        String json = RayneoContextProtocol.json(e);
        ok(!json.contains("IGNORE PREVIOUS") && !json.contains("owner-working") && !json.contains("\"instructions\""), "unknown server fields (instructions, space_id) never reach the model");
        ok(!json.contains("\"instruction_eligible\":true"), "a server claiming instruction_eligible=true is forced to false");
        ok(json.contains("\"derived\":[{") && json.contains("whale:episode"), "derived summaries pass through, sanitized");
        e = RayneoQueryBridge.invoke("rayneo_context_query", "完全无关的词zzz", poster, true, 8, T0);
        ok("empty".equals(e.get("status")) && !RayneoQueryBridge.hasUsableResults(e), "no match -> empty (judged by array contents, not by the word 'text')");
        mode("{\"query\":\"disabled\"}");
        e = RayneoQueryBridge.invoke("rayneo_context_query", "接口", poster, true, 8, T0);
        ok("query_disabled".equals(e.get("error_class")), "server-side query_disabled degrades to an empty labeled result");
        mode("{\"query\":\"500\"}");
        e = RayneoQueryBridge.invoke("rayneo_context_query", "接口", poster, true, 8, T0);
        ok("empty".equals(e.get("status")) && "http".equals(e.get("error_class")), "HTTP 500 degrades, never throws");
        mode("{\"query\":\"delay\",\"query_delay_s\":3.0}");
        RayneoContextProtocol.Poster fast = (path, body) -> RayneoContextProtocol.fromTransport(WhaleHttp.post(root + path, TOKEN, body, 1000, 1000, true));
        long t = System.nanoTime();
        e = RayneoQueryBridge.invoke("rayneo_context_query", "接口", fast, true, 8, T0);
        ok("timeout".equals(e.get("error_class")) && (System.nanoTime() - t) < 2_500_000_000L, "a slow server times out and the conversation continues");
        mode("{\"query\":\"ok\"}");
        RayneoContextProtocol.Poster bad = (path, body) -> RayneoContextProtocol.fromTransport(WhaleHttp.post(root + path, "wrong", body, 1000, 1000, true));
        ok("permission".equals(RayneoQueryBridge.invoke("rayneo_context_query", "接口", bad, true, 8, T0).get("error_class")), "401 on query degrades as permission");
        RayneoContextProtocol.Poster dead = (path, body) -> RayneoContextProtocol.fromTransport(WhaleHttp.post("http://127.0.0.1:1" + path, TOKEN, body, 1000, 1000, true));
        ok("offline".equals(RayneoQueryBridge.invoke("rayneo_context_query", "接口", dead, true, 8, T0).get("error_class")), "unreachable server degrades as offline");
        ok("unconfigured".equals(RayneoQueryBridge.invoke("rayneo_context_query", "x", poster, false, 8, T0).get("error_class")), "unconfigured degrades");
        Map<String, Object> hostile = RayneoQueryBridge.envelope("{\"ok\":true,\"source\":\"rayneo\",\"items\":[" + ("{\"text\":\"" + "长".repeat(5000) + "\"},").repeat(5) + "{\"text\":\"" + "长".repeat(5000) + "\"}],\"derived\":[]}", T0);
        ok(RayneoContextProtocol.json(hostile).length() < 12000 && Boolean.TRUE.equals(hostile.get("truncated")), "total returned text is bounded");
        ok("protocol".equals(RayneoQueryBridge.envelope("<html>", T0).get("error_class")) && "protocol".equals(RayneoQueryBridge.envelope("{\"ok\":true,\"source\":\"other\"}", T0).get("error_class")), "malformed or wrong-source bodies degrade");
        try { RayneoQueryBridge.invoke("other_tool", "x", poster, true, 8, T0); ok(false, "tool name is enforced"); } catch (IllegalArgumentException expected) { ok(true, "tool name enforced"); }
        ok(WhaleEndpoint.QUERY_CONNECT_MS == 4000 && WhaleEndpoint.QUERY_READ_MS == 6000, "query timeouts are 4 s connect / 6 s read");
    }

    static void toolTurnRules() {
        ToolTurn t = new ToolTurn(true, true, true, true, true);
        ok(t.validate(Arrays.asList("rayneo_context_query")) == null, "single rayneo query is allowed");
        t.attempt("rayneo_context_query");
        ok("none".equals(t.toolChoice()), "tool_choice none after a rayneo query");
        ok(t.validate(Arrays.asList("rayneo_context_query")) != null && t.validate(Arrays.asList("web_search")) != null && t.validate(Arrays.asList("create_todo")) != null, "nothing else runs after it");
        ok(new ToolTurn(true, true, true, true, true).validate(Arrays.asList("rayneo_context_query", "web_search")) != null, "no mixing with search");
        ok(new ToolTurn(true, true, true, true, true).validate(Arrays.asList("rayneo_context_query", "rayneo_context_query")) != null, "no two in one batch");
        ok(new ToolTurn(true, true, true, true, false).validate(Arrays.asList("rayneo_context_query")) != null, "refused when not enabled");
        ToolTurn s = new ToolTurn(true, true, true, true, true); s.attempt("web_search");
        ok(s.validate(Arrays.asList("rayneo_context_query")) != null, "no private query after a public search (privacy ordering)");
        ok(new ToolTurn(true, true, true, true).validate(Arrays.asList("web_search")) == null, "4-argument constructor keeps old behaviour");
    }
}
