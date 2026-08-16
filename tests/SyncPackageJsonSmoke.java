import org.json.JSONObject;
import org.json.JSONArray;
import org.unrwa.yarmoukfield.sync.StudyRevisionPackage;
import org.unrwa.yarmoukfield.sync.SyncPackageJson;

import java.util.ArrayList;
import java.util.List;

/** Standalone JVM test for SyncPackageJson (needs org.json on the classpath, provided here via android.jar —
 * this class itself has zero Android dependency and would compile/run identically against any org.json
 * implementation). Verifies the exported JSON schema carries every field required to reconstruct a study's
 * revision history on the Windows side: study_uuid, revision_uuid, previous_revision_uuid, revision_no,
 * engineer_name, plus quantities/boq_delivery/photos, all under an explicit schema_version. */
public class SyncPackageJsonSmoke {
    static int failures = 0;
    static void check(boolean cond, String msg) {
        if (!cond) { System.out.println("FAIL: " + msg); failures++; }
        else System.out.println("OK: " + msg);
    }

    public static void main(String[] args) throws Exception {
        StudyRevisionPackage rev1 = new StudyRevisionPackage();
        rev1.studyUuid = "study-A"; rev1.revisionUuid = "rev-1"; rev1.previousRevisionUuid = "";
        rev1.revisionNo = 1; rev1.engineerName = "م. سامر"; rev1.name = "أحمد حسين دياب";
        rev1.studyStatus = "superseded"; rev1.workflowStage = "study";
        StudyRevisionPackage.QuantityRow q = new StudyRevisionPackage.QuantityRow(); q.itemNo = 1; q.quantity = 3; q.unitPrice = 50;
        rev1.quantities.add(q);
        StudyRevisionPackage.PhotoRow p = new StudyRevisionPackage.PhotoRow(); p.phase = "before"; p.uri = "content://photos/1.jpg"; p.fileName = "1.jpg"; p.capturedAt = 2000;
        rev1.photos.add(p);

        StudyRevisionPackage rev2 = new StudyRevisionPackage();
        rev2.studyUuid = "study-A"; rev2.revisionUuid = "rev-2"; rev2.previousRevisionUuid = "rev-1";
        rev2.revisionNo = 2; rev2.engineerName = "م. سامر"; rev2.name = "أحمد حسين دياب"; rev2.studyStatus = "approved";
        StudyRevisionPackage.BoqDeliveryRow d = new StudyRevisionPackage.BoqDeliveryRow(); d.itemNo = 1; d.status = "delivered"; d.engineerName = "م. سامر"; d.updatedAt = 9000;
        rev2.boqDelivery.add(d);

        List<StudyRevisionPackage> revisions = new ArrayList<>(); revisions.add(rev1); revisions.add(rev2);

        System.out.println("=== schema_version and top-level envelope ===");
        JSONObject root = SyncPackageJson.toJson(revisions, "مشروع مخيم اليرموك", "م. سامر");
        check(SyncPackageJson.SCHEMA_VERSION.equals(root.getString("schema_version")), "schema_version present and matches the constant");
        check(root.getString("project").equals("مشروع مخيم اليرموك"), "project name present");
        check(root.getString("exported_by_engineer").equals("م. سامر"), "exported_by_engineer present");
        check(root.has("exported_at"), "exported_at timestamp present");

        JSONArray array = root.getJSONArray("revisions");
        check(array.length() == 2, "both revisions present in the exported array");

        System.out.println("\n=== every field needed to rebuild study/revision history on Windows ===");
        JSONObject r1 = array.getJSONObject(0), r2 = array.getJSONObject(1);
        check(r1.getString("study_uuid").equals("study-A") && r2.getString("study_uuid").equals("study-A"), "study_uuid present and shared across revisions of the same study");
        check(!r1.getString("revision_uuid").equals(r2.getString("revision_uuid")), "revision_uuid differs per revision");
        check(r1.getString("previous_revision_uuid").isEmpty(), "first revision has no previous_revision_uuid");
        check(r2.getString("previous_revision_uuid").equals("rev-1"), "second revision's previous_revision_uuid points at the first");
        check(r1.getInt("revision_no") == 1 && r2.getInt("revision_no") == 2, "revision_no present and correctly incrementing");
        check(r1.getString("engineer_name").equals("م. سامر"), "engineer_name present");
        check(r1.getString("study_status").equals("superseded") && r2.getString("study_status").equals("approved"), "study_status present, correctly distinguishing superseded from active");

        System.out.println("\n=== dependent data (quantities/boq_delivery/photos) included ===");
        JSONArray quantities = r1.getJSONArray("quantities");
        check(quantities.length() == 1 && quantities.getJSONObject(0).getInt("item_no") == 1, "quantities included for the revision that has them");
        JSONArray photos = r1.getJSONArray("photos");
        check(photos.length() == 1 && photos.getJSONObject(0).getString("uri").equals("content://photos/1.jpg"), "photo index included (metadata only, matching the existing local-backup convention -- not the image bytes)");
        JSONArray boq = r2.getJSONArray("boq_delivery");
        check(boq.length() == 1 && boq.getJSONObject(0).getString("status").equals("delivered"), "boq_delivery included for the revision that has it");
        JSONArray emptyBoq = r1.getJSONArray("boq_delivery");
        check(emptyBoq.length() == 0, "revision with no boq_delivery rows serializes an empty array, not null/missing");

        System.out.println("\n" + (failures == 0 ? "SYNC_PACKAGE_JSON_SMOKE_OK" : "SMOKE_TEST_FAILED: " + failures + " failure(s)"));
        if (failures > 0) System.exit(1);
    }
}
