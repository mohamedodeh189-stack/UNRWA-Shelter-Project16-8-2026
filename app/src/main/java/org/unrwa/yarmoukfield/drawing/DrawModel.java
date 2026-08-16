package org.unrwa.yarmoukfield.drawing;

import java.util.ArrayList;
import java.util.List;

/** The house sketch document: calibration scale, north bearing, layers and elements, plus undo/redo. Pure
 * Java (no Android) so it is fully JVM-testable. Calibration is the linchpin of the whole tool — a drawing
 * has no real dimensions until {@link #calibrate} has been given one known real length, and every guard here
 * refuses to invent a scale from bad input. */
public final class DrawModel {
    /** World units (≈ pixels at zoom 1) per real meter. 0 means NOT calibrated — dimensions are unknown. */
    public double pxPerMeter = 0;
    /** North bearing in degrees clockwise from "up"; 0 = north is up. Display-only. */
    public double northDeg = 0;
    /** Optional ceiling height in metres (0 = not entered) — shown as the "h=..." header on the PDF. */
    public double ceilingHeightM = 0;
    /** Default wall thickness in metres used to DISPLAY any wall whose own thicknessM is still 0 (unset) — e.g.
     * an older drawing. Never written back onto those walls; it is display-only until the engineer picks one. */
    public static final double DEFAULT_WALL_THICKNESS_M = 0.15;
    /** A2: default door leaf width (metres) used to DISPLAY any door whose own widthM is still 0 (unset). */
    public static final double DEFAULT_DOOR_WIDTH_M = 0.90;
    /** A3: default window width (metres) used to DISPLAY any window whose own widthM is still 0 (unset). */
    public static final double DEFAULT_WINDOW_WIDTH_M = 1.20;
    /** Which reference the shown wall dimensions follow — "net" (INNER clear length: the axis length minus the
     * connecting walls' half-thickness at each end — what the engineer measures on site) or "axis" (wall
     * centre-line, axis-to-axis). Defaults to "net" because field measurements are interior. Serialized. */
    public String dimensionRef = "net";

    /** Effective display thickness (metres) for a wall: its own value, or the default while unset. */
    public double wallThicknessM(DrawElement e) { return e != null && e.thicknessM > 0 ? e.thicknessM : DEFAULT_WALL_THICKNESS_M; }
    /** A2: effective door leaf width (metres): its own value, or the default while unset. */
    public double doorWidthM(DrawElement e) { return e != null && e.widthM > 0 ? e.widthM : DEFAULT_DOOR_WIDTH_M; }
    /** A2/A3: effective width (metres) of a wall opening (door or window): its own value, or the type default. */
    public double openingWidthM(DrawElement e) {
        if (e != null && e.widthM > 0) return e.widthM;
        return e != null && DrawElement.WINDOW.equals(e.type) ? DEFAULT_WINDOW_WIDTH_M : DEFAULT_DOOR_WIDTH_M;
    }

    private long idSeq = 1;
    /** A2: give an element a stable unique id if it has none yet (walls need one so doors can reference them). */
    public String ensureId(DrawElement e) {
        if (e.id == null || e.id.isEmpty()) e.id = "e" + (idSeq++);
        return e.id;
    }
    public DrawElement elementById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (DrawElement e : elements) if (id.equals(e.id)) return e;
        return null;
    }

    /** A2: after loading a saved drawing, advance the id sequence past any existing "eN" ids so freshly added
     * elements never collide with (and steal the host of) a loaded one. */
    public void syncIdSeq() {
        long max = 0;
        for (DrawElement e : elements) if (e.id != null && e.id.startsWith("e")) {
            try { max = Math.max(max, Long.parseLong(e.id.substring(1))); } catch (NumberFormatException ignored) {}
        }
        idSeq = max + 1;
    }

    /** A2/A3: resolve a wall opening's (door OR window) live geometry from its host wall — {cx, cy, ux, uy,
     * thicknessWorld, widthWorld} in world units, or null if the host is missing. Recomputed from the host each
     * time, so the opening automatically follows its wall when the wall is moved. Fixed pixels when uncalibrated. */
    public double[] openingGeom(DrawElement op) {
        DrawElement host = elementById(op.hostId);
        if (host == null || host.pts == null || host.pts.length < 4) return null;
        double x1 = host.pts[0], y1 = host.pts[1], x2 = host.pts[2], y2 = host.pts[3];
        double dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy);
        if (len < 1e-9) return null;
        double ux = dx / len, uy = dy / len;
        double cx = x1 + op.t * dx, cy = y1 + op.t * dy;
        double th = pxPerMeter > 0 ? wallThicknessM(host) * pxPerMeter : 8;
        double w = pxPerMeter > 0 ? openingWidthM(op) * pxPerMeter : (DrawElement.WINDOW.equals(op.type) ? 50 : 40);
        return new double[]{cx, cy, ux, uy, th, w};
    }
    /** Back-compat alias (A2 callers). */
    public double[] doorGeom(DrawElement door) { return openingGeom(door); }

    public final List<DrawLayer> layers = new ArrayList<>();
    public final List<DrawElement> elements = new ArrayList<>();

    private final List<List<DrawElement>> undo = new ArrayList<>();
    private final List<List<DrawElement>> redo = new ArrayList<>();
    private static final int MAX_HISTORY = 60;

    public DrawModel() { layers.addAll(DrawLayer.defaults()); }

    public boolean isCalibrated() { return pxPerMeter > 0; }

    /** Set the scale from one drawn reference segment of known real length. Refuses non-positive input so a
     * mis-tap can never silently corrupt every dimension. Returns true only if the scale was actually set. */
    public boolean calibrate(double drawnLengthWorld, double realMeters) {
        if (drawnLengthWorld > 0 && realMeters > 0) { pxPerMeter = drawnLengthWorld / realMeters; return true; }
        return false;
    }

    /** Real length in meters of a world-space length, or 0 while uncalibrated. */
    public double meters(double worldLength) { return pxPerMeter > 0 ? worldLength / pxPerMeter : 0; }

    /** Real length in meters of an element's first segment (WALL/LINE/ROOM edge), or 0. */
    public double segmentMeters(DrawElement e) {
        if (e == null || e.pts == null || e.pts.length < 4) return 0;
        return meters(DrawGeo.dist(e.pts[0], e.pts[1], e.pts[2], e.pts[3]));
    }

    /** True when dimensions are shown/measured as INTERIOR clear spans (default) rather than axis-to-axis. */
    public boolean netDimensions() { return "net".equals(dimensionRef); }

    /** Representative wall thickness (m): mean over the drawn walls, or the default when none carry one. Used to
     * turn axis chain-segments into interior spans. */
    public double averageWallThicknessM() {
        double sum = 0; int n = 0;
        for (DrawElement e : elements) if (DrawElement.WALL.equals(e.type)) { sum += wallThicknessM(e); n++; }
        return n == 0 ? DEFAULT_WALL_THICKNESS_M : sum / n;
    }

    /** Half-thickness (world units) of a wall — OTHER than {@code self} — meeting at the corner (x,y); 0 if free. */
    private double connHalfThicknessWorld(DrawElement self, double x, double y) {
        for (DrawElement e : elements) {
            if (e == self || !DrawElement.WALL.equals(e.type) || e.pts == null || e.pts.length < 4) continue;
            if (DrawGeo.dist(x, y, e.pts[0], e.pts[1]) <= 3.0 || DrawGeo.dist(x, y, e.pts[2], e.pts[3]) <= 3.0)
                return wallThicknessM(e) * pxPerMeter / 2.0;
        }
        return 0;
    }

    /** Interior clear length (m) of a wall = axis length − the connecting walls' half-thickness at each end (the
     * span a field engineer measures on site), or 0 while uncalibrated. Shared by the canvas + PDF/DXF exporters. */
    public double interiorMetersOf(DrawElement wall) {
        if (wall == null || wall.pts == null || wall.pts.length < 4 || pxPerMeter <= 0) return 0;
        double axisW = DrawGeo.dist(wall.pts[0], wall.pts[1], wall.pts[2], wall.pts[3]);
        double cut = connHalfThicknessWorld(wall, wall.pts[0], wall.pts[1]) + connHalfThicknessWorld(wall, wall.pts[2], wall.pts[3]);
        return meters(Math.max(0, axisW - cut));
    }

    /** Extra axis length (m) to ADD to an interior target so the clear span comes out right (0 in axis mode). */
    public double interiorPaddingMOf(DrawElement wall) {
        if (!netDimensions() || pxPerMeter <= 0 || wall == null || wall.pts == null || wall.pts.length < 4) return 0;
        return meters(connHalfThicknessWorld(wall, wall.pts[0], wall.pts[1]) + connHalfThicknessWorld(wall, wall.pts[2], wall.pts[3]));
    }

    /** Dimension label VALUE (m) for a wall — interior clear in net mode, axis length otherwise. */
    public double wallDimM(DrawElement wall) { return netDimensions() ? interiorMetersOf(wall) : segmentMeters(wall); }

    /** Turn an axis chain-segment length (m, between two wall centre-lines) into the value to print — interior
     * clear (segment − one wall thickness) in net mode, or the axis segment otherwise. */
    public double chainSpanM(double axisSegM) { return netDimensions() ? Math.max(0, axisSegM - averageWallThicknessM()) : axisSegM; }

    // ---- mutations (each snapshots for undo) ----
    public void add(DrawElement e) { snapshot(); ensureId(e); elements.add(e); }

    /** Take ONE undo baseline before a live in-view edit (e.g. dragging an element across many touch-move
     * frames), then let the caller mutate points directly — so a whole drag is a single undoable step, not
     * hundreds. Pair with the caller's own change/autosave call on gesture-up. */
    public void beginEdit() { snapshot(); }

    public boolean remove(DrawElement e) { snapshot(); boolean r = elements.remove(e); if (!r) popSnapshot(); return r; }

    public void moveBy(DrawElement e, double dx, double dy) {
        if (e == null || e.pts == null) return;
        snapshot();
        for (int i = 0; i + 1 < e.pts.length; i += 2) { e.pts[i] += dx; e.pts[i + 1] += dy; }
    }

    // ---- undo / redo ----
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public void undo() {
        if (undo.isEmpty()) return;
        redo.add(deepCopy(elements));
        restore(undo.remove(undo.size() - 1));
    }

    public void redo() {
        if (redo.isEmpty()) return;
        undo.add(deepCopy(elements));
        restore(redo.remove(redo.size() - 1));
    }

    private void snapshot() {
        undo.add(deepCopy(elements));
        if (undo.size() > MAX_HISTORY) undo.remove(0);
        redo.clear();
    }

    /** Undo the most recent snapshot push without changing elements (used when a mutation turned out to be a no-op). */
    private void popSnapshot() { if (!undo.isEmpty()) undo.remove(undo.size() - 1); }

    private void restore(List<DrawElement> snap) {
        elements.clear();
        elements.addAll(snap);
    }

    private static List<DrawElement> deepCopy(List<DrawElement> src) {
        List<DrawElement> out = new ArrayList<>(src.size());
        for (DrawElement e : src) out.add(e.copy());
        return out;
    }

    /** Axis-aligned bounds {minX,minY,maxX,maxY} over all element points, or null when empty. */
    public double[] bounds() {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (DrawElement e : elements) {
            if (e.pts == null) continue;
            for (int i = 0; i + 1 < e.pts.length; i += 2) {
                double x = e.pts[i], y = e.pts[i + 1];
                if (x < minX) minX = x; if (y < minY) minY = y;
                if (x > maxX) maxX = x; if (y > maxY) maxY = y;
                any = true;
            }
        }
        return any ? new double[]{minX, minY, maxX, maxY} : null;
    }

    /** Number of ROOM rectangles in the sketch. */
    public int roomCount() {
        int n = 0;
        for (DrawElement e : elements) if (DrawElement.ROOM.equals(e.type)) n++;
        return n;
    }

    /** Total floor area in m² summed over room rectangles (w×h), or 0 while uncalibrated / no rooms. A whole-
     * house closed-boundary area (shoelace) is a later enhancement; MVP sums the drawn rooms. */
    public double roomAreaM2() {
        if (pxPerMeter <= 0) return 0;
        double a = 0;
        for (DrawElement e : elements)
            if (DrawElement.ROOM.equals(e.type) && e.pts != null && e.pts.length >= 4)
                a += (Math.abs(e.pts[2] - e.pts[0]) / pxPerMeter) * (Math.abs(e.pts[3] - e.pts[1]) / pxPerMeter);
        return a;
    }

    /** B3: after loading, make sure every default layer id exists (adds newly-introduced layers like "roomnames"
     * to older drawings) so the show/hide sheet always offers them. Never removes or reorders existing layers. */
    public void ensureDefaultLayers() {
        for (DrawLayer d : DrawLayer.defaults()) if (layer(d.id) == null) layers.add(d.copy());
    }

    public DrawLayer layer(String id) {
        for (DrawLayer l : layers) if (l.id.equals(id)) return l;
        return null;
    }

    public boolean isLayerVisible(String id) {
        DrawLayer l = layer(id);
        return l == null || l.visible;
    }
}
