package com.turboio.addon;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** SQLite backing of WhaleOutbox.Store (private app database, never exported). The SQL strings are plain constants so
 * tests/WhaleSqlTest.py can replay the exact statements against a real SQLite engine. */
final class WhaleOutboxSqlite extends SQLiteOpenHelper implements WhaleOutbox.Store {
    static final String SQL_OUTBOX = "CREATE TABLE IF NOT EXISTS outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, dedup_key TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, origin TEXT NOT NULL, segment_id TEXT NOT NULL, revision INTEGER NOT NULL, hash TEXT NOT NULL, body TEXT NOT NULL, reason TEXT NOT NULL DEFAULT '', attempts INTEGER NOT NULL DEFAULT 0, state INTEGER NOT NULL DEFAULT 0, created_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL, next_try_ms INTEGER NOT NULL DEFAULT 0)";
    static final String SQL_META = "CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)";
    static final String SQL_INDEX = "CREATE INDEX IF NOT EXISTS outbox_due ON outbox (state, next_try_ms, seq)";
    static final String SQL_DUE = "SELECT * FROM outbox WHERE state=0 AND next_try_ms<=? ORDER BY seq LIMIT ?";
    static final String SQL_COUNT = "SELECT COUNT(*) FROM outbox WHERE state=?";
    static final String SQL_BYTES = "SELECT COALESCE(SUM(LENGTH(body)),0) FROM outbox WHERE state=0";
    static final String SQL_DROP_OLDEST = "DELETE FROM outbox WHERE seq IN (SELECT seq FROM outbox WHERE state=0 ORDER BY seq LIMIT ?)";
    static final String SQL_PRUNE = "DELETE FROM outbox WHERE state<>0 AND updated_ms<?";
    static final String SQL_META_PUT = "INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)";
    static final String SQL_META_GET = "SELECT v FROM meta WHERE k=?";
    private SQLiteDatabase db;

    WhaleOutboxSqlite(Context context) { super(context, "turboio_whale.db", null, 1); setWriteAheadLoggingEnabled(true); }

    @Override public void onCreate(SQLiteDatabase d) { d.execSQL(SQL_OUTBOX); d.execSQL(SQL_META); d.execSQL(SQL_INDEX); }
    @Override public void onUpgrade(SQLiteDatabase d, int from, int to) { }
    private synchronized SQLiteDatabase handle() { if (db == null || !db.isOpen()) db = getWritableDatabase(); return db; }

    private static WhaleOutbox.Row row(Cursor c) {
        WhaleOutbox.Row r = new WhaleOutbox.Row();
        r.seq = c.getLong(c.getColumnIndexOrThrow("seq")); r.dedupKey = c.getString(c.getColumnIndexOrThrow("dedup_key")); r.kind = c.getString(c.getColumnIndexOrThrow("kind"));
        r.origin = c.getString(c.getColumnIndexOrThrow("origin")); r.segmentId = c.getString(c.getColumnIndexOrThrow("segment_id")); r.revision = c.getInt(c.getColumnIndexOrThrow("revision"));
        r.hash = c.getString(c.getColumnIndexOrThrow("hash")); r.body = c.getString(c.getColumnIndexOrThrow("body")); r.reason = c.getString(c.getColumnIndexOrThrow("reason"));
        r.attempts = c.getInt(c.getColumnIndexOrThrow("attempts")); r.state = c.getInt(c.getColumnIndexOrThrow("state")); r.createdMs = c.getLong(c.getColumnIndexOrThrow("created_ms"));
        r.updatedMs = c.getLong(c.getColumnIndexOrThrow("updated_ms")); r.nextTryMs = c.getLong(c.getColumnIndexOrThrow("next_try_ms")); return r;
    }
    private static ContentValues values(WhaleOutbox.Row r) {
        ContentValues v = new ContentValues();
        v.put("dedup_key", r.dedupKey); v.put("kind", r.kind); v.put("origin", r.origin); v.put("segment_id", r.segmentId); v.put("revision", r.revision); v.put("hash", r.hash);
        v.put("body", r.body); v.put("reason", r.reason); v.put("attempts", r.attempts); v.put("state", r.state); v.put("created_ms", r.createdMs); v.put("updated_ms", r.updatedMs); v.put("next_try_ms", r.nextTryMs);
        return v;
    }
    private WhaleOutbox.Row one(String where, String arg) {
        try (Cursor c = handle().query("outbox", null, where, new String[] {arg}, null, null, null)) { return c.moveToFirst() ? row(c) : null; }
    }

    public WhaleOutbox.Row byKey(String key) { return one("dedup_key=?", key); }
    public WhaleOutbox.Row bySeq(long seq) { return one("seq=?", String.valueOf(seq)); }
    public long insert(WhaleOutbox.Row r) { long seq = handle().insertOrThrow("outbox", null, values(r)); r.seq = seq; return seq; }
    public void update(WhaleOutbox.Row r) { handle().update("outbox", values(r), "seq=?", new String[] {String.valueOf(r.seq)}); }
    public List<WhaleOutbox.Row> due(long nowMs, int limit) {
        ArrayList<WhaleOutbox.Row> out = new ArrayList<WhaleOutbox.Row>();
        try (Cursor c = handle().rawQuery(SQL_DUE, new String[] {String.valueOf(nowMs), String.valueOf(limit)})) { while (c.moveToNext()) out.add(row(c)); }
        return out;
    }
    public int count(int state) { try (Cursor c = handle().rawQuery(SQL_COUNT, new String[] {String.valueOf(state)})) { return c.moveToFirst() ? c.getInt(0) : 0; } }
    public long pendingBytes() { try (Cursor c = handle().rawQuery(SQL_BYTES, null)) { return c.moveToFirst() ? c.getLong(0) : 0; } }
    public int dropOldestPending(int n) { int before = count(0); handle().execSQL(SQL_DROP_OLDEST, new Object[] {Integer.valueOf(n)}); return before - count(0); }
    public int deleteFinishedBefore(long beforeMs) { int before = count(1) + count(2); handle().execSQL(SQL_PRUNE, new Object[] {Long.valueOf(beforeMs)}); return before - count(1) - count(2); }
    public void clear() { handle().delete("outbox", null, null); }
    public String meta(String key) { try (Cursor c = handle().rawQuery(SQL_META_GET, new String[] {key})) { return c.moveToFirst() ? c.getString(0) : null; } }
    public void meta(String key, String value) { handle().execSQL(SQL_META_PUT, new Object[] {key, value == null ? "" : value}); }
}
