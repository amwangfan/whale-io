#!/usr/bin/env python3
"""Replays the exact SQL constants of WhaleOutboxSqlite.java against a real SQLite engine (the Java Store itself needs
Android's SQLiteDatabase, which the JVM unit tests cannot load). Checks DDL, ordering, due filter, counts, byte sum,
drop-oldest, prune and meta upsert, plus that every column ContentValues writes exists in the DDL."""
import re
import sqlite3
import sys
from pathlib import Path

src = (Path(__file__).resolve().parents[2] / 'src/com/turboio/addon/WhaleOutboxSqlite.java').read_text()
sql = dict(re.findall(r'static final String (SQL_[A-Z_]+) = "((?:[^"\\]|\\.)*)";', src))
need = {'SQL_OUTBOX', 'SQL_META', 'SQL_INDEX', 'SQL_DUE', 'SQL_COUNT', 'SQL_BYTES', 'SQL_DROP_OLDEST', 'SQL_PRUNE', 'SQL_META_PUT', 'SQL_META_GET'}
assert need <= set(sql), need - set(sql)
db = sqlite3.connect(':memory:')
for name in ('SQL_OUTBOX', 'SQL_META', 'SQL_INDEX'):
    db.execute(sql[name])
db.execute(sql['SQL_OUTBOX'])  # IF NOT EXISTS: idempotent
cols = {r[1] for r in db.execute('PRAGMA table_info(outbox)')}
written = set(re.findall(r'v\.put\("([a-z_]+)"', src))
assert written <= cols, written - cols
read = set(re.findall(r'getColumnIndexOrThrow\("([a-z_]+)"', src))
assert read <= cols | {'seq'}, read - cols

def put(key, state=0, nxt=0, body='x', updated=0):
    db.execute("INSERT INTO outbox (dedup_key,kind,origin,segment_id,revision,hash,body,state,created_ms,updated_ms,next_try_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
               (key, 'transcript', 'live', key, 1, 'h', body, state, 0, updated, nxt))
for i in range(6):
    put('k%d' % i, nxt=100 if i == 2 else 0, body='abc' * (i + 1))
put('done', state=1, updated=50)
put('dead', state=2, updated=500)
try:
    put('k0')
    raise SystemExit('dedup_key must be UNIQUE')
except sqlite3.IntegrityError:
    pass
due = [r[1] for r in db.execute(sql['SQL_DUE'], (50, 10))]
assert due == ['k0', 'k1', 'k3', 'k4', 'k5'] or [r for r in due] == ['k0', 'k1', 'k3', 'k4', 'k5'], due
assert [r[1] for r in db.execute(sql['SQL_DUE'], (100, 3))] == ['k0', 'k1', 'k2'], 'LIMIT and next_try boundary'
assert db.execute(sql['SQL_COUNT'], (0,)).fetchone()[0] == 6 and db.execute(sql['SQL_COUNT'], (1,)).fetchone()[0] == 1
assert db.execute(sql['SQL_BYTES']).fetchone()[0] == 3 * (1 + 2 + 3 + 4 + 5 + 6)
db.execute(sql['SQL_DROP_OLDEST'].replace('?', '2'))
assert [r[0] for r in db.execute("SELECT dedup_key FROM outbox WHERE state=0 ORDER BY seq")] == ['k2', 'k3', 'k4', 'k5'], 'oldest two pending rows dropped, finished rows untouched'
db.execute(sql['SQL_PRUNE'].replace('?', '100'))
assert [r[0] for r in db.execute("SELECT dedup_key FROM outbox WHERE state<>0")] == ['dead'], 'only finished rows older than the cutoff are pruned'
db.execute(sql['SQL_META_PUT'].replace('(?, ?)', "('a','1')"))
db.execute(sql['SQL_META_PUT'].replace('(?, ?)', "('a','2')"))
assert db.execute(sql['SQL_META_GET'].replace('?', "'a'")).fetchone()[0] == '2', 'meta upsert'
print('Whale SQL tests passed (%d statements replayed on SQLite %s).' % (len(need), sqlite3.sqlite_version))
