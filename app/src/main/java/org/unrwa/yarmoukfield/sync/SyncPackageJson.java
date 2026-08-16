package org.unrwa.yarmoukfield.sync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Serializes/deserializes the sync package JSON. Depends only on org.json (no Android, no AppDatabase) so
 * the exact same schema can be validated on the desktop (Python) side without guessing the format from Java
 * source. schema_version is bumped whenever a field is added or its meaning changes. */
public final class SyncPackageJson {
    public static final String SCHEMA_VERSION = "yarmouk-sync-1.0";
    private SyncPackageJson() {}

    public static JSONObject toJson(List<StudyRevisionPackage> revisions, String project, String exportedByEngineer) throws Exception {
        JSONObject root = new JSONObject();
        root.put("schema_version", SCHEMA_VERSION);
        root.put("project", project);
        root.put("exported_by_engineer", exportedByEngineer == null ? "" : exportedByEngineer);
        root.put("exported_at", System.currentTimeMillis());
        JSONArray array = new JSONArray();
        for (StudyRevisionPackage r : revisions) array.put(toJson(r));
        root.put("revisions", array);
        return root;
    }

    private static JSONObject toJson(StudyRevisionPackage r) throws Exception {
        JSONObject o = new JSONObject();
        o.put("study_uuid", r.studyUuid);
        o.put("revision_uuid", r.revisionUuid);
        o.put("previous_revision_uuid", r.previousRevisionUuid);
        o.put("revision_no", r.revisionNo);
        o.put("engineer_name", r.engineerName);
        o.put("name", r.name); o.put("registration", r.registration); o.put("relation", r.relation);
        o.put("related_to", r.relatedTo); o.put("phone1", r.phone1); o.put("phone2", r.phone2);
        o.put("address", r.address); o.put("original_address", r.originalAddress); o.put("address_source", r.addressSource);
        o.put("sector", r.sector); o.put("street", r.street); o.put("match_status", r.matchStatus); o.put("confidence", r.confidence);
        o.put("amount", r.amount);
        o.put("workflow_stage", r.workflowStage); o.put("study_status", r.studyStatus);
        o.put("damage_notes", r.damageNotes); o.put("quantities_notes", r.quantitiesNotes); o.put("drawing_uri", r.drawingUri);
        o.put("followup_batch", r.followupBatch); o.put("approved_at", r.approvedAt); o.put("work_delivered", r.workDelivered);
        o.put("visit_status", r.visitStatus); o.put("progress_percent", r.progressPercent); o.put("notes", r.notes);
        o.put("gps_lat", r.gpsLat); o.put("gps_lng", r.gpsLng); o.put("gps_accuracy_m", r.gpsAccuracyM); o.put("gps_captured_at", r.gpsCapturedAt);
        o.put("delivery_confirmed_at", r.deliveryConfirmedAt);
        o.put("first_payment_available_at", r.firstPaymentAvailableAt); o.put("first_payment_confirmed_by", r.firstPaymentConfirmedBy);
        o.put("second_payment_ready", r.secondPaymentReady); o.put("second_payment_approved_by", r.secondPaymentApprovedBy); o.put("second_payment_approved_at", r.secondPaymentApprovedAt);

        JSONArray quantities = new JSONArray();
        for (StudyRevisionPackage.QuantityRow q : r.quantities) {
            JSONObject qo = new JSONObject(); qo.put("item_no", q.itemNo); qo.put("quantity", q.quantity); qo.put("unit_price", q.unitPrice);
            quantities.put(qo);
        }
        o.put("quantities", quantities);

        JSONArray boq = new JSONArray();
        for (StudyRevisionPackage.BoqDeliveryRow b : r.boqDelivery) {
            JSONObject bo = new JSONObject(); bo.put("item_no", b.itemNo); bo.put("status", b.status); bo.put("note", b.note);
            bo.put("engineer_name", b.engineerName); bo.put("updated_at", b.updatedAt);
            boq.put(bo);
        }
        o.put("boq_delivery", boq);

        JSONArray photos = new JSONArray();
        for (StudyRevisionPackage.PhotoRow p : r.photos) {
            JSONObject po = new JSONObject(); po.put("phase", p.phase); po.put("uri", p.uri); po.put("file_name", p.fileName);
            po.put("captured_at", p.capturedAt); po.put("note", p.note);
            photos.put(po);
        }
        o.put("photos", photos);
        return o;
    }
}
