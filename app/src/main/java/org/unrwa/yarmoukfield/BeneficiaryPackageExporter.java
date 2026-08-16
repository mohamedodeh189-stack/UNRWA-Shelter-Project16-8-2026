package org.unrwa.yarmoukfield;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** P2 — writes the final beneficiary delivery folder into a user-picked document tree (SAF), ready to copy to a
 * laptop. Nothing goes in the database; this only writes files. Layout under the picked root:
 * <pre>
 *   مشروع المأوى في الأونروا/          (fixed project archive folder)
 *     المستفيدين/
 *       الاسم - رقم التسجيل/          (never overwritten — «…_2», «…_3» on clash)
 *       01_الكميات/الاسم_الكميات.xlsx
 *       02_CAD/الاسم.dxf, الاسم.pdf   (.bak is AutoCAD's own — the app never writes it)
 *       03_صور قبل الترميم/ …selected before-photos
 *       04_صور أثناء الترميم/         (empty until follow-up)
 *       05_صور بعد الترميم/           (empty until follow-up)
 *       06_الوثائق/
 *       07_البيانات/backup.json
 * </pre>
 * Any missing input (no plan, no quantities, no photos) is simply skipped and reported — it never fails the run.
 * SAF plumbing follows the proven pattern in {@link BeneficiaryPhotoManager}. */
public final class BeneficiaryPackageExporter {
    private BeneficiaryPackageExporter() {}

    /** Fixed project archive folder created under the picked root; all beneficiaries live inside it. */
    public static final String PROJECT_FOLDER = "مشروع المأوى في الأونروا";
    public static final String PARENT_FOLDER = "المستفيدين";

    public static final class Result {
        public Uri folderUri;          // the created «الاسم - رقم التسجيل» folder
        public String folderName;
        public final List<String> written = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
    }

    /** Build the package. {@code xlsx/dxf/pdf/backupJson} may be null (→ skipped). {@code beforePhotos} are source
     * content Uris to copy into «03_صور قبل الترميم». {@code refPhotos} are the IMPORTED reference photos, copied
     * into a «مرجعية_مستوردة» sub-folder so they stay apart from the engineer's own shots. Returns written/skipped. */
    public static Result export(ContentResolver r, Uri treeUri, String beneficiaryBaseName, String fileBaseName,
                                byte[] xlsx, byte[] dxf, byte[] pdf, byte[] backupJson, List<Uri> beforePhotos, List<Uri> refPhotos) throws Exception {
        if (treeUri == null) throw new Exception("اختر مجلد حفظ ملفات المستفيدين أولاً");
        Result res = new Result();
        Uri root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        // If the engineer picked the «مشروع المأوى في الأونروا» folder itself as root, don't nest a second one.
        Uri project = PROJECT_FOLDER.equals(displayName(r, root)) ? root : findOrCreateDir(r, treeUri, root, PROJECT_FOLDER);
        Uri parent = findOrCreateDir(r, treeUri, project, PARENT_FOLDER);
        Uri folder = createUniqueDir(r, treeUri, parent, beneficiaryBaseName);
        res.folderUri = folder;
        res.folderName = displayName(r, folder);

        Uri kmy = findOrCreateDir(r, treeUri, folder, "01_الكميات");
        if (xlsx != null) { writeFile(r, kmy, fileBaseName + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx); res.written.add("01_الكميات/" + fileBaseName + ".xlsx"); }
        else res.skipped.add("ملف المستفيد Excel (لا توجد كميات)");

        Uri cad = findOrCreateDir(r, treeUri, folder, "02_CAD");
        if (dxf != null) { writeFile(r, cad, fileBaseName + ".dxf", "application/dxf", dxf); res.written.add("02_CAD/" + fileBaseName + ".dxf"); }
        else res.skipped.add("DXF (لا يوجد مخطط)");
        if (pdf != null) { writeFile(r, cad, fileBaseName + ".pdf", "application/pdf", pdf); res.written.add("02_CAD/" + fileBaseName + ".pdf"); }
        else res.skipped.add("PDF (لا يوجد مخطط)");

        Uri before = findOrCreateDir(r, treeUri, folder, "03_صور قبل الترميم");
        findOrCreateDir(r, treeUri, folder, "04_صور أثناء الترميم"); // empty until follow-up
        findOrCreateDir(r, treeUri, folder, "05_صور بعد الترميم");   // empty until follow-up
        findOrCreateDir(r, treeUri, folder, "06_الوثائق");
        int copied = 0;
        if (beforePhotos != null) {
            int idx = 1;
            for (Uri src : beforePhotos) {
                try { copyStream(r, src, before, String.format(java.util.Locale.US, "%s_%02d.jpg", fileBaseName, idx)); idx++; copied++; }
                catch (Exception ignored) { /* a single unreadable photo must not fail the whole package */ }
            }
        }
        if (copied > 0) res.written.add("03_صور قبل الترميم (" + copied + " صورة)");
        else res.skipped.add("صور قبل الترميم (لم تُختَر صور)");
        copyReferencePhotos(r, treeUri, before, refPhotos, res);

        Uri data = findOrCreateDir(r, treeUri, folder, "07_البيانات");
        if (backupJson != null) { writeFile(r, data, "backup.json", "application/json", backupJson); res.written.add("07_البيانات/backup.json"); }
        return res;
    }

    /** Copy the imported «before_reference» photos into «03_صور قبل الترميم/مرجعية_مستوردة/» — kept separate from
     * the engineer's own before-shots. No-op when there are none; a single bad photo never fails the package. */
    private static void copyReferencePhotos(ContentResolver r, Uri treeUri, Uri before, List<Uri> refPhotos, Result res) throws Exception {
        if (refPhotos == null || refPhotos.isEmpty()) return;
        Uri refDir = findOrCreateDir(r, treeUri, before, "مرجعية_مستوردة");
        int n = 0, i = 1;
        for (Uri src : refPhotos) {
            String ext = src != null && src.getLastPathSegment() != null && src.getLastPathSegment().toLowerCase(java.util.Locale.US).endsWith(".png") ? "png" : "jpg";
            try { copyStream(r, src, refDir, String.format(java.util.Locale.US, "مرجعية_%02d.%s", i, ext)); i++; n++; }
            catch (Exception ignored) { }
        }
        if (n > 0) res.written.add("03_صور قبل الترميم/مرجعية_مستوردة (" + n + " صورة مرجعية)");
    }

    /** IN-PLACE update of the SAME official beneficiary folder — used by «اعتماد الدراسة وإرسالها للمراجعة
     * المكتبية». Unlike {@link #export}, this NEVER makes a «_2» copy: it reuses the exact «الاسم - رقم التسجيل»
     * folder (creating it once if it doesn't exist yet). backup.json is always refreshed; CAD/Excel/PDF and the
     * before-photos are only written when MISSING, so an engineer's edited AutoCAD/Excel is never clobbered. */
    public static Result update(ContentResolver r, Uri treeUri, String beneficiaryBaseName, String fileBaseName,
                                byte[] xlsx, byte[] dxf, byte[] pdf, byte[] backupJson, List<Uri> beforePhotos, List<Uri> refPhotos) throws Exception {
        return update(r, treeUri, beneficiaryBaseName, fileBaseName, xlsx, dxf, pdf, backupJson, beforePhotos, refPhotos, null);
    }
    /** §3.8 overload: also writes damage_locations.json into 07_البيانات (refreshed each run) when non-null. */
    public static Result update(ContentResolver r, Uri treeUri, String beneficiaryBaseName, String fileBaseName,
                                byte[] xlsx, byte[] dxf, byte[] pdf, byte[] backupJson, List<Uri> beforePhotos, List<Uri> refPhotos, byte[] damageJson) throws Exception {
        if (treeUri == null) throw new Exception("اختر مجلد حفظ ملفات المستفيدين أولاً");
        Result res = new Result();
        Uri root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        Uri project = PROJECT_FOLDER.equals(displayName(r, root)) ? root : findOrCreateDir(r, treeUri, root, PROJECT_FOLDER);
        Uri parent = findOrCreateDir(r, treeUri, project, PARENT_FOLDER);
        // Reuse the exact existing folder (or create it once) — NEVER «_2».
        Uri folder = findOrCreateDir(r, treeUri, parent, beneficiaryBaseName);
        res.folderUri = folder;
        res.folderName = displayName(r, folder);

        Uri kmy = findOrCreateDir(r, treeUri, folder, "01_الكميات");
        if (xlsx != null) writeIfMissing(r, treeUri, kmy, fileBaseName + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx, res, "01_الكميات/" + fileBaseName + ".xlsx");

        Uri cad = findOrCreateDir(r, treeUri, folder, "02_CAD");
        if (dxf != null) writeIfMissing(r, treeUri, cad, fileBaseName + ".dxf", "application/dxf", dxf, res, "02_CAD/" + fileBaseName + ".dxf");
        if (pdf != null) writeIfMissing(r, treeUri, cad, fileBaseName + ".pdf", "application/pdf", pdf, res, "02_CAD/" + fileBaseName + ".pdf");

        Uri before = findOrCreateDir(r, treeUri, folder, "03_صور قبل الترميم");
        findOrCreateDir(r, treeUri, folder, "04_صور أثناء الترميم");
        findOrCreateDir(r, treeUri, folder, "05_صور بعد الترميم");
        findOrCreateDir(r, treeUri, folder, "06_الوثائق");
        // Only populate before-photos when that folder is still empty, so we don't duplicate on every send.
        if (beforePhotos != null && isEmptyDir(r, treeUri, before)) {
            int idx = 1, copied = 0;
            for (Uri src : beforePhotos) {
                try { copyStream(r, src, before, String.format(java.util.Locale.US, "%s_%02d.jpg", fileBaseName, idx)); idx++; copied++; }
                catch (Exception ignored) { }
            }
            if (copied > 0) res.written.add("03_صور قبل الترميم (" + copied + " صورة)");
        }
        // Reference photos: copy into مرجعية_مستوردة only when that sub-folder is still empty (don't duplicate).
        Uri refCheck = findChild(r, treeUri, before, "مرجعية_مستوردة");
        if (refCheck == null || isEmptyDir(r, treeUri, refCheck)) copyReferencePhotos(r, treeUri, before, refPhotos, res);

        Uri data = findOrCreateDir(r, treeUri, folder, "07_البيانات");
        // backup.json is ALWAYS refreshed (this is the whole point of the office-review send).
        if (backupJson != null) { writeOrReplace(r, treeUri, data, "backup.json", "application/json", backupJson); res.written.add("07_البيانات/backup.json (محدّث)"); }
        // §3.8: damage locations as their own file, refreshed each run. Excel/quantities sheet is never touched.
        if (damageJson != null) { writeOrReplace(r, treeUri, data, "damage_locations.json", "application/json", damageJson); res.written.add("07_البيانات/damage_locations.json"); }
        return res;
    }

    /** Best-effort lookup of an already-created beneficiary folder (exact «base», else the newest «base_n»),
     * without creating anything. Returns null if the archive or the folder doesn't exist yet. Used by the
     * «فتح مجلد المستفيد» button. */
    public static Uri locateBeneficiaryFolder(ContentResolver r, Uri treeUri, String beneficiaryBaseName) {
        if (treeUri == null) return null;
        try {
            Uri root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            Uri project = PROJECT_FOLDER.equals(displayName(r, root)) ? root : findChild(r, treeUri, root, PROJECT_FOLDER);
            if (project == null) return null;
            Uri parent = findChild(r, treeUri, project, PARENT_FOLDER);
            if (parent == null) return null;
            Uri exact = findChild(r, treeUri, parent, beneficiaryBaseName);
            if (exact != null) return exact;
            // no exact match — return the highest «base_n» if any exist
            Uri best = null; int bestN = 1;
            for (int n = 2; n < 999; n++) {
                Uri u = findChild(r, treeUri, parent, beneficiaryBaseName + "_" + n);
                if (u == null) break;
                best = u; bestN = n;
            }
            return best;
        } catch (Exception e) { return null; }
    }

    // ---- SAF helpers (mirrors BeneficiaryPhotoManager) ----
    private static Uri findChild(ContentResolver r, Uri treeUri, Uri parent, String name) throws Exception {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        String[] proj = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor c = r.query(children, proj, null, null, null)) {
            while (c != null && c.moveToNext())
                if (name.equals(c.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
        }
        return null;
    }

    private static Uri findOrCreateDir(ContentResolver r, Uri treeUri, Uri parent, String name) throws Exception {
        Uri existing = findChild(r, treeUri, parent, name);
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(r, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new Exception("تعذر إنشاء المجلد: " + name);
        return created;
    }

    /** Create «base», or «base_2», «base_3»… so a previous delivery is never overwritten. */
    private static Uri createUniqueDir(ContentResolver r, Uri treeUri, Uri parent, String base) throws Exception {
        String name = base;
        for (int n = 2; findChild(r, treeUri, parent, name) != null; n++) name = base + "_" + n;
        Uri created = DocumentsContract.createDocument(r, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new Exception("تعذر إنشاء مجلد المستفيد");
        return created;
    }

    private static void writeFile(ContentResolver r, Uri dir, String name, String mime, byte[] data) throws Exception {
        Uri f = DocumentsContract.createDocument(r, dir, mime, name);
        if (f == null) throw new Exception("تعذر إنشاء الملف: " + name);
        try (OutputStream os = r.openOutputStream(f, "w")) {
            if (os == null) throw new Exception("تعذر فتح الملف للكتابة: " + name);
            os.write(data);
        }
    }

    /** Write {@code name} only if it doesn't already exist in {@code dir} — never clobbers an engineer's edited
     * CAD/Excel/PDF. Records to written/skipped for the summary. */
    private static void writeIfMissing(ContentResolver r, Uri treeUri, Uri dir, String name, String mime, byte[] data,
                                       Result res, String label) throws Exception {
        if (findChild(r, treeUri, dir, name) != null) { res.skipped.add(label + " (موجود مسبقاً — لم يُستبدل)"); return; }
        writeFile(r, dir, name, mime, data); res.written.add(label);
    }

    /** Create {@code name}, or truncate-and-rewrite it if it already exists (used for backup.json). */
    private static void writeOrReplace(ContentResolver r, Uri treeUri, Uri dir, String name, String mime, byte[] data) throws Exception {
        Uri f = findChild(r, treeUri, dir, name);
        if (f == null) { f = DocumentsContract.createDocument(r, dir, mime, name); if (f == null) throw new Exception("تعذر إنشاء الملف: " + name); }
        try (OutputStream os = r.openOutputStream(f, "wt")) {              // "wt" truncates existing content
            if (os == null) throw new Exception("تعذر فتح الملف للكتابة: " + name);
            os.write(data);
        }
    }

    private static boolean isEmptyDir(ContentResolver r, Uri treeUri, Uri dir) {
        try (Cursor c = r.query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(dir)),
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return c == null || !c.moveToFirst();
        } catch (Exception e) { return false; }
    }

    private static void copyStream(ContentResolver r, Uri src, Uri dir, String name) throws Exception {
        Uri f = DocumentsContract.createDocument(r, dir, "image/jpeg", name);
        if (f == null) throw new Exception("تعذر إنشاء صورة الوجهة");
        try (InputStream in = r.openInputStream(src); OutputStream os = r.openOutputStream(f, "w")) {
            if (in == null || os == null) throw new Exception("تعذر نسخ الصورة");
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private static String displayName(ContentResolver r, Uri doc) {
        try (Cursor c = r.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return "";
    }
}
