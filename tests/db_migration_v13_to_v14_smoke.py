# -*- coding: utf-8 -*-
"""Migration safety test for v13 -> v14 (dual-mode GPS): one new additive column on beneficiaries
(gps_source). Existing rows with a real GPS fix already captured are backfilled to 'gps_offline' (the
only provider that existed before this migration); rows with no fix stay untouched, and every other
column is left completely unmodified.
"""
import sqlite3
import sys
import tempfile
import os

V13_SCHEMA = [
    "CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)",
    "CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,name TEXT,"
    "gps_lat REAL,gps_lng REAL,gps_accuracy_m REAL,gps_captured_at INTEGER DEFAULT 0,"
    "original_address TEXT DEFAULT '',updated_at INTEGER NOT NULL,"
    "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)",
]

V14_MIGRATION = [
    "ALTER TABLE beneficiaries ADD COLUMN gps_source TEXT DEFAULT ''",
    "UPDATE beneficiaries SET gps_source='gps_offline' WHERE gps_captured_at>0 AND (gps_source IS NULL OR gps_source='')",
]


def build_v13_database(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys=ON")
    for stmt in V13_SCHEMA:
        conn.execute(stmt)
    conn.execute("INSERT INTO projects(_id,name,created_at) VALUES (1,'مشروع مخيم اليرموك',1000)")
    # Beneficiary 1: already has a real pre-migration GPS fix (captured under the old GPS_PROVIDER-only code).
    conn.execute("INSERT INTO beneficiaries(_id,project_id,name,gps_lat,gps_lng,gps_accuracy_m,gps_captured_at,updated_at) "
                 "VALUES (1,1,'أحمد حسين دياب',33.5012,36.2988,12.5,1700000000000,1000)")
    # Beneficiary 2: no GPS fix at all yet.
    conn.execute("INSERT INTO beneficiaries(_id,project_id,name,gps_captured_at,updated_at) VALUES (2,1,'مستفيد بلا موقع بعد',0,1000)")
    conn.commit()
    return conn


def test_v13_to_v14_backfills_gps_source_only_for_existing_fixes():
    print("=== v13 -> v14 migration: additive gps_source column, safe backfill, zero data loss ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_v13_database(os.path.join(tmp, "yarmouk_field_v13.db"))
        before_counts = conn.execute("SELECT COUNT(*) FROM beneficiaries").fetchone()[0]
        before_coords = conn.execute("SELECT _id,gps_lat,gps_lng,gps_accuracy_m,gps_captured_at FROM beneficiaries ORDER BY _id").fetchall()

        for stmt in V14_MIGRATION:
            conn.execute(stmt)
        conn.commit()

        after_counts = conn.execute("SELECT COUNT(*) FROM beneficiaries").fetchone()[0]
        after_coords = conn.execute("SELECT _id,gps_lat,gps_lng,gps_accuracy_m,gps_captured_at FROM beneficiaries ORDER BY _id").fetchall()
        assert before_counts == after_counts
        assert before_coords == after_coords, "existing GPS coordinates/accuracy/timestamp must be completely untouched"

        row1 = conn.execute("SELECT gps_source FROM beneficiaries WHERE _id=1").fetchone()[0]
        assert row1 == "gps_offline", f"a pre-existing real fix must be backfilled as gps_offline (the only mode that existed before), got {row1!r}"

        row2 = conn.execute("SELECT gps_source FROM beneficiaries WHERE _id=2").fetchone()[0]
        assert row2 == "", f"a beneficiary with no fix yet must NOT get an invented source, got {row2!r}"

        cols = {r[1] for r in conn.execute("PRAGMA table_info(beneficiaries)")}
        assert "gps_source" in cols

        print("OK: v13->v14 backfilled gps_source='gps_offline' only for rows with an existing real fix, "
              "left the no-GPS row's source empty, and never touched any coordinate/accuracy/timestamp column.\n")
        conn.close()


def test_original_address_column_survives_the_new_migration_untouched():
    print("=== Sanity: an unrelated earlier column (original_address) is never touched by this migration ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_v13_database(os.path.join(tmp, "yarmouk_field_v13.db"))
        conn.execute("UPDATE beneficiaries SET original_address='عنوان أصلي كما أُدخل أول مرة' WHERE _id=1")
        conn.commit()
        for stmt in V14_MIGRATION:
            conn.execute(stmt)
        conn.commit()
        value = conn.execute("SELECT original_address FROM beneficiaries WHERE _id=1").fetchone()[0]
        assert value == "عنوان أصلي كما أُدخل أول مرة"
        print("OK: original_address (from the v13 migration) stayed byte-identical after the v14 migration ran.\n")
        conn.close()


if __name__ == "__main__":
    try:
        test_v13_to_v14_backfills_gps_source_only_for_existing_fixes()
        test_original_address_column_survives_the_new_migration_untouched()
        print("DB_MIGRATION_V13_TO_V14_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
