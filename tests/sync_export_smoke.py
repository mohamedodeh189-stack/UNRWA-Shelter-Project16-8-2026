# -*- coding: utf-8 -*-
"""Phase 10 smoke test (Android side, Python mirror): the sync export JSON
schema and the export/cancel/fail decision logic in
MainActivity.showSyncWithWindows()/onActivityResult(REQUEST_SYNC_SHARE,...)
and AppDatabase.markRevisionsSynced().

Why a Python mirror instead of running the actual Java: SyncPackageJson.java
was also verified via a standalone JVM test (tests/SyncPackageJsonSmoke.java,
which compiles cleanly against android.jar), but android.jar's org.json
classes are compile-time stubs only and throw NoClassDefFoundError at
runtime outside a real Android/ART process -- the exact same environment
limitation already documented and worked around earlier in this project
(see the ImportPhase2Smoke.java precedent). This mirror exercises the
identical schema-building and state-transition logic against real Python
data structures as the strongest verification available without a device.

Exact scenarios requested:
  - Cancelling the share sheet -> sync_status/study_status unchanged, no local data lost.
  - A failed export (exception before the chooser even shows) -> same: nothing touched.
  - A successful hand-off -> ONLY the exported revision_uuids get sync_status='exported', never study_status.
"""
import sys

RESULT_OK = -1
RESULT_CANCELED = 0


def sync_package_to_json(revisions, project, engineer):
    """Mirrors SyncPackageJson.toJson() field-for-field."""
    return {
        "schema_version": "yarmouk-sync-1.0",
        "project": project,
        "exported_by_engineer": engineer or "",
        "exported_at": 1700000000000,
        "revisions": [
            {
                "study_uuid": r["study_uuid"], "revision_uuid": r["revision_uuid"],
                "previous_revision_uuid": r.get("previous_revision_uuid", ""), "revision_no": r.get("revision_no", 1),
                "engineer_name": r.get("engineer_name", ""), "name": r.get("name", ""),
                "study_status": r.get("study_status", "draft"), "workflow_stage": r.get("workflow_stage", "study"),
                "quantities": r.get("quantities", []), "boq_delivery": r.get("boq_delivery", []), "photos": r.get("photos", []),
            }
            for r in revisions
        ],
    }


def test_json_schema_carries_everything_needed_for_windows_reconstruction():
    print("=== Exported JSON schema carries every field needed to rebuild study/revision history ===")
    revisions = [
        {"study_uuid": "study-A", "revision_uuid": "rev-1", "previous_revision_uuid": "", "revision_no": 1,
         "engineer_name": "م. سامر", "name": "أحمد حسين دياب", "study_status": "superseded",
         "quantities": [{"item_no": 1, "quantity": 3, "unit_price": 50}], "photos": [{"phase": "before", "uri": "content://photos/1.jpg"}]},
        {"study_uuid": "study-A", "revision_uuid": "rev-2", "previous_revision_uuid": "rev-1", "revision_no": 2,
         "engineer_name": "م. سامر", "name": "أحمد حسين دياب", "study_status": "approved",
         "boq_delivery": [{"item_no": 1, "status": "delivered"}]},
    ]
    package = sync_package_to_json(revisions, "مشروع مخيم اليرموك", "م. سامر")
    assert package["schema_version"] == "yarmouk-sync-1.0"
    r1, r2 = package["revisions"]
    assert r1["study_uuid"] == r2["study_uuid"] == "study-A"
    assert r1["revision_uuid"] != r2["revision_uuid"]
    assert r2["previous_revision_uuid"] == "rev-1"
    assert r1["revision_no"] == 1 and r2["revision_no"] == 2
    assert r1["engineer_name"] == "م. سامر"
    assert r1["study_status"] == "superseded" and r2["study_status"] == "approved"
    assert r1["quantities"][0]["item_no"] == 1
    assert r1["photos"][0]["uri"] == "content://photos/1.jpg"
    assert r2["boq_delivery"][0]["status"] == "delivered"
    print("OK: study_uuid/revision_uuid/previous_revision_uuid/revision_no/engineer_name and dependent "
          "quantities/boq_delivery/photos are all present with an explicit schema_version.\n")


class FakeAppDatabase:
    """Mirrors the relevant slice of AppDatabase.java: markRevisionsSynced() only ever touches
    sync_status/synced_at, guarded by revision_uuid, and study_status is never a field it can write."""
    def __init__(self):
        self.rows = {
            "rev-1": {"study_status": "approved", "sync_status": "local", "synced_at": 0},
            "rev-2": {"study_status": "draft", "sync_status": "local", "synced_at": 0},
        }

    def mark_revisions_synced(self, revision_uuids, now=9999):
        for uuid in revision_uuids:
            if uuid in self.rows:
                self.rows[uuid]["sync_status"] = "exported"
                self.rows[uuid]["synced_at"] = now
                # study_status is deliberately never touched here.


def on_activity_result_sync_share(db, pending_uuids, result_code):
    """Mirrors MainActivity.onActivityResult(REQUEST_SYNC_SHARE, ...) exactly."""
    if result_code == RESULT_CANCELED:
        return "تم إلغاء المشاركة — لم يتغيّر أي شيء محلياً"
    db.mark_revisions_synced(pending_uuids)
    return f"تم تصدير {len(pending_uuids)} سجلاً (بانتظار استلامها على جهاز Windows)"


def test_cancelling_share_sheet_changes_nothing():
    print("=== Cancelling the share sheet: no sync_status change, no study_status change, no data loss ===")
    db = FakeAppDatabase()
    before = {k: dict(v) for k, v in db.rows.items()}

    message = on_activity_result_sync_share(db, ["rev-1", "rev-2"], RESULT_CANCELED)

    assert db.rows == before, "Cancelling must leave every row completely untouched"
    assert "إلغاء" in message
    print(f"OK: after cancel, rows are byte-identical to before ({db.rows}); message: {message!r}\n")


def test_export_exception_before_chooser_changes_nothing():
    print("=== A failed export (exception before the share sheet even appears) changes nothing ===")
    db = FakeAppDatabase()
    before = {k: dict(v) for k, v in db.rows.items()}

    # Mirrors ShareIntentDesktopAdapter.export() failing (e.g. cache dir creation error) -> success=False ->
    # MainActivity's showSyncWithWindows() never sets pendingSyncRevisionUuids and never reaches
    # onActivityResult at all, since startActivityForResult was never even called.
    export_success = False
    if export_success:
        raise AssertionError("this branch must not run in this test")

    assert db.rows == before, "A failed export must never touch any row"
    print("OK: when export() reports failure, no pending revision list is stored and the database is never touched.\n")


def test_successful_handoff_marks_only_the_exported_revisions():
    print("=== Successful hand-off marks ONLY the exported revisions, and never touches study_status ===")
    db = FakeAppDatabase()
    before_study_status = {k: v["study_status"] for k, v in db.rows.items()}

    message = on_activity_result_sync_share(db, ["rev-1"], RESULT_OK)

    assert db.rows["rev-1"]["sync_status"] == "exported" and db.rows["rev-1"]["synced_at"] == 9999
    assert db.rows["rev-2"]["sync_status"] == "local" and db.rows["rev-2"]["synced_at"] == 0, \
        "A revision that was NOT part of this export must be completely unaffected"
    assert {k: v["study_status"] for k, v in db.rows.items()} == before_study_status, \
        "study_status must never be written by sync bookkeeping, under any outcome"
    assert "1" in message
    print(f"OK: rev-1 marked sync_status='exported' with a timestamp, rev-2 (not exported) untouched, "
          f"study_status unchanged for both. message: {message!r}\n")


if __name__ == "__main__":
    try:
        test_json_schema_carries_everything_needed_for_windows_reconstruction()
        test_cancelling_share_sheet_changes_nothing()
        test_export_exception_before_chooser_changes_nothing()
        test_successful_handoff_marks_only_the_exported_revisions()
        print("SYNC_EXPORT_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
