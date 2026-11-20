package com.turboio.addon;

import java.util.ArrayList;
import java.util.List;

/** Durable upload queue logic (rows live in a Store: SQLite on the device, memory in tests).
 * One row per dedup_key. Offering the same key again with identical content is a no-op (DUPLICATE); with different
 * content it raises the revision and re-queues the row (REVISED). Acked/dead rows keep only hash+revision for 24 h
 * (their text is dropped) so late repeats are still recognised. Caps: 20000 pending rows or 64 MiB of pending text;
 * beyond that the oldest pending rows are dropped and counted, and the count is reported to the server as a gap. */
final class WhaleOutbox {
    static final int PENDING = 0, ACKED = 1, DEAD = 2;
    static final int MAX_ROWS = 20000;
    static final long MAX_BYTES = 64L << 20, RETAIN_MS = 24L * 3600 * 1000;
    enum Offer { NEW, REVISED, DUPLICATE }

    static final class Row {
        long seq;
        String dedupKey, kind, origin, segmentId, hash, body, reason = "";
        int revision = 1, attempts, state = PENDING;
        long createdMs, updatedMs, nextTryMs;
        Row copy() {
            Row r = new Row(); r.seq = seq; r.dedupKey = dedupKey; r.kind = kind; r.origin = origin; r.segmentId = segmentId; r.hash = hash;
            r.body = body; r.reason = reason; r.revision = revision; r.attempts = attempts; r.state = state; r.createdMs = createdMs;
            r.updatedMs = updatedMs; r.nextTryMs = nextTryMs; return r;
        }
    }

    interface Store {
        Row byKey(String key);
        Row bySeq(long seq);
        long insert(Row row);                         // assigns and returns a new increasing seq
        void update(Row row);
        List<Row> due(long nowMs, int limit);         // state PENDING and nextTryMs <= now, ascending seq
        int count(int state);
        long pendingBytes();
        int dropOldestPending(int n);                 // deletes, returns how many
        int deleteFinishedBefore(long beforeMs);      // ACKED/DEAD rows with updatedMs < beforeMs
        void clear();
        String meta(String key);
        void meta(String key, String value);
    }

    private final Store store;
    private final Object lock = new Object();
    WhaleOutbox(Store store) { this.store = store; }
    Store store() { return store; }

    /** @param bodyJson JSON members of the item without client_seq/dedup_key/kind/origin/revision, e.g. "segment_id":"..","text":".." */
    Offer offer(String dedupKey, String kind, String origin, String segmentId, String hash, String bodyJson, long nowMs) {
        synchronized (lock) {
            Row old = store.byKey(dedupKey);
            Offer result;
            if (old == null) {
                Row r = new Row(); r.dedupKey = dedupKey; r.kind = kind; r.origin = origin; r.segmentId = segmentId; r.hash = hash; r.body = bodyJson;
                r.createdMs = nowMs; r.updatedMs = nowMs; store.insert(r); result = Offer.NEW;
            } else if (hash.equals(old.hash)) {
                return Offer.DUPLICATE;
            } else {
                old.revision++; old.hash = hash; old.body = bodyJson; old.state = PENDING; old.attempts = 0; old.nextTryMs = 0; old.reason = ""; old.updatedMs = nowMs;
                store.update(old); result = Offer.REVISED;
            }
            enforceCaps();
            return result;
        }
    }

    private void enforceCaps() {
        int over = store.count(PENDING) - MAX_ROWS;
        if (over > 0) drop(over);
        while (store.pendingBytes() > MAX_BYTES) { int n = store.dropOldestPending(Math.max(1, store.count(PENDING) / 20)); if (n <= 0) break; addDropped(n); }
    }

    private void drop(int n) { addDropped(store.dropOldestPending(n)); }
    private void addDropped(int n) { if (n > 0) store.meta("dropped", String.valueOf(droppedUnreported() + n)); }
    int droppedUnreported() { try { String v = store.meta("dropped"); return v == null ? 0 : Integer.parseInt(v); } catch (NumberFormatException e) { return 0; } }
    void clearDropped(int reported) { synchronized (lock) { int left = Math.max(0, droppedUnreported() - reported); store.meta("dropped", String.valueOf(left)); } }

    List<Row> due(long nowMs, int limit) { synchronized (lock) { return store.due(nowMs, limit); } }

    /** Ack only if the row still carries the revision that was sent; a newer revision stays pending. */
    boolean ack(long seq, int sentRevision, long nowMs) {
        synchronized (lock) {
            Row r = store.bySeq(seq);
            if (r == null || r.state != PENDING || r.revision != sentRevision) return false;
            r.state = ACKED; r.body = ""; r.reason = ""; r.updatedMs = nowMs; store.update(r); return true;
        }
    }

    /** Terminal server verdict (stale_ignored / rejected / tombstoned / too_large). Same revision rule as ack. */
    boolean dead(long seq, int sentRevision, String reason, long nowMs) {
        synchronized (lock) {
            Row r = store.bySeq(seq);
            if (r == null || r.state != PENDING || r.revision != sentRevision) return false;
            r.state = DEAD; r.body = ""; r.reason = reason; r.updatedMs = nowMs; store.update(r); return true;
        }
    }

    void retryLater(long seq, int sentRevision, long nextTryMs) {
        synchronized (lock) {
            Row r = store.bySeq(seq);
            if (r == null || r.state != PENDING || r.revision != sentRevision) return;
            r.attempts++; r.nextTryMs = nextTryMs; store.update(r);
        }
    }

    /** Re-queue immediately without counting an attempt (used when a batch is split after HTTP 413). */
    void releaseNow(long seq) {
        synchronized (lock) { Row r = store.bySeq(seq); if (r != null && r.state == PENDING) { r.nextTryMs = 0; store.update(r); } }
    }

    void prune(long nowMs) { synchronized (lock) { store.deleteFinishedBefore(nowMs - RETAIN_MS); } }

    void clearAll() { synchronized (lock) { store.clear(); store.meta("dropped", "0"); } }

    List<String> status() {
        ArrayList<String> out = new ArrayList<String>();
        out.add("pending=" + store.count(PENDING)); out.add("acked=" + store.count(ACKED)); out.add("dead=" + store.count(DEAD));
        return out;
    }
    int pending() { return store.count(PENDING); }
    int acked() { return store.count(ACKED); }
    int dead() { return store.count(DEAD); }
}
