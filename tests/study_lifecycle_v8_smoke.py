# -*- coding: utf-8 -*-
"""Phase 3 smoke test: study lifecycle state machine + historically-reviewable
corrected revisions, run against a real v9-schema database.

Design mirrored here exactly matches AppDatabase.java's Phase 3 methods
(submitForOfficeReview, approveStudy, promoteReadyBatch, studyReadyCount,
createCorrectedRevision) after the revision-history correction:

  1. draft -> ready -> office_review -> approved is the only reachable path;
     every bypass (draft->office_review, draft->approved, ready->approved)
     is a no-op, guarded by the UPDATE ... WHERE clause itself.
  2. createCorrectedRevision keeps study_uuid identical, generates a new
     revision_uuid, increments revision_no, and sets previous_revision_uuid
     to the old row's revision_uuid.
  3. Quantities and boq_delivery are COPIED (INSERT ... SELECT) to the new
     revision, never moved -- the old (superseded) revision's own rows are
     never touched again, so it remains a true point-in-time Snapshot even
     after the new revision's quantities are edited.
  4. Photos are neither copied nor moved -- a new revision_photo_links row
     lets the new revision see the old revision's photos (same physical
     file/row, no duplication), while the old revision keeps direct
     ownership (beneficiary_photos.beneficiary_id) of exactly the photos it
     originally captured, unaffected by anything captured later under the
     new revision.
  5. The old (superseded) row survives untouched in the database, is
     read-only by construction (nothing in this module ever writes to a
     superseded row's dependent tables again), and is never counted by
     studyReadyCount()/promoteReadyBatch() -- proving it cannot leak into
     current work, current batches, or (by the same status filter) future
     sync of "active" revisions.
"""
import sqlite3
import sys
import tempfile
import uuid
import os

V9_SCHEMA = [
    "CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)",
    "CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,sequence_no INTEGER DEFAULT 0,"
    "name TEXT,registration TEXT,relation TEXT,related_to TEXT,phone1 TEXT,phone2 TEXT,address TEXT,sector TEXT,street TEXT,amount TEXT,"
    "match_status TEXT,confidence REAL DEFAULT 0,sector_order INTEGER DEFAULT 999,route_order INTEGER DEFAULT 999,street_order INTEGER DEFAULT 999,"
    "alley_order INTEGER DEFAULT 999,visit_status TEXT DEFAULT 'pending',progress_percent INTEGER DEFAULT 0,notes TEXT DEFAULT '',workflow_stage TEXT DEFAULT 'study',"
    "study_status TEXT DEFAULT 'draft',damage_notes TEXT DEFAULT '',quantities_notes TEXT DEFAULT '',drawing_uri TEXT DEFAULT '',"
    "followup_batch TEXT DEFAULT '',approved_at INTEGER DEFAULT 0,work_delivered INTEGER DEFAULT 0,engineer_name TEXT DEFAULT '',updated_at INTEGER NOT NULL,"
    "study_uuid TEXT DEFAULT '',revision_uuid TEXT DEFAULT '',revision_no INTEGER DEFAULT 1,previous_revision_uuid TEXT DEFAULT '',"
    "gps_lat REAL,gps_lng REAL,gps_accuracy_m REAL,gps_captured_at INTEGER DEFAULT 0,"
    "sync_status TEXT DEFAULT 'local',synced_at INTEGER DEFAULT 0,delivery_confirmed_at INTEGER DEFAULT 0,"
    "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)",
    "CREATE TABLE beneficiary_photos (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "phase TEXT NOT NULL,uri TEXT NOT NULL UNIQUE,file_name TEXT,captured_at INTEGER NOT NULL,note TEXT DEFAULT '',"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)",
    "CREATE TABLE beneficiary_quantities (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "item_no INTEGER NOT NULL,quantity REAL NOT NULL,unit_price REAL NOT NULL,"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,UNIQUE(beneficiary_id,item_no))",
    "CREATE TABLE boq_delivery (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "item_no INTEGER NOT NULL,status TEXT DEFAULT 'not_delivered',note TEXT DEFAULT '',engineer_name TEXT DEFAULT '',"
    "updated_at INTEGER,FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,UNIQUE(beneficiary_id,item_no))",
    "CREATE TABLE revision_photo_links (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "photo_id INTEGER NOT NULL,linked_at INTEGER,"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE,"
    "FOREIGN KEY(photo_id) REFERENCES beneficiary_photos(_id) ON DELETE CASCADE,"
    "UNIQUE(beneficiary_id,photo_id))",
]


def build_v9_database(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys=ON")
    for stmt in V9_SCHEMA:
        conn.execute(stmt)
    conn.execute("INSERT INTO projects(_id,name,created_at) VALUES (1,'مشروع مخيم اليرموك',1000)")

    study_uuid = str(uuid.uuid4())
    revision_uuid = str(uuid.uuid4())
    conn.execute(
        "INSERT INTO beneficiaries(_id,project_id,sequence_no,name,registration,address,sector,street,amount,"
        "workflow_stage,study_status,damage_notes,drawing_uri,updated_at,study_uuid,revision_uuid,revision_no) "
        "VALUES (1,1,1,'أحمد حسين دياب','1-10000001','عنوان تجريبي','شرق اليرموك 1','بئر السبع','3982',"
        "'study','draft','أضرار جسيمة في السقف','content://drawings/1.jpg',1000,?,?,1)",
        (study_uuid, revision_uuid),
    )
    for phase in ("before", "during", "after"):
        conn.execute(
            "INSERT INTO beneficiary_photos(beneficiary_id,phase,uri,file_name,captured_at,note) VALUES (1,?,?,?,2000,'')",
            (phase, f"content://photos/1_{phase}.jpg", f"1_{phase}.jpg"),
        )
    for item_no, qty in ((1, 3), (7, 3), (19, 3)):
        conn.execute(
            "INSERT INTO beneficiary_quantities(beneficiary_id,item_no,quantity,unit_price) VALUES (1,?,?,50)",
            (item_no, qty),
        )
    conn.execute(
        "INSERT INTO boq_delivery(beneficiary_id,item_no,status,engineer_name,updated_at) VALUES (1,1,'delivered','م. سامر',3000)"
    )
    conn.commit()
    return conn, study_uuid, revision_uuid


# --- Mirrors of the AppDatabase.java methods (guarded UPDATE ... WHERE) ---

def mark_study_ready(conn, bid):
    cur = conn.execute("UPDATE beneficiaries SET study_status='ready',updated_at=1 WHERE _id=? AND study_status='draft'", (bid,))
    conn.commit()
    return cur.rowcount > 0


def submit_for_office_review(conn, bid):
    cur = conn.execute(
        "UPDATE beneficiaries SET study_status='office_review',updated_at=1 WHERE _id=? AND workflow_stage='study' AND study_status='ready'",
        (bid,),
    )
    conn.commit()
    return cur.rowcount > 0


def approve_study(conn, bid):
    cur = conn.execute(
        "UPDATE beneficiaries SET study_status='approved',approved_at=1,updated_at=1 WHERE _id=? AND workflow_stage='study' AND study_status='office_review'",
        (bid,),
    )
    conn.commit()
    return cur.rowcount > 0


def study_ready_count(conn, project_id):
    return conn.execute(
        "SELECT COUNT(*) FROM beneficiaries WHERE project_id=? AND workflow_stage='study' AND study_status='approved'",
        (project_id,),
    ).fetchone()[0]


def promote_ready_batch(conn, project_id, batch_name):
    cur = conn.execute(
        "UPDATE beneficiaries SET workflow_stage='followup',followup_batch=?,visit_status='pending',updated_at=1 "
        "WHERE _id IN (SELECT _id FROM beneficiaries WHERE project_id=? AND workflow_stage='study' AND study_status='approved' ORDER BY sequence_no,_id LIMIT 50)",
        (batch_name, project_id),
    )
    conn.commit()
    return cur.rowcount


def photos_visible_to(conn, beneficiary_id, phase=None):
    sql = ("SELECT p._id,p.uri,p.phase FROM beneficiary_photos p WHERE p.beneficiary_id=? "
           "UNION SELECT p._id,p.uri,p.phase FROM beneficiary_photos p JOIN revision_photo_links l ON l.photo_id=p._id WHERE l.beneficiary_id=?")
    args = [beneficiary_id, beneficiary_id]
    if phase:
        sql = f"SELECT * FROM ({sql}) WHERE phase=?"
        args.append(phase)
    return conn.execute(sql, args).fetchall()


def create_corrected_revision(conn, approved_id, engineer_name):
    row = conn.execute("SELECT * FROM beneficiaries WHERE _id=?", (approved_id,)).fetchone()
    cols = [d[0] for d in conn.execute("SELECT * FROM beneficiaries LIMIT 0").description]
    old = dict(zip(cols, row))
    if old["workflow_stage"] != "study" or old["study_status"] != "approved":
        raise AssertionError("createCorrectedRevision requires an approved study")

    new_revision_uuid = str(uuid.uuid4())
    conn.execute(
        "INSERT INTO beneficiaries(project_id,sequence_no,name,registration,address,sector,street,amount,"
        "workflow_stage,study_status,damage_notes,quantities_notes,drawing_uri,followup_batch,engineer_name,updated_at,"
        "study_uuid,revision_uuid,revision_no,previous_revision_uuid) "
        "VALUES (?,?,?,?,?,?,?,?,'study','draft',?,?,?,?,?,1,?,?,?,?)",
        (
            old["project_id"], old["sequence_no"], old["name"], old["registration"], old["address"], old["sector"], old["street"], old["amount"],
            old["damage_notes"], old["quantities_notes"], old["drawing_uri"], old["followup_batch"], engineer_name or old["engineer_name"],
            old["study_uuid"], new_revision_uuid, old["revision_no"] + 1, old["revision_uuid"],
        ),
    )
    new_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]

    # Quantities/BOQ: independent copies -- the old row's rows are never touched again.
    conn.execute(
        "INSERT INTO beneficiary_quantities(beneficiary_id,item_no,quantity,unit_price) "
        "SELECT ?,item_no,quantity,unit_price FROM beneficiary_quantities WHERE beneficiary_id=?",
        (new_id, approved_id),
    )
    conn.execute(
        "INSERT INTO boq_delivery(beneficiary_id,item_no,status,note,engineer_name,updated_at) "
        "SELECT ?,item_no,status,note,engineer_name,updated_at FROM boq_delivery WHERE beneficiary_id=?",
        (new_id, approved_id),
    )

    # Photos: link, never move or duplicate -- inherit everything the old revision could see.
    now = 5000
    conn.execute(
        "INSERT OR IGNORE INTO revision_photo_links(beneficiary_id,photo_id,linked_at) "
        "SELECT ?,_id,? FROM beneficiary_photos WHERE beneficiary_id=?",
        (new_id, now, approved_id),
    )
    conn.execute(
        "INSERT OR IGNORE INTO revision_photo_links(beneficiary_id,photo_id,linked_at) "
        "SELECT ?,photo_id,? FROM revision_photo_links WHERE beneficiary_id=?",
        (new_id, now, approved_id),
    )

    conn.execute("UPDATE beneficiaries SET study_status='superseded',updated_at=1 WHERE _id=?", (approved_id,))
    conn.commit()
    return new_id


def test_state_machine_path_and_bypass_rejection():
    print("=== Test 1: state machine only advances draft->ready->office_review->approved ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn, _, _ = build_v9_database(os.path.join(tmp, "yarmouk_field_v9.db"))

        assert not submit_for_office_review(conn, 1), "submitForOfficeReview must not work from draft"
        assert not approve_study(conn, 1), "approveStudy must not work from draft"
        assert conn.execute("SELECT study_status FROM beneficiaries WHERE _id=1").fetchone()[0] == "draft"

        assert mark_study_ready(conn, 1)
        assert conn.execute("SELECT study_status FROM beneficiaries WHERE _id=1").fetchone()[0] == "ready"
        assert not approve_study(conn, 1), "approveStudy must not work directly from ready (bypasses office review)"

        assert submit_for_office_review(conn, 1)
        assert conn.execute("SELECT study_status FROM beneficiaries WHERE _id=1").fetchone()[0] == "office_review"
        assert approve_study(conn, 1)
        assert conn.execute("SELECT study_status FROM beneficiaries WHERE _id=1").fetchone()[0] == "approved"
        print("OK: only draft->ready->office_review->approved is reachable; every bypass attempt was rejected.\n")
        conn.close()


def test_corrected_revision_identity_and_photo_linking():
    print("=== Test 2: corrected revision keeps study_uuid, links (not copies/moves) photos, copies quantities/BOQ ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn, study_uuid, old_revision_uuid = build_v9_database(os.path.join(tmp, "yarmouk_field_v9.db"))
        mark_study_ready(conn, 1); submit_for_office_review(conn, 1); approve_study(conn, 1)

        photos_before = photos_visible_to(conn, 1)
        assert len(photos_before) == 3

        new_id = create_corrected_revision(conn, 1, "م. لينا")

        old = dict(zip([d[0] for d in conn.execute("SELECT * FROM beneficiaries LIMIT 0").description],
                        conn.execute("SELECT * FROM beneficiaries WHERE _id=1").fetchone()))
        new = dict(zip([d[0] for d in conn.execute("SELECT * FROM beneficiaries LIMIT 0").description],
                        conn.execute("SELECT * FROM beneficiaries WHERE _id=?", (new_id,)).fetchone()))

        assert old["study_status"] == "superseded"
        assert new["study_status"] == "draft"
        assert new["study_uuid"] == study_uuid == old["study_uuid"]
        assert new["revision_uuid"] != old_revision_uuid
        assert new["revision_no"] == 2
        assert new["previous_revision_uuid"] == old_revision_uuid

        # Photo ownership: still owned by the OLD row (never moved).
        directly_owned_by_old = conn.execute("SELECT COUNT(*) FROM beneficiary_photos WHERE beneficiary_id=1").fetchone()[0]
        directly_owned_by_new = conn.execute("SELECT COUNT(*) FROM beneficiary_photos WHERE beneficiary_id=?", (new_id,)).fetchone()[0]
        assert directly_owned_by_old == 3, "Photos must stay owned by the revision that captured them, not be moved"
        assert directly_owned_by_new == 0, "No photo row should be duplicated onto the new revision"
        total_photo_rows = conn.execute("SELECT COUNT(*) FROM beneficiary_photos").fetchone()[0]
        assert total_photo_rows == 3, "No photo row should ever be duplicated by a correction"

        # But the new revision can still SEE all 3 via the link table.
        assert sorted(u for _, u, _ in photos_visible_to(conn, new_id)) == sorted(u for _, u, _ in photos_before)

        # Quantities/BOQ: independent copies exist on the new revision.
        qty_new = conn.execute("SELECT item_no,quantity,unit_price FROM beneficiary_quantities WHERE beneficiary_id=?", (new_id,)).fetchall()
        qty_old = conn.execute("SELECT item_no,quantity,unit_price FROM beneficiary_quantities WHERE beneficiary_id=1").fetchall()
        assert sorted(qty_new) == sorted(qty_old) == [(1, 3, 50), (7, 3, 50), (19, 3, 50)]
        boq_new = conn.execute("SELECT COUNT(*) FROM boq_delivery WHERE beneficiary_id=?", (new_id,)).fetchone()[0]
        assert boq_new == 1

        print(f"OK: new revision _id={new_id} shares study_uuid={study_uuid[:8]}..., revision_no=2; "
              f"photos stayed owned by _id=1 and are only linked (not duplicated) to the new revision; "
              f"quantities/BOQ were independently copied.\n")
        conn.close()


def test_editing_new_revision_does_not_mutate_old_snapshot():
    print("=== Test 3: EXACT requested scenario -- approve rev1, create rev2, edit rev2's quantity/photo/BOQ, rev1 still shows the old snapshot ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn, study_uuid, rev1_uuid = build_v9_database(os.path.join(tmp, "yarmouk_field_v9.db"))
        mark_study_ready(conn, 1); submit_for_office_review(conn, 1); approve_study(conn, 1)

        rev1_quantities_at_approval = sorted(conn.execute(
            "SELECT item_no,quantity,unit_price FROM beneficiary_quantities WHERE beneficiary_id=1").fetchall())
        rev1_photo_uris_at_approval = sorted(u for _, u, _ in photos_visible_to(conn, 1))
        rev1_boq_at_approval = sorted(conn.execute(
            "SELECT item_no,status FROM boq_delivery WHERE beneficiary_id=1").fetchall())

        rev2_id = create_corrected_revision(conn, 1, "م. لينا")

        # --- Edit revision 2's quantity: change item 1 from qty 3 to qty 9. ---
        conn.execute("UPDATE beneficiary_quantities SET quantity=9 WHERE beneficiary_id=? AND item_no=1", (rev2_id,))
        # --- Add a brand-new photo captured under revision 2 only. ---
        conn.execute(
            "INSERT INTO beneficiary_photos(beneficiary_id,phase,uri,file_name,captured_at,note) VALUES (?,?,?,?,?,'')",
            (rev2_id, "before", "content://photos/2_before_new.jpg", "2_before_new.jpg", 6000),
        )
        # --- Flip revision 2's BOQ delivery status for item 1. ---
        conn.execute("UPDATE boq_delivery SET status='not_delivered' WHERE beneficiary_id=? AND item_no=1", (rev2_id,))
        conn.commit()

        # Revision 1 (superseded) must show EXACTLY what it showed at approval time.
        rev1_quantities_now = sorted(conn.execute(
            "SELECT item_no,quantity,unit_price FROM beneficiary_quantities WHERE beneficiary_id=1").fetchall())
        rev1_photo_uris_now = sorted(u for _, u, _ in photos_visible_to(conn, 1))
        rev1_boq_now = sorted(conn.execute(
            "SELECT item_no,status FROM boq_delivery WHERE beneficiary_id=1").fetchall())
        rev1_status_now = conn.execute("SELECT study_status FROM beneficiaries WHERE _id=1").fetchone()[0]

        assert rev1_quantities_now == rev1_quantities_at_approval, \
            f"Revision 1's Snapshot must be untouched by revision 2's edits! {rev1_quantities_at_approval} -> {rev1_quantities_now}"
        assert rev1_boq_now == rev1_boq_at_approval, \
            f"Revision 1's BOQ Snapshot must be untouched! {rev1_boq_at_approval} -> {rev1_boq_now}"
        assert rev1_photo_uris_now == rev1_photo_uris_at_approval, \
            f"Revision 1 must not gain the new photo captured under revision 2! {rev1_photo_uris_at_approval} -> {rev1_photo_uris_now}"
        assert "content://photos/2_before_new.jpg" not in rev1_photo_uris_now
        assert rev1_status_now == "superseded", "Revision 1 must remain marked as a read-only superseded Snapshot"

        # Revision 2 must show its own edited values, PLUS the inherited old photos.
        rev2_quantities = sorted(conn.execute(
            "SELECT item_no,quantity,unit_price FROM beneficiary_quantities WHERE beneficiary_id=?", (rev2_id,)).fetchall())
        rev2_photo_uris = sorted(u for _, u, _ in photos_visible_to(conn, rev2_id))
        assert (1, 9, 50) in rev2_quantities, "Revision 2's edit to item 1 must be visible on revision 2"
        assert "content://photos/2_before_new.jpg" in rev2_photo_uris, "Revision 2's own new photo must be visible on revision 2"
        assert set(rev1_photo_uris_at_approval).issubset(set(rev2_photo_uris)), "Revision 2 must still see all photos inherited from revision 1"

        print("OK: after editing revision 2's quantity/photo/BOQ, opening revision 1 shows exactly the quantities, "
              "BOQ statuses and photo set it had at the moment it was approved -- fully reviewable, not just a text row.\n")
        conn.close()


def test_superseded_row_never_leaks_into_current_work():
    print("=== Test 4: superseded old revision never appears in ready/approved counts or batch promotion ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn, _, _ = build_v9_database(os.path.join(tmp, "yarmouk_field_v9.db"))
        mark_study_ready(conn, 1); submit_for_office_review(conn, 1); approve_study(conn, 1)
        assert study_ready_count(conn, 1) == 1

        new_id = create_corrected_revision(conn, 1, "م. لينا")

        still_present = conn.execute("SELECT study_status FROM beneficiaries WHERE _id=1").fetchone()
        assert still_present is not None, "Old revision row must NOT be deleted from the database"
        assert still_present[0] == "superseded"
        assert study_ready_count(conn, 1) == 0

        moved = promote_ready_batch(conn, 1, "دفعة 2026-08-10")
        assert moved == 0
        assert conn.execute("SELECT workflow_stage FROM beneficiaries WHERE _id=1").fetchone()[0] == "study"

        mark_study_ready(conn, new_id); submit_for_office_review(conn, new_id); approve_study(conn, new_id)
        assert study_ready_count(conn, 1) == 1
        moved = promote_ready_batch(conn, 1, "دفعة 2026-08-10")
        assert moved == 1
        promoted_ids = {r[0] for r in conn.execute("SELECT _id FROM beneficiaries WHERE workflow_stage='followup'")}
        assert promoted_ids == {new_id}
        assert conn.execute("SELECT workflow_stage FROM beneficiaries WHERE _id=1").fetchone()[0] == "study"

        print(f"OK: superseded row _id=1 stayed in the database, never counted as ready/approved, and was never "
              f"promoted; only the new approved revision (_id={new_id}) was promoted.\n")
        conn.close()


if __name__ == "__main__":
    try:
        test_state_machine_path_and_bypass_rejection()
        test_corrected_revision_identity_and_photo_linking()
        test_editing_new_revision_does_not_mutate_old_snapshot()
        test_superseded_row_never_leaks_into_current_work()
        print("STUDY_LIFECYCLE_V8_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
