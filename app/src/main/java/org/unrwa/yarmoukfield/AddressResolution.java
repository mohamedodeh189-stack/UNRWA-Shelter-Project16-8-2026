package org.unrwa.yarmoukfield;

/** Combines every available address-verification source into one resolution, without ever touching the
 * beneficiary's original address text and without ever silently auto-correcting a conflict:
 *   1. A previously CONFIRMED correction for this exact normalized address text (address_corrections —
 *      only ever written by an explicit human confirmation, never guessed) — consulted first, authoritative.
 *   2. Otherwise, the fuzzy AddressClassifier match against the reference plan.
 *   3. Optionally, a consistency check between the resolved sector and the beneficiary's real GPS fix (only
 *      when map calibration is reliable — see MapCalibration/SectorBoundaries). A conflict here (GPS falls
 *      inside a DIFFERENT sector's polygon than the text classification) always forces needs-review, no
 *      matter how confident the text match was; it is never resolved automatically.
 * Pure logic, no Android dependency — directly unit-testable. */
public final class AddressResolution {
    public static final String STATUS_CONFIRMED_CORRECTION = "confirmed_correction";
    public static final String STATUS_CLASSIFIED = "classified";
    public static final String STATUS_NEEDS_REVIEW = "needs_review";

    /** Below this confidence, AddressClassifier's own match is not trusted alone (mirrors the classifier's
     * internal "confident" bar of 0.78 used to decide whether to trust the best fuzzy match). */
    public static final double CONFIDENCE_THRESHOLD = 0.78;

    public static final class ConfirmedCorrection {
        public final String sector, street, confirmedBy;
        public ConfirmedCorrection(String sector, String street, String confirmedBy) {
            this.sector = sector; this.street = street; this.confirmedBy = confirmedBy;
        }
    }

    /** Result of testing the beneficiary's real GPS fix against the sector reference boundaries. Only ever
     * built when a reliable calibration AND a real GPS fix both exist — otherwise pass null to resolve(). */
    public static final class GpsSectorCheck {
        public final boolean matchesSector;
        public final String gpsSector; // "" if the point fell outside every known sector polygon
        public GpsSectorCheck(boolean matchesSector, String gpsSector) { this.matchesSector = matchesSector; this.gpsSector = gpsSector; }
    }

    public static final class Result {
        public String sector = "", street = "", addressSource = "", reviewReason = "";
        public double confidence;
        public int sectorIndex = 999, streetIndex = 999, routeIndex = 999, alleyIndex = 999;
        public String status = STATUS_NEEDS_REVIEW;
        public boolean needsReview;
    }

    public static Result resolve(AddressClassifier.Match fuzzyMatch, ConfirmedCorrection correction, GpsSectorCheck gpsCheck) {
        Result r = new Result();
        if (correction != null) {
            r.sector = correction.sector; r.street = correction.street; r.confidence = 1.0;
            r.status = STATUS_CONFIRMED_CORRECTION;
            r.addressSource = "تصحيح مؤكَّد سابقاً" + (correction.confirmedBy == null || correction.confirmedBy.isEmpty() ? "" : " بواسطة " + correction.confirmedBy);
            r.needsReview = false;
        } else {
            r.sector = fuzzyMatch.sector; r.street = fuzzyMatch.street; r.confidence = fuzzyMatch.confidence;
            r.sectorIndex = fuzzyMatch.sectorIndex; r.streetIndex = fuzzyMatch.streetIndex;
            r.routeIndex = fuzzyMatch.routeIndex; r.alleyIndex = fuzzyMatch.alleyIndex;
            boolean weak = fuzzyMatch.confidence < CONFIDENCE_THRESHOLD;
            r.status = weak ? STATUS_NEEDS_REVIEW : STATUS_CLASSIFIED;
            r.needsReview = weak;
            r.addressSource = "تصنيف تلقائي | " + fuzzyMatch.review;
        }

        if (gpsCheck != null) {
            if (!gpsCheck.matchesSector && !gpsCheck.gpsSector.isEmpty() && !gpsCheck.gpsSector.equals(r.sector)) {
                r.needsReview = true;
                r.status = STATUS_NEEDS_REVIEW;
                r.reviewReason = "تعارض: العنوان النصي/التصحيح يشير إلى '" + r.sector + "' بينما موقع GPS الحقيقي يقع ضمن حدود '" + gpsCheck.gpsSector + "'";
                r.addressSource += " — تعارض مع GPS، يحتاج مراجعة يدوية";
            } else if (gpsCheck.matchesSector && STATUS_CLASSIFIED.equals(r.status)) {
                r.confidence = Math.min(1.0, r.confidence + 0.1);
                r.addressSource += " (متوافق مع GPS)";
            }
        }
        return r;
    }
}
