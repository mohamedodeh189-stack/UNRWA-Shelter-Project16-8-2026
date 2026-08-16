# -*- coding: utf-8 -*-
"""Migration safety test for v8 -> v9 (Phase 3 correction): the only schema
change is one new additive table, revision_photo_links, needed so a
corrected revision can see photos it did not itself capture without moving
or duplicating the beneficiary_photos row. Nothing existing is dropped,
renamed, or altered.
"""
import sqlite3
import sys
import tempfile
import os

V8_SCHEMA = [
    "CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)",
    "CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,sequence_no INTEGER DEFAULT 0,"
    "name TEXT,updated_at INTEGER NOT NULL,study_uuid TEXT DEFAULT '',revision_uuid TEXT DEFAULT '',"
    "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)",
    "CREATE TABLE beneficiary_photos (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "phase TEXT NOT NULL,uri TEXT NOT NULL UNIQUE,file_name TEXT,captured_at INTEGER NOT NULL,note TEXT DEFAULT '',"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)",
    "CREATE TABLE beneficiary_quantities (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "item_no INTEGER NOT NULL,quantity REAL NOT NULL,unit_price REAL NOT NULL,"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,UNIQUE(beneficiary_id,item_no))",
    "CREATE TABLE boq_delivery (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "item_no INTEGER NOT NULL,status TEXT DEFAULT 'not_delivered',"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,UNIQUE(beneficiary_id,item_no))",
]

V9_MIGRATION = [
    "CREATE TABLE IF NOT EXISTS revision_photo_links (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "photo_id INTEGER NOT NULL,linked_at INTEGER,"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,"
    "FOREIGN KEY(photo_id) REFERENCES beneficiary_photos(_id) ON DELETE CASCADE,"
    "UNIQUE(beneficiary_id,photo_id))",
    "CREATE INDEX IF NOT EXISTS idx_revision_photo_links_beneficiary ON revision_photo_links(beneficiary_id)",
]


def build_v8_database(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys=ON")
    for stmt in V8_SCHEMA:
        conn.execute(stmt)
    conn.execute("INSERT INTO projects(_id,name,created_at) VALUES (1,'مشروع مخيم اليرموك',1000)")
    for bid in (1, 2, 3):
        conn.execute("INSERT INTO beneficiaries(_id,project_id,sequence_no,name,updated_at,study_uuid,revision_uuid) VALUES (?,1,?,?,1000,?,?)",
                     (bid, bid, f"مستفيد {bid}", f"study-{bid}", f"rev-{bid}"))
    for bid in (1, 2):
        conn.execute("INSERT INTO beneficiary_photos(beneficiary_id,phase,uri,file_name,captured_at) VALUES (?,'before',?,?,2000)",
                     (bid, f"content://photos/{bid}.jpg", f"{bid}.jpg"))
        conn.execute("INSERT INTO beneficiary_quantities(beneficiary_id,item_no,quantity,unit_price) VALUES (?,1,3,50)", (bid,))
    conn.commit()
    return conn


def snapshot(conn):
    cur = conn.cursor()
    counts = {t: cur.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
              for t in ("projects", "beneficiaries", "beneficiary_photos", "beneficiary_quantities", "boq_delivery")}
    names = sorted(r[0] for r in cur.execute("SELECT name FROM beneficiaries"))
    photo_uris = sorted(r[0] for r in cur.execute("SELECT uri FROM beneficiary_photos"))
    return counts, names, photo_uris


def test_v8_to_v9_is_additive_only():
    print("=== v8 -> v9 migration: additive-only, zero data loss ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_v8_database(os.path.join(tmp, "yarmouk_field_v8.db"))
        before_counts, before_names, before_photos = snapshot(conn)

        for stmt in V9_MIGRATION:
            conn.execute(stmt)
        conn.commit()

        after_counts, after_names, after_photos = snapshot(conn)
        assert before_counts == after_counts, f"Row counts changed! {before_counts} -> {after_counts}"
        assert before_names == after_names, "Beneficiary data mutated by the migration!"
        assert before_photos == after_photos, "Photo data mutated by the migration!"

        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert "revision_photo_links" in tables
        assert conn.execute("SELECT COUNT(*) FROM revision_photo_links").fetchone()[0] == 0, \
            "New table must start empty -- no data invented by the migration"

        # The new table must actually work: link photo 1 to beneficiary 3 (a revision that didn't capture it).
        conn.execute("INSERT INTO revision_photo_links(beneficiary_id,photo_id,linked_at) VALUES (3,1,9999)")
        conn.commit()
        linked = conn.execute("SELECT photo_id FROM revision_photo_links WHERE beneficiary_id=3").fetchall()
        assert linked == [(1,)]

        # FK cascade sanity: deleting the beneficiary that owns the photo must cascade the link away too.
        conn.execute("DELETE FROM beneficiaries WHERE _id=1")
        conn.commit()
        remaining_links = conn.execute("SELECT COUNT(*) FROM revision_photo_links").fetchone()[0]
        assert remaining_links == 0, "Link must cascade-delete when the linked photo's owning beneficiary (and thus the photo) is removed"

        print("OK: v8->v9 added only revision_photo_links (empty, additive), no existing row/table was touched, "
              "and its foreign keys cascade correctly.\n")
        conn.close()


if __name__ == "__main__":
    try:
        test_v8_to_v9_is_additive_only()
        print("DB_MIGRATION_V8_TO_V9_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
