# -*- coding: utf-8 -*-
"""Migration safety test for v12 -> v13 (Phase 7): two new additive columns
on beneficiaries (original_address, address_source). Existing rows are
backfilled so original_address = current address (the only "original" ever
recorded for them), and nothing else is touched.
"""
import sqlite3
import sys
import tempfile
import os

V12_SCHEMA = [
    "CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)",
    "CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,name TEXT,address TEXT DEFAULT '',"
    "sector TEXT DEFAULT '',street TEXT DEFAULT '',updated_at INTEGER NOT NULL,"
    "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)",
]

V13_MIGRATION = [
    "ALTER TABLE beneficiaries ADD COLUMN original_address TEXT DEFAULT ''",
    "ALTER TABLE beneficiaries ADD COLUMN address_source TEXT DEFAULT ''",
    "UPDATE beneficiaries SET original_address=address WHERE (original_address IS NULL OR original_address='') AND address<>''",
]


def build_v12_database(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys=ON")
    for stmt in V12_SCHEMA:
        conn.execute(stmt)
    conn.execute("INSERT INTO projects(_id,name,created_at) VALUES (1,'مشروع مخيم اليرموك',1000)")
    conn.execute("INSERT INTO beneficiaries(_id,project_id,name,address,sector,updated_at) VALUES (1,1,'أحمد حسين دياب','شرق اليرموك، شارع الوحدة','شرق اليرموك 1',1000)")
    conn.execute("INSERT INTO beneficiaries(_id,project_id,name,address,sector,updated_at) VALUES (2,1,'مستفيد بلا عنوان بعد','','',1000)")
    conn.commit()
    return conn


def test_v12_to_v13_backfills_original_address_safely():
    print("=== v12 -> v13 migration: additive columns + safe backfill, zero data loss ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_v12_database(os.path.join(tmp, "yarmouk_field_v12.db"))
        before_counts = conn.execute("SELECT COUNT(*) FROM beneficiaries").fetchone()[0]
        before_addresses = conn.execute("SELECT _id,address FROM beneficiaries ORDER BY _id").fetchall()

        for stmt in V13_MIGRATION:
            conn.execute(stmt)
        conn.commit()

        after_counts = conn.execute("SELECT COUNT(*) FROM beneficiaries").fetchone()[0]
        after_addresses = conn.execute("SELECT _id,address FROM beneficiaries ORDER BY _id").fetchall()
        assert before_counts == after_counts
        assert before_addresses == after_addresses, "The `address` (current working address) column must be completely untouched"

        row1 = conn.execute("SELECT address,original_address FROM beneficiaries WHERE _id=1").fetchone()
        assert row1 == ('شرق اليرموك، شارع الوحدة', 'شرق اليرموك، شارع الوحدة'), \
            f"Row with an existing address must get original_address backfilled to match it, got {row1}"

        row2 = conn.execute("SELECT address,original_address FROM beneficiaries WHERE _id=2").fetchone()
        assert row2 == ('', ''), f"Row with no address yet must stay empty, not invent one, got {row2}"

        cols = {r[1] for r in conn.execute("PRAGMA table_info(beneficiaries)")}
        assert {"original_address", "address_source"} <= cols

        print("OK: v12->v13 backfilled original_address only where an address already existed, invented nothing "
              "for the empty case, and never touched the live `address` column.\n")
        conn.close()


def test_original_address_guard_never_overwrites_once_set():
    """Mirrors AppDatabase.updateStudy()'s guarded second UPDATE exactly."""
    print("=== original_address one-shot guard: never overwritten once set, even across multiple edits ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_v12_database(os.path.join(tmp, "yarmouk_field_v12.db"))
        for stmt in V13_MIGRATION:
            conn.execute(stmt)
        conn.commit()

        def update_study_address(bid, new_address):
            conn.execute("UPDATE beneficiaries SET address=? WHERE _id=?", (new_address, bid))
            if new_address:
                conn.execute(
                    "UPDATE beneficiaries SET original_address=? WHERE _id=? AND (original_address IS NULL OR original_address='')",
                    (new_address, bid),
                )
            conn.commit()

        # Beneficiary 2 starts with no address at all; the engineer fills it in during the study visit.
        update_study_address(2, "غرب اليرموك، شارع الأمل")
        row = conn.execute("SELECT address,original_address FROM beneficiaries WHERE _id=2").fetchone()
        assert row == ("غرب اليرموك، شارع الأمل", "غرب اليرموك، شارع الأمل")

        # Later, the address text gets corrected/edited multiple times -- original_address must never change again.
        update_study_address(2, "غرب اليرموك، شارع الأمل، بجانب المسجد")
        update_study_address(2, "غرب اليرموك 2، شارع الأمل، بجانب المسجد الكبير")
        row = conn.execute("SELECT address,original_address FROM beneficiaries WHERE _id=2").fetchone()
        assert row[0] == "غرب اليرموك 2، شارع الأمل، بجانب المسجد الكبير", "Current address must reflect the latest edit"
        assert row[1] == "غرب اليرموك، شارع الأمل", \
            f"original_address must stay exactly what was FIRST recorded, got {row[1]!r}"

        print(f"OK: original_address captured on first entry ('غرب اليرموك، شارع الأمل') and survived two "
              f"subsequent address edits completely untouched.\n")
        conn.close()


if __name__ == "__main__":
    try:
        test_v12_to_v13_backfills_original_address_safely()
        test_original_address_guard_never_overwrites_once_set()
        print("DB_MIGRATION_V12_TO_V13_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
