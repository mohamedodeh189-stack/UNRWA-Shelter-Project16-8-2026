package org.unrwa.yarmoukfield.drawing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Self-contained JSON serialization for {@link DrawModel} — no org.json, no Android, so a sketch round-trips
 * identically on a plain JDK (test) and on the device. Includes a small strict JSON reader (objects, arrays,
 * strings with \\uXXXX escapes, numbers, booleans, null) sufficient for both this module's own output and a
 * reasonably hand-edited file. Arabic room names are written literally as UTF-8 and read back byte-for-byte. */
public final class DrawJson {
    private DrawJson() {}

    // ---------------- write ----------------
    public static String toJson(DrawModel m) {
        StringBuilder b = new StringBuilder();
        b.append('{');
        num(b, "pxPerMeter", m.pxPerMeter).append(',');
        num(b, "northDeg", m.northDeg).append(',');
        num(b, "ceilingHeightM", m.ceilingHeightM).append(',');
        str(b, "dimensionRef", m.dimensionRef).append(',');
        b.append("\"layers\":[");
        for (int i = 0; i < m.layers.size(); i++) {
            DrawLayer l = m.layers.get(i);
            if (i > 0) b.append(',');
            b.append('{'); str(b, "id", l.id).append(','); str(b, "nameAr", l.nameAr).append(',');
            str(b, "dxfName", l.dxfName).append(','); bool(b, "visible", l.visible); b.append('}');
        }
        b.append("],\"elements\":[");
        for (int i = 0; i < m.elements.size(); i++) {
            DrawElement e = m.elements.get(i);
            if (i > 0) b.append(',');
            b.append('{'); str(b, "type", e.type).append(','); str(b, "layer", e.layer).append(',');
            b.append("\"pts\":[");
            if (e.pts != null) for (int p = 0; p < e.pts.length; p++) { if (p > 0) b.append(','); b.append(trim(e.pts[p])); }
            b.append("],");
            str(b, "label", e.label).append(','); str(b, "code", e.code).append(',');
            bool(b, "damaged", e.damaged).append(','); str(b, "damageNote", e.damageNote).append(',');
            num(b, "thicknessM", e.thicknessM).append(',');
            // A2 (doors): identity + host link + placement so a door round-trips and stays attached to its wall
            str(b, "id", e.id).append(','); str(b, "hostId", e.hostId).append(',');
            num(b, "t", e.t).append(','); num(b, "widthM", e.widthM).append(',');
            num(b, "swing", e.swing).append(','); str(b, "kind", e.kind);
            b.append('}');
        }
        b.append("]}");
        return b.toString();
    }

    private static StringBuilder num(StringBuilder b, String k, double v) { return b.append('"').append(k).append("\":").append(trim(v)); }
    private static StringBuilder bool(StringBuilder b, String k, boolean v) { return b.append('"').append(k).append("\":").append(v); }
    private static StringBuilder str(StringBuilder b, String k, String v) { return b.append('"').append(k).append("\":").append(quote(v)); }

    /** Compact number: integers without a trailing ".0". */
    private static String trim(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return Double.toString(v);
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c); // Arabic and other printable characters written literally
            }
        }
        return b.append('"').toString();
    }

    // ---------------- read ----------------
    @SuppressWarnings("unchecked")
    public static DrawModel fromJson(String s) {
        Object root = new Reader(s).parseValue();
        if (!(root instanceof Map)) throw new IllegalArgumentException("JSON root is not an object");
        Map<String, Object> o = (Map<String, Object>) root;
        DrawModel m = new DrawModel();
        m.layers.clear();
        m.pxPerMeter = asDouble(o.get("pxPerMeter"), 0);
        m.northDeg = asDouble(o.get("northDeg"), 0);
        m.ceilingHeightM = asDouble(o.get("ceilingHeightM"), 0);
        String dref = asStr(o.get("dimensionRef")); if (!dref.isEmpty()) m.dimensionRef = dref;
        Object layers = o.get("layers");
        if (layers instanceof List) for (Object item : (List<Object>) layers) {
            Map<String, Object> lo = (Map<String, Object>) item;
            m.layers.add(new DrawLayer(asStr(lo.get("id")), asStr(lo.get("nameAr")), asStr(lo.get("dxfName")), asBool(lo.get("visible"), true)));
        }
        if (m.layers.isEmpty()) m.layers.addAll(DrawLayer.defaults());
        Object els = o.get("elements");
        if (els instanceof List) for (Object item : (List<Object>) els) {
            Map<String, Object> eo = (Map<String, Object>) item;
            DrawElement e = new DrawElement();
            e.type = asStr(eo.get("type")); e.layer = asStr(eo.get("layer"));
            Object pts = eo.get("pts");
            if (pts instanceof List) {
                List<Object> pl = (List<Object>) pts;
                e.pts = new double[pl.size()];
                for (int i = 0; i < pl.size(); i++) e.pts[i] = asDouble(pl.get(i), 0);
            }
            e.label = asStr(eo.get("label")); e.code = asStr(eo.get("code"));
            e.damaged = asBool(eo.get("damaged"), false); e.damageNote = asStr(eo.get("damageNote"));
            e.thicknessM = asDouble(eo.get("thicknessM"), 0); // absent => 0 (unset) => shown at default, data untouched
            e.id = asStr(eo.get("id")); e.hostId = asStr(eo.get("hostId"));   // A2 (doors)
            e.t = asDouble(eo.get("t"), 0); e.widthM = asDouble(eo.get("widthM"), 0);
            e.swing = (int) asDouble(eo.get("swing"), 0); e.kind = asStr(eo.get("kind"));
            m.elements.add(e);
        }
        m.syncIdSeq(); // A2: keep new ids from colliding with loaded ones
        m.ensureDefaultLayers(); // B3: add any newly-introduced default layer (e.g. roomnames) to older drawings
        return m;
    }

    private static double asDouble(Object o, double def) { return o instanceof Number ? ((Number) o).doubleValue() : def; }
    private static boolean asBool(Object o, boolean def) { return o instanceof Boolean ? (Boolean) o : def; }
    private static String asStr(Object o) { return o == null ? "" : o.toString(); }

    /** Minimal recursive-descent JSON reader. */
    private static final class Reader {
        private final String s; private int i;
        Reader(String s) { this.s = s; }

        Object parseValue() {
            skipWs();
            char c = peek();
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; skipWs();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs(); if (next() != ':') throw err("expected ':'");
                m.put(key, parseValue());
                skipWs();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw err("expected ',' or '}'");
            }
            return m;
        }

        private List<Object> parseArray() {
            List<Object> l = new ArrayList<>();
            i++; skipWs();
            if (peek() == ']') { i++; return l; }
            while (true) {
                l.add(parseValue());
                skipWs();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw err("expected ',' or ']'");
            }
            return l;
        }

        private String parseString() {
            if (next() != '"') throw err("expected string");
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') break;
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: throw err("bad escape \\" + e);
                    }
                } else b.append(c);
            }
            return b.toString();
        }

        private Double parseNumber() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) i++;
                else break;
            }
            if (i == start) throw err("invalid value");
            return Double.parseDouble(s.substring(start, i));
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, i)) throw err("expected '" + lit + "'");
            i += lit.length();
        }

        private void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private char peek() { if (i >= s.length()) throw err("unexpected end"); return s.charAt(i); }
        private char next() { if (i >= s.length()) throw err("unexpected end"); return s.charAt(i++); }
        private IllegalArgumentException err(String m) { return new IllegalArgumentException("JSON parse error at " + i + ": " + m); }
    }
}
