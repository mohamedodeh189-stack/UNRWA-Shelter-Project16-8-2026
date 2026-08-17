package org.unrwa.yarmoukfield;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Fills the project's own quantities workbook (bundled as {@code assets/quantities_template.xlsx}) with the
 * data the APP is responsible for — nothing else. It copies every part of the template byte-for-byte and edits
 * ONLY the الكميات sheet: it writes each item's engineer-entered quantity into the empty column F, and the
 * beneficiary name into the header cell. Prices, descriptions, item list, formulas (cost = F×G, totals, the
 * 60%/40% payments) and every other sheet stay exactly as the template — so the delivered file matches the
 * original project format and Excel recalculates the totals on open.
 *
 * Pure Java (java.util.zip only, no external library, no Android) → unit-testable on a plain JDK. In the app the
 * template comes from AssetManager; here any InputStream works. The الكميات sheet is the workbook's first sheet
 * (xl/worksheets/sheet1.xml) and item N lives on row N+6 (item 1 → row 7 … item 37 → row 43). */
public final class BeneficiaryXlsx {
    private BeneficiaryXlsx() {}

    public static final String TEMPLATE_ASSET = "quantities_template.xlsx";
    private static final String QTY_SHEET = "xl/worksheets/sheet1.xml"; // ورقة «الكميات»
    private static final int FIRST_ITEM_ROW = 7;                        // item 1 sits on row 7
    private static final String NAME_CELL = "I3";                      // header value cell for اسم المستفيد
    private static final String PHOTO_SHEET = "sheet4.xml";            // ورقة «صور قبل الترميم» (rId4)
    private static final String DRAW_RID = "rId100";                   // sheet→drawing rel id (unused by template)
    private static final String EVAL_SHEET = "xl/worksheets/sheet7.xml"; // ورقة «التقييم» — أول ورقة بالحزمة

    /** Placeholder key -> cell reference on the «التقييم» sheet, matching exactly how that sheet was authored
     * (see HANDOFF for the generating script). NAME and REG are filled for every export straight from the
     * app's own beneficiary record; everything else only when a matching EvaluationRecord was found on this
     * device (see EvaluationImporter) — a beneficiary with no imported KoBo match simply keeps those cells
     * blank, never a guessed value. */
    private static final Map<String, String> EVAL_CELL_BY_KEY = buildEvalCellMap();
    private static Map<String, String> buildEvalCellMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("NAME","D1"); m.put("ENGINEER","G1");
        m.put("AGE","D3"); m.put("GENDER","F3"); m.put("MARITAL","D4"); m.put("RESIDENCE","F4"); m.put("SECONDWIFE","D5");
        m.put("FAMILYREG","B6"); m.put("OWNERSHIP","D6"); m.put("OWNERDOCS","F6");
        m.put("REG","B7"); m.put("PLOTREF","D7");
        m.put("LEDGER","B8"); m.put("BUILDINGNO","D8"); m.put("STREET","F8");
        m.put("FAMSIZE","B9"); m.put("PHONE1","D9"); m.put("ADDRESS","F9");
        m.put("NATID","B10"); m.put("PHONE2","D10"); m.put("FLOOR","F10");
        m.put("HEALTHSOCIAL_TOTAL","G12"); m.put("HEALTH_SUB","G13");
        m.put("SCORE_CHRONIC","G14"); m.put("SCORE_MENTAL","G15");
        m.put("SOCIAL_SUB","G16"); m.put("SCORE_VULN","G17"); m.put("SCORE_CROWD","G18");
        m.put("SCORE_GENDERSEP","G19"); m.put("SCORE_INCOME","G20");
        m.put("PHYSICAL_TOTAL","G21");
        m.put("SCORE_STRUCT_DEVIATION","C22"); m.put("SCORE_STRUCT_CRACKING","D22"); m.put("SCORE_STRUCT_SPALLING","E22");
        m.put("SCORE_STRUCT_STABILITY","F22"); m.put("SCORE_STRUCT_TOTAL","G22");
        m.put("OTHERCOND_SUB","G23"); m.put("SCORE_TOILET","G24"); m.put("SCORE_KITCHEN","G25");
        m.put("SCORE_VENTILATION","G26"); m.put("SCORE_DAMPNESS","G27"); m.put("SCORE_SEWAGE","G28");
        m.put("GRAND_TOTAL","G29");
        return m;
    }

    /** Backward-compatible overload: quantities + name only, no embedded photos (used by the standalone quantities
     * export). */
    public static void write(InputStream template, Map<Integer, Double> quantitiesByItemNo,
                             String beneficiaryName, OutputStream out) throws IOException {
        write(template, quantitiesByItemNo, beneficiaryName, null, null, null, out);
    }

    /** Copy the template, injecting quantities (keyed by 1-based item number) into column F, the beneficiary name
     * into {@link #NAME_CELL}, and — when {@code beforePhotos} is non-empty — embedding those JPEGs into the
     * «صور قبل الترميم» sheet so the delivered workbook carries the photos inside it, not just alongside it. Zero
     * quantities stay blank so the template's empty look is kept. */
    public static void write(InputStream template, Map<Integer, Double> quantitiesByItemNo,
                             String beneficiaryName, List<byte[]> beforePhotos, OutputStream out) throws IOException {
        write(template, quantitiesByItemNo, beneficiaryName, beforePhotos, null, null, out);
    }

    /** Full form: also fills the «التقييم» sheet (first in the workbook) — {@code registration} always (the app
     * already has it for every beneficiary), plus every other field on that sheet when {@code evaluation} is a
     * match found on this device (see EvaluationImporter); null leaves those cells blank. */
    public static void write(InputStream template, Map<Integer, Double> quantitiesByItemNo,
                             String beneficiaryName, List<byte[]> beforePhotos, String registration,
                             EvaluationRecord evaluation, OutputStream out) throws IOException {
        LinkedHashMap<String, byte[]> parts = new LinkedHashMap<>();
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(template));
        byte[] buf = new byte[8192];
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int n;
            while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
            parts.put(e.getName(), bo.toByteArray());
        }
        zin.close();

        byte[] qty = parts.get(QTY_SHEET);
        if (qty != null) {
            String xml = new String(qty, StandardCharsets.UTF_8);
            xml = injectQuantities(xml, quantitiesByItemNo);
            xml = injectName(xml, beneficiaryName);
            parts.put(QTY_SHEET, xml.getBytes(StandardCharsets.UTF_8));
        }

        byte[] eval = parts.get(EVAL_SHEET);
        if (eval != null) {
            String xml = new String(eval, StandardCharsets.UTF_8);
            Map<String, String> values = new LinkedHashMap<>();
            if (evaluation != null) values.putAll(evaluation.values);
            if (beneficiaryName != null && !beneficiaryName.trim().isEmpty()) values.put("NAME", beneficiaryName.trim());
            if (registration != null && !registration.trim().isEmpty()) values.put("REG", registration.trim());
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String cellRef = EVAL_CELL_BY_KEY.get(entry.getKey());
                if (cellRef == null || entry.getValue() == null || entry.getValue().trim().isEmpty()) continue;
                xml = injectInlineText(xml, cellRef, entry.getValue().trim());
            }
            parts.put(EVAL_SHEET, xml.getBytes(StandardCharsets.UTF_8));
        }

        if (beforePhotos != null && !beforePhotos.isEmpty()) embedPhotos(parts, beforePhotos);

        ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(out));
        for (Map.Entry<String, byte[]> p : parts.entrySet()) {
            zout.putNextEntry(new ZipEntry(p.getKey()));
            zout.write(p.getValue());
            zout.closeEntry();
        }
        zout.finish();
        zout.flush();
    }

    /** Adds the media, drawing part, drawing rels, the sheet→drawing relationship, the sheet's {@code <drawing/>}
     * element, and the two content-type declarations — the full set of parts Excel needs to render pictures on
     * the «صور قبل الترميم» sheet. */
    private static void embedPhotos(LinkedHashMap<String, byte[]> parts, List<byte[]> photos) {
        int count = photos.size();
        for (int i = 0; i < count; i++) parts.put("xl/media/image" + (i + 1) + ".jpeg", photos.get(i));
        parts.put("xl/drawings/drawing1.xml", buildDrawingXml(count).getBytes(StandardCharsets.UTF_8));
        parts.put("xl/drawings/_rels/drawing1.xml.rels", buildDrawingRels(count).getBytes(StandardCharsets.UTF_8));

        String relsName = "xl/worksheets/_rels/" + PHOTO_SHEET + ".rels";
        byte[] existingRels = parts.get(relsName);
        String rels = existingRels != null ? new String(existingRels, StandardCharsets.UTF_8)
                : "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>";
        rels = rels.replace("</Relationships>", "<Relationship Id=\"" + DRAW_RID
                + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing1.xml\"/></Relationships>");
        parts.put(relsName, rels.getBytes(StandardCharsets.UTF_8));

        String sheetName = "xl/worksheets/" + PHOTO_SHEET;
        byte[] sheet = parts.get(sheetName);
        if (sheet != null) {
            String sx = new String(sheet, StandardCharsets.UTF_8);
            if (!sx.contains("<drawing ")) sx = sx.replace("</worksheet>", "<drawing r:id=\"" + DRAW_RID + "\"/></worksheet>");
            parts.put(sheetName, sx.getBytes(StandardCharsets.UTF_8));
        }

        byte[] ct = parts.get("[Content_Types].xml");
        if (ct != null) {
            String cx = new String(ct, StandardCharsets.UTF_8);
            if (!cx.contains("Extension=\"jpeg\"")) cx = cx.replace("</Types>", "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/></Types>");
            if (!cx.contains("/xl/drawings/drawing1.xml")) cx = cx.replace("</Types>", "<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/></Types>");
            parts.put("[Content_Types].xml", cx.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** One {@code twoCellAnchor} per image, stacked down the sheet (each ~18 rows tall with a 2-row gap), spanning
     * columns B..J so every photo is comfortably large. */
    private static String buildDrawingXml(int count) {
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        b.append("<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" ");
        b.append("xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ");
        b.append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        for (int i = 0; i < count; i++) {
            int r0 = i * 20 + 1, r1 = r0 + 18, id = i + 2;
            b.append("<xdr:twoCellAnchor editAs=\"oneCell\">");
            b.append("<xdr:from><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>").append(r0).append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>");
            b.append("<xdr:to><xdr:col>9</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>").append(r1).append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>");
            b.append("<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"").append(id).append("\" name=\"Photo ").append(i + 1).append("\"/>");
            b.append("<xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr></xdr:nvPicPr>");
            b.append("<xdr:blipFill><a:blip r:embed=\"rId").append(i + 1).append("\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>");
            b.append("<xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></a:xfrm>");
            b.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:twoCellAnchor>");
        }
        b.append("</xdr:wsDr>");
        return b.toString();
    }

    private static String buildDrawingRels(int count) {
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        b.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < count; i++)
            b.append("<Relationship Id=\"rId").append(i + 1)
             .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image")
             .append(i + 1).append(".jpeg\"/>");
        b.append("</Relationships>");
        return b.toString();
    }

    /** Replace each empty styled cell {@code <c r="F{row}" s="…"/>} with a valued one, preserving its style. */
    static String injectQuantities(String xml, Map<Integer, Double> quantities) {
        if (quantities == null) return xml;
        for (Map.Entry<Integer, Double> en : quantities.entrySet()) {
            if (en.getKey() == null || en.getValue() == null) continue;
            double qty = en.getValue();
            if (qty <= 0) continue;                                    // leave blank, like the template
            int row = FIRST_ITEM_ROW + en.getKey() - 1;
            String ref = "F" + row;
            Matcher m = Pattern.compile("<c r=\"" + ref + "\"([^>]*?)/>").matcher(xml);
            if (m.find()) {
                String rep = "<c r=\"" + ref + "\"" + m.group(1) + "><v>" + num(qty) + "</v></c>";
                xml = xml.substring(0, m.start()) + rep + xml.substring(m.end());
            }
        }
        return xml;
    }

    /** Put the beneficiary name into the header value cell as an inline string (no sharedStrings edit needed). */
    static String injectName(String xml, String name) {
        if (name == null || name.trim().isEmpty()) return xml;
        String esc = xmlEscape(name.trim());
        Matcher m = Pattern.compile("<c r=\"" + NAME_CELL + "\"([^>]*?)/>").matcher(xml);
        if (m.find()) {
            String attrs = m.group(1).replaceAll("\\s+t=\"[^\"]*\"", ""); // drop any existing cell type
            String rep = "<c r=\"" + NAME_CELL + "\"" + attrs + " t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                    + esc + "</t></is></c>";
            xml = xml.substring(0, m.start()) + rep + xml.substring(m.end());
        }
        return xml;
    }

    /** Put an arbitrary value into an arbitrary cell as an inline string (used for the «التقييم» sheet). */
    static String injectInlineText(String xml, String cellRef, String value) {
        if (cellRef == null || value == null || value.trim().isEmpty()) return xml;
        String esc = xmlEscape(value.trim());
        Matcher m = Pattern.compile("<c r=\"" + cellRef + "\"([^>]*?)/>").matcher(xml);
        if (m.find()) {
            String attrs = m.group(1).replaceAll("\\s+t=\"[^\"]*\"", ""); // drop any existing cell type
            String rep = "<c r=\"" + cellRef + "\"" + attrs + " t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                    + esc + "</t></is></c>";
            xml = xml.substring(0, m.start()) + rep + xml.substring(m.end());
        }
        return xml;
    }

    private static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return Double.toString(v);
    }

    private static String xmlEscape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }
}
