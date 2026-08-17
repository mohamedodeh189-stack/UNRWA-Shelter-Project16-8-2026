/* End-to-end regression test for the «التقييم» evaluation export.
 *
 * WHY THIS EXISTS: the evaluation feature spans three separate stages that were each verified in
 * isolation and still shipped broken, because the BUG WAS IN THE SEAM BETWEEN THEM — the importer
 * indexed records by the KoBo "1.5 - رقم التسجيل الفردي" column while the app's own
 * Beneficiary.registration field actually holds "1.4 - رقم تسجيل العائلة", so every lookup silently
 * missed and every evaluation cell exported blank. This test exercises the REAL code of all three
 * stages in one chain — import a KoBo xlsx -> look the record up by registration -> write the
 * workbook — so a mismatch like that fails here instead of in the field.
 *
 * Android/org.json are stubbed under tests/eval_e2e_stubs so the real classes run on a plain JDK.
 * The stubs must come FIRST on the runtime classpath so they shadow android.jar's throwing stubs.
 *
 *   javac -d out -cp "tests/eval_e2e_stubs_classes:app-classes:gson.jar" tests/EvaluationExportE2E.java
 *   java  -cp "tests/eval_e2e_stubs_classes:app-classes:gson.jar:android.jar" \
 *         EvaluationExportE2E <appDataDir> <koboExport.xlsx> <output.xlsx> <registrationNo> [name]
 *
 * Expected: import count > 0, lookup MATCHED, and every evaluation cell populated from the KoBo row.
 */
import android.content.Context;
import android.net.Uri;
import org.unrwa.yarmoukfield.*;
import java.io.*;
import java.util.*;

public class EvaluationExportE2E {
    public static void main(String[] args) throws Exception {
        File base = new File(args[0]);
        File filesDir = new File(base, "files"); filesDir.mkdirs();
        File cacheDir = new File(base, "cache"); cacheDir.mkdirs();
        Context ctx = new Context(filesDir, cacheDir);

        // ---- STEP 1: the real importer, on a realistic KoBo export ----
        Uri koboUri = Uri.fromFile(new File(args[1]));
        EvaluationImporter.ImportResult res = EvaluationImporter.importFrom(ctx, koboUri);
        System.out.println("STEP 1 import -> count=" + res.count + "  error=" + (res.error == null ? "" : res.error));
        System.out.println("         storedCount=" + EvaluationImporter.storedCount(ctx));
        if (res.error != null && !res.error.isEmpty()) { System.out.println("IMPORT FAILED"); return; }

        // ---- STEP 2: the real lookup, using the registration the app actually stores ----
        // Passed in, never hardcoded: this is a real beneficiary's registration number in practice,
        // and no real beneficiary data may be committed to this repository (see project HANDOFF).
        String appRegistration = args[3];
        String beneficiaryName = args.length > 4 ? args[4] : "";
        EvaluationRecord rec = EvaluationImporter.findByRegistration(ctx, appRegistration);
        System.out.println("STEP 2 findByRegistration(\"" + appRegistration + "\") -> " + (rec == null ? "NULL (NO MATCH)" : "MATCHED"));
        if (rec == null) { System.out.println("LOOKUP FAILED"); return; }
        System.out.println("         name=" + rec.name + "  values=" + rec.values.size());
        for (Map.Entry<String,String> e : rec.values.entrySet())
            System.out.println("           " + e.getKey() + " = " + e.getValue());

        // ---- STEP 3: the real export ----
        Map<Integer, Double> qty = new LinkedHashMap<>();
        for (int i = 1; i <= 37; i++) qty.put(i, (double)(i % 5) * 1.5 + 1);
        try (InputStream tpl = new FileInputStream("/home/user/UNRWA-Shelter-Project16-8-2026/app/src/main/assets/quantities_template.xlsx");
             OutputStream out = new FileOutputStream(args[2])) {
            BeneficiaryXlsx.write(tpl, qty, beneficiaryName, null, appRegistration, rec, out);
        }
        System.out.println("STEP 3 wrote " + args[2]);
    }
}
