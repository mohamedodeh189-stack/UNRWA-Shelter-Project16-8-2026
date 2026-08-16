package org.unrwa.yarmoukfield;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Safely extracts supported import files (.xlsx/.xlsm/.csv/.json) from a user-picked .zip archive into a
 * private per-import subfolder of the app's own cache dir, then hands each extracted file to the SAME
 * SpreadsheetImporter.read() used for a normal single-file import — a .zip is just a container here, never a
 * parallel import path with its own parsing rules.
 *
 * Guards, since the archive comes from unmanaged field storage and its structure can't be trusted:
 *  - Zip Slip / path traversal: every extracted name is flattened to its own basename (no subfolders are ever
 *    recreated) and the resolved destination file's canonical path is re-checked to still sit inside the
 *    extraction directory before anything is written.
 *  - Zip Bomb / resource limits: caps on the compressed archive size itself, on entry count, on each entry's
 *    uncompressed size, and on the running total uncompressed size — enforced while streaming out, not just
 *    trusted from the (spoofable) central directory header.
 *  - __MACOSX/hidden/unsupported entries are skipped, never treated as an error.
 * Uses only the already-granted SAF Uri — no broad storage permission, no network. */
public final class ZipImportHelper {
    private ZipImportHelper() {}

    public static final int MAX_ENTRIES = 300;
    public static final long MAX_ENTRY_UNCOMPRESSED_BYTES = 80L * 1024 * 1024;   // 80MB per extracted file
    public static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 250L * 1024 * 1024;  // 250MB whole archive content
    /** A real field-collected .zip was rejected here at 120MB even though its actual content (spreadsheets)
     * was small — the Zip Bomb defense is the UNCOMPRESSED streaming counters above (MAX_ENTRY_/MAX_TOTAL_
     * UNCOMPRESSED_BYTES), which catch a small-compressed/huge-uncompressed bomb regardless of this number.
     * Capping the RAW COMPRESSED input size here was a redundant, overly conservative gate unrelated to that
     * actual risk — compressed size says nothing about danger, only about how much legitimate data (e.g.
     * several XLSX workbooks, or embedded images inside them) the archive happens to hold. Raised well above
     * the uncompressed content cap (compressed can never exceed uncompressed) to a sanity ceiling that only
     * guards against a truly absurd/corrupted input, not real exports. */
    public static final long MAX_ARCHIVE_BYTES = 400L * 1024 * 1024;             // 400MB compressed input sanity ceiling

    // ".xls" (legacy binary Excel) is collected here so it appears in the archive summary and is attempted,
    // but SpreadsheetImporter.read() rejects it explicitly with a clear "convert to XLSX first" message
    // rather than silently mis-reading its binary bytes as CSV — so a batch reports it as a clean, named
    // failure instead of ever producing garbage beneficiary rows from it.
    private static final Set<String> SUPPORTED_EXT = new HashSet<>(Arrays.asList("xlsx", "xlsm", "xls", "csv", "json"));

    public static final class ExtractedFile {
        public final String originalName;
        public final File file;
        public final long size;
        ExtractedFile(String originalName, File file, long size) { this.originalName = originalName; this.file = file; this.size = size; }
    }

    public static final class ExtractResult {
        public final File extractDir;
        public final List<ExtractedFile> files = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        ExtractResult(File dir) { extractDir = dir; }
    }

    public static boolean looksLikeZip(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    /** Copies the picked zip to cache, validates and extracts only supported entries into a fresh subfolder.
     * Throws with a clear Arabic message on any structural or size problem; the raw zip copy is always deleted
     * before returning, success or failure. Caller is responsible for calling cleanup(extractDir) once done
     * with the returned files. */
    public static ExtractResult extract(Context context, Uri zipUri) throws Exception {
        File rawZip = File.createTempFile("yarmouk_zip_", ".zip", context.getCacheDir());
        try {
            long copied = 0;
            try (InputStream in = context.getContentResolver().openInputStream(zipUri);
                 FileOutputStream out = new FileOutputStream(rawZip)) {
                if (in == null) throw new IllegalArgumentException("تعذر فتح الملف المضغوط");
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    copied += n;
                    if (copied > MAX_ARCHIVE_BYTES)
                        throw new IllegalArgumentException("الملف المضغوط أكبر من الحد المسموح (" + (MAX_ARCHIVE_BYTES / 1024 / 1024) + " ميغابايت).");
                    out.write(buffer, 0, n);
                }
            }

            File extractDir = new File(context.getCacheDir(), "yarmouk_zip_import_" + System.currentTimeMillis());
            if (!extractDir.mkdirs()) throw new IllegalArgumentException("تعذر إنشاء مجلد مؤقت للاستخراج");
            String canonicalRoot = extractDir.getCanonicalPath() + File.separator;

            ExtractResult result = new ExtractResult(extractDir);
            int entryCount = 0;
            long totalUncompressed = 0;
            try (ZipFile zip = new ZipFile(rawZip)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    entryCount++;
                    if (entryCount > MAX_ENTRIES)
                        throw new IllegalArgumentException("عدد الملفات داخل الأرشيف أكبر من الحد المسموح (" + MAX_ENTRIES + ").");
                    if (entry.isDirectory()) continue;
                    String name = entry.getName() == null ? "" : entry.getName();
                    String base = lastSegment(name);
                    // «~$…» is an Excel lock/owner temp file left inside field ZIPs when a workbook was open — it is
                    // not real data and would otherwise be parsed as a failed/garbage beneficiary row.
                    if (name.contains("__MACOSX") || base.isEmpty() || base.startsWith(".") || base.startsWith("~$") || base.startsWith("~")) {
                        result.skipped.add(name + " — تم تجاهله");
                        continue;
                    }
                    String ext = extensionOf(base);
                    if (!SUPPORTED_EXT.contains(ext)) {
                        result.skipped.add(name + " — نوع غير مدعوم");
                        continue;
                    }
                    long declaredSize = entry.getSize();
                    if (declaredSize > MAX_ENTRY_UNCOMPRESSED_BYTES)
                        throw new IllegalArgumentException("ملف داخل الأرشيف أكبر من الحد المسموح: " + base);

                    File outFile = uniqueTarget(extractDir, base);
                    String outCanonical = outFile.getCanonicalPath();
                    if (!outCanonical.startsWith(canonicalRoot)) {
                        // Should be unreachable given the flattened basename above — kept as defense in depth.
                        result.skipped.add(name + " — مسار غير آمن، تم تجاهله");
                        continue;
                    }

                    long written = 0;
                    try (InputStream entryIn = zip.getInputStream(entry); FileOutputStream entryOut = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = entryIn.read(buffer)) > 0) {
                            written += n;
                            totalUncompressed += n;
                            if (written > MAX_ENTRY_UNCOMPRESSED_BYTES || totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES)
                                throw new IllegalArgumentException("حجم المحتوى بعد فك الضغط أكبر من الحد المسموح — تم إيقاف الاستخراج لحماية الجهاز.");
                            entryOut.write(buffer, 0, n);
                        }
                    }
                    result.files.add(new ExtractedFile(name, outFile, written));
                }
            }
            return result;
        } finally {
            rawZip.delete();
        }
    }

    /** Deletes every extracted file plus the extraction subfolder itself. Best-effort and safe to call more
     * than once — it only ever touches this app's own cache directory, never any real field data. */
    public static void cleanup(File extractDir) {
        if (extractDir == null || !extractDir.exists()) return;
        File[] children = extractDir.listFiles();
        if (children != null) for (File f : children) f.delete();
        extractDir.delete();
    }

    private static String extensionOf(String baseName) {
        int dot = baseName.lastIndexOf('.');
        return dot < 0 ? "" : baseName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String lastSegment(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    /** Every entry is flattened directly into extractDir (no subfolders are ever recreated) — this alone
     * removes any possibility of Zip Slip, independent of the canonical-path check above. Name collisions
     * between entries from different subfolders in the source archive are resolved by appending a counter
     * rather than silently overwriting one extracted file with another. */
    private static File uniqueTarget(File dir, String baseName) {
        File candidate = new File(dir, baseName);
        if (!candidate.exists()) return candidate;
        String stem = baseName, ext = "";
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) { stem = baseName.substring(0, dot); ext = baseName.substring(dot); }
        int counter = 2;
        while (candidate.exists()) { candidate = new File(dir, stem + "_" + counter + ext); counter++; }
        return candidate;
    }
}
