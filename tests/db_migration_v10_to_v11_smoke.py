# -*- coding: utf-8 -*-
"""Migration safety test for v10 -> v11 (Phase 4 correction): two new
additive columns on beneficiaries (first_payment_available_at,
first_payment_confirmed_by) plus one new additive table
(first_payment_log). delivery_confirmed_at (the work-delivery date, added
in Phase 0) and boq_delivery are untouched. Nothing existing is dropped,
renamed, or altered.
"""
import sqlite3
import sys
import tempfile
import os

V10_SCHEMA = [
    "CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)",
    "CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,sequence_no INTEGER DEFAULT 0,"
    "name TEXT,updated_at INTEGER NOT NULL,delivery_confirmed_at INTEGER DEFAULT 0,"
    "second_payment_ready INTEGER DEFAULT 0,second_payment_approved_by TEXT DEFAULT '',second_payment_approved_at INTEGER DEFAULT 0,"
    "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)",
    "CREATE TABLE boq_delivery (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "item_no INTEGER NOT NULL,status TEXT DEFAULT 'not_delivered',"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,UNIQUE(beneficiary_id,item_no))",
]

V11_MIGRATION = [
    "ALTER TABLE beneficiaries ADD COLUMN first_payment_available_at INTEGER DEFAULT 0",
    "ALTER TABLE beneficiaries ADD COLUMN first_payment_confirmed_by TEXT DEFAULT ''",
    "CREATE TABLE IF NOT EXISTS first_payment_log (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "action TEXT NOT NULL,engineer_name TEXT DEFAULT '',at INTEGER NOT NULL,reason TEXT DEFAULT '',"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS idx_first_payment_log_beneficiary ON first_payment_log(beneficiary_id,at)",
]


def build_v10_database(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys=ON")
    for stmt in V10_SCHEMA:
        conn.execute(stmt)
    conn.execute("INSERT INTO projects(_id,name,created_at) VALUES (1,'مشروع مخيم اليرموك',1000)")
    for bid in (1, 2):
        conn.execute(
            "INSERT INTO beneficiaries(_id,project_id,sequence_no,name,updated_at,delivery_confirmed_at) VALUES (?,1,?,?,1000,?)",
            (bid, bid, f"مستفيد {bid}", 5000 if bid == 1 else 0),
        )
        conn.execute("INSERT INTO boq_delivery(beneficiary_id,item_no,status) VALUES (?,1,'delivered')", (bid,))
    conn.commit()
    return conn


def snapshot(conn):
    cur = conn.cursor()
    counts = {t: cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] for t in ("projects", "beneficiaries", "boq_delivery")}
    delivery_dates = sorted(r[0] for r in cur.execute("SELECT delivery_confirmed_at FROM beneficiaries"))
    return counts, delivery_dates


def test_v10_to_v11_is_additive_only():
    print("=== v10 -> v11 migration: additive-only, zero data loss ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_v10_database(os.path.join(tmp, "yarmouk_field_v10.db"))
        before_counts, before_delivery_dates = snapshot(conn)

        for stmt in V11_MIGRATION:
            conn.execute(stmt)
        conn.commit()

        after_counts, after_delivery_dates = snapshot(conn)
        assert before_counts == after_counts, f"Row counts changed! {before_counts} -> {after_counts}"
        assert before_delivery_dates == after_delivery_dates == [0, 5000], \
            "delivery_confirmed_at (work-delivery date) must be completely untouched by this migration"

        cols = {r[1] for r in conn.execute("PRAGMA table_info(beneficiaries)")}
        assert {"first_payment_available_at", "first_payment_confirmed_by"} <= cols

        defaults = conn.execute("SELECT DISTINCT first_payment_available_at,first_payment_confirmed_by FROM beneficiaries").fetchall()
        assert defaults == [(0, "")], f"Existing rows must get safe defaults, got {defaults}"

        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert "first_payment_log" in tables
        assert conn.execute("SELECT COUNT(*) FROM first_payment_log").fetchone()[0] == 0

        boq_rows = conn.execute("SELECT beneficiary_id,status FROM boq_delivery ORDER BY beneficiary_id").fetchall()
        assert boq_rows == [(1, "delivered"), (2, "delivered")], "boq_delivery must be untouched by this migration"

        print("OK: v10->v11 added only 2 columns (safe defaults) + 1 empty table; delivery_confirmed_at and "
              "boq_delivery were untouched.\n")
        conn.close()


if __name__ == "__main__":
    try:
        test_v10_to_v11_is_additive_only()
        print("DB_MIGRATION_V10_TO_V11_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
