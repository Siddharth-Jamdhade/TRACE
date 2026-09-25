"""Verification harness for TRACE's Room migrations.

Mirrors what Room does at runtime: build a "fresh install" database from the
exported v4 schema, build a "migrated" database by running the hand-written
migration over a v3 database with real rows, then compare the resulting schemas
column for column. Also checks the migrated/legacy row contents.

Why this exists: a wrong migration crashes the app on open for anyone with an
older database, and instrumented migration tests need a device. This runs the
migration against real SQLite in a second and diffs the result against the
schema Room itself exported, so a bad migration is caught before it ships.

Usage (from the project root):
    python tools/verify_room_migration.py

When the schema version is bumped, extend MIGRATION_SQL and the seed data here
with the new migration, and keep app/schemas/ committed.
"""

import json
import os
import sqlite3
import sys

SCHEMA_DIR = os.path.join("app", "schemas", "com.example.trace.TraceDatabase")
GENESIS = "0" * 64
FAILURES = []


def check(label, condition, detail=""):
    if condition:
        print(f"  PASS  {label}")
    else:
        print(f"  FAIL  {label} {detail}")
        FAILURES.append(label)


def load_create_sql(version):
    with open(os.path.join(SCHEMA_DIR, f"{version}.json"), encoding="utf-8") as fh:
        data = json.load(fh)
    return {
        e["tableName"]: e["createSql"].replace("${TABLE_NAME}", e["tableName"])
        for e in data["database"]["entities"]
    }


def schema_of(conn):
    """Column-level schema, the same shape Room's TableInfo compares."""
    out = {}
    tables = [
        r[0]
        for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table'"
        )
    ]
    for table in sorted(tables):
        cols = [
            (r[1], (r[2] or "").upper(), r[3], r[4], r[5])
            for r in conn.execute(f"PRAGMA table_info(`{table}`)")
        ]
        out[table] = cols
    return out


# --- The migration under test, copied verbatim from TraceDatabase.kt ---------

MIGRATION_SQL = [
    (
        "CREATE TABLE IF NOT EXISTS `sessions` ("
        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
        "`natureOfWork` TEXT NOT NULL, "
        "`startedAt` INTEGER NOT NULL, "
        "`endedAt` INTEGER, "
        "`state` TEXT NOT NULL, "
        "`sensorSet` TEXT NOT NULL, "
        "`sessionHash` TEXT NOT NULL, "
        "`eventCount` INTEGER NOT NULL, "
        "`confirmedCount` INTEGER NOT NULL, "
        "`legacy` INTEGER NOT NULL)",
        (),
    ),
    ("ALTER TABLE events ADD COLUMN sessionId INTEGER NOT NULL DEFAULT 1", ()),
    (
        "INSERT INTO `sessions` (`id`, `natureOfWork`, `startedAt`, `endedAt`, "
        "`state`, `sensorSet`, `sessionHash`, `eventCount`, `confirmedCount`, `legacy`) "
        "SELECT 1, 'Pre-session timeline (legacy)', MIN(`timestamp`), MAX(`timestamp`), "
        "'COMPLETED', '', ?, COUNT(*), "
        "SUM(CASE WHEN `status` = 'CONFIRMED' THEN 1 ELSE 0 END), 1 FROM `events`",
        (GENESIS,),
    ),
]


def migrate(conn, has_events):
    """Executes the migration exactly as MIGRATION_3_4 does."""
    conn.execute(MIGRATION_SQL[0][0])
    conn.execute(MIGRATION_SQL[1][0])

    count = conn.execute("SELECT COUNT(*) FROM events").fetchone()[0]
    has_legacy_events = count > 0
    check(f"guarded insert takes the expected branch (has_events={has_events})",
          has_legacy_events == has_events)
    if has_legacy_events:
        conn.execute(MIGRATION_SQL[2][0], MIGRATION_SQL[2][1])


def build_db(create_sqls, seed):
    conn = sqlite3.connect(":memory:")
    for sql in create_sqls.values():
        conn.execute(sql)
    if seed:
        conn.executemany(
            "INSERT INTO events (type, timestamp, source, confidence, status, hash, previousHash) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            [
                ("impact", 1000, "motion", 0.8, "CONFIRMED", "hash-a", GENESIS),
                ("alarm", 1250, "audio", 0.9, "CONFIRMED", "hash-b", "hash-a"),
                ("object_moves", 1500, "camera", 0.4, "UNCONFIRMED", "hash-c", "hash-b"),
            ],
        )
    return conn


def main():
    v3 = load_create_sql(3)
    v4 = load_create_sql(4)

    print("Fresh v4 install vs migrated v3 database")
    fresh = build_db(v4, seed=False)
    migrated = build_db(v3, seed=True)
    migrate(migrated, has_events=True)

    fresh_schema = schema_of(fresh)
    migrated_schema = schema_of(migrated)

    check("same set of tables",
          set(fresh_schema) == set(migrated_schema),
          f"fresh={sorted(fresh_schema)} migrated={sorted(migrated_schema)}")

    for table in sorted(set(fresh_schema) & set(migrated_schema)):
        check(f"{table}: column definitions identical",
              fresh_schema[table] == migrated_schema[table],
              f"\n    fresh   ={fresh_schema[table]}\n    migrated={migrated_schema[table]}")

    # --- Legacy session contents ------------------------------------------
    row = migrated.execute(
        "SELECT id, natureOfWork, startedAt, endedAt, state, sensorSet, "
        "sessionHash, eventCount, confirmedCount, legacy FROM sessions"
    ).fetchone()
    check("exactly one legacy session exists",
          migrated.execute("SELECT COUNT(*) FROM sessions").fetchone()[0] == 1)
    check("legacy session id is 1", row[0] == 1, str(row))
    check("legacy session holds the pre-session description",
          row[1] == "Pre-session timeline (legacy)", str(row))
    check("legacy session spans the existing events (MIN/MAX timestamp)",
          (row[2], row[3]) == (1000, 1500), str(row))
    check("legacy session is COMPLETED", row[4] == "COMPLETED", str(row))
    check("legacy session has no sensor set", row[5] == "", str(row))
    check("legacy session anchors on GENESIS", row[6] == GENESIS, str(row))
    check("legacy aggregates: 3 events, 2 confirmed",
          (row[7], row[8]) == (3, 2), str(row))
    check("legacy session is flagged legacy", row[9] == 1, str(row))

    # --- Existing evidence untouched --------------------------------------
    events = migrated.execute(
        "SELECT type, hash, previousHash, sessionId FROM events ORDER BY id"
    ).fetchall()
    check("all migrated events belong to the legacy session",
          all(e[3] == 1 for e in events), str(events))
    check("recorded hashes and links were not rewritten",
          [(e[1], e[2]) for e in events] ==
          [("hash-a", GENESIS), ("hash-b", "hash-a"), ("hash-c", "hash-b")],
          str(events))

    # --- Fresh install path -----------------------------------------------
    print("\nFresh (empty) v3 database")
    empty = build_db(v3, seed=False)
    migrate(empty, has_events=False)

    empty_schema = schema_of(empty)
    check("empty migration still produces the v4 schema",
          empty_schema == fresh_schema,
          f"\n    fresh ={fresh_schema}\n    empty ={empty_schema}")
    check("no legacy session is created for an empty database",
          empty.execute("SELECT COUNT(*) FROM sessions").fetchone()[0] == 0)

    print()
    if FAILURES:
        print(f"FAILED: {len(FAILURES)} check(s): {FAILURES}")
        return 1
    print("All migration checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
