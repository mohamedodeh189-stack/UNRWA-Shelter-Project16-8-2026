package org.unrwa.yarmoukfield.sync;

import org.unrwa.yarmoukfield.AppDatabase;

import java.util.ArrayList;
import java.util.List;

/** The ONLY place that bridges AppDatabase's Android-side rows into the domain-clean StudyRevisionPackage
 * model. SyncTarget implementations never touch AppDatabase directly — they only ever see the plain
 * packages this class builds. Read-only: never writes anything back to the database. */
public final class SyncPackageBuilder {
    private SyncPackageBuilder() {}

    /** Every revision (including superseded ones) of every beneficiary passed in is included — Windows needs
     * the full correction history to reconstruct study_uuid groupings correctly, not just the live revision. */
    public static List<StudyRevisionPackage> build(AppDatabase db, List<AppDatabase.Beneficiary> beneficiaries) {
        List<StudyRevisionPackage> out = new ArrayList<>();
        for (AppDatabase.Beneficiary b : beneficiaries) out.add(buildOne(db, b));
        return out;
    }

    private static StudyRevisionPackage buildOne(AppDatabase db, AppDatabase.Beneficiary b) {
        StudyRevisionPackage r = new StudyRevisionPackage();
        r.studyUuid = b.studyUuid; r.revisionUuid = b.revisionUuid; r.previousRevisionUuid = b.previousRevisionUuid;
        r.revisionNo = b.revisionNo; r.engineerName = b.engineerName;
        r.name = b.name; r.registration = b.registration; r.relation = b.relation; r.relatedTo = b.relatedTo;
        r.phone1 = b.phone1; r.phone2 = b.phone2;
        r.address = b.address; r.originalAddress = b.originalAddress; r.addressSource = b.addressSource;
        r.sector = b.sector; r.street = b.street; r.matchStatus = b.matchStatus; r.confidence = b.confidence;
        r.amount = b.amount;
        r.workflowStage = b.workflowStage; r.studyStatus = b.studyStatus;
        r.damageNotes = b.damageNotes; r.quantitiesNotes = b.quantitiesNotes; r.drawingUri = b.drawingUri;
        r.followupBatch = b.followupBatch; r.approvedAt = b.approvedAt; r.workDelivered = b.workDelivered;
        r.visitStatus = b.visitStatus; r.progressPercent = b.progressPercent; r.notes = b.notes;
        r.gpsLat = b.gpsLat; r.gpsLng = b.gpsLng; r.gpsAccuracyM = b.gpsAccuracyM; r.gpsCapturedAt = b.gpsCapturedAt;
        r.deliveryConfirmedAt = b.deliveryConfirmedAt;
        r.firstPaymentAvailableAt = b.firstPaymentAvailableAt; r.firstPaymentConfirmedBy = b.firstPaymentConfirmedBy;
        r.secondPaymentReady = b.secondPaymentReady; r.secondPaymentApprovedBy = b.secondPaymentApprovedBy; r.secondPaymentApprovedAt = b.secondPaymentApprovedAt;

        for (AppDatabase.QuantityEntry q : db.quantities(b.id)) {
            StudyRevisionPackage.QuantityRow row = new StudyRevisionPackage.QuantityRow();
            row.itemNo = q.itemNo; row.quantity = q.quantity; row.unitPrice = q.unitPrice;
            r.quantities.add(row);
        }
        for (AppDatabase.BoqDeliveryEntry d : db.boqDeliveryStatus(b.id)) {
            if (d.updatedAt <= 0) continue; // untouched items (never set by an engineer) carry nothing worth exporting
            StudyRevisionPackage.BoqDeliveryRow row = new StudyRevisionPackage.BoqDeliveryRow();
            row.itemNo = d.itemNo; row.status = d.status; row.note = d.note; row.engineerName = d.engineerName; row.updatedAt = d.updatedAt;
            r.boqDelivery.add(row);
        }
        for (AppDatabase.Photo p : db.photos(b.id, "")) {
            StudyRevisionPackage.PhotoRow row = new StudyRevisionPackage.PhotoRow();
            row.phase = p.phase; row.uri = p.uri; row.fileName = p.fileName; row.capturedAt = p.capturedAt; row.note = p.note;
            r.photos.add(row);
        }
        return r;
    }
}
