package com.turboio.addon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** TEST ONLY: in-memory WhaleOutbox.Store with the same semantics as WhaleOutboxSqlite (see tests/WhaleSqlTest.py). */
final class MemoryStore implements WhaleOutbox.Store {
    final TreeMap<Long, WhaleOutbox.Row> rows = new TreeMap<Long, WhaleOutbox.Row>();
    final Map<String, Long> keys = new HashMap<String, Long>();
    final Map<String, String> meta = new HashMap<String, String>();
    long next = 1;
    final int[] counts = new int[3];
    long bytes;
    private void add(WhaleOutbox.Row r, int sign) { counts[r.state] += sign; if (r.state == WhaleOutbox.PENDING) bytes += sign * (long) r.body.length(); }
    public WhaleOutbox.Row byKey(String key) { Long s = keys.get(key); return s == null ? null : rows.get(s).copy(); }
    public WhaleOutbox.Row bySeq(long seq) { WhaleOutbox.Row r = rows.get(seq); return r == null ? null : r.copy(); }
    public long insert(WhaleOutbox.Row row) { row.seq = next++; WhaleOutbox.Row c = row.copy(); rows.put(row.seq, c); add(c, 1); keys.put(row.dedupKey, row.seq); return row.seq; }
    public void update(WhaleOutbox.Row row) { WhaleOutbox.Row old = rows.get(row.seq); if (old != null) add(old, -1); WhaleOutbox.Row c = row.copy(); rows.put(row.seq, c); add(c, 1); }
    public List<WhaleOutbox.Row> due(long nowMs, int limit) {
        ArrayList<WhaleOutbox.Row> out = new ArrayList<WhaleOutbox.Row>();
        for (WhaleOutbox.Row r : rows.values()) { if (out.size() >= limit) break; if (r.state == WhaleOutbox.PENDING && r.nextTryMs <= nowMs) out.add(r.copy()); }
        return out;
    }
    public int count(int state) { return counts[state]; }
    public long pendingBytes() { return bytes; }
    public int dropOldestPending(int n) {
        int dropped = 0;
        for (java.util.Iterator<WhaleOutbox.Row> it = rows.values().iterator(); it.hasNext() && dropped < n;) { WhaleOutbox.Row r = it.next(); if (r.state == WhaleOutbox.PENDING) { add(r, -1); keys.remove(r.dedupKey); it.remove(); dropped++; } }
        return dropped;
    }
    public int deleteFinishedBefore(long beforeMs) {
        int n = 0;
        for (java.util.Iterator<WhaleOutbox.Row> it = rows.values().iterator(); it.hasNext();) { WhaleOutbox.Row r = it.next(); if (r.state != WhaleOutbox.PENDING && r.updatedMs < beforeMs) { add(r, -1); keys.remove(r.dedupKey); it.remove(); n++; } }
        return n;
    }
    public void clear() { rows.clear(); keys.clear(); counts[0] = counts[1] = counts[2] = 0; bytes = 0; }
    public String meta(String key) { return meta.get(key); }
    public void meta(String key, String value) { meta.put(key, value); }
}
