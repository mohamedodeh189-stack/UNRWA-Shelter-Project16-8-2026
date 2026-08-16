# -*- coding: utf-8 -*-
"""Final Acceptance Test: true end-to-end Android-JSON -> Windows-import cycle.

Unlike sync_export_smoke.py (Android-side field presence) and
Yarmouk_Camp_Shelter_Project_V9_0_Stable_Strict/tests/sync_import_smoke.py
(Windows-side merge logic, tested against its own hand-built fixtures), this
test builds a package using the EXACT same JSON-shaping function mirrored
from SyncPackageJson.java, then feeds that exact output into the REAL
Windows sync_import.py module (imported directly, not re-implemented) to
prove the two sides' schemas are actually compatible field-for-field, not
just independently self-consistent.

Scenario: two engineers, a study with an original approved revision that
gets corrected into a Revision 2, the same package re-imported a second
time, and a final check that history/dedup/active-revision selection are
all correct in the real Windows module.
"""
import os
import sys

WINDOWS_APP_DIR = os.path.join(
    os.path.dirname(__file__), "..", "..",
    "Yarmouk_Camp_Shelter_Project_V9_0_Stable_Strict", "app",
)
sys.path.insert(0, WINDOWS_APP_DIR)
from sync_import import SyncStore, load_package  # noqa: E402  (real Windows module, not a mirror)


def sync_package_to_json(revisions, project, engineer):
    """Mirrors SyncPackageJson.toJson() field-for-field (same shape as sync_export_smoke.py's version)."""
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
                "registration": r.get("registration", ""), "phone1": r.get("phone1", ""),
                "address": r.get("address", ""), "original_address": r.get("original_address", ""),
                "sector": r.get("sector", ""), "amount": r.get("amount", ""),
                "study_status": r.get("study_status", "draft"), "workflow_stage": r.get("workflow_stage", "study"),
                "quantities": r.get("quantities", []), "boq_delivery": r.get("boq_delivery", []), "photos": r.get("photos", []),
            }
            for r in revisions
        ],
    }


def beneficiary_revision(study_uuid, revision_uuid, revision_no, engineer, status, previous="", **overrides):
    row = {
        "study_uuid": study_uuid, "revision_uuid": revision_uuid, "previous_revision_uuid": previous,
        "revision_no": revision_no, "engineer_name": engineer, "name": "أحمد حسين دياب", "registration": "1-10000001",
        "phone1": "0991234567", "address": "شرق اليرموك، شارع الوحدة، بجانب المسجد", "original_address": "شرق اليرموك، شارع الوحدة",
        "sector": "شرق اليرموك 1", "amount": "3982", "study_status": status, "workflow_stage": "study",
        "quantities": [{"item_no": 1, "quantity": 3, "unit_price": 50}],
        "boq_delivery": [], "photos": [{"phase": "before", "uri": "content://photos/1_before.jpg", "file_name": "1_before.jpg", "captured_at": 2000, "note": ""}],
    }
    row.update(overrides)
    return row


def test_end_to_end_android_to_windows_cycle():
    print("=== END-TO-END: Android JSON schema -> real Windows sync_import.py module ===")
    study_uuid = "e2e-study-1"

    # --- Engineer 1 (م. سامر): creates the study, gets it approved (Revision 1). ---
    rev1 = beneficiary_revision(study_uuid, "e2e-rev-1", 1, "م. سامر", "approved")
    package_samer = sync_package_to_json([rev1], "مشروع مخيم اليرموك", "م. سامر")

    store = SyncStore()
    loaded_samer = load_package(package_samer)  # real Windows-side schema_version validation
    stats1 = store.merge_package(loaded_samer, source_label="samer_export.json")
    assert stats1["added"] == 1 and stats1["total_in_package"] == 1
    active, warning = store.active_revision(study_uuid)
    assert active["revision_uuid"] == "e2e-rev-1" and warning is None
    print("Step 1 OK: Engineer 1's approved Revision 1 imported cleanly; it is the active revision.")

    # --- Engineer 2 (م. لينا): finds an issue after approval, issues a corrected Revision 2. ---
    # (Mirrors AppDatabase.createCorrectedRevision(): same study_uuid, new revision_uuid, revision_no+1,
    # previous_revision_uuid points at rev1, old row's study_status flips to 'superseded'.)
    rev1_superseded = beneficiary_revision(study_uuid, "e2e-rev-1", 1, "م. سامر", "superseded")
    rev2 = beneficiary_revision(study_uuid, "e2e-rev-2", 2, "م. لينا", "approved", previous="e2e-rev-1",
                                 address="شرق اليرموك، شارع الوحدة، بجانب المسجد الكبير (مصحَّح)")
    package_lina = sync_package_to_json([rev1_superseded, rev2], "مشروع مخيم اليرموك", "م. لينا")

    loaded_lina = load_package(package_lina)
    stats2 = store.merge_package(loaded_lina, source_label="lina_export.json")
    # rev1 arrives again (now marked superseded) -- but its revision_uuid is unchanged, so it's a duplicate
    # by the dedup key and must NOT overwrite/duplicate the already-imported rev1 entry.
    assert stats2["added"] == 1, f"Only the genuinely new rev2 should be added, got {stats2}"
    assert stats2["already_present"] == 1, "rev1 (same revision_uuid, now just re-sent) must be recognized as already present"
    print(f"Step 2 OK: merging Engineer 2's package added only the new Revision 2 (stats={stats2}); "
          f"the re-sent Revision 1 was correctly recognized as already present, not duplicated.")

    # --- Full revision history must show both revisions in order, with the link intact. ---
    history = store.revision_history(study_uuid)
    assert [r["revision_uuid"] for r in history] == ["e2e-rev-1", "e2e-rev-2"]
    assert history[1]["previous_revision_uuid"] == "e2e-rev-1"
    assert history[0]["study_status"] == "superseded" and history[1]["study_status"] == "approved"
    print("Step 3 OK: revision history for the study shows both revisions in order, linked via previous_revision_uuid.")

    active, warning = store.active_revision(study_uuid)
    assert active["revision_uuid"] == "e2e-rev-2" and warning is None
    assert active["address"] == "شرق اليرموك، شارع الوحدة، بجانب المسجد الكبير (مصحَّح)"
    assert active["original_address"] == "شرق اليرموك، شارع الوحدة", "original_address must survive the corrected revision unchanged"
    print("Step 4 OK: active_revision() correctly resolved to Revision 2 (the corrected one), and original_address "
          "was carried through unchanged even though the working address was corrected.")

    # --- Re-import BOTH packages a second time (simulating re-sharing/duplicate delivery) -- total must not grow. ---
    stats_re1 = store.merge_package(load_package(package_samer), source_label="samer_export.json (resent)")
    stats_re2 = store.merge_package(load_package(package_lina), source_label="lina_export.json (resent)")
    assert stats_re1 == {"added": 0, "already_present": 1, "skipped_malformed": 0, "total_in_package": 1}
    assert stats_re2["added"] == 0 and stats_re2["already_present"] == 2
    assert len(store.revisions_by_uuid) == 2, "Total distinct revisions must still be exactly 2 after re-importing both packages again"
    assert len(store.studies[study_uuid]) == 2
    print("Step 5 OK: re-importing both original packages a second time added zero new revisions "
          f"(store still holds exactly {len(store.revisions_by_uuid)} distinct revisions).")

    assert set(store.all_engineers()) == {"م. سامر", "م. لينا"}
    print(f"Step 6 OK: contributions from both engineers ({sorted(store.all_engineers())}) are visible in the merged store.\n")


if __name__ == "__main__":
    try:
        test_end_to_end_android_to_windows_cycle()
        print("END_TO_END_SYNC_CYCLE_SMOKE_OK")
    except AssertionError as exc:
        print(f"SMOKE_TEST_FAILED: {exc}")
        sys.exit(1)
    except ImportError as exc:
        print(f"SMOKE_TEST_FAILED (could not import real Windows module): {exc}")
        sys.exit(1)
