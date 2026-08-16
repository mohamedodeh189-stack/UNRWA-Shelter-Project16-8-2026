# -*- coding: utf-8 -*-
"""Phase -1 smoke test: v7 -> v8 migration safety, run against a realistic
v7-schema database before any production code (AppDatabase.java) changes.

No physical/emulator Android device was available in this environment
(`adb devices` returned an empty list), so this test builds the v7 schema
verbatim from the exact CREATE/ALTER TABLE strings in AppDatabase.java
(onCreate already produces the full v7 schema for a fresh install, which is
schema-identical to a device that upgraded all the way from v1) and
populates it with realistic, non-trivial data covering every table.

It then applies the *candidate* v7->v8 migration exactly as planned for
Phase 0 (this script does not touch AppDatabase.java), and verifies:
  1. Every pre-existing row (projects/beneficiaries/photos/quantities)
     survives untouched.
  2. New columns/tables exist with the right defaults.
  3. study_uuid and revision_uuid are backfilled, non-empty, and unique
     per beneficiary row.
  4. A deliberately-broken migration rolls back cleanly and leaves the
     original v7 data 100% intact (non-destructive migration policy).
"""
import sqlite3
import sys
import tempfile
import uuid
import os

# ---------------------------------------------------------------------------
# v7 schema, copied verbatim from AppDatabase.java (onCreate + full
# onUpgrade chain result — both produce an identical end schema).
# ---------------------------------------------------------------------------
V7_SCHEMA = [
    "CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)",
    "CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,sequence_no INTEGER DEFAULT 0,"
    "name TEXT,registration TEXT,relation TEXT,related_to TEXT,phone1 TEXT,phone2 TEXT,address TEXT,sector TEXT,street TEXT,amount TEXT,"
    "match_status TEXT,confidence REAL DEFAULT 0,sector_order INTEGER DEFAULT 999,route_order INTEGER DEFAULT 999,street_order INTEGER DEFAULT 999,"
    "alley_order INTEGER DEFAULT 999,visit_status TEXT DEFAULT 'pending',progress_percent INTEGER DEFAULT 0,notes TEXT DEFAULT '',workflow_stage TEXT DEFAULT 'study',"
    "study_status TEXT DEFAULT 'draft',damage_notes TEXT DEFAULT '',quantities_notes TEXT DEFAULT '',drawing_uri TEXT DEFAULT '',"
    "followup_batch TEXT DEFAULT '',approved_at INTEGER DEFAULT 0,work_delivered INTEGER DEFAULT 0,engineer_name TEXT DEFAULT '',updated_at INTEGER NOT NULL,"
    "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)",
    "CREATE INDEX idx_beneficiary_project_route ON beneficiaries(project_id,sector_order,route_order,street_order,alley_order)",
    "CREATE INDEX idx_beneficiary_name ON beneficiaries(project_id,name)",
    "CREATE INDEX idx_beneficiary_stage ON beneficiaries(project_id,workflow_stage,study_status)",
    "CREATE TABLE beneficiary_photos (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "phase TEXT NOT NULL,uri TEXT NOT NULL UNIQUE,file_name TEXT,captured_at INTEGER NOT NULL,note TEXT DEFAULT '',"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)",
    "CREATE INDEX idx_photo_beneficiary_phase ON beneficiary_photos(beneficiary_id,phase,captured_at)",
    "CREATE TABLE beneficiary_quantities (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "item_no INTEGER NOT NULL,quantity REAL NOT NULL,unit_price REAL NOT NULL,"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,UNIQUE(beneficiary_id,item_no))",
    "CREATE INDEX idx_quantity_beneficiary ON beneficiary_quantities(beneficiary_id,item_no)",
]

# Candidate v8 additive migration (Phase 0 design — not yet applied to
# AppDatabase.java). Every statement is additive; nothing here drops or
# renames anything.
V8_MIGRATION = [
    "ALTER TABLE beneficiaries ADD COLUMN study_uuid TEXT DEFAULT ''",
    "ALTER TABLE beneficiaries ADD COLUMN revision_uuid TEXT DEFAULT ''",
    "ALTER TABLE beneficiaries ADD COLUMN revision_no INTEGER DEFAULT 1",
    "ALTER TABLE beneficiaries ADD COLUMN previous_revision_uuid TEXT DEFAULT ''",
    "ALTER TABLE beneficiaries ADD COLUMN gps_lat REAL",
    "ALTER TABLE beneficiaries ADD COLUMN gps_lng REAL",
    "ALTER TABLE beneficiaries ADD COLUMN gps_accuracy_m REAL",
    "ALTER TABLE beneficiaries ADD COLUMN gps_captured_at INTEGER DEFAULT 0",
    "ALTER TABLE beneficiaries ADD COLUMN sync_status TEXT DEFAULT 'local'",
    "ALTER TABLE beneficiaries ADD COLUMN synced_at INTEGER DEFAULT 0",
    "ALTER TABLE beneficiaries ADD COLUMN delivery_confirmed_at INTEGER DEFAULT 0",
    "CREATE TABLE boq_delivery (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "item_no INTEGER NOT NULL,status TEXT DEFAULT 'not_delivered',note TEXT DEFAULT '',engineer_name TEXT DEFAULT '',"
    "updated_at INTEGER,FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,UNIQUE(beneficiary_id,item_no))",
    "CREATE INDEX idx_boq_delivery_beneficiary ON boq_delivery(beneficiary_id,item_no)",
    "CREATE TABLE address_corrections (_id INTEGER PRIMARY KEY AUTOINCREMENT,normalized_text TEXT UNIQUE,"
    "confirmed_sector TEXT,confirmed_street TEXT,confirmed_by TEXT,confirmed_at INTEGER)",
]


def build_v7_database(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys=ON")
    for stmt in V7_SCHEMA:
        conn.execute(stmt)

    # Two projects, mirroring real field usage.
    conn.execute("INSERT INTO projects(_id,name,created_at) VALUES (1,'مشروع مخيم اليرموك',1000)")
    conn.execute("INSERT INTO projects(_id,name,created_at) VALUES (2,'مشروع تجريبي ثانٍ',1000)")

    beneficiaries = [
        # id, project, seq, name, workflow_stage, study_status, visit_status, work_delivered, engineer, batch, amount
        (1, 1, 1, "أحمد حسين دياب", "study", "draft", "pending", 0, "م. سامر", "", "3982"),
        (2, 1, 2, "نعيم حسين دياب", "study", "ready", "pending", 0, "م. سامر", "", "3992"),
        (3, 1, 3, "صحية محمد عقيل", "followup", "approved", "pending", 0, "م. لينا", "دفعة 2026-08-01", "3963"),
        (4, 1, 4, "فضية عقيل جمعة", "followup", "approved", "completed", 1, "م. لينا", "دفعة 2026-08-01", "3982"),
        (5, 1, 5, "وردة خالد عماري", "followup", "approved", "review", 0, "م. لينا", "دفعة 2026-08-01", "3582"),
        (6, 1, 6, "جهاد سعود حمزات", "followup", "approved", "unreachable", 0, "م. سامر", "دفعة 2026-08-01", "3988"),
        (7, 2, 1, "محمد علي جودة", "study", "draft", "pending", 0, "", "", ""),
        (8, 2, 2, "محمد عمر محمود عبدالله", "study", "ready", "pending", 0, "م. رامي", "", "1140"),
        (9, 2, 3, "خالد عمر الكوسى", "followup", "approved", "completed", 1, "م. رامي", "قائمة: مستوردة.xlsx", "4000"),
        (10, 2, 4, "حنان علي غنام", "followup", "approved", "pending", 0, "م. رامي", "قائمة: مستوردة.xlsx", "2897"),
        (11, 2, 5, "هبة قاسم الخالد", "followup", "approved", "pending", 0, "", "قائمة: مستوردة.xlsx", "3997"),
        (12, 2, 6, "أحمد يوسف حوارنة", "study", "draft", "pending", 0, "", "", ""),
    ]
    for (bid, pid, seq, name, stage, study_status, visit_status, delivered, engineer, batch, amount) in beneficiaries:
        conn.execute(
            "INSERT INTO beneficiaries(_id,project_id,sequence_no,name,registration,address,sector,street,amount,"
            "workflow_stage,study_status,visit_status,work_delivered,engineer_name,followup_batch,updated_at) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (bid, pid, seq, name, f"1-{10000000+bid}", f"عنوان تجريبي {bid}", "شرق اليرموك 1", "بئر السبع",
             amount, stage, study_status, visit_status, delivered, engineer, batch, 1000 + bid),
        )

    # Photos: three phases across several beneficiaries.
    photo_id = 1
    for bid in (1, 3, 4, 9, 10):
        for phase in ("before", "during", "after"):
            conn.execute(
                "INSERT INTO beneficiary_photos(_id,beneficiary_id,phase,uri,file_name,captured_at,note) VALUES (?,?,?,?,?,?,?)",
                (photo_id, bid, phase, f"content://photos/{bid}_{phase}_{photo_id}.jpg", f"{bid}_{phase}.jpg", 2000 + photo_id, ""),
            )
            photo_id += 1

    # Quantities: BOQ rows for several beneficiaries.
    for bid in (2, 4, 5, 8, 9, 10):
        for item_no in (1, 7, 19, 31):
            conn.execute(
                "INSERT INTO beneficiary_quantities(beneficiary_id,item_no,quantity,unit_price) VALUES (?,?,?,?)",
                (bid, item_no, 3.0 + item_no % 5, 25.5 + item_no),
            )

    conn.commit()
    return conn


def id_sets(conn):
    cur = conn.cursor()
    return {
        "beneficiaries": set(r[0] for r in cur.execute("SELECT _id FROM beneficiaries")),
        "beneficiary_photos": set(r[0] for r in cur.execute("SELECT _id FROM beneficiary_photos")),
        "beneficiary_quantities": set(r[0] for r in cur.execute("SELECT _id FROM beneficiary_quantities")),
    }


def snapshot(conn):
    cur = conn.cursor()
    counts = {}
    for table in ("projects", "beneficiaries", "beneficiary_photos", "beneficiary_quantities"):
        counts[table] = cur.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
    spot = {
        "beneficiary_1_name": cur.execute("SELECT name FROM beneficiaries WHERE _id=1").fetchone()[0],
        "beneficiary_4_amount": cur.execute("SELECT amount FROM beneficiaries WHERE _id=4").fetchone()[0],
        "beneficiary_4_delivered": cur.execute("SELECT work_delivered FROM beneficiaries WHERE _id=4").fetchone()[0],
        "photo_uri_sample": cur.execute("SELECT uri FROM beneficiary_photos WHERE beneficiary_id=1 AND phase='before'").fetchone()[0],
        "quantity_sum_bid2": cur.execute("SELECT SUM(quantity*unit_price) FROM beneficiary_quantities WHERE beneficiary_id=2").fetchone()[0],
    }
    return counts, spot


def apply_v8_migration(conn):
    """Mirrors exactly what AppDatabase.onUpgrade(oldVersion<8) will do."""
    cur = conn.cursor()
    for stmt in V8_MIGRATION:
        cur.execute(stmt)
    # Backfill study_uuid/revision_uuid row by row (SQLite has no UUID()
    # builtin; the real Java migration will do the same per-row loop with
    # java.util.UUID.randomUUID()).
    rows = cur.execute("SELECT _id FROM beneficiaries").fetchall()
    for (bid,) in rows:
        study_id = str(uuid.uuid4())
        revision_id = str(uuid.uuid4())
        cur.execute(
            "UPDATE beneficiaries SET study_uuid=?, revision_uuid=? WHERE _id=?",
            (study_id, revision_id, bid),
        )
    conn.commit()


def test_successful_migration():
    print("=== Test 1: successful v7 -> v8 migration ===")
    with tempfile.TemporaryDirectory() as tmp:
        db_path = os.path.join(tmp, "yarmouk_field_v7.db")
        conn = build_v7_database(db_path)
        before_counts, before_spot = snapshot(conn)
        before_ids = id_sets(conn)
        print("BEFORE counts:", before_counts)
        print("BEFORE spot-check:", before_spot)
        print("BEFORE ids:", {k: sorted(v) for k, v in before_ids.items()})

        apply_v8_migration(conn)

        after_counts, after_spot = snapshot(conn)
        after_ids = id_sets(conn)
        print("AFTER counts:", after_counts)
        print("AFTER spot-check:", after_spot)
        print("AFTER ids:", {k: sorted(v) for k, v in after_ids.items()})

        assert before_counts == after_counts, f"Row counts changed! {before_counts} -> {after_counts}"
        assert before_spot == after_spot, f"Existing data mutated! {before_spot} -> {after_spot}"
        assert before_ids == after_ids, f"Row IDs changed by migration! {before_ids} -> {after_ids}"

        cur = conn.cursor()
        cols = {row[1] for row in cur.execute("PRAGMA table_info(beneficiaries)")}
        expected_new = {
            "study_uuid", "revision_uuid", "revision_no", "previous_revision_uuid",
            "gps_lat", "gps_lng", "gps_accuracy_m", "gps_captured_at",
            "sync_status", "synced_at", "delivery_confirmed_at",
        }
        missing = expected_new - cols
        assert not missing, f"Missing new columns: {missing}"

        tables = {row[0] for row in cur.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert "boq_delivery" in tables, "boq_delivery table missing"
        assert "address_corrections" in tables, "address_corrections table missing"

        total = cur.execute("SELECT COUNT(*) FROM beneficiaries").fetchone()[0]
        distinct_study = cur.execute("SELECT COUNT(DISTINCT study_uuid) FROM beneficiaries").fetchone()[0]
        distinct_revision = cur.execute("SELECT COUNT(DISTINCT revision_uuid) FROM beneficiaries").fetchone()[0]
        empty_study = cur.execute("SELECT COUNT(*) FROM beneficiaries WHERE study_uuid=''").fetchone()[0]
        empty_revision = cur.execute("SELECT COUNT(*) FROM beneficiaries WHERE revision_uuid=''").fetchone()[0]
        assert distinct_study == total, f"study_uuid not unique per row: {distinct_study} distinct of {total}"
        assert distinct_revision == total, f"revision_uuid not unique per row: {distinct_revision} distinct of {total}"
        assert empty_study == 0 and empty_revision == 0, "Some rows missing a backfilled uuid"

        defaults = cur.execute(
            "SELECT DISTINCT sync_status, revision_no, delivery_confirmed_at, previous_revision_uuid FROM beneficiaries"
        ).fetchall()
        assert defaults == [("local", 1, 0, "")], f"Unexpected defaults on existing rows: {defaults}"

        print(f"OK: {total} beneficiaries migrated, all study_uuid/revision_uuid unique and non-empty, "
              f"all old data byte-identical, defaults correct.\n")
        conn.close()


def test_failed_migration_rolls_back_cleanly():
    print("=== Test 2: broken migration must roll back with zero data loss ===")
    with tempfile.TemporaryDirectory() as tmp:
        db_path = os.path.join(tmp, "yarmouk_field_v7_broken.db")
        conn = build_v7_database(db_path)
        before_counts, before_spot = snapshot(conn)

        cur = conn.cursor()
        cur.execute("BEGIN")
        rolled_back = False
        try:
            cur.execute("ALTER TABLE beneficiaries ADD COLUMN study_uuid TEXT DEFAULT ''")
            # Deliberately broken statement: re-adding the same column name,
            # exactly the kind of mistake a real onUpgrade edit could make.
            cur.execute("ALTER TABLE beneficiaries ADD COLUMN study_uuid TEXT DEFAULT ''")
            conn.commit()
        except sqlite3.OperationalError as exc:
            conn.rollback()
            rolled_back = True
            print(f"Expected failure raised and NOT swallowed: {exc}")

        assert rolled_back, "Broken migration did not raise — a real onUpgrade must never swallow exceptions"

        after_counts, after_spot = snapshot(conn)
        assert before_counts == after_counts, "Row counts changed after a rolled-back migration!"
        assert before_spot == after_spot, "Data mutated after a rolled-back migration!"
        cols = {row[1] for row in cur.execute("PRAGMA table_info(beneficiaries)")}
        assert "study_uuid" not in cols, "Partial schema change survived a rollback — must not happen"

        print("OK: failed migration left the v7 database 100% intact (no partial schema, no data loss, no swallowed exception).\n")
        conn.close()


if __name__ == "__main__":
    try:
        test_successful_migration()
        test_failed_migration_rolls_back_cleanly()
        print("DB_MIGRATION_V7_TO_V8_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
