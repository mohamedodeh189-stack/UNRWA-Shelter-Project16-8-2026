package org.unrwa.yarmoukfield;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/** Imports a KoBo housing-evaluation export ("التطبيق" sheet, one row per household, every score already
 * computed by the survey tool) and keeps it ONLY on this device — never bundled into the app, never written to
 * any git-tracked file. Real names, phone numbers and health/vulnerability data for named households are
 * exactly the kind of sensitive data that must not ship inside a distributed APK (see project HANDOFF), so
 * this file lives at {@link #storageFile}, protected by the same device/app-PIN as everything else the app
 * stores locally, and is looked up by registration number at export time — never copied further.
 *
 * Column layout is specific to the 2026 KoBo export the field team is currently using (identification fields
 * are matched by header TEXT so a reordered export still works; the score columns' headers are generic
 * instruction text repeated across questions, not unique per-column, so those are matched by column LETTER
 * for this specific export version — re-verify the letters below if a materially different KoBo form version
 * is imported later). */
public final class EvaluationImporter {
    private EvaluationImporter() {}

    private static final String STORAGE_FILE = "kobo_evaluation_2026.json";

    // Identification fields: matched by a unique substring in the row-1 header text.
    private static final String[][] TEXT_FIELDS = {
        {"NAME", "1.3 - اسم رب الاسرة"},
        {"FAMILYREG", "1.4 - رقم تسجيل العائلة"},
        {"REG", "1.5 - رقم التسجيل الفردي"},          // the match key
        {"LEDGER", "1.6 - رقم الليدجر"},
        {"NATID", "1.7 - رقم الهوية الوطني"},
        {"GENDER", "1.9 - جنس رب الاسرة"},
        {"AGE", "1.11"},
        {"MARITAL", "1.12 - الحالة العائلية"},
        {"RESIDENCE", "1.13 - مكان سكن العائلة"},
        {"SECONDWIFE", "1.14"},
        {"FAMSIZE", "1.15 - كم عائلة تسكن"},
        {"OWNERSHIP", "1.16 - وضع ملكية المنزل"},
        {"OWNERDOCS", "1.17 - اوراق الملكية المتوفرة"},
        {"PLOTREF", "1.21"},
        {"STREET", "1.22 - اسم الشارع"},
        {"BUILDINGNO", "1.23"},
        {"FLOOR", "1.25 - الطابق"},
        {"ADDRESS", "1.26 - عنوان البيت"},
        {"PHONE1", "1.27 - رقم الجوال 1"},
        {"PHONE2", "1.28 - رقم الجوال 2"},
    };
    // Score fields: this KoBo version's headers are generic/repeated, so matched by fixed column letter.
    private static final String[][] LETTER_FIELDS = {
        {"SCORE_CHRONIC", "BI"}, {"SCORE_MENTAL", "BJ"},
        {"SCORE_VULN", "BL"}, {"SCORE_CROWD", "BM"}, {"SCORE_GENDERSEP", "BN"}, {"SCORE_INCOME", "BO"},
        {"SCORE_STRUCT_DEVIATION", "CC"}, {"SCORE_STRUCT_CRACKING", "CD"}, {"SCORE_STRUCT_SPALLING", "CE"},
        {"SCORE_STRUCT_STABILITY", "CF"}, {"SCORE_STRUCT_TOTAL", "CG"},
        {"SCORE_TOILET", "CI"}, {"SCORE_KITCHEN", "CJ"}, {"SCORE_VENTILATION", "CK"},
        {"SCORE_DAMPNESS", "CL"}, {"SCORE_SEWAGE", "CM"}, {"GRAND_TOTAL", "CO"},
    };

    public static final class ImportResult {
        public int count;
        public String error = "";
    }

    /** Reads the KoBo xlsx and OVERWRITES this device's local evaluation store. Never touches app assets. */
    public static ImportResult importFrom(Context context, Uri uri) {
        ImportResult result = new ImportResult();
        File temp = null;
        try {
            temp = File.createTempFile("kobo_eval_", ".xlsx", context.getCacheDir());
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(temp)) {
                if (in == null) throw new IllegalArgumentException("تعذر فتح الملف");
                byte[] buffer = new byte[64 * 1024]; int n; while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            List<EvaluationRecord> records;
            try (ZipFile zip = new ZipFile(temp)) {
                List<String> shared = SpreadsheetImporter.readSharedStrings(zip);
                List<String> sheetNames = SpreadsheetImporter.readSheetNames(zip);
                List<java.util.zip.ZipEntry> sheets = new ArrayList<>();
                zip.stream().filter(e -> e.getName().matches("xl/worksheets/sheet\\d+\\.xml")).forEach(sheets::add);
                java.util.Collections.sort(sheets, java.util.Comparator.comparing(java.util.zip.ZipEntry::getName));
                List<SpreadsheetImporter.SheetRow> app = null;
                for (int i = 0; i < sheets.size(); i++) {
                    String name = i < sheetNames.size() ? sheetNames.get(i) : "";
                    if (AddressClassifier.normalize(name).contains(AddressClassifier.normalize("تطبيق"))) {
                        app = SpreadsheetImporter.parseSheet(zip.getInputStream(sheets.get(i)), shared);
                        break;
                    }
                }
                if (app == null || app.size() < 2) throw new IllegalArgumentException("لم يتم العثور على ورقة «التطبيق» بالملف — تأكد أنه ملف تصدير كوبو الصحيح.");
                records = extract(app);
            }
            save(context, records);
            result.count = records.size();
        } catch (Exception e) {
            result.error = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        } finally {
            if (temp != null) temp.delete();
        }
        return result;
    }

    private static List<EvaluationRecord> extract(List<SpreadsheetImporter.SheetRow> rows) {
        SpreadsheetImporter.SheetRow header = rows.get(0);
        Map<String, Integer> textCol = new LinkedHashMap<>();
        for (String[] field : TEXT_FIELDS) {
            for (Map.Entry<Integer, String> cell : header.cells.entrySet()) {
                if (cell.getValue() != null && cell.getValue().contains(field[1])) { textCol.put(field[0], cell.getKey()); break; }
            }
        }
        Map<String, Integer> letterCol = new LinkedHashMap<>();
        for (String[] field : LETTER_FIELDS) letterCol.put(field[0], columnFromLetters(field[1]));

        List<EvaluationRecord> out = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            SpreadsheetImporter.SheetRow row = rows.get(r);
            Integer regCol = textCol.get("REG");
            String reg = regCol == null ? "" : row.get(regCol).trim();
            if (reg.isEmpty()) continue; // no registration number to match on — skip the row
            EvaluationRecord rec = new EvaluationRecord();
            rec.registration = reg;
            for (Map.Entry<String, Integer> e : textCol.entrySet()) rec.values.put(e.getKey(), row.get(e.getValue()).trim());
            for (Map.Entry<String, Integer> e : letterCol.entrySet()) rec.values.put(e.getKey(), row.get(e.getValue()).trim());
            rec.name = rec.values.getOrDefault("NAME", "");
            addComputedSubtotals(rec);
            out.add(rec);
        }
        return out;
    }

    /** The «التقييم» sheet's category subtotals are plain sums of the KoBo-computed per-question scores —
     * mirrors the reference form's own G-column formulas exactly (health/social/structural sub- and grand
     * totals), just evaluated once here instead of left as live formulas with nothing to reference. */
    private static void addComputedSubtotals(EvaluationRecord rec) {
        double healthSub = num(rec, "SCORE_CHRONIC") + num(rec, "SCORE_MENTAL");
        double socialSub = num(rec, "SCORE_VULN") + num(rec, "SCORE_CROWD") + num(rec, "SCORE_GENDERSEP") + num(rec, "SCORE_INCOME");
        double otherCondSub = num(rec, "SCORE_TOILET") + num(rec, "SCORE_KITCHEN") + num(rec, "SCORE_VENTILATION") + num(rec, "SCORE_DAMPNESS") + num(rec, "SCORE_SEWAGE");
        double physicalTotal = otherCondSub + num(rec, "SCORE_STRUCT_TOTAL");
        double healthSocialTotal = healthSub + socialSub;
        rec.values.put("HEALTH_SUB", fmt(healthSub));
        rec.values.put("SOCIAL_SUB", fmt(socialSub));
        rec.values.put("HEALTHSOCIAL_TOTAL", fmt(healthSocialTotal));
        rec.values.put("OTHERCOND_SUB", fmt(otherCondSub));
        rec.values.put("PHYSICAL_TOTAL", fmt(physicalTotal));
        // GRAND_TOTAL already came straight from the KoBo CO column above; only fill it from our own sum when
        // that source cell was blank, so a real discrepancy in the source data stays visible rather than hidden.
        if (rec.values.get("GRAND_TOTAL") == null || rec.values.get("GRAND_TOTAL").trim().isEmpty())
            rec.values.put("GRAND_TOTAL", fmt(healthSocialTotal + physicalTotal));
    }
    private static double num(EvaluationRecord rec, String key) {
        String v = rec.values.get(key);
        if (v == null || v.trim().isEmpty()) return 0;
        try { return Double.parseDouble(v.trim()); } catch (Exception e) { return 0; }
    }
    private static String fmt(double v) { return v == Math.rint(v) ? Long.toString((long) v) : String.valueOf(v); }

    private static int columnFromLetters(String letters) {
        int value = 0;
        for (int i = 0; i < letters.length(); i++) value = value * 26 + (letters.charAt(i) - 'A' + 1);
        return value - 1;
    }

    private static void save(Context context, List<EvaluationRecord> records) throws Exception {
        JSONArray array = new JSONArray();
        for (EvaluationRecord rec : records) {
            JSONObject o = new JSONObject();
            o.put("registration", rec.registration);
            o.put("name", rec.name);
            JSONObject values = new JSONObject();
            for (Map.Entry<String, String> e : rec.values.entrySet()) values.put(e.getKey(), e.getValue());
            o.put("values", values);
            array.put(o);
        }
        try (FileWriter w = new FileWriter(new File(context.getFilesDir(), STORAGE_FILE))) {
            w.write(array.toString());
        }
    }

    /** Null if nothing has been imported on this device yet. */
    public static int storedCount(Context context) {
        File f = new File(context.getFilesDir(), STORAGE_FILE);
        if (!f.exists()) return 0;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder(); String line; while ((line = r.readLine()) != null) sb.append(line);
            return new JSONArray(sb.toString()).length();
        } catch (Exception e) { return 0; }
    }

    /** Looks up a beneficiary's evaluation record by registration number (digits-only match, tolerant of
     * dashes/spacing differences between how the app and the KoBo export format the same number). Returns
     * null when nothing was imported yet, or no row matches — the caller must treat that as "no evaluation
     * available", never fabricate one. */
    public static EvaluationRecord findByRegistration(Context context, String registration) {
        if (registration == null || registration.trim().isEmpty()) return null;
        String key = digitsOnly(registration);
        if (key.isEmpty()) return null;
        File f = new File(context.getFilesDir(), STORAGE_FILE);
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder(); String line; while ((line = r.readLine()) != null) sb.append(line);
            JSONArray array = new JSONArray(sb.toString());
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                if (!digitsOnly(o.optString("registration")).equals(key)) continue;
                EvaluationRecord rec = new EvaluationRecord();
                rec.registration = o.optString("registration");
                rec.name = o.optString("name");
                JSONObject values = o.optJSONObject("values");
                if (values != null) { java.util.Iterator<String> keys = values.keys(); while (keys.hasNext()) { String k = keys.next(); rec.values.put(k, values.optString(k)); } }
                return rec;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String digitsOnly(String value) { return value == null ? "" : value.replaceAll("[^0-9]", ""); }
}
