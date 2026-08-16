package org.unrwa.yarmoukfield;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Pulls the RASTER images embedded inside an OOXML workbook (.xlsx / .xlsm) — the «صور قبل الترميم» that the
 * field team pasted into each beneficiary's Excel live under the OPC part «xl/media/». An .xlsx is itself a ZIP,
 * so this opens it and returns the jpeg/jpg/png parts (skipping emf/wmf vector previews and anything non-raster).
 * Pure Java, no Android — deterministic and JVM-testable. Never throws on a damaged/non-zip input: returns empty.
 * The images are returned UNCHANGED (byte-for-byte), so callers keep the originals intact as reference photos. */
public final class EmbeddedImageExtractor {
    private EmbeddedImageExtractor() {}

    public static final class Img {
        public final String name;      // original media part name, e.g. "image1.jpeg"
        public final byte[] bytes;     // raw image bytes, unmodified
        Img(String name, byte[] bytes) { this.name = name; this.bytes = bytes; }
    }

    /** Raster images under xl/media/ (jpeg/jpg/png only), in workbook zip order; empty for a non-xlsx/damaged file. */
    public static List<Img> fromXlsx(File xlsx) {
        List<Img> out = new ArrayList<>();
        if (xlsx == null || !xlsx.exists() || xlsx.length() == 0) return out;
        try (ZipFile z = new ZipFile(xlsx)) {
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                if (en.isDirectory()) continue;
                String n = en.getName() == null ? "" : en.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!n.startsWith("xl/media/")) continue;
                if (!(n.endsWith(".jpeg") || n.endsWith(".jpg") || n.endsWith(".png"))) continue; // skip emf/wmf/gif/…
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = z.getInputStream(en)) {
                    byte[] buf = new byte[8192]; int r;
                    while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                }
                byte[] b = bo.toByteArray();
                if (b.length > 0) out.add(new Img(lastSegment(en.getName()), b));
            }
        } catch (Exception ignored) { /* non-zip / damaged / password — treat as "no embedded images" */ }
        return out;
    }

    private static String lastSegment(String p) {
        if (p == null) return "";
        int i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return i >= 0 ? p.substring(i + 1) : p;
    }

    /** Raster images grouped by the WORKSHEET they are anchored to, resolved through the OPC relationship chain
     * (workbook → sheet → drawing → media). This lets a caller tell a «صور قبل الترميم» photo from an «الوثائق»
     * document scan instead of lumping every xl/media image together. Returns sheetName → images (jpeg/jpg/png
     * only; emf/wmf skipped). Never throws — an empty map for a non-xlsx/damaged file. */
    public static java.util.Map<String, List<Img>> fromXlsxBySheet(File xlsx) {
        java.util.LinkedHashMap<String, List<Img>> out = new java.util.LinkedHashMap<>();
        if (xlsx == null || !xlsx.exists() || xlsx.length() == 0) return out;
        try (ZipFile z = new ZipFile(xlsx)) {
            // 1) sheet display name -> rId (declaration order)
            String wb = readText(z, "xl/workbook.xml");
            java.util.List<String[]> sheets = new ArrayList<>();
            java.util.regex.Matcher ms = java.util.regex.Pattern.compile("<sheet\\b[^>]*\\bname=\"([^\"]*)\"[^>]*\\br:id=\"(rId\\d+)\"").matcher(wb);
            while (ms.find()) sheets.add(new String[]{unescapeXml(ms.group(1)), ms.group(2)});
            if (sheets.isEmpty()) return out;
            // 2) rId -> worksheet target
            java.util.Map<String, String> rid2ws = new java.util.HashMap<>();
            java.util.regex.Matcher mr = java.util.regex.Pattern.compile("Id=\"(rId\\d+)\"[^>]*Target=\"([^\"]+)\"").matcher(readText(z, "xl/_rels/workbook.xml.rels"));
            while (mr.find()) rid2ws.put(mr.group(1), mr.group(2));
            for (String[] s : sheets) {
                String sheetName = s[0], wsTarget = rid2ws.get(s[1]);
                if (wsTarget == null) continue;
                String wsBase = lastSegment(wsTarget);
                // 3) worksheet -> drawing (real .xml drawing only; a VML/vmlDrawing plan sketch is skipped)
                java.util.regex.Matcher md = java.util.regex.Pattern.compile("Target=\"([^\"]*drawing\\d+\\.xml)\"").matcher(readText(z, "xl/worksheets/_rels/" + wsBase + ".rels"));
                if (!md.find()) continue;
                // 4) drawing -> media
                java.util.regex.Matcher mi = java.util.regex.Pattern.compile("Target=\"([^\"]*media/[^\"]+)\"").matcher(readText(z, "xl/drawings/_rels/" + lastSegment(md.group(1)) + ".rels"));
                List<Img> imgs = new ArrayList<>();
                while (mi.find()) {
                    String mediaName = lastSegment(mi.group(1));
                    String low = mediaName.toLowerCase(Locale.ROOT);
                    if (!(low.endsWith(".jpeg") || low.endsWith(".jpg") || low.endsWith(".png"))) continue;
                    ZipEntry en = z.getEntry("xl/media/" + mediaName);
                    if (en == null) continue;
                    byte[] b = readBytes(z, en);
                    if (b.length > 0) imgs.add(new Img(mediaName, b));
                }
                if (!imgs.isEmpty()) {
                    List<Img> list = out.get(sheetName);
                    if (list == null) { list = new ArrayList<>(); out.put(sheetName, list); }
                    list.addAll(imgs);
                }
            }
        } catch (Exception ignored) { /* non-zip / damaged — treat as "no images" */ }
        return out;
    }

    private static String readText(ZipFile z, String path) {
        try {
            ZipEntry e = z.getEntry(path);
            if (e == null) return "";
            return new String(readBytes(z, e), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }
    private static byte[] readBytes(ZipFile z, ZipEntry e) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = z.getInputStream(e)) {
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        }
        return bo.toByteArray();
    }
    private static String unescapeXml(String s) {
        return s == null ? "" : s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'");
    }
}
