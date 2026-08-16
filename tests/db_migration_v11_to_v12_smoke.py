# -*- coding: utf-8 -*-
"""Migration safety test for v11 -> v12 (Phase 6): one new additive table,
map_calibration_points, completely separate from beneficiaries. No column is
added to beneficiaries, no beneficiary data is touched -- gps_lat/gps_lng on
beneficiaries (Phase 5) remain the only real-GPS storage; this new table only
ever holds calibration reference points for the map raster itself.
"""
import sqlite3
import sys
import tempfile
import os

V11_SCHEMA = [
    "CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)",
    "CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,name TEXT,"
    "updated_at INTEGER NOT NULL,gps_lat REAL,gps_lng REAL,gps_accuracy_m REAL,gps_captured_at INTEGER DEFAULT 0,"
    "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)",
]

V12_MIGRATION = [
    "CREATE TABLE IF NOT EXISTS map_calibration_points (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
    "label TEXT DEFAULT '',lat REAL NOT NULL,lng REAL NOT NULL,frac_x REAL NOT NULL,frac_y REAL NOT NULL,"
    "created_at INTEGER NOT NULL)",
]


def build_v11_database(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys=ON")
    for stmt in V11_SCHEMA:
        conn.execute(stmt)
    conn.execute("INSERT INTO projects(_id,name,created_at) VALUES (1,'مشروع مخيم اليرموك',1000)")
    conn.execute(
        "INSERT INTO beneficiaries(_id,project_id,name,updated_at,gps_lat,gps_lng,gps_accuracy_m,gps_captured_at) "
        "VALUES (1,1,'أحمد حسين دياب',1000,33.4991,36.2853,8.5,5000)"
    )
    conn.commit()
    return conn


def snapshot(conn):
    cur = conn.cursor()
    counts = {t: cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("projects", "beneficiaries")}
    gps = cur.execute("SELECT gps_lat,gps_lng,gps_accuracy_m,gps_captured_at FROM beneficiaries WHERE _id=1").fetchone()
    return counts, gps


def test_v11_to_v12_is_additive_only():
    print("=== v11 -> v12 migration: additive-only, zero data loss, beneficiaries untouched ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_v11_database(os.path.join(tmp, "yarmouk_field_v11.db"))
        before_counts, before_gps = snapshot(conn)

        for stmt in V12_MIGRATION:
            conn.execute(stmt)
        conn.commit()

        after_counts, after_gps = snapshot(conn)
        assert before_counts == after_counts, f"Row counts changed! {before_counts} -> {after_counts}"
        assert before_gps == after_gps == (33.4991, 36.2853, 8.5, 5000), \
            "Beneficiary GPS data (Phase 5) must be completely untouched by this migration"

        beneficiary_cols = {r[1] for r in conn.execute("PRAGMA table_info(beneficiaries)")}
        assert "frac_x" not in beneficiary_cols and "frac_y" not in beneficiary_cols, \
            "No pixel/fraction column must ever be added to the beneficiaries table"

        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert "map_calibration_points" in tables
        assert conn.execute("SELECT COUNT(*) FROM map_calibration_points").fetchone()[0] == 0

        # Sanity: the new table actually works and is independent of any beneficiary row.
        conn.execute("INSERT INTO map_calibration_points(label,lat,lng,frac_x,frac_y,created_at) VALUES ('بوابة رئيسية',33.5,36.29,0.4,0.6,9000)")
        conn.commit()
        row = conn.execute("SELECT label,lat,lng,frac_x,frac_y FROM map_calibration_points").fetchone()
        assert row == ('بوابة رئيسية', 33.5, 36.29, 0.4, 0.6)

        print("OK: v11->v12 added only the empty map_calibration_points table; beneficiaries (including "
              "Phase 5 GPS columns) were completely untouched, and no pixel column was added to beneficiaries.\n")
        conn.close()


if __name__ == "__main__":
    try:
        test_v11_to_v12_is_additive_only()
        print("DB_MIGRATION_V11_TO_V12_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
