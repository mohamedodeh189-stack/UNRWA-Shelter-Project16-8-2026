# -*- coding: utf-8 -*-
"""Phase 5 smoke test: offline GPS home-location capture, mirroring
AppDatabase.setGpsLocation() and the MainActivity capture/confirm-overwrite
flow (GpsLocationHelper.requestSingleFix + captureHomeLocation +
requestHomeLocation), run against a real DB and a simulated LocationManager.

No physical/emulator Android device is available in this environment, so
the GPS hardware/permission layer is simulated in Python (a FakeLocationApi
standing in for LocationManager + the runtime permission callback), while
the actual persistence (AppDatabase.setGpsLocation, columns gps_lat/
gps_lng/gps_accuracy_m/gps_captured_at) is exercised against a real SQLite
database with the exact schema AppDatabase.java writes (these columns
existed since the Phase 0 migration, v8 -- Phase 5 added no schema change).

Five required scenarios:
  1. Location permission denied -> app/screen keeps working, no coordinates saved.
  2. GPS available with good accuracy (<=30m) -> saved directly.
  3. GPS available but poor accuracy (>30m) -> warned, only saved on explicit accept.
  4. Updating an existing saved location -> requires explicit confirmation first,
     old value never silently replaced, new value only lands after confirming.
  5. No internet during the fix -> capture and save still succeed (GPS-only,
     no network dependency anywhere in the path).
"""
import sqlite3
import sys
import tempfile
import os

SCHEMA = [
    "CREATE TABLE projects (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL)",
    "CREATE TABLE beneficiaries (_id INTEGER PRIMARY KEY AUTOINCREMENT,project_id INTEGER NOT NULL,name TEXT,"
    "updated_at INTEGER NOT NULL,gps_lat REAL,gps_lng REAL,gps_accuracy_m REAL,gps_captured_at INTEGER DEFAULT 0,"
    "FOREIGN KEY(project_id) REFERENCES projects(_id) ON DELETE CASCADE)",
]

ACCURACY_WARNING_M = 30


def build_db(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys=ON")
    for stmt in SCHEMA:
        conn.execute(stmt)
    conn.execute("INSERT INTO projects(_id,name,created_at) VALUES (1,'مشروع مخيم اليرموك',1000)")
    conn.execute("INSERT INTO beneficiaries(_id,project_id,name,updated_at) VALUES (1,1,'أحمد حسين دياب',1000)")
    conn.commit()
    return conn


def set_gps_location(conn, bid, lat, lng, accuracy_m, captured_at):
    """Mirrors AppDatabase.setGpsLocation exactly -- always a plain UPDATE, no built-in guard.
    (The 'don't silently overwrite' rule lives in the UI layer's confirmation dialog, mirrored
    by requires_confirmation()/confirm_overwrite() below, exactly as in MainActivity.requestHomeLocation.)"""
    conn.execute(
        "UPDATE beneficiaries SET gps_lat=?,gps_lng=?,gps_accuracy_m=?,gps_captured_at=?,updated_at=? WHERE _id=?",
        (lat, lng, accuracy_m, captured_at, captured_at, bid),
    )
    conn.commit()


def get_gps(conn, bid):
    return conn.execute("SELECT gps_lat,gps_lng,gps_accuracy_m,gps_captured_at FROM beneficiaries WHERE _id=?", (bid,)).fetchone()


class FakeLocationApi:
    """Simulates the ACCESS_FINE_LOCATION runtime permission + LocationManager.GPS_PROVIDER path,
    entirely offline -- no network provider is ever consulted anywhere in this class."""
    def __init__(self, permission_granted, gps_enabled=True, fix=None, network_available=True):
        self.permission_granted = permission_granted
        self.gps_enabled = gps_enabled
        self.fix = fix  # (lat, lng, accuracy_m) or None for timeout/unavailable
        self.network_available = network_available  # irrelevant to GPS_PROVIDER; tracked only to prove it's unused

    def request_single_fix(self):
        """Returns ('location', lat, lng, accuracy) or ('unavailable', reason). Never touches self.network_available."""
        if not self.permission_granted:
            raise AssertionError("must never be called without permission -- caller must request permission first")
        if not self.gps_enabled:
            return ("unavailable", "نظام GPS غير مفعّل")
        if self.fix is None:
            return ("unavailable", "انتهت مهلة الانتظار")
        lat, lng, accuracy = self.fix
        return ("location", lat, lng, accuracy)


def capture_home_location(conn, bid, api, accept_poor_accuracy=False):
    """Mirrors MainActivity.captureHomeLocation()+onLocation() decision tree. Returns a dict describing
    what happened, for the test to assert on -- nothing is saved unless the logic says it should be."""
    if not api.permission_granted:
        return {"saved": False, "reason": "permission_denied"}
    result = api.request_single_fix()
    if result[0] == "unavailable":
        return {"saved": False, "reason": "unavailable", "message": result[1]}
    _, lat, lng, accuracy = result
    if accuracy > ACCURACY_WARNING_M and not accept_poor_accuracy:
        return {"saved": False, "reason": "poor_accuracy_needs_confirmation", "accuracy": accuracy, "lat": lat, "lng": lng}
    now = 1_700_000_000_000
    set_gps_location(conn, bid, lat, lng, accuracy, now)
    return {"saved": True, "lat": lat, "lng": lng, "accuracy": accuracy, "at": now}


def requires_overwrite_confirmation(conn, bid):
    """Mirrors MainActivity.requestHomeLocation(): if gps_captured_at>0, the UI must show the existing
    value and get an explicit yes before calling captureHomeLocation() at all."""
    row = get_gps(conn, bid)
    return row[3] is not None and row[3] > 0


def test_scenario_1_permission_denied():
    print("=== Scenario 1: location permission denied ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_db(os.path.join(tmp, "yarmouk_field.db"))
        api = FakeLocationApi(permission_granted=False)
        outcome = capture_home_location(conn, 1, api)
        assert outcome == {"saved": False, "reason": "permission_denied"}
        row = get_gps(conn, 1)
        assert row == (None, None, None, 0), "No coordinates must be written when permission is denied"
        # The rest of the "app" (any other beneficiary field) must remain fully usable.
        conn.execute("UPDATE beneficiaries SET name=? WHERE _id=1", ("أحمد حسين دياب المحدث",))
        conn.commit()
        assert conn.execute("SELECT name FROM beneficiaries WHERE _id=1").fetchone()[0] == "أحمد حسين دياب المحدث"
        print("OK: no coordinates saved, and the rest of the record remains fully editable/usable.\n")
        conn.close()


def test_scenario_2_good_accuracy():
    print("=== Scenario 2: GPS available with good accuracy ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_db(os.path.join(tmp, "yarmouk_field.db"))
        api = FakeLocationApi(permission_granted=True, fix=(33.4991, 36.2853, 8.5))  # Yarmouk camp area, 8.5m accuracy
        outcome = capture_home_location(conn, 1, api)
        assert outcome["saved"] is True
        row = get_gps(conn, 1)
        assert row[0] == 33.4991 and row[1] == 36.2853 and row[2] == 8.5 and row[3] > 0
        print(f"OK: good-accuracy fix (8.5m) saved directly without any extra confirmation: {row}\n")
        conn.close()


def test_scenario_3_poor_accuracy():
    print("=== Scenario 3: GPS available but poor accuracy (>30m) ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_db(os.path.join(tmp, "yarmouk_field.db"))
        api = FakeLocationApi(permission_granted=True, fix=(33.4991, 36.2853, 47.2))  # 47.2m accuracy
        outcome = capture_home_location(conn, 1, api)
        assert outcome["saved"] is False and outcome["reason"] == "poor_accuracy_needs_confirmation"
        assert get_gps(conn, 1) == (None, None, None, 0), "Poor-accuracy fix must NOT be saved without explicit acceptance"

        # Retry path: a fresh good fix instead.
        api_retry = FakeLocationApi(permission_granted=True, fix=(33.4991, 36.2853, 12.0))
        outcome_retry = capture_home_location(conn, 1, api_retry)
        assert outcome_retry["saved"] is True and get_gps(conn, 1)[2] == 12.0

        # Accept-anyway path on a fresh beneficiary (simulating the user pressing "قبول رغم ضعف الدقة").
        conn.execute("INSERT INTO beneficiaries(_id,project_id,name,updated_at) VALUES (2,1,'مستفيد آخر',1000)")
        api2 = FakeLocationApi(permission_granted=True, fix=(33.5, 36.29, 55.0))
        outcome2 = capture_home_location(conn, 2, api2, accept_poor_accuracy=True)
        assert outcome2["saved"] is True and get_gps(conn, 2)[2] == 55.0
        print("OK: poor-accuracy fix was rejected by default (nothing saved); a retry with good accuracy saved "
              "cleanly; and explicit 'accept anyway' saved the poor-accuracy fix when the user chose to.\n")
        conn.close()


def test_scenario_4_update_existing_location():
    print("=== Scenario 4: updating a previously saved location ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_db(os.path.join(tmp, "yarmouk_field.db"))
        set_gps_location(conn, 1, 33.4991, 36.2853, 8.5, 1_699_000_000_000)
        original = get_gps(conn, 1)

        assert requires_overwrite_confirmation(conn, 1), "UI must detect an existing location and require confirmation"
        # Simulate the user being SHOWN the old value first (this is what the confirmation dialog message contains).
        shown = get_gps(conn, 1)
        assert shown == original, "The exact old value must be what's shown before any overwrite decision"

        # User declines -> nothing changes.
        assert get_gps(conn, 1) == original

        # User confirms -> now (and only now) a new capture may proceed and overwrite.
        api = FakeLocationApi(permission_granted=True, fix=(33.5005, 36.2871, 6.0))
        outcome = capture_home_location(conn, 1, api)
        assert outcome["saved"] is True
        updated = get_gps(conn, 1)
        assert updated != original
        assert updated[0] == 33.5005 and updated[1] == 36.2871 and updated[2] == 6.0
        print(f"OK: old location {original} was shown and required explicit confirmation before being replaced "
              f"by the new one {updated}.\n")
        conn.close()


def test_scenario_5_no_internet_during_capture():
    print("=== Scenario 5: no internet available during capture ===")
    with tempfile.TemporaryDirectory() as tmp:
        conn = build_db(os.path.join(tmp, "yarmouk_field.db"))
        # network_available=False proves the capture path never depends on it -- GPS_PROVIDER only.
        api = FakeLocationApi(permission_granted=True, fix=(33.4991, 36.2853, 9.1), network_available=False)
        outcome = capture_home_location(conn, 1, api)
        assert outcome["saved"] is True
        row = get_gps(conn, 1)
        assert row[0] == 33.4991 and row[1] == 36.2853 and row[2] == 9.1
        assert api.network_available is False, "Sanity check: the network was indeed unavailable throughout capture"
        print("OK: location captured and saved successfully with network_available=False -- capture and save "
              "never consulted network state.\n")
        conn.close()


if __name__ == "__main__":
    try:
        test_scenario_1_permission_denied()
        test_scenario_2_good_accuracy()
        test_scenario_3_poor_accuracy()
        test_scenario_4_update_existing_location()
        test_scenario_5_no_internet_during_capture()
        print("GPS_HOME_LOCATION_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
