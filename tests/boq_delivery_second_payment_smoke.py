# -*- coding: utf-8 -*-
"""Phase 4 smoke test: per-item BOQ delivery status, the 35-day (5-week)
first-stage countdown, and manual second-payment approval/revocation, run
against a real v10-schema database and mirroring AppDatabase.java's Phase 4
methods exactly (boqDeliveryStatus, updateBoqDeliveryStatus,
confirmDeliveryStart, computeSecondPaymentSummary, approveSecondPayment,
revokeSecondPayment, secondPaymentReadyList).

Exact scenario requested: deliver BOQ items -> confirm delivery start ->
35-day counter begins -> change item statuses -> during-repair photos exist
-> approve for second payment -> beneficiary appears in the "ready for
second payment" list -> revoke approval -> the approval's trace survives.
Also verifies: closing/reopening (re-reading from the DB, simulating an app
restart) preserves every date/status, and pressing "confirm delivery start"
twice never resets the original date.
"""
import sqlite3
import sys
import tempfile
import os
import time

V10_SCHEMA = [
    "CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)",
    "CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,sequence_no INTEGER DEFAULT 0,"
    "name TEXT,workflow_stage TEXT DEFAULT 'study',study_status TEXT DEFAULT 'draft',followup_batch TEXT DEFAULT '',"
    "work_delivered INTEGER DEFAULT 0,engineer_name TEXT DEFAULT '',updated_at INTEGER NOT NULL,"
    "delivery_confirmed_at INTEGER DEFAULT 0,second_payment_ready INTEGER DEFAULT 0,"
    "second_payment_approved_by TEXT DEFAULT '',second_payment_approved_at INTEGER DEFAULT 0,"
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
    "CREATE TABLE second_payment_log (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "action TEXT NOT NULL,engineer_name TEXT DEFAULT '',at INTEGER NOT NULL,reason TEXT DEFAULT '',"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)",
]

DAY_MS = 24 * 3600 * 1000
BOQ_NOT_DELIVERED, BOQ_DELIVERED, BOQ_IN_PROGRESS, BOQ_READY_FOR_PICKUP, BOQ_RECEIVED = \
    "not_delivered", "delivered", "in_progress", "ready_for_pickup", "received"


def build_db(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys=ON")
    for stmt in V10_SCHEMA:
        conn.execute(stmt)
    conn.execute("INSERT INTO projects(_id,name,created_at) VALUES (1,'مشروع مخيم اليرموك',1000)")
    conn.execute(
        "INSERT INTO beneficiaries(_id,project_id,sequence_no,name,workflow_stage,study_status,followup_batch,engineer_name,updated_at) "
        "VALUES (1,1,1,'أحمد حسين دياب','followup','approved','دفعة 2026-08-01','م. سامر',1000)"
    )
    for item_no, qty, price in ((1, 3, 50), (7, 2, 80), (19, 5, 20)):
        conn.execute("INSERT INTO beneficiary_quantities(beneficiary_id,item_no,quantity,unit_price) VALUES (1,?,?,?)", (item_no, qty, price))
    conn.commit()
    return conn


def boq_delivery_status(conn, bid):
    sql = ("SELECT q.item_no,q.quantity,q.unit_price,d.status,d.note,d.engineer_name,d.updated_at "
           "FROM beneficiary_quantities q LEFT JOIN boq_delivery d ON d.beneficiary_id=q.beneficiary_id AND d.item_no=q.item_no "
           "WHERE q.beneficiary_id=? AND q.quantity>0 ORDER BY q.item_no")
    rows = []
    for item_no, qty, price, status, note, engineer, updated_at in conn.execute(sql, (bid,)):
        rows.append({
            "item_no": item_no, "quantity": qty, "unit_price": price,
            "status": status or BOQ_NOT_DELIVERED, "note": note or "", "engineer": engineer or "", "updated_at": updated_at or 0,
        })
    return rows


def update_boq_status(conn, bid, item_no, status, note, engineer, now):
    conn.execute(
        "INSERT INTO boq_delivery(beneficiary_id,item_no,status,note,engineer_name,updated_at) VALUES (?,?,?,?,?,?) "
        "ON CONFLICT(beneficiary_id,item_no) DO UPDATE SET status=excluded.status,note=excluded.note,engineer_name=excluded.engineer_name,updated_at=excluded.updated_at",
        (bid, item_no, status, note, engineer, now),
    )
    conn.commit()


def confirm_delivery_start(conn, bid, now):
    cur = conn.execute(
        "UPDATE beneficiaries SET delivery_confirmed_at=?,updated_at=? WHERE _id=? AND (delivery_confirmed_at IS NULL OR delivery_confirmed_at=0)",
        (now, now, bid),
    )
    conn.commit()
    return cur.rowcount > 0


def compute_second_payment_summary(conn, bid):
    entries = boq_delivery_status(conn, bid)
    delivered = sum(1 for e in entries if e["status"] in (BOQ_DELIVERED, BOQ_IN_PROGRESS, BOQ_READY_FOR_PICKUP))
    received = sum(1 for e in entries if e["status"] == BOQ_RECEIVED)
    pending = len(entries) - delivered - received
    during_photos = conn.execute("SELECT COUNT(*) FROM beneficiary_photos WHERE beneficiary_id=? AND phase='during'", (bid,)).fetchone()[0]
    total = sum(e["quantity"] * e["unit_price"] for e in entries)
    first_payment = round(round(total) * 0.6)
    second_payment = round(total) - first_payment
    return {
        "total_items": len(entries), "delivered": delivered, "received": received, "pending": pending,
        "during_photos": during_photos, "total_amount": total, "first_payment": first_payment, "second_payment": second_payment,
    }


def approve_second_payment(conn, bid, engineer, now):
    conn.execute(
        "UPDATE beneficiaries SET second_payment_ready=1,second_payment_approved_by=?,second_payment_approved_at=?,updated_at=? WHERE _id=?",
        (engineer, now, now, bid),
    )
    conn.execute("INSERT INTO second_payment_log(beneficiary_id,action,engineer_name,at,reason) VALUES (?,?,?,?,?)", (bid, "approved", engineer, now, ""))
    conn.commit()


def revoke_second_payment(conn, bid, engineer, reason, now):
    conn.execute("UPDATE beneficiaries SET second_payment_ready=0,updated_at=? WHERE _id=?", (now, bid))
    conn.execute("INSERT INTO second_payment_log(beneficiary_id,action,engineer_name,at,reason) VALUES (?,?,?,?,?)", (bid, "revoked", engineer, now, reason))
    conn.commit()


def second_payment_ready_list(conn, project_id):
    return conn.execute(
        "SELECT _id,name FROM beneficiaries WHERE project_id=? AND workflow_stage='followup' AND second_payment_ready=1", (project_id,)
    ).fetchall()


def test_full_requested_scenario():
    print("=== Phase 4: full requested scenario on a multi-item BOQ beneficiary ===")
    with tempfile.TemporaryDirectory() as tmp:
        db_path = os.path.join(tmp, "yarmouk_field_v10.db")
        conn = build_db(db_path)
        bid = 1
        t0 = 1_700_000_000_000  # fixed base "now" so the test is deterministic

        # 1. Items start as not_delivered by construction (no boq_delivery rows yet).
        entries = boq_delivery_status(conn, bid)
        assert len(entries) == 3
        assert all(e["status"] == BOQ_NOT_DELIVERED for e in entries)

        # 2. Deliver the items to the worker.
        update_boq_status(conn, bid, 1, BOQ_DELIVERED, "", "م. سامر", t0)
        update_boq_status(conn, bid, 7, BOQ_DELIVERED, "بحاجة سقالة", "م. سامر", t0 + 10)
        update_boq_status(conn, bid, 19, BOQ_IN_PROGRESS, "", "م. سامر", t0 + 20)
        entries = boq_delivery_status(conn, bid)
        by_item = {e["item_no"]: e for e in entries}
        assert by_item[1]["status"] == BOQ_DELIVERED and by_item[1]["engineer"] == "م. سامر"
        assert by_item[7]["note"] == "بحاجة سقالة"

        # 3. Confirm delivery start -> begins the 35-day counter.
        assert confirm_delivery_start(conn, bid, t0 + 100)
        confirmed_at = conn.execute("SELECT delivery_confirmed_at FROM beneficiaries WHERE _id=?", (bid,)).fetchone()[0]
        assert confirmed_at == t0 + 100
        target = confirmed_at + 35 * DAY_MS
        assert target == confirmed_at + 35 * 24 * 3600 * 1000

        # Pressing "confirm delivery start" again must NOT reset the original date.
        assert not confirm_delivery_start(conn, bid, t0 + 999999), "Second press must be a no-op"
        assert conn.execute("SELECT delivery_confirmed_at FROM beneficiaries WHERE _id=?", (bid,)).fetchone()[0] == confirmed_at, \
            "Original delivery_confirmed_at must survive a repeated confirm press"

        # 4. Change item statuses further, including reaching 'received'.
        update_boq_status(conn, bid, 1, BOQ_RECEIVED, "", "م. سامر", t0 + 200)
        update_boq_status(conn, bid, 19, BOQ_READY_FOR_PICKUP, "", "م. سامر", t0 + 210)

        # 5. During-repair photos exist.
        conn.execute("INSERT INTO beneficiary_photos(beneficiary_id,phase,uri,file_name,captured_at) VALUES (?,?,?,?,?)",
                     (bid, "during", "content://photos/1_during_1.jpg", "1_during_1.jpg", t0 + 250))
        conn.execute("INSERT INTO beneficiary_photos(beneficiary_id,phase,uri,file_name,captured_at) VALUES (?,?,?,?,?)",
                     (bid, "during", "content://photos/1_during_2.jpg", "1_during_2.jpg", t0 + 260))
        conn.commit()

        # 6. Compute the summary an engineer would see before approving.
        summary = compute_second_payment_summary(conn, bid)
        assert summary["total_items"] == 3
        assert summary["received"] == 1  # item 1
        assert summary["delivered"] == 2  # item 7 (delivered), item 19 (ready_for_pickup)
        assert summary["pending"] == 0
        assert summary["during_photos"] == 2
        assert summary["total_amount"] == 3 * 50 + 2 * 80 + 5 * 20  # 150+160+100=410
        assert summary["first_payment"] + summary["second_payment"] == round(summary["total_amount"])

        # 7. Approve for second payment.
        approve_second_payment(conn, bid, "م. سامر", t0 + 300)
        row = conn.execute("SELECT second_payment_ready,second_payment_approved_by,second_payment_approved_at FROM beneficiaries WHERE _id=?", (bid,)).fetchone()
        assert row == (1, "م. سامر", t0 + 300)

        # 8. Beneficiary appears in "ready for second payment" list.
        ready = second_payment_ready_list(conn, 1)
        assert ready == [(bid, "أحمد حسين دياب")]

        # 9. Revoke approval.
        revoke_second_payment(conn, bid, "م. لينا", "نقص في توثيق بند 19", t0 + 400)
        row = conn.execute("SELECT second_payment_ready,second_payment_approved_by,second_payment_approved_at FROM beneficiaries WHERE _id=?", (bid,)).fetchone()
        assert row[0] == 0, "Ready flag must flip off after revoke"
        assert row[1] == "م. سامر" and row[2] == t0 + 300, \
            "Revoke must NOT erase the original approval's trace (approved_by/approved_at stay as history)"
        assert second_payment_ready_list(conn, 1) == [], "Revoked beneficiary must disappear from the ready list"

        # 10. Full audit trail (approve + revoke) must both still be present, in order.
        log = conn.execute("SELECT action,engineer_name,reason,at FROM second_payment_log WHERE beneficiary_id=? ORDER BY at", (bid,)).fetchall()
        assert log == [
            ("approved", "م. سامر", "", t0 + 300),
            ("revoked", "م. لينا", "نقص في توثيق بند 19", t0 + 400),
        ], f"Audit trail must keep both events untouched: {log}"

        print("OK: delivered items -> confirmed delivery start (35-day counter) -> repeated confirm press was a "
              "no-op -> statuses changed -> during-photos counted -> approved for second payment -> appeared in "
              "the ready list -> revoked -> disappeared from the ready list while the approval's trace and the "
              "full approve/revoke audit log both survived intact.\n")
        conn.close()


def test_state_survives_close_and_reopen():
    print("=== Test: closing and reopening the DB (simulated app restart) preserves every date/status ===")
    with tempfile.TemporaryDirectory() as tmp:
        db_path = os.path.join(tmp, "yarmouk_field_v10.db")
        conn = build_db(db_path)
        bid = 1
        t0 = 1_700_000_000_000
        update_boq_status(conn, bid, 1, BOQ_DELIVERED, "ملاحظة قبل الإغلاق", "م. سامر", t0)
        confirm_delivery_start(conn, bid, t0 + 50)
        approve_second_payment(conn, bid, "م. سامر", t0 + 60)
        conn.close()  # simulate closing the app

        reopened = sqlite3.connect(db_path)  # simulate reopening it
        entries = boq_delivery_status(reopened, bid)
        assert entries[0]["status"] == BOQ_DELIVERED and entries[0]["note"] == "ملاحظة قبل الإغلاق"
        row = reopened.execute("SELECT delivery_confirmed_at,second_payment_ready,second_payment_approved_by FROM beneficiaries WHERE _id=?", (bid,)).fetchone()
        assert row == (t0 + 50, 1, "م. سامر")

        # And a repeated confirm press after reopening must still be a no-op against the persisted date.
        assert not confirm_delivery_start(reopened, bid, t0 + 999999)
        assert reopened.execute("SELECT delivery_confirmed_at FROM beneficiaries WHERE _id=?", (bid,)).fetchone()[0] == t0 + 50

        print("OK: BOQ status/note, delivery_confirmed_at and second-payment approval all survived a close+reopen, "
              "and the double-press guard still holds after reopening.\n")
        reopened.close()


if __name__ == "__main__":
    try:
        test_full_requested_scenario()
        test_state_survives_close_and_reopen()
        print("BOQ_DELIVERY_SECOND_PAYMENT_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
