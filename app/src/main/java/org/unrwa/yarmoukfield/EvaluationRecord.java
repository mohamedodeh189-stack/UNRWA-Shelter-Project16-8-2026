package org.unrwa.yarmoukfield;

import java.util.LinkedHashMap;
import java.util.Map;

/** One beneficiary's pre-computed KoBo housing-evaluation record ("التقييم") — every score in {@link #values}
 * is already a final computed number in the source survey (health/social/structural criteria are scored by
 * the KoBo tool itself), so nothing here re-derives scoring rules; it only carries values through. Matched to
 * an app beneficiary by {@link #registration} (the FAMILY registration number, KoBo column «1.4 - رقم تسجيل
 * العائلة»), which is the same format as {@link AppDatabase.Beneficiary#registration} — NOT the individual
 * "1.5 - رقم التسجيل الفردي" column, which is a different number shown separately on the sheet. */
public final class EvaluationRecord {
    public String registration = "";
    public String name = "";
    /** Keyed by the placeholder names used in the bundled quantities_template.xlsx «التقييم» sheet
     * (NAME, AGE, GENDER, SCORE_CHRONIC, GRAND_TOTAL, ...) — see BeneficiaryXlsx.EVAL_CELL_BY_KEY. */
    public final Map<String, String> values = new LinkedHashMap<>();
}
