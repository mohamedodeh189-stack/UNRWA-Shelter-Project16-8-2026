# -*- coding: utf-8 -*-
"""Migration safety test for v9 -> v10 (Phase 4): three new additive columns
on beneficiaries (second_payment_ready, second_payment_approved_by,
second_payment_approved_at) plus one new additive table (second_payment_log).
boq_delivery and delivery_confirmed_at already existed since Phase 0 and are
untouched. Nothing existing is dropped, renamed, or altered.
"""
import sqlite3
import sys
import tempfile
import os

V9_SCHEMA = [
    "CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)",
    "CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,sequence_no INTEGER DEFAULT 0,"
    "name TEXT,updated_at INTEGER NOT NULL,delivery_confirmed_at INTEGER DEFAULT 0,"
    "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)",
    "CREATE TABLE boq_delivery (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "item_no INTEGER NOT NULL,status TEXT DEFAULT 'not_delivered',"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,UNIQUE(beneficiary_id,item_no))",
]

V10_MIGRATION = [
    "ALTER TABLE beneficiaries ADD COLUMN second_payment_ready INTEGER DEFAULT 0",
    "ALTER TABLE beneficiaries ADD COLUMN second_payment_approved_by TEXT DEFAULT ''",
    "ALTER TABLE beneficiaries ADD COLUMN second_payment_approved_at INTEGER DEFAULT 0",
    "CREATE TABLE IF NOT EXISTS second_payment_log (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "action TEXT NOT NULL,engineer_name TEXT DEFAULT '',at INTEGER NOT NULL,reason TEXT DEFAULT '',"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS idx_second_payment_log_beneficiary ON second_payment_log(beneficiary_id,at)",
]


def build_v9_database(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys=ON")
    for stmt in V9_SCHEMA:
        conn.execute(stmt)
    conn.execute("INSERT INTO projects(_id,name,created_at) VALUES (1,'مشروع مخيم اليرموك',1000)")
    for bid in (1, 2):
        conn.execute("INSERT INTO beneficiaries(_id,project_id,sequence_no,name,updated_at,delivery_confirmed_at) VALUES (?,1,?,?,1000,0)", (bid, bid, f"مستفيد {bid}"))
        conn.execute("INSERT INTO boq_delivery(beneficiary_id,item_no,status) VALUES (?,1,'not_delivered')", (bid,))
    conn.commit()
    return conn


def snapshot(conn):
    cur = conn.cursor()
    counts = {t: cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("projects", "beneficiaries", "boq_delivery")}
    names = sorted(r[0] for r in cur.execute("SELECT name FROM beneficiaries"))
    return counts, names


def test_v9_to_v10_is_additive_only():
    print("=== v9 -> v10 migration: additive-only, zero data loss ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_v9_database(os.path.join(tmp, "yarmouk_field_v9.db"))
        before_counts, before_names = snapshot(conn)

        for stmt in V10_MIGRATION:
            conn.execute(stmt)
        conn.commit()

        after_counts, after_names = snapshot(conn)
        assert before_counts == after_counts, f"Row counts changed! {before_counts} -> {after_counts}"
        assert before_names == after_names, "Beneficiary data mutated by the migration!"

        cols = {r[1] for r in conn.execute("PRAGMA table_info(beneficiaries)")}
        assert {"second_payment_ready", "second_payment_approved_by", "second_payment_approved_at"} <= cols

        defaults = conn.execute("SELECT DISTINCT second_payment_ready,second_payment_approved_by,second_payment_approved_at FROM beneficiaries").fetchall()
        assert defaults == [(0, "", 0)], f"Existing rows must get safe defaults, got {defaults}"

        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert "second_payment_log" in tables
        assert conn.execute("SELECT COUNT(*) FROM second_payment_log").fetchone()[0] == 0

        # boq_delivery (Phase 0) must be completely untouched by this migration.
        boq_rows = conn.execute("SELECT beneficiary_id,item_no,status FROM boq_delivery ORDER BY beneficiary_id").fetchall()
        assert boq_rows == [(1, 1, "not_delivered"), (2, 1, "not_delivered")]

        print("OK: v9->v10 added only 3 columns (safe defaults) + 1 empty table; boq_delivery and all existing "
              "rows were untouched.\n")
        conn.close()


if __name__ == "__main__":
    try:
        test_v9_to_v10_is_additive_only()
        print("DB_MIGRATION_V9_TO_V10_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
