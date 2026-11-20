package com.turboio.addon;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Batch upload engine for POST /v1/rayneo/ingest (contract: whale-core handoff 3.2). Pure Java: Android gating
 * (Wi-Fi, battery, switches) lives in WhaleSyncScheduler, so this class can be driven by a mock server in unit tests.
 * Acks are per item and strict: ok=true, source=rayneo, same dedup_key/segment_id/revision, non-empty raw_id and
 * canonical_id, effect in {ingested, revision_applied, replay_idempotent}. stale_ignored is not an ack. */
final class WhaleSyncCore {
    static final int MAX_ITEMS = 50, MAX_BATCH_BYTES = 256 * 1024;
    interface Transport { WhaleHttp.Result post(String path, String json); }

    static final class Report {
        int batches, sent, acked, dead, retried;
        String status = "idle";          // idle | ok | waiting | auth_paused | server_disabled | rate_limited | offline | timeout | server_error | protocol_error
        String error = "";               // content-free label, e.g. "HTTP 401 unauthorized"
    }

    private final WhaleOutbox outbox;
    private final Transport transport;
    private final String instance;
    private final Random random;
    private int batchLimit = MAX_ITEMS, goodStreak, counter;

    WhaleSyncCore(WhaleOutbox outbox, Transport transport, String sourceInstance, Random random) {
        this.outbox = outbox; this.transport = transport; this.instance = sourceInstance; this.random = random;
    }

    int batchLimit() { return batchLimit; }
    boolean authPaused() { return !meta("auth_error").isEmpty(); }
    String lastError() { return meta("last_error"); }
    long retryAt() { return longMeta("retry_at"); }
    /** Call after the token changes or the user taps resume. */
    void resumeAuth() { set("auth_error", ""); set("retry_at", "0"); }

    private String meta(String k) { String v = outbox.store().meta(k); return v == null ? "" : v; }
    private long longMeta(String k) { try { return Long.parseLong(meta(k)); } catch (NumberFormatException e) { return 0; } }
    private void set(String k, String v) { outbox.store().meta(k, v); }

    Report runOnce(long now, int maxBatches) {
        Report report = new Report();
        outbox.prune(now);
        if (authPaused()) { report.status = "auth_paused"; report.error = meta("auth_error"); return report; }
        if (longMeta("retry_at") > now) { report.status = "waiting"; report.error = meta("last_error"); return report; }
        for (int n = 0; n < maxBatches; n++) {
            if (n > 0 && longMeta("retry_at") > now) return report;
            List<WhaleOutbox.Row> rows = pick(outbox.due(now, MAX_ITEMS));
            if (rows.isEmpty()) { if (report.batches == 0) report.status = "idle"; return report; }
            int gaps = outbox.droppedUnreported();
            String batchId = "b-" + Long.toString(now, 36) + "-" + (++counter);
            String json = envelope(batchId, rows, gaps);
            WhaleHttp.Result r = transport.post(WhaleEndpoint.INGEST, json);
            report.batches++; report.sent += rows.size();
            if (!r.ok()) { if (failure(r, rows, now, report)) continue; return report; }
            if (!success(r.body, batchId, rows, gaps, now, report)) return report;
        }
        return report;
    }

    private List<WhaleOutbox.Row> pick(List<WhaleOutbox.Row> due) {
        ArrayList<WhaleOutbox.Row> out = new ArrayList<WhaleOutbox.Row>();
        int bytes = 400;
        for (WhaleOutbox.Row row : due) {
            if (out.size() >= batchLimit) break;
            int size = itemJson(row).getBytes(StandardCharsets.UTF_8).length + 1;
            if (!out.isEmpty() && bytes + size > MAX_BATCH_BYTES) break;
            out.add(row); bytes += size;
        }
        return out;
    }

    static String itemJson(WhaleOutbox.Row row) {
        return "{\"client_seq\":" + row.seq + ",\"dedup_key\":" + RayneoContextProtocol.quote(row.dedupKey) + ",\"kind\":" + RayneoContextProtocol.quote(row.kind)
            + ",\"origin\":" + RayneoContextProtocol.quote(row.origin) + ",\"revision\":" + row.revision + "," + row.body + "}";
    }

    private String envelope(String batchId, List<WhaleOutbox.Row> rows, int gaps) {
        StringBuilder s = new StringBuilder(2048);
        s.append("{\"contract_version\":\"").append(RayneoContextProtocol.CONTRACT_VERSION).append("\",\"source\":\"rayneo\",\"source_instance\":")
            .append(RayneoContextProtocol.quote(instance)).append(",\"batch_id\":").append(RayneoContextProtocol.quote(batchId));
        if (gaps > 0) s.append(",\"gaps\":[{\"kind\":\"client_dropped\",\"count\":").append(gaps).append("}]");
        s.append(",\"items\":[");
        for (int i = 0; i < rows.size(); i++) { if (i > 0) s.append(','); s.append(itemJson(rows.get(i))); }
        return s.append("]}").toString();
    }

    /** @return true to continue with another batch immediately (only after a 413 split) */
    private boolean failure(WhaleHttp.Result r, List<WhaleOutbox.Row> rows, long now, Report report) {
        report.error = r.label();
        if (r.auth()) { set("auth_error", r.label()); set("last_error", r.label()); report.status = "auth_paused"; return false; }
        if (r.status == 413) {
            if (rows.size() > 1) { batchLimit = Math.max(1, rows.size() / 2); goodStreak = 0; return true; }
            WhaleOutbox.Row only = rows.get(0);
            if (outbox.dead(only.seq, only.revision, "too_large", now)) report.dead++;
            return true;
        }
        long delay; String status;
        if (r.status == 429) { delay = Math.max(5, Math.min(3600, r.retryAfterS == 0 ? 30 : r.retryAfterS)) * 1000L; status = "rate_limited"; }
        else if (r.status == 503 && "ingest_disabled".equals(r.serverCode)) { delay = 3600_000L; status = "server_disabled"; }
        else {
            int fails = (int) longMeta("fails"); set("fails", String.valueOf(fails + 1));
            delay = WhaleBackoff.delayMs(fails, random);
            status = r.status == 0 ? ("timeout".equals(r.errorClass) ? "timeout" : "offline".equals(r.errorClass) ? "offline" : "server_error") : (r.status == 400 ? "protocol_error" : "server_error");
        }
        for (WhaleOutbox.Row row : rows) { outbox.retryLater(row.seq, row.revision, now + delay); report.retried++; }
        set("retry_at", String.valueOf(now + delay)); set("last_error", report.error);
        report.status = status;
        return false;
    }

    private boolean success(String body, String batchId, List<WhaleOutbox.Row> rows, int gaps, long now, Report report) {
        Map<?, ?> m = null;
        try { Object parsed = BoundedJson.readLarge(body.getBytes(StandardCharsets.UTF_8), 1 << 20, 50000); if (parsed instanceof Map) m = (Map<?, ?>) parsed; }
        catch (RuntimeException ignored) { m = null; }
        if (m == null || !Boolean.TRUE.equals(m.get("ok")) || !RayneoContextProtocol.SOURCE.equals(m.get("source")) || !batchId.equals(m.get("batch_id")) || !(m.get("results") instanceof List)) {
            return protocolFailure(rows, now, report);
        }
        LinkedHashMap<String, Map<?, ?>> byKey = new LinkedHashMap<String, Map<?, ?>>();
        for (Object o : (List<?>) m.get("results")) if (o instanceof Map && ((Map<?, ?>) o).get("dedup_key") instanceof String) byKey.put((String) ((Map<?, ?>) o).get("dedup_key"), (Map<?, ?>) o);
        boolean trouble = false;
        for (WhaleOutbox.Row row : rows) {
            Map<?, ?> res = byKey.get(row.dedupKey);
            String effect = res == null || !(res.get("effect") instanceof String) ? "" : (String) res.get("effect");
            if (res != null && (effect.equals("ingested") || effect.equals("revision_applied") || effect.equals("replay_idempotent"))) {
                boolean match = res.get("revision") instanceof Number && ((Number) res.get("revision")).longValue() == row.revision && row.segmentId.equals(res.get("segment_id"))
                    && nonEmpty(res.get("raw_id")) && nonEmpty(res.get("canonical_id"));
                if (match) { if (outbox.ack(row.seq, row.revision, now)) report.acked++; }
                else { trouble = true; outbox.retryLater(row.seq, row.revision, now + WhaleBackoff.delayMs(row.attempts, random)); report.retried++; }
            } else if (effect.equals("stale_ignored")) {
                if (outbox.dead(row.seq, row.revision, "stale_ignored", now)) report.dead++;
            } else if (effect.startsWith("rejected")) {
                String reason = effect.length() > 8 ? effect.substring(8) : (res.get("reason") instanceof String ? (String) res.get("reason") : "");
                reason = reason.replaceAll("[^a-z_]", "");
                if (outbox.dead(row.seq, row.revision, reason.isEmpty() ? "rejected" : reason, now)) report.dead++;
            } else {
                trouble = true; outbox.retryLater(row.seq, row.revision, now + WhaleBackoff.delayMs(row.attempts, random)); report.retried++;
            }
        }
        if (gaps > 0) outbox.clearDropped(gaps);
        Object after = m.get("retry_after_s");
        if (after instanceof Number && ((Number) after).longValue() > 0) set("retry_at", String.valueOf(now + Math.min(3600, ((Number) after).longValue()) * 1000L));
        if (trouble) { report.status = "protocol_error"; report.error = "ack_mismatch"; set("last_error", "ack_mismatch"); return false; }
        set("fails", "0"); set("last_error", ""); set("retry_at", after instanceof Number && ((Number) after).longValue() > 0 ? meta("retry_at") : "0"); set("last_ok_ms", String.valueOf(now));
        if (++goodStreak >= 5 && batchLimit < MAX_ITEMS) { batchLimit = Math.min(MAX_ITEMS, batchLimit * 2); goodStreak = 0; }
        report.status = "ok";
        return true;
    }

    private boolean protocolFailure(List<WhaleOutbox.Row> rows, long now, Report report) {
        int fails = (int) longMeta("fails"); set("fails", String.valueOf(fails + 1));
        long delay = WhaleBackoff.delayMs(fails, random);
        for (WhaleOutbox.Row row : rows) { outbox.retryLater(row.seq, row.revision, now + delay); report.retried++; }
        set("retry_at", String.valueOf(now + delay)); set("last_error", "protocol_mismatch");
        report.status = "protocol_error"; report.error = "protocol_mismatch";
        return false;
    }

    private static boolean nonEmpty(Object o) { return o instanceof String && !((String) o).isEmpty(); }
}
