import org.unrwa.yarmoukfield.AddressResolution;
import org.unrwa.yarmoukfield.AddressClassifier;

/** Standalone JVM test for AddressResolution (pure Java, zero Android dependency — runs directly against a
 * plain JDK, no emulator/device needed). AddressClassifier.Match is a plain public-field class so it can be
 * constructed directly here without a real AddressClassifier (which needs an Android Context to load its
 * reference JSON). */
public class AddressResolutionSmoke {
    static int failures = 0;
    static void check(boolean cond, String msg) {
        if (!cond) { System.out.println("FAIL: " + msg); failures++; }
        else System.out.println("OK: " + msg);
    }

    static AddressClassifier.Match match(String sector, double confidence, String review) {
        AddressClassifier.Match m = new AddressClassifier.Match();
        m.sector = sector; m.confidence = confidence; m.review = review; m.street = "شارع تجريبي";
        return m;
    }

    public static void main(String[] args) {
        System.out.println("=== Test 1: confirmed correction is authoritative, bypasses fuzzy match entirely ===");
        AddressResolution.ConfirmedCorrection correction = new AddressResolution.ConfirmedCorrection("شرق اليرموك 2", "شارع الوحدة", "م. سامر");
        AddressClassifier.Match weakFuzzy = match("غرب اليرموك 1", 0.3, "لم يظهر تشابه");
        AddressResolution.Result r1 = AddressResolution.resolve(weakFuzzy, correction, null);
        check(AddressResolution.STATUS_CONFIRMED_CORRECTION.equals(r1.status), "status is confirmed_correction");
        check(r1.sector.equals("شرق اليرموك 2"), "uses the CORRECTION's sector, not the weak fuzzy match's");
        check(!r1.needsReview, "a confirmed correction never needs review");
        check(r1.confidence == 1.0, "confirmed correction has full confidence");

        System.out.println("\n=== Test 2: confident fuzzy match with no GPS check -> classified, not needing review ===");
        AddressClassifier.Match strongFuzzy = match("شرق اليرموك 1", 0.95, "مطابقة مؤكدة");
        AddressResolution.Result r2 = AddressResolution.resolve(strongFuzzy, null, null);
        check(AddressResolution.STATUS_CLASSIFIED.equals(r2.status), "status is classified");
        check(!r2.needsReview, "a confident match (>=0.78) does not need review");

        System.out.println("\n=== Test 3: weak fuzzy match -> needs_review ===");
        AddressClassifier.Match weak = match("العروبة والتقدم", 0.45, "أقرب قطاع من المخطط المرجعي — يحتاج تأكيداً ميدانياً");
        AddressResolution.Result r3 = AddressResolution.resolve(weak, null, null);
        check(AddressResolution.STATUS_NEEDS_REVIEW.equals(r3.status), "status is needs_review");
        check(r3.needsReview, "needsReview flag is true");

        System.out.println("\n=== Test 4: CRITICAL — GPS/sector conflict forces needs_review even on a highly confident text match ===");
        AddressClassifier.Match veryConfident = match("شرق اليرموك 3", 0.99, "مطابقة مؤكدة");
        AddressResolution.GpsSectorCheck conflicting = new AddressResolution.GpsSectorCheck(false, "غرب اليرموك 2");
        AddressResolution.Result r4 = AddressResolution.resolve(veryConfident, null, conflicting);
        check(AddressResolution.STATUS_NEEDS_REVIEW.equals(r4.status), "a GPS/text conflict overrides even a 0.99-confidence classification");
        check(r4.needsReview, "needsReview is forced true on conflict");
        check(r4.sector.equals("شرق اليرموك 3"), "the text-classified sector is preserved as-is (never silently replaced by the GPS sector)");
        check(r4.reviewReason.contains("شرق اليرموك 3") && r4.reviewReason.contains("غرب اليرموك 2"), "reviewReason names both conflicting sectors: " + r4.reviewReason);
        check(!r4.addressSource.isEmpty() && r4.addressSource.contains("تعارض"), "addressSource records that a conflict occurred");

        System.out.println("\n=== Test 5: GPS confirms the same sector -> confidence boosted, no conflict, still classified ===");
        AddressClassifier.Match confident = match("شرق اليرموك 2", 0.80, "مطابقة تقريبية — راجعها");
        AddressResolution.GpsSectorCheck agreeing = new AddressResolution.GpsSectorCheck(true, "شرق اليرموك 2");
        AddressResolution.Result r5 = AddressResolution.resolve(confident, null, agreeing);
        check(AddressResolution.STATUS_CLASSIFIED.equals(r5.status), "status stays classified when GPS agrees");
        check(!r5.needsReview, "no review needed when GPS confirms the text sector");
        check(r5.confidence > 0.80, "confidence is boosted when GPS agrees, got " + r5.confidence);

        System.out.println("\n=== Test 6: a confirmed correction is NOT overridden even if GPS disagrees with it ===");
        // A human already confirmed this exact address text maps to a sector; GPS conflicting with a PRIOR
        // human confirmation should still surface as needing review (trust neither blindly), but must not
        // silently discard the correction's sector value.
        AddressResolution.GpsSectorCheck conflictsWithCorrection = new AddressResolution.GpsSectorCheck(false, "العروبة والتقدم");
        AddressResolution.Result r6 = AddressResolution.resolve(weakFuzzy, correction, conflictsWithCorrection);
        check(r6.sector.equals("شرق اليرموك 2"), "the confirmed correction's sector is preserved, never silently swapped for the GPS sector");
        check(r6.needsReview, "even a confirmed correction gets flagged for review if a real GPS fix now disagrees with it");

        System.out.println("\n" + (failures == 0 ? "ADDRESS_RESOLUTION_SMOKE_OK" : "SMOKE_TEST_FAILED: " + failures + " failure(s)"));
        if (failures > 0) System.exit(1);
    }
}
