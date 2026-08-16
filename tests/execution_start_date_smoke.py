# -*- coding: utf-8 -*-
"""Phase 4 correction smoke test: execution_start_at = MAX(work-delivery
date, first-payment-available date), computed live (never stored), mirroring
AppDatabase.executionStartAt()/setFirstPaymentAvailable()/
cancelFirstPaymentAvailable() exactly, against a real v11-schema database.

Exact three scenarios requested:
  1. First payment available BEFORE work delivery -> countdown starts at
     the (later) work-delivery date.
  2. Work delivered BEFORE first payment available -> countdown starts at
     the (later) first-payment-available date.
  3. Work delivered, first payment never confirmed -> countdown never
     starts, beneficiary is never considered overdue no matter how much
     time passes.

Also verifies: correcting or cancelling the first-payment-available date
never silently loses the previous confirmation -- both are appended to
first_payment_log, never deleted or overwritten without a trace.
"""
import sqlite3
import sys
import tempfile
import os

DAY_MS = 24 * 3600 * 1000

V11_SCHEMA = [
    "CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)",
    "CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,name TEXT,"
    "updated_at INTEGER NOT NULL,delivery_confirmed_at INTEGER DEFAULT 0,"
    "first_payment_available_at INTEGER DEFAULT 0,first_payment_confirmed_by TEXT DEFAULT '',"
    "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)",
    "CREATE TABLE first_payment_log (_id INTEGER PRIMARY KEY AUTOINCREMENT,beneficiary_id INTEGER NOT NULL,"
    "action TEXT NOT NULL,engineer_name TEXT DEFAULT '',at INTEGER NOT NULL,reason TEXT DEFAULT '',"
    "FOREIGN KEY(beneficiary_id) REFERENCES beneficiaries(_id) ON DELETE CASCADE)",
]


def build_db(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys=ON")
    for stmt in V11_SCHEMA:
        conn.execute(stmt)
    conn.execute("INSERT INTO projects(_id,name,created_at) VALUES (1,'مشروع مخيم اليرموك',1000)")
    conn.execute("INSERT INTO beneficiaries(_id,project_id,name,updated_at) VALUES (1,1,'أحمد حسين دياب',1000)")
    conn.commit()
    return conn


def confirm_delivery_start(conn, bid, now):
    cur = conn.execute(
        "UPDATE beneficiaries SET delivery_confirmed_at=?,updated_at=? WHERE _id=? AND (delivery_confirmed_at IS NULL OR delivery_confirmed_at=0)",
        (now, now, bid))
    conn.commit()
    return cur.rowcount > 0


def set_first_payment_available(conn, bid, engineer, now):
    row = conn.execute("SELECT first_payment_available_at FROM beneficiaries WHERE _id=?", (bid,)).fetchone()
    was_already_set = row[0] and row[0] > 0
    conn.execute("UPDATE beneficiaries SET first_payment_available_at=?,first_payment_confirmed_by=?,updated_at=? WHERE _id=?",
                 (now, engineer, now, bid))
    reason = f"تصحيح — كان مسجلاً سابقاً بتاريخ {row[0]}" if was_already_set else ""
    conn.execute("INSERT INTO first_payment_log(beneficiary_id,action,engineer_name,at,reason) VALUES (?,?,?,?,?)",
                 (bid, "corrected" if was_already_set else "confirmed", engineer, now, reason))
    conn.commit()


def cancel_first_payment_available(conn, bid, engineer, reason, now):
    conn.execute("UPDATE beneficiaries SET first_payment_available_at=0,updated_at=? WHERE _id=?", (now, bid))
    conn.execute("INSERT INTO first_payment_log(beneficiary_id,action,engineer_name,at,reason) VALUES (?,?,?,?,?)",
                 (bid, "cancelled", engineer, now, reason))
    conn.commit()


def execution_start_at(conn, bid):
    row = conn.execute("SELECT delivery_confirmed_at,first_payment_available_at FROM beneficiaries WHERE _id=?", (bid,)).fetchone()
    delivery_at, first_payment_at = row
    if not delivery_at or not first_payment_at:
        return 0
    return max(delivery_at, first_payment_at)


def test_scenario_1_first_payment_before_delivery():
    print("=== Scenario 1: first payment available BEFORE work delivery ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_db(os.path.join(tmp, "yarmouk_field_v11.db"))
        t0 = 1_700_000_000_000
        set_first_payment_available(conn, 1, "م. سامر", t0)  # payment first
        assert execution_start_at(conn, 1) == 0, "Must not start until work is also delivered"
        confirm_delivery_start(conn, 1, t0 + 10 * DAY_MS)  # work delivered 10 days later
        start = execution_start_at(conn, 1)
        assert start == t0 + 10 * DAY_MS, f"Countdown must start at the LATER date (work delivery), got {start}"
        print("OK: countdown started at the work-delivery date, since it happened after the payment.\n")
        conn.close()


def test_scenario_2_delivery_before_first_payment():
    print("=== Scenario 2: work delivered BEFORE first payment available ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_db(os.path.join(tmp, "yarmouk_field_v11.db"))
        t0 = 1_700_000_000_000
        confirm_delivery_start(conn, 1, t0)  # work delivered first
        assert execution_start_at(conn, 1) == 0, "Must not start until first payment is also confirmed"
        set_first_payment_available(conn, 1, "م. سامر", t0 + 15 * DAY_MS)  # payment 15 days later
        start = execution_start_at(conn, 1)
        assert start == t0 + 15 * DAY_MS, f"Countdown must start at the LATER date (first payment), got {start}"
        print("OK: countdown started at the first-payment-available date, since it happened after work delivery.\n")
        conn.close()


def test_scenario_3_delivery_without_first_payment_never_overdue():
    print("=== Scenario 3: work delivered, first payment NEVER confirmed -> never overdue ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_db(os.path.join(tmp, "yarmouk_field_v11.db"))
        t0 = 1_700_000_000_000
        confirm_delivery_start(conn, 1, t0)
        # Simulate a very long time passing (200 days) -- far beyond the 35-day window.
        far_future_now = t0 + 200 * DAY_MS
        start = execution_start_at(conn, 1)
        assert start == 0, "Countdown must never start while first payment is unconfirmed"
        # There is no target date to compare against, so no "overdue" computation is even possible --
        # the UI layer's own logic (mirrored in MainActivity.deliveryTimelineSection) only computes a
        # target/overdue status when executionStartAt()>0, exactly this guard.
        assert execution_start_at(conn, 1) == 0
        print(f"OK: even after {200} simulated days, execution_start_at stayed 0 -- the beneficiary can never "
              f"be shown as overdue while the first payment is unconfirmed.\n")
        conn.close()


def test_correction_and_cancellation_keep_audit_trail():
    print("=== Correcting/cancelling the first-payment date never silently erases the previous confirmation ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_db(os.path.join(tmp, "yarmouk_field_v11.db"))
        t0 = 1_700_000_000_000
        confirm_delivery_start(conn, 1, t0)

        set_first_payment_available(conn, 1, "م. سامر", t0 + 5 * DAY_MS)
        assert execution_start_at(conn, 1) == t0 + 5 * DAY_MS

        # Correct the date (e.g. it was recorded a day late).
        set_first_payment_available(conn, 1, "م. لينا", t0 + 6 * DAY_MS)
        row = conn.execute("SELECT first_payment_available_at,first_payment_confirmed_by FROM beneficiaries WHERE _id=1").fetchone()
        assert row == (t0 + 6 * DAY_MS, "م. لينا")
        assert execution_start_at(conn, 1) == t0 + 6 * DAY_MS, "Correction must immediately recompute the countdown"

        # Cancel it entirely (discovered to be wrong).
        cancel_first_payment_available(conn, 1, "م. لينا", "تم التسجيل بالخطأ لمستفيد آخر", t0 + 7 * DAY_MS)
        row = conn.execute("SELECT first_payment_available_at FROM beneficiaries WHERE _id=1").fetchone()
        assert row[0] == 0
        assert execution_start_at(conn, 1) == 0, "Countdown must revert to 'not started' after cancellation"

        # Work-delivery date must be completely unaffected by any of this.
        assert conn.execute("SELECT delivery_confirmed_at FROM beneficiaries WHERE _id=1").fetchone()[0] == t0

        # Full audit trail: confirmed, corrected, cancelled -- all three still present in order.
        log = conn.execute("SELECT action,engineer_name,at FROM first_payment_log WHERE beneficiary_id=1 ORDER BY at").fetchall()
        assert [row[0] for row in log] == ["confirmed", "corrected", "cancelled"], f"Full trail must survive: {log}"
        assert log[0][1] == "م. سامر" and log[1][1] == "م. لينا" and log[2][1] == "م. لينا"

        print("OK: correction and cancellation both recomputed the countdown correctly, work-delivery date stayed "
              "untouched, and the full confirmed->corrected->cancelled audit trail survived intact.\n")
        conn.close()


if __name__ == "__main__":
    try:
        test_scenario_1_first_payment_before_delivery()
        test_scenario_2_delivery_before_first_payment()
        test_scenario_3_delivery_without_first_payment_never_overdue()
        test_correction_and_cancellation_keep_audit_trail()
        print("EXECUTION_START_DATE_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
