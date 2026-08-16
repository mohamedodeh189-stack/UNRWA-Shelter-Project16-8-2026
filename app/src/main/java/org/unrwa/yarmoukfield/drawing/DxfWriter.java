package org.unrwa.yarmoukfield.drawing;

import java.util.Locale;

/** Hand-written ASCII DXF in AutoCAD R12 (AC1009) format — the most widely-openable DXF flavour: it loads
 * directly in AutoCAD, DWG TrueView and LibreCAD without a DWG round-trip. A minimal R12 header alone is NOT
 * enough for AutoCAD, so this writer emits the full structure real CAD readers require:
 *   HEADER  ($ACADVER, $INSUNITS = 6 metres, drawing extents)
 *   TABLES  (VPORT *ACTIVE, LTYPE CONTINUOUS, all seven LAYERs, STYLE STANDARD) — every layer references a
 *            linetype that actually exists, and every TEXT references a text style that actually exists.
 *   BLOCKS  (present, empty — required section)
 *   ENTITIES (closed POLYLINE for wall bands + rooms, LINE + ARC for doors, LINE for windows, TEXT for codes
 *            and dimensions) — genuine geometry, never a raster image.
 *   EOF
 *
 * R12 has no LWPOLYLINE, so closed outlines are emitted as POLYLINE/VERTEX/SEQEND with the closed flag (70=1).
 * Coordinates are metres (world units ÷ pxPerMeter) with Y negated so the plan is upright in CAD. Pure Java —
 * byte output is unit-testable on a plain JDK and validated with a real DXF parser in the test suite.
 *
 * B1: wall bands are drawn on {@link WallJoin} CLEANED axes so corners meet with no gaps; dimension VALUES stay
 * the original drawn lengths. The drawing model, the in-app view and the PDF are untouched by this class. */
public final class DxfWriter {
    private DxfWriter() {}

    public static String toDxf(DrawModel m) {
        double ppm = m.pxPerMeter;
        if (ppm <= 0) throw new IllegalStateException("DXF export needs a calibrated scale");
        StringBuilder b = new StringBuilder();

        // B1: cleaned wall axes for output (corners meet, no gaps); model untouched.
        java.util.List<DrawElement> wallList = new java.util.ArrayList<>();
        for (DrawElement e : m.elements) if (DrawElement.WALL.equals(e.type) && e.pts != null && e.pts.length >= 4) wallList.add(e);
        double[][] cleanedArr = WallJoin.cleanedAxes(wallList, 0.30 * ppm, 0.60 * ppm);
        java.util.IdentityHashMap<DrawElement, double[]> cleaned = new java.util.IdentityHashMap<>();
        for (int i = 0; i < wallList.size(); i++) cleaned.put(wallList.get(i), cleanedArr[i]);

        // drawing extents (metres) for the header + viewport, so CAD opens zoomed to the plan
        double[] ext = extents(m, ppm, cleaned);

        // ---- HEADER ----
        section(b, "HEADER");
        pair(b, 9, "$ACADVER"); pair(b, 1, "AC1009");
        pair(b, 9, "$DWGCODEPAGE"); pair(b, 3, "ANSI_1256"); // Arabic code page (matches the engineer's own DXFs)
        pair(b, 9, "$INSUNITS"); pair(b, 70, "6");           // metres
        pair(b, 9, "$EXTMIN"); num(b, 10, ext[0]); num(b, 20, ext[1]); num(b, 30, 0);
        pair(b, 9, "$EXTMAX"); num(b, 10, ext[2]); num(b, 20, ext[3]); num(b, 30, 0);
        endsec(b);

        // ---- TABLES ----
        section(b, "TABLES");
        // VPORT (a valid *ACTIVE viewport centred on the drawing so AutoCAD has a view to show)
        pair(b, 0, "TABLE"); pair(b, 2, "VPORT"); pair(b, 70, "1");
        activeVport(b, ext);
        pair(b, 0, "ENDTAB");
        // LTYPE — CONTINUOUS must exist because every layer references it
        pair(b, 0, "TABLE"); pair(b, 2, "LTYPE"); pair(b, 70, "1");
        pair(b, 0, "LTYPE"); pair(b, 2, "CONTINUOUS"); pair(b, 70, "0"); pair(b, 3, "Solid line"); pair(b, 72, "65"); pair(b, 73, "0"); num(b, 40, 0);
        pair(b, 0, "ENDTAB");
        // LAYER — all seven required layers, each on the CONTINUOUS linetype
        pair(b, 0, "TABLE"); pair(b, 2, "LAYER"); pair(b, 70, "9");
        layer(b, "Walls", 5);        // blue — the real, primary geometry
        layer(b, "Doors", 6);        // magenta
        layer(b, "Windows", 4);      // cyan
        // B3: room BOUNDARY polylines live on Rooms, shipped OFF (negative colour) so AutoCAD opens to a clean
        // plan of walls only — the engineer can turn Rooms on to see/verify the areas.
        layer(b, "Rooms", -3);       // green but OFF by default
        layer(b, "RoomNames", 7);    // white/black — the visible Arabic room names
        layer(b, "Dimensions", 8);   // grey
        layer(b, "Notes", 2);        // yellow
        layer(b, "Damage", 1);       // red
        layer(b, "Stairs", 30);      // orange — stairs symbol (rectangle + treads + ascent arrow)
        pair(b, 0, "ENDTAB");
        // STYLE — STANDARD must exist because every TEXT references it
        pair(b, 0, "TABLE"); pair(b, 2, "STYLE"); pair(b, 70, "1");
        pair(b, 0, "STYLE"); pair(b, 2, "STANDARD"); pair(b, 70, "0"); num(b, 40, 0); num(b, 41, 1); num(b, 50, 0); pair(b, 71, "0"); num(b, 42, 0.2); pair(b, 3, "arial.ttf"); pair(b, 4, "");
        pair(b, 0, "ENDTAB");
        endsec(b);

        // ---- BLOCKS (required section; no user blocks) ----
        section(b, "BLOCKS");
        endsec(b);

        // ---- ENTITIES ----
        section(b, "ENTITIES");
        int roomNo = 0, doorNo = 0, winNo = 0;
        for (DrawElement e : m.elements) {
            if (DrawElement.DOOR.equals(e.type)) { doorNo++; door(b, m, e, ppm, doorNo); continue; }
            if (DrawElement.WINDOW.equals(e.type)) { winNo++; window(b, m, e, ppm, winNo); continue; }
            if (DrawElement.STAIRS.equals(e.type)) { stairs(b, m, e, ppm); continue; }
            if (e.pts == null || e.pts.length < 4) continue;
            String layer = layerName(m, e.layer);
            if (DrawElement.ROOM.equals(e.type)) {
                double x1 = mx(e.pts[0], ppm), y1 = my(e.pts[1], ppm), x2 = mx(e.pts[2], ppm), y2 = my(e.pts[3], ppm);
                roomNo++;
                // B3: boundary polyline on the (off-by-default) Rooms layer — only when that layer is visible.
                if (m.isLayerVisible("rooms"))
                    polyClosed(b, "Rooms", new double[][]{{x1, y1}, {x2, y1}, {x2, y2}, {x1, y2}});
                // B2/B3: the Arabic room name goes on the visible RoomNames layer (ROOM-NN only if unnamed).
                if (m.isLayerVisible("roomnames")) {
                    String rn = e.label != null && !e.label.isEmpty() ? e.label : String.format(Locale.US, "ROOM-%02d", roomNo);
                    text(b, "RoomNames", (x1 + x2) / 2, (y1 + y2) / 2, 0.25, rn);
                }
            } else if (DrawElement.WALL.equals(e.type)) {
                // wall band on the CLEANED axis (corners meet); dimension VALUE stays the original length
                double[] wc = cleaned.get(e);
                double ax1 = wc != null ? wc[0] : e.pts[0], ay1 = wc != null ? wc[1] : e.pts[1];
                double ax2 = wc != null ? wc[2] : e.pts[2], ay2 = wc != null ? wc[3] : e.pts[3];
                double x1 = mx(ax1, ppm), y1 = my(ay1, ppm), x2 = mx(ax2, ppm), y2 = my(ay2, ppm);
                double t = m.wallThicknessM(e);
                double dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy);
                if (len > 1e-9 && t > 0) {
                    double ox = -dy / len * (t / 2), oy = dx / len * (t / 2);
                    polyClosed(b, "Walls", new double[][]{{x1 + ox, y1 + oy}, {x2 + ox, y2 + oy}, {x2 - ox, y2 - oy}, {x1 - ox, y1 - oy}});
                } else {
                    line(b, "Walls", x1, y1, x2, y2);
                }
                // B2: axis-aligned walls are dimensioned by the external chains; keep an inline length only for
                // OBLIQUE walls (which no orthogonal chain can capture).
                boolean axisAligned = Math.abs(dx) < 0.02 || Math.abs(dy) < 0.02;
                if (m.isLayerVisible("dims") && !axisAligned)
                    text(b, "Dimensions", (x1 + x2) / 2, (y1 + y2) / 2 + 0.1, 0.2, fmt(m.wallDimM(e))); // interior (net) or axis
            } else {
                double x1 = mx(e.pts[0], ppm), y1 = my(e.pts[1], ppm), x2 = mx(e.pts[2], ppm), y2 = my(e.pts[3], ppm);
                line(b, layer, x1, y1, x2, y2);
                if (m.isLayerVisible("dims"))
                    text(b, "Dimensions", (x1 + x2) / 2, (y1 + y2) / 2 + 0.1, 0.2, fmt(Math.hypot(x2 - x1, y2 - y1)));
            }
        }
        // B2: professional external dimension chains around the plan (top = vertical-wall stations, left =
        // horizontal-wall stations) as LINE+TEXT on the Dimensions layer — fully editable, no DIMSTYLE needed.
        if (m.isLayerVisible("dims")) dimensionChains(b, m, ppm, cleaned, ext);
        endsec(b);

        pair(b, 0, "EOF");
        return b.toString();
    }

    /** External dimension chains: a horizontal chain above the plan built from the X of every vertical wall (+
     * the plan sides), and a vertical chain to the left built from the Y of every horizontal wall. Each segment
     * gets extension lines, architectural tick marks and its measured length — the way an engineer dimensions. */
    private static void dimensionChains(StringBuilder b, DrawModel m, double ppm,
                                        java.util.IdentityHashMap<DrawElement, double[]> cleaned, double[] ext) {
        java.util.TreeSet<Double> xs = new java.util.TreeSet<>(), ys = new java.util.TreeSet<>();
        for (DrawElement e : m.elements) {
            if (!DrawElement.WALL.equals(e.type)) continue;
            double[] p = cleaned.get(e); if (p == null) p = e.pts; if (p == null || p.length < 4) continue;
            double dx = p[2] - p[0], dy = p[3] - p[1];
            if (Math.abs(dx) < Math.abs(dy) && Math.abs(dx) < 0.02 * ppm) addStation(xs, mx((p[0] + p[2]) / 2, ppm));       // vertical wall → X station
            else if (Math.abs(dy) < 0.02 * ppm) addStation(ys, my((p[1] + p[3]) / 2, ppm));                                 // horizontal wall → Y station
        }
        addStation(xs, ext[0]); addStation(xs, ext[2]);
        addStation(ys, ext[1]); addStation(ys, ext[3]);
        Double[] xa = xs.toArray(new Double[0]), ya = ys.toArray(new Double[0]);
        double off = 0.7, tick = 0.09, tgap = 0.14;
        double topY = ext[3] + off, leftX = ext[0] - off;
        // top horizontal chain
        for (Double x : xa) line(b, "Dimensions", x, ext[3], x, topY);                        // extension lines
        for (int i = 0; i + 1 < xa.length; i++) {
            double x0 = xa[i], x1 = xa[i + 1];
            line(b, "Dimensions", x0, topY, x1, topY);
            line(b, "Dimensions", x0 - tick, topY - tick, x0 + tick, topY + tick);
            line(b, "Dimensions", x1 - tick, topY - tick, x1 + tick, topY + tick);
            text(b, "Dimensions", (x0 + x1) / 2, topY + tgap, 0.2, fmt(m.chainSpanM(x1 - x0))); // interior (net) or axis
        }
        // left vertical chain (text rotated 90°)
        for (Double y : ya) line(b, "Dimensions", ext[0], y, leftX, y);
        for (int i = 0; i + 1 < ya.length; i++) {
            double y0 = ya[i], y1 = ya[i + 1];
            line(b, "Dimensions", leftX, y0, leftX, y1);
            line(b, "Dimensions", leftX - tick, y0 - tick, leftX + tick, y0 + tick);
            line(b, "Dimensions", leftX - tick, y1 - tick, leftX + tick, y1 + tick);
            textRot(b, "Dimensions", leftX - tgap, (y0 + y1) / 2, 0.2, fmt(m.chainSpanM(y1 - y0)), 90); // interior (net) or axis
        }
    }

    private static void addStation(java.util.TreeSet<Double> set, double v) {
        for (Double e : set) if (Math.abs(e - v) < 0.03) return; // merge near-duplicate stations (3 cm)
        set.add(v);
    }

    /** A2: door = two jamb LINEs + leaf LINE + swing ARC + a DOOR-NN code, all real CAD entities on layer Doors. */
    private static void door(StringBuilder b, DrawModel m, DrawElement e, double ppm, int doorNo) {
        double[] g = m.openingGeom(e);
        if (g == null) return;
        double cx = g[0], cy = g[1], ux = g[2], uy = g[3], th = g[4], w = g[5];
        double half = w / 2, nx = -uy, ny = ux;
        double e0x = cx - ux * half, e0y = cy - uy * half, e1x = cx + ux * half, e1y = cy + uy * half;
        boolean hingeE0 = (e.swing & 1) == 0; double side = (e.swing & 2) == 0 ? 1 : -1;
        double hx = hingeE0 ? e0x : e1x, hy = hingeE0 ? e0y : e1y;
        double fx = hingeE0 ? e1x : e0x, fy = hingeE0 ? e1y : e0y;
        double lx = hx + nx * side * w, ly = hy + ny * side * w;
        line(b, "Doors", mx(e0x + nx * th / 2, ppm), my(e0y + ny * th / 2, ppm), mx(e0x - nx * th / 2, ppm), my(e0y - ny * th / 2, ppm));
        line(b, "Doors", mx(e1x + nx * th / 2, ppm), my(e1y + ny * th / 2, ppm), mx(e1x - nx * th / 2, ppm), my(e1y - ny * th / 2, ppm));
        line(b, "Doors", mx(hx, ppm), my(hy, ppm), mx(lx, ppm), my(ly, ppm));
        double hxm = mx(hx, ppm), hym = my(hy, ppm);
        double a0 = Math.toDegrees(Math.atan2(my(ly, ppm) - hym, mx(lx, ppm) - hxm));
        double a1 = Math.toDegrees(Math.atan2(my(fy, ppm) - hym, mx(fx, ppm) - hxm));
        double ccw = ((a1 - a0) % 360 + 360) % 360;
        double s0 = a0, s1 = a1; if (ccw > 180) { s0 = a1; s1 = a0; }
        arc(b, "Doors", hxm, hym, m.doorWidthM(e), s0, s1);
        text(b, "Doors", mx(cx, ppm), my(cy, ppm), 0.2, String.format(Locale.US, "DOOR-%02d", doorNo));
    }

    /** A3: window = two jamb LINEs + two glazing LINEs + a WIN-NN code on layer Windows (no ARC). */
    private static void window(StringBuilder b, DrawModel m, DrawElement e, double ppm, int winNo) {
        double[] g = m.openingGeom(e);
        if (g == null) return;
        double cx = g[0], cy = g[1], ux = g[2], uy = g[3], th = g[4], w = g[5];
        double half = w / 2, nx = -uy, ny = ux, gl = th * 0.25;
        double e0x = cx - ux * half, e0y = cy - uy * half, e1x = cx + ux * half, e1y = cy + uy * half;
        line(b, "Windows", mx(e0x + nx * th / 2, ppm), my(e0y + ny * th / 2, ppm), mx(e0x - nx * th / 2, ppm), my(e0y - ny * th / 2, ppm));
        line(b, "Windows", mx(e1x + nx * th / 2, ppm), my(e1y + ny * th / 2, ppm), mx(e1x - nx * th / 2, ppm), my(e1y - ny * th / 2, ppm));
        line(b, "Windows", mx(e0x + nx * gl, ppm), my(e0y + ny * gl, ppm), mx(e1x + nx * gl, ppm), my(e1y + ny * gl, ppm));
        line(b, "Windows", mx(e0x - nx * gl, ppm), my(e0y - ny * gl, ppm), mx(e1x - nx * gl, ppm), my(e1y - ny * gl, ppm));
        text(b, "Windows", mx(cx, ppm), my(cy, ppm), 0.2, String.format(Locale.US, "WIN-%02d", winNo));
    }

    /** Drawing extents in metres over the cleaned walls + every other element, {minX,minY,maxX,maxY}. */
    private static double[] extents(DrawModel m, double ppm, java.util.IdentityHashMap<DrawElement, double[]> cleaned) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (DrawElement e : m.elements) {
            double[] p = e.pts;
            if (DrawElement.WALL.equals(e.type) && cleaned.get(e) != null) p = cleaned.get(e);
            if (p == null) continue;
            for (int i = 0; i + 1 < p.length; i += 2) {
                double x = mx(p[i], ppm), y = my(p[i + 1], ppm);
                if (x < minX) minX = x; if (y < minY) minY = y; if (x > maxX) maxX = x; if (y > maxY) maxY = y; any = true;
            }
        }
        if (!any) return new double[]{0, 0, 1, 1};
        return new double[]{minX, minY, maxX, maxY};
    }

    private static void activeVport(StringBuilder b, double[] ext) {
        double cx = (ext[0] + ext[2]) / 2, cy = (ext[1] + ext[3]) / 2;
        double h = Math.max(1e-3, Math.max(ext[3] - ext[1], (ext[2] - ext[0]) * 0.75)) * 1.2;
        pair(b, 0, "VPORT"); pair(b, 2, "*ACTIVE"); pair(b, 70, "0");
        num(b, 10, 0); num(b, 20, 0);      // lower-left of viewport
        num(b, 11, 1); num(b, 21, 1);      // upper-right of viewport
        num(b, 12, cx); num(b, 22, cy);    // view centre
        num(b, 13, 0); num(b, 23, 0);      // snap base
        num(b, 14, 0.5); num(b, 24, 0.5);  // snap spacing
        num(b, 15, 0.5); num(b, 25, 0.5);  // grid spacing
        num(b, 16, 0); num(b, 26, 0); num(b, 36, 1); // view direction
        num(b, 17, 0); num(b, 27, 0); num(b, 37, 0); // view target
        num(b, 40, h);                     // view height
        num(b, 41, 1.8);                   // aspect ratio
        num(b, 42, 50); num(b, 43, 0); num(b, 44, 0); num(b, 50, 0); num(b, 51, 0);
        pair(b, 71, "0"); pair(b, 72, "100"); pair(b, 73, "1"); pair(b, 74, "3"); pair(b, 75, "0"); pair(b, 76, "0"); pair(b, 77, "0"); pair(b, 78, "0");
    }

    // metres, Y negated for CAD's upward Y
    /** Emit the simple stairs symbol (rectangle + step treads + ascent arrow) on the Stairs layer. */
    private static void stairs(StringBuilder b, DrawModel m, DrawElement e, double ppm) {
        double x1 = Math.min(e.pts[0], e.pts[2]), y1 = Math.min(e.pts[1], e.pts[3]);
        double x2 = Math.max(e.pts[0], e.pts[2]), y2 = Math.max(e.pts[1], e.pts[3]);
        polyClosed(b, "Stairs", new double[][]{{mx(x1, ppm), my(y1, ppm)}, {mx(x2, ppm), my(y1, ppm)}, {mx(x2, ppm), my(y2, ppm)}, {mx(x1, ppm), my(y2, ppm)}});
        double w = x2 - x1, h = y2 - y1; boolean runX = w >= h; double runLen = runX ? w : h;
        int n = m.isCalibrated() ? (int) Math.round(m.meters(runLen) / 0.27) : 8; n = Math.max(2, Math.min(n, 40));
        for (int i = 1; i < n; i++) {
            double t = (double) i / n;
            if (runX) { double x = x1 + w * t; line(b, "Stairs", mx(x, ppm), my(y1, ppm), mx(x, ppm), my(y2, ppm)); }
            else { double y = y1 + h * t; line(b, "Stairs", mx(x1, ppm), my(y, ppm), mx(x2, ppm), my(y, ppm)); }
        }
        boolean rev = (e.swing & 1) == 1;
        double ax, ay, bx, by;
        if (runX) { double ym = (y1 + y2) / 2; ay = by = ym; ax = rev ? x2 : x1; bx = rev ? x1 : x2; }
        else { double xm = (x1 + x2) / 2; ax = bx = xm; ay = rev ? y2 : y1; by = rev ? y1 : y2; }
        line(b, "Stairs", mx(ax, ppm), my(ay, ppm), mx(bx, ppm), my(by, ppm));
        double ang = Math.atan2(by - ay, bx - ax), hl = Math.min(w, h) * 0.22;
        line(b, "Stairs", mx(bx, ppm), my(by, ppm), mx(bx - hl * Math.cos(ang - 0.4), ppm), my(by - hl * Math.sin(ang - 0.4), ppm));
        line(b, "Stairs", mx(bx, ppm), my(by, ppm), mx(bx - hl * Math.cos(ang + 0.4), ppm), my(by - hl * Math.sin(ang + 0.4), ppm));
    }

    private static double mx(double x, double ppm) { return x / ppm; }
    private static double my(double y, double ppm) { return -y / ppm; }
    private static String fmt(double meters) { return String.format(Locale.US, "%.2f", meters); }

    /** Map the stable layer id to the canonical DXF layer name so entities always match the LAYER table. */
    private static String layerName(DrawModel m, String layerId) {
        if (layerId == null) return "Walls";
        switch (layerId) {
            case "doors": return "Doors";
            case "windows": return "Windows";
            case "rooms": return "Rooms";
            case "roomnames": return "RoomNames";
            case "dims": return "Dimensions";
            case "notes": return "Notes";
            case "damage": return "Damage";
            default: return "Walls";
        }
    }

    private static void section(StringBuilder b, String name) { pair(b, 0, "SECTION"); pair(b, 2, name); }
    private static void endsec(StringBuilder b) { pair(b, 0, "ENDSEC"); }

    private static void layer(StringBuilder b, String name, int color) {
        pair(b, 0, "LAYER"); pair(b, 2, name); pair(b, 70, "0"); pair(b, 62, Integer.toString(color)); pair(b, 6, "CONTINUOUS");
    }

    private static void line(StringBuilder b, String layer, double x1, double y1, double x2, double y2) {
        pair(b, 0, "LINE"); pair(b, 8, layer);
        num(b, 10, x1); num(b, 20, y1); num(b, 30, 0);
        num(b, 11, x2); num(b, 21, y2); num(b, 31, 0);
    }

    /** R12 closed outline: POLYLINE (66=1 vertices-follow, 70=1 closed) + a VERTEX per corner + SEQEND. */
    private static void polyClosed(StringBuilder b, String layer, double[][] verts) {
        pair(b, 0, "POLYLINE"); pair(b, 8, layer); pair(b, 66, "1"); pair(b, 70, "1");
        num(b, 10, 0); num(b, 20, 0); num(b, 30, 0);
        for (double[] v : verts) {
            pair(b, 0, "VERTEX"); pair(b, 8, layer); num(b, 10, v[0]); num(b, 20, v[1]); num(b, 30, 0);
        }
        pair(b, 0, "SEQEND"); pair(b, 8, layer);
    }

    private static void arc(StringBuilder b, String layer, double cx, double cy, double r, double a0, double a1) {
        pair(b, 0, "ARC"); pair(b, 8, layer);
        num(b, 10, cx); num(b, 20, cy); num(b, 30, 0);
        num(b, 40, r); num(b, 50, a0); num(b, 51, a1);
    }

    private static void text(StringBuilder b, String layer, double x, double y, double height, String s) { textRot(b, layer, x, y, height, s, 0); }

    private static void textRot(StringBuilder b, String layer, double x, double y, double height, String s, double rotDeg) {
        pair(b, 0, "TEXT"); pair(b, 8, layer);
        num(b, 10, x); num(b, 20, y); num(b, 30, 0);
        num(b, 40, height); pair(b, 1, s);
        if (rotDeg != 0) num(b, 50, rotDeg);
        pair(b, 7, "STANDARD");
    }

    private static void num(StringBuilder b, int code, double v) { pair(b, code, String.format(Locale.US, "%.4f", v)); }
    private static void pair(StringBuilder b, int code, String v) { b.append(code).append('\n').append(v).append('\n'); }
}
