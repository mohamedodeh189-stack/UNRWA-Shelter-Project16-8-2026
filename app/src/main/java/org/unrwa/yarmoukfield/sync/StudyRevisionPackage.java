package org.unrwa.yarmoukfield.sync;

import java.util.ArrayList;
import java.util.List;

/** A single study revision's full exportable state — a plain data holder with NO Android dependency
 * whatsoever (no Intent, no Context, no AppDatabase). This is the domain model every SyncTarget speaks:
 * building one of these from a real beneficiary row is the Android-side adapter's job (see
 * SyncPackageBuilder), and consuming one to reconstruct a study on Windows is the desktop side's job.
 *
 * study_uuid groups every revision of the same study together (constant across corrections);
 * revision_uuid uniquely identifies THIS exact revision and is the deduplication key on import.
 * previous_revision_uuid + revision_no let the receiving side rebuild the full correction history,
 * including superseded revisions — which is why a sync export always includes every revision, not just
 * the currently-active one. */
public final class StudyRevisionPackage {
    // Identity — the fields multi-engineer merge logic depends on.
    public String studyUuid = "";
    public String revisionUuid = "";
    public String previousRevisionUuid = "";
    public int revisionNo = 1;
    public String engineerName = "";

    // Person / contact.
    public String name = "", registration = "", relation = "", relatedTo = "", phone1 = "", phone2 = "";

    // Address (the original text is carried separately and must never be treated as editable on import).
    public String address = "", originalAddress = "", addressSource = "";
    public String sector = "", street = "", matchStatus = "";
    public double confidence;
    public String amount = "";

    // Lifecycle.
    public String workflowStage = "study", studyStatus = "draft";
    public String damageNotes = "", quantitiesNotes = "", drawingUri = "", followupBatch = "";
    public long approvedAt;
    public boolean workDelivered;
    public String visitStatus = "pending";
    public int progressPercent;
    public String notes = "";

    // Real GPS only (never pixel/map coordinates — see MapCalibration).
    public double gpsLat, gpsLng, gpsAccuracyM;
    public long gpsCapturedAt;

    // Phase 4 payment/delivery timeline.
    public long deliveryConfirmedAt;
    public long firstPaymentAvailableAt;
    public String firstPaymentConfirmedBy = "";
    public boolean secondPaymentReady;
    public String secondPaymentApprovedBy = "";
    public long secondPaymentApprovedAt;

    public static final class QuantityRow {
        public int itemNo; public double quantity, unitPrice;
    }
    public static final class BoqDeliveryRow {
        public int itemNo; public String status = "", note = "", engineerName = ""; public long updatedAt;
    }
    public static final class PhotoRow {
        public String phase = "", uri = "", fileName = "", note = ""; public long capturedAt;
    }

    public List<QuantityRow> quantities = new ArrayList<>();
    public List<BoqDeliveryRow> boqDelivery = new ArrayList<>();
    public List<PhotoRow> photos = new ArrayList<>();
}
