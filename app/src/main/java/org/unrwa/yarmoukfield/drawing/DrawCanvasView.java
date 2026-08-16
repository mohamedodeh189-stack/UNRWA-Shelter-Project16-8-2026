package org.unrwa.yarmoukfield.drawing;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** The field sketch surface (M1 canvas + M2 tools). Renders a {@link DrawModel} through a pan/zoom Matrix and
 * turns finger gestures into walls/lines/rooms with easy tap-selection. Deliberately a *field* tool, not CAD:
 * one clear tool at a time, generous touch targets, constant on-screen line width regardless of zoom, and a
 * single onChange callback the host uses for autosave + Undo/Redo button state. Dimension labels + the
 * calibration tool are added in M3; only the value pipeline (world units → meters) already exists in the model. */
public final class DrawCanvasView extends View {
    public static final int TOOL_SELECT = 0, TOOL_WALL = 1, TOOL_LINE = 2, TOOL_ROOM = 3, TOOL_DELETE = 4, TOOL_CALIBRATE = 5, TOOL_DOOR = 6, TOOL_WINDOW = 7, TOOL_STAIRS = 8;

    public interface Listener {
        void onChange();
        /** A reference line of this world-length was drawn with the calibrate tool — the host asks the engineer
         * for its real length in metres and calls {@link DrawModel#calibrate}. */
        void onCalibrate(double drawnLengthWorld);
        /** A1: a new ROOM was just drawn — the host opens the name picker so the engineer labels it immediately. */
        void onRoomCommitted(DrawElement room);
        /** A1: an existing ROOM was tapped (not dragged) in the select tool — the host opens rename. */
        void onRoomTap(DrawElement room);
        /** A3: an existing WINDOW was tapped in the select tool — the host opens length/delete. */
        void onWindowTap(DrawElement window);
        /** A2: an existing DOOR was tapped — the host opens width (70/80/90)/type/swing/delete editing. */
        void onDoorTap(DrawElement door);
        /** A door/window tool tapped where there is NO wall — the host hints that openings must sit on a wall. */
        void onOpeningMissedWall();
        /** Stairs: an existing STAIRS rectangle was tapped — the host opens flip-ascent-direction / delete. */
        void onStairsTap(DrawElement stairs);
        /** B4: an existing WALL was TAPPED (not dragged) with the select tool — the host opens numeric
         * length/angle editing. Deliberately NOT fired on draw: drawing 30–40 walls must never be interrupted
         * by a dialog; precision is opt-in, on demand. */
        void onWallTap(DrawElement wall);
        /** FAST FIELD MODE: a new WALL was just finished being drawn (commit, not tap). Only fires for TOOL_WALL
         * (never LINE/ROOM), and only actually opens a dialog on the host side when the "طلب طول الجدار بعد
         * الرسم" preference is on — so field speed stays the default and this is strictly opt-in. */
        void onWallDrawn(DrawElement wall);
    }

    // B4: how a numeric length re-shapes the wall — keep the drawn angle, or snap to horizontal / vertical.
    public static final int ANGLE_KEEP = 0, ANGLE_H = 1, ANGLE_V = 2;

    private DrawModel model = new DrawModel();
    private int tool = TOOL_SELECT;
    private boolean showGrid = true;
    private Listener listener;

    private final Matrix matrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private final float[] tmp = new float[2];
    private final float[] mv = new float[9];
    private final float density;

    private final ScaleGestureDetector scaleDetector;
    private float prevFocusX, prevFocusY;

    // single-finger interaction state
    private static final int MODE_NONE = 0, MODE_PAN = 1, MODE_MOVE = 2, MODE_DRAW = 3;
    private int mode = MODE_NONE;
    private float lastScreenX, lastScreenY;   // for PAN
    private float lastWorldX, lastWorldY;     // for MOVE
    private float downSX, downSY;             // A1: where a select-touch began (to tell a tap from a drag)
    private boolean movedSinceDown;           // A1: did the finger actually move (drag) vs stay put (tap)?
    private float startWX, startWY, curWX, curWY; // for DRAW (world)
    private DrawElement selected;
    private double currentThicknessM = DrawModel.DEFAULT_WALL_THICKNESS_M; // thickness applied to newly drawn walls
    // Continuous drawing: the previous wall's end point stays "sticky" so a run of connected walls is easy to chain.
    private double chainX, chainY; private boolean hasChain;
    private boolean endSnapped; // true while the wall being drawn is snapping onto an existing corner (draw a marker)
    // Alignment guides: while drawing, the free end catches an existing corner's X/Y so walls square up.
    private double guideX, guideY; private boolean hasGuideX, hasGuideY;

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wallP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wallFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path wallPath = new android.graphics.Path();
    private final Paint roomP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghost = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint uiText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roomNameFill = new Paint(Paint.ANTI_ALIAS_FLAG); // A1: room name centred in the room
    private final Paint roomNameHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint doorP = new Paint(Paint.ANTI_ALIAS_FLAG);        // A2: door leaf + swing arc
    private final Paint doorGap = new Paint(Paint.ANTI_ALIAS_FLAG);      // A2: white fill that "opens" the wall
    private final Paint windowP = new Paint(Paint.ANTI_ALIAS_FLAG);      // A3: window jambs + glazing lines
    private final Paint snapDot = new Paint(Paint.ANTI_ALIAS_FLAG);      // connection marker at a snapped wall corner
    private final Paint guideP = new Paint(Paint.ANTI_ALIAS_FLAG);       // dashed alignment guide (drawn in screen space)
    private final Paint bannerBg = new Paint(Paint.ANTI_ALIAS_FLAG);     // total-area chip background
    private final Paint bannerText = new Paint(Paint.ANTI_ALIAS_FLAG);   // total-area chip text
    private final android.graphics.Path doorPath = new android.graphics.Path();

    public DrawCanvasView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.WHITE);
        grid.setColor(0xffE6EDF1);
        wallP.setColor(0xff0B2F52); wallP.setStyle(Paint.Style.STROKE); wallP.setStrokeCap(Paint.Cap.ROUND);
        wallFill.setStyle(Paint.Style.FILL);
        roomP.setColor(0xff0E8A9C); roomP.setStyle(Paint.Style.STROKE);
        selP.setColor(0xffC9851C); selP.setStyle(Paint.Style.STROKE);
        ghost.setColor(0xff7A5CB8); ghost.setStyle(Paint.Style.STROKE);
        ui.setColor(0xff0B2F52); ui.setStyle(Paint.Style.STROKE); ui.setStrokeWidth(2 * density);
        uiText.setColor(0xff0B2F52); uiText.setTextSize(12 * density); uiText.setTextAlign(Paint.Align.CENTER);
        dimFill.setColor(0xff0E8A9C); dimFill.setTextSize(11 * density); dimFill.setTextAlign(Paint.Align.CENTER); dimFill.setFakeBoldText(true);
        dimHalo.setColor(0xffFFFFFF); dimHalo.setTextSize(11 * density); dimHalo.setTextAlign(Paint.Align.CENTER); dimHalo.setStyle(Paint.Style.STROKE); dimHalo.setStrokeWidth(3 * density); dimHalo.setFakeBoldText(true);
        roomNameFill.setColor(0xff16323F); roomNameFill.setTextSize(13 * density); roomNameFill.setTextAlign(Paint.Align.CENTER); roomNameFill.setFakeBoldText(true);
        roomNameHalo.setColor(0xffFFFFFF); roomNameHalo.setTextSize(13 * density); roomNameHalo.setTextAlign(Paint.Align.CENTER); roomNameHalo.setStyle(Paint.Style.STROKE); roomNameHalo.setStrokeWidth(4 * density); roomNameHalo.setFakeBoldText(true);
        doorP.setColor(0xff0B2F52); doorP.setStyle(Paint.Style.STROKE); doorP.setStrokeJoin(Paint.Join.ROUND);
        doorGap.setColor(0xffFFFFFF); doorGap.setStyle(Paint.Style.FILL);
        windowP.setColor(0xff0E7490); windowP.setStyle(Paint.Style.STROKE);
        snapDot.setColor(0xff149176); snapDot.setStyle(Paint.Style.FILL);
        guideP.setColor(0xff7A5CB8); guideP.setStyle(Paint.Style.STROKE); guideP.setStrokeWidth(1.6f * density);
        guideP.setPathEffect(new android.graphics.DashPathEffect(new float[]{8 * density, 6 * density}, 0));
        bannerBg.setColor(0xE60B2F52); bannerBg.setStyle(Paint.Style.FILL);
        bannerText.setColor(0xffFFFFFF); bannerText.setTextSize(12 * density); bannerText.setTextAlign(Paint.Align.LEFT); bannerText.setFakeBoldText(true);
        // start centered-ish so world origin sits comfortably on screen
        matrix.postTranslate(60 * density, 120 * density);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) { prevFocusX = d.getFocusX(); prevFocusY = d.getFocusY(); return true; }
            @Override public boolean onScale(ScaleGestureDetector d) {
                float s = d.getScaleFactor();
                float cur = scale();
                float next = cur * s;
                if (next < 0.15f) s = 0.15f / cur; else if (next > 12f) s = 12f / cur; // clamp zoom
                matrix.postScale(s, s, d.getFocusX(), d.getFocusY());
                matrix.postTranslate(d.getFocusX() - prevFocusX, d.getFocusY() - prevFocusY); // two-finger pan
                prevFocusX = d.getFocusX(); prevFocusY = d.getFocusY();
                invalidate();
                return true;
            }
        });
    }

    // ---- host API ----
    public DrawModel model() { return model; }
    public void setModel(DrawModel m) { model = m == null ? new DrawModel() : m; selected = null; invalidate(); }
    public void setListener(Listener l) { listener = l; }
    public void setTool(int t) { tool = t; selected = null; hasChain = false; hasGuideX = hasGuideY = false; invalidate(); }
    public int tool() { return tool; }
    public void toggleGrid() { showGrid = !showGrid; invalidate(); }
    public boolean gridOn() { return showGrid; }
    public void undo() { model.undo(); selected = null; changed(); }
    public void redo() { model.redo(); selected = null; changed(); }
    /** A1: the host changed an element's label directly (room naming) — repaint + autosave + undo/redo refresh. */
    public void externalChanged() { changed(); }
    /** A3: clear the current selection (e.g. after the host deletes the selected element). */
    public void clearSelection() { selected = null; invalidate(); }

    private void changed() { invalidate(); if (listener != null) listener.onChange(); }

    private float scale() { matrix.getValues(mv); return mv[Matrix.MSCALE_X]; }

    private float[] toWorld(float sx, float sy) {
        matrix.invert(inverse); tmp[0] = sx; tmp[1] = sy; inverse.mapPoints(tmp); return new float[]{tmp[0], tmp[1]};
    }

    // ---- touch ----
    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        if (e.getPointerCount() > 1 || scaleDetector.isInProgress()) { mode = MODE_NONE; return true; }
        float sx = e.getX(), sy = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float[] w = toWorld(sx, sy);
                if (tool == TOOL_DELETE) {
                    DrawElement hit = hitTest(w[0], w[1]);
                    if (hit != null) { model.remove(hit); selected = null; changed(); }
                    mode = MODE_NONE;
                } else if (tool == TOOL_SELECT) {
                    DrawElement hit = hitTest(w[0], w[1]);
                    if (hit != null) { selected = hit; mode = MODE_MOVE; lastWorldX = w[0]; lastWorldY = w[1];
                        downSX = sx; downSY = sy; movedSinceDown = false; } // beginEdit() is deferred to the first real move
                    else { selected = null; mode = MODE_PAN; lastScreenX = sx; lastScreenY = sy; }
                    invalidate();
                } else if (tool == TOOL_DOOR) { // A2: a door is placed by tapping a wall (never free-floating)
                    placeOpening(w[0], w[1], DrawElement.DOOR); mode = MODE_NONE;
                } else if (tool == TOOL_WINDOW) { // A3: a window is placed by tapping a wall too
                    placeOpening(w[0], w[1], DrawElement.WINDOW); mode = MODE_NONE;
                } else { // WALL / LINE / ROOM
                    double[] snap = DrawGeo.snapToEndpoint(w[0], w[1], model.elements, endpointTolWorld());
                    startWX = (float) snap[0]; startWY = (float) snap[1];
                    // Continuous drawing: the last wall's end point is extra-sticky so the next wall connects to it
                    // even with an imprecise touch — draw wall after wall without hunting for the corner.
                    if ((tool == TOOL_WALL || tool == TOOL_LINE) && hasChain
                            && Math.hypot(w[0] - chainX, w[1] - chainY) < endpointTolWorld() * 2.4) {
                        startWX = (float) chainX; startWY = (float) chainY;
                    }
                    curWX = startWX; curWY = startWY; endSnapped = false; mode = MODE_DRAW; invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == MODE_PAN) {
                    matrix.postTranslate(sx - lastScreenX, sy - lastScreenY); lastScreenX = sx; lastScreenY = sy; invalidate();
                } else if (mode == MODE_MOVE && selected != null && selected.pts != null) {
                    float[] w = toWorld(sx, sy);
                    // A1: only treat this as a drag once the finger clearly moved; a stationary touch stays a "tap"
                    // (→ rename), and beginEdit() is taken exactly once here so the whole drag is one undo step.
                    if (!movedSinceDown && Math.hypot(sx - downSX, sy - downSY) > 8 * density) { movedSinceDown = true; model.beginEdit(); }
                    if (movedSinceDown) {
                        if (isOpening(selected)) reanchorOpening(selected, w[0], w[1]); // A2/A3: dragging a door/window slides it onto the nearest wall
                        else { float dx = w[0] - lastWorldX, dy = w[1] - lastWorldY;
                            for (int i = 0; i + 1 < selected.pts.length; i += 2) { selected.pts[i] += dx; selected.pts[i + 1] += dy; } }
                    }
                    lastWorldX = w[0]; lastWorldY = w[1]; invalidate();
                } else if (mode == MODE_DRAW) {
                    float[] w = toWorld(sx, sy); curWX = w[0]; curWY = w[1];
                    hasGuideX = hasGuideY = false;
                    if (tool != TOOL_ROOM && tool != TOOL_STAIRS) {
                        // 1) square to the nearest right angle (wider tolerance = easier to hit a clean 90°).
                        double[] a = DrawGeo.snapToAxis(startWX, startWY, curWX, curWY, 11); curWX = (float) a[0]; curWY = (float) a[1];
                        // 2) exact-corner snap wins: as the free end nears an existing corner it locks on (marked),
                        //    so closing a room or continuing a wall run is effortless and visible before releasing.
                        double[] es = DrawGeo.snapToEndpoint(curWX, curWY, model.elements, endpointTolWorld());
                        endSnapped = Math.abs(es[0] - curWX) > 1e-6 || Math.abs(es[1] - curWY) > 1e-6;
                        if (endSnapped) { curWX = (float) es[0]; curWY = (float) es[1]; }
                        else applyAlignment(); // 3) softer: catch a parallel/opposite wall's X or Y + show the guide
                    }
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mode == MODE_DRAW) commitDraw();
                else if (mode == MODE_MOVE) {
                    if (movedSinceDown) changed(); // a real move → finalize (autosave)
                    else if (selected != null && DrawElement.ROOM.equals(selected.type) && listener != null)
                        listener.onRoomTap(selected); // A1: a plain tap on a room → rename only, no data change
                    else if (selected != null && DrawElement.DOOR.equals(selected.type) && listener != null)
                        listener.onDoorTap(selected); // A2: tap a door → width (70/80/90) / type / swing / delete
                    else if (selected != null && DrawElement.WINDOW.equals(selected.type) && listener != null)
                        listener.onWindowTap(selected); // A3: tap a window → length / delete
                    else if (selected != null && DrawElement.STAIRS.equals(selected.type) && listener != null)
                        listener.onStairsTap(selected); // stairs → flip ascent direction / delete
                    else if (selected != null && DrawElement.WALL.equals(selected.type) && listener != null)
                        listener.onWallTap(selected); // B4: tap a wall → exact length / angle (opt-in precision)
                }
                mode = MODE_NONE;
                return true;
            }
        }
        return true;
    }

    /** Softer than the exact-corner snap: after the wall has been squared to an axis, nudge its free end onto a
     * nearby existing corner's X (for a horizontal wall) or Y (for a vertical wall) — or both when oblique — so it
     * lines up with the parallel/opposite wall. Records which guide lines to draw. Never runs when an exact corner
     * was already caught. */
    private void applyAlignment() {
        double tol = endpointTolWorld() * 1.3;
        boolean horiz = Math.abs(curWY - startWY) < 1e-3, vert = Math.abs(curWX - startWX) < 1e-3;
        double[] al = DrawGeo.snapToAlignments(curWX, curWY, model.elements, tol);
        if (!vert && !Double.isNaN(al[2])) { curWX = (float) al[0]; guideX = al[2]; hasGuideX = true; }   // align along X
        if (!horiz && !Double.isNaN(al[3])) { curWY = (float) al[1]; guideY = al[3]; hasGuideY = true; }  // align along Y
    }

    private void commitDraw() {
        hasGuideX = hasGuideY = false;
        if (tool == TOOL_CALIBRATE) { // a reference line, not a saved element — hand its length to the host
            double len = DrawGeo.dist(startWX, startWY, curWX, curWY);
            if (len >= minLenWorld() && listener != null) listener.onCalibrate(len);
            invalidate();
            return;
        }
        float ex = curWX, ey = curWY;
        if (tool != TOOL_ROOM && tool != TOOL_STAIRS) {
            double[] snap = DrawGeo.snapToEndpoint(ex, ey, model.elements, endpointTolWorld());
            ex = (float) snap[0]; ey = (float) snap[1];
            double[] a = DrawGeo.snapToAxis(startWX, startWY, ex, ey, 11); ex = (float) a[0]; ey = (float) a[1];
        }
        double len = DrawGeo.dist(startWX, startWY, ex, ey);
        if (len < minLenWorld()) { invalidate(); return; } // ignore an accidental tap
        DrawElement el;
        if (tool == TOOL_ROOM || tool == TOOL_STAIRS) {
            float x1 = Math.min(startWX, ex), y1 = Math.min(startWY, ey), x2 = Math.max(startWX, ex), y2 = Math.max(startWY, ey);
            el = new DrawElement(tool == TOOL_ROOM ? DrawElement.ROOM : DrawElement.STAIRS, tool == TOOL_ROOM ? "rooms" : "stairs", new double[]{x1, y1, x2, y2});
        } else {
            el = new DrawElement(tool == TOOL_WALL ? DrawElement.WALL : DrawElement.LINE, tool == TOOL_WALL ? "walls" : "rooms",
                    new double[]{startWX, startWY, ex, ey});
            if (tool == TOOL_WALL) el.thicknessM = currentThicknessM; // stamp the chosen thickness on the new wall
        }
        model.add(el); selected = el; changed();
        if (tool == TOOL_WALL || tool == TOOL_LINE) { chainX = ex; chainY = ey; hasChain = true; } // remember end → chain next
        if (tool == TOOL_ROOM && listener != null) listener.onRoomCommitted(el); // A1: prompt for the room name right away
        // B4: no dialog after drawing a wall BY DEFAULT — field speed first. Tap the wall later to set an exact
        // length. FAST FIELD MODE adds an opt-in exception: onWallDrawn always fires for a new wall, but the host
        // only actually shows a dialog when "طلب طول الجدار بعد الرسم" is enabled — off by default, matching B4.
        if (tool == TOOL_WALL && listener != null) listener.onWallDrawn(el);
    }

    /** FAST FIELD MODE — «تمديد الجدار»: extend the wall's free (unconnected) endpoint(s) along its own drawn
     * direction until it meets the nearest other wall it would actually cross, without creating a separate wall
     * segment and without touching the connected end. An endpoint already snapped onto another wall/corner is
     * left alone. Returns true if at least one endpoint was extended. One undo step. */
    public boolean extendWall(DrawElement wall) {
        if (wall == null || wall.pts == null || wall.pts.length < 4) return false;
        double searchWorld = endpointTolWorld() * 60; // generous field-scale search radius
        double[] a = tryExtendEnd(wall, wall.pts[2], wall.pts[3], wall.pts[0], wall.pts[1], searchWorld);
        double[] b = tryExtendEnd(wall, wall.pts[0], wall.pts[1], wall.pts[2], wall.pts[3], searchWorld);
        if (a == null && b == null) return false;
        model.beginEdit();
        if (a != null) { wall.pts[2] = a[0]; wall.pts[3] = a[1]; }
        if (b != null) { wall.pts[0] = b[0]; wall.pts[1] = b[1]; }
        selected = wall; changed();
        return true;
    }

    /** Try to extend the endpoint (freeX,freeY) — the end AWAY from the anchor (anchorX,anchorY), i.e. the
     * direction anchor→free is the wall's own angle, preserved exactly — outward until it meets another wall's
     * line. Returns null when that end is already connected (nothing to do) or no wall is found ahead within
     * {@code maxDist}. */
    private double[] tryExtendEnd(DrawElement wall, double freeX, double freeY, double anchorX, double anchorY, double maxDist) {
        double connTol = endpointTolWorld();
        // Already touching another wall's endpoint or running into its body → nothing to extend, this end is fine.
        for (DrawElement e : model.elements) {
            if (e == wall || !DrawElement.WALL.equals(e.type) || e.pts == null || e.pts.length < 4) continue;
            if (DrawGeo.pointToSegment(freeX, freeY, e.pts[0], e.pts[1], e.pts[2], e.pts[3]) <= connTol) return null;
        }
        double best = -1; double[] bestPt = null;
        for (DrawElement e : model.elements) {
            if (e == wall || !DrawElement.WALL.equals(e.type) || e.pts == null || e.pts.length < 4) continue;
            double[] hit = DrawGeo.lineIntersection(anchorX, anchorY, freeX, freeY, e.pts[0], e.pts[1], e.pts[2], e.pts[3]);
            if (hit == null) continue;
            double t = hit[2], u = hit[3];
            if (t <= 1.0 + 1e-6) continue;               // must be AHEAD of the free end (a true extension, not a shortening)
            if (u < -0.02 || u > 1.02) continue;          // must land on the other wall's actual span (small slack for near-miss)
            double d = DrawGeo.dist(freeX, freeY, hit[0], hit[1]);
            if (d > maxDist) continue;
            if (best < 0 || d < best) { best = d; bestPt = new double[]{hit[0], hit[1]}; }
        }
        return bestPt;
    }

    /** B4: re-shape a wall to an exact metric length, keeping its start point fixed. {@code angleMode} keeps the
     * drawn direction (ANGLE_KEEP — preserves oblique walls), or snaps to horizontal (ANGLE_H) / vertical
     * (ANGLE_V). No-op if uncalibrated or length ≤ 0. One undoable step; autosaves. */
    public void adjustWall(DrawElement wall, double lengthM, int angleMode) {
        if (wall == null || wall.pts == null || wall.pts.length < 4 || !model.isCalibrated() || lengthM <= 0) return;
        double sx = wall.pts[0], sy = wall.pts[1], dx = wall.pts[2] - sx, dy = wall.pts[3] - sy, len = Math.hypot(dx, dy);
        double ux, uy;
        if (angleMode == ANGLE_H) { ux = dx >= 0 ? 1 : -1; uy = 0; }
        else if (angleMode == ANGLE_V) { ux = 0; uy = dy >= 0 ? 1 : -1; }
        else if (len < 1e-6) { ux = 1; uy = 0; }
        else { ux = dx / len; uy = dy / len; }
        // Interior(net) mode: the engineer types the CLEAR inner length, so grow the axis by the connecting
        // walls' half-thickness at each end — the displayed interior then matches what they typed.
        double lw = (lengthM + interiorPaddingM(wall)) * model.pxPerMeter;
        model.beginEdit();
        wall.pts[2] = sx + ux * lw; wall.pts[3] = sy + uy * lw;
        selected = wall; changed();
    }

    /** B4: current real length (metres) of a wall's segment, or 0 while uncalibrated. */
    public double segmentMeters(DrawElement e) { return model.segmentMeters(e); }

    // ---- Interior ("net") dimensions: what the engineer measures on site (clear inner span), not axis-to-axis ----
    /** Half-thickness (world units) of a wall — OTHER than {@code self} — that meets at the corner (x,y); 0 if
     * that end is free. Used to trim the axis length down to the clear interior span. */
    private double connHalfThicknessWorld(DrawElement self, double x, double y) {
        double tolW = Math.max(3.0, 2.0 * density / scale());
        for (DrawElement e : model.elements) {
            if (e == self || !DrawElement.WALL.equals(e.type) || e.pts == null || e.pts.length < 4) continue;
            if (DrawGeo.dist(x, y, e.pts[0], e.pts[1]) <= tolW || DrawGeo.dist(x, y, e.pts[2], e.pts[3]) <= tolW)
                return model.wallThicknessM(e) * model.pxPerMeter / 2.0;
        }
        return 0;
    }
    /** Interior clear length (m) of a wall = axis length − the connecting walls' half-thickness at each end.
     * 0 while uncalibrated. This is the dimension a field engineer reads off the tape. */
    public double interiorMeters(DrawElement wall) {
        if (wall == null || wall.pts == null || wall.pts.length < 4 || !model.isCalibrated()) return 0;
        double axisW = DrawGeo.dist(wall.pts[0], wall.pts[1], wall.pts[2], wall.pts[3]);
        double cut = connHalfThicknessWorld(wall, wall.pts[0], wall.pts[1]) + connHalfThicknessWorld(wall, wall.pts[2], wall.pts[3]);
        return model.meters(Math.max(0, axisW - cut));
    }
    /** Extra axis length (m) to ADD to an interior target so the clear span comes out right — the connecting
     * walls' half-thickness at each end. 0 in axis mode or uncalibrated. */
    private double interiorPaddingM(DrawElement wall) {
        if (!"net".equals(model.dimensionRef) || !model.isCalibrated() || wall == null || wall.pts == null || wall.pts.length < 4) return 0;
        return model.meters(connHalfThicknessWorld(wall, wall.pts[0], wall.pts[1]) + connHalfThicknessWorld(wall, wall.pts[2], wall.pts[3]));
    }
    public boolean netDimensions() { return "net".equals(model.dimensionRef); }
    /** Toggle interior(net) ⇄ axis dimension display. Repaints + autosaves. */
    public void toggleDimensionRef() { model.dimensionRef = netDimensions() ? "axis" : "net"; changed(); }

    // ---- Room helpers: build a rectangle of REAL walls, so «غرفة» produces walls (not just an overlay) ----
    private void addWallDirect(double ax, double ay, double bx, double by) {
        DrawElement w = new DrawElement(DrawElement.WALL, "walls", new double[]{ax, ay, bx, by});
        w.thicknessM = currentThicknessM; model.ensureId(w); model.elements.add(w);
    }
    /** Build 4 axis-aligned walls forming a rectangle (world coords) as ONE undo step. */
    private void addRectWalls(double x1, double y1, double x2, double y2) {
        double ax = Math.min(x1, x2), ay = Math.min(y1, y2), bx = Math.max(x1, x2), by = Math.max(y1, y2);
        addWallDirect(ax, ay, bx, ay); addWallDirect(bx, ay, bx, by); addWallDirect(bx, by, ax, by); addWallDirect(ax, by, ax, ay);
    }

    /** «غرفة بأبعاد»: draw a room as four REAL walls of exact metric size, centred in the current view — the
     * easy "type length × width and get walls" flow. Needs a calibrated scale. Returns false if uncalibrated. */
    public boolean addRoomWallsByDimensions(double lengthM, double widthM) { return addRoomWallsByDimensions(lengthM, widthM, null); }

    /** FAST FIELD MODE (#4): same as above, plus an optional room TYPE — when given, a ROOM overlay element is
     * added over the same rectangle (same pattern «توليد الجدران حول الغرفة» already uses: real walls + a room
     * outline together) purely so the name/area label renders, exactly like any other named room. */
    public boolean addRoomWallsByDimensions(double lengthM, double widthM, String label) {
        if (!model.isCalibrated() || lengthM <= 0 || widthM <= 0) return false;
        // In interior(net) mode the typed size is the CLEAR inner room, so the axis rectangle is bigger by one
        // wall thickness on each dimension (half at each of the two facing walls) — the interior comes out exact.
        double pad = netDimensions() ? currentThicknessM : 0;
        double w = (lengthM + pad) * model.pxPerMeter, h = (widthM + pad) * model.pxPerMeter;
        float[] c = toWorld(getWidth() / 2f, getHeight() / 2f);
        model.beginEdit();                                   // one undo step for the whole room
        double x1 = c[0] - w / 2, y1 = c[1] - h / 2, x2 = c[0] + w / 2, y2 = c[1] + h / 2;
        addRectWalls(x1, y1, x2, y2);
        if (label != null && !label.trim().isEmpty()) {
            DrawElement room = new DrawElement(DrawElement.ROOM, "rooms", new double[]{x1, y1, x2, y2});
            room.label = label.trim(); model.ensureId(room); model.elements.add(room);
            selected = room;
        } else selected = null;
        changed();
        return true;
    }

    /** «توليد الجدران حول الغرفة»: turn a drawn ROOM rectangle into four real walls (kept as one undo step). */
    public void roomToWalls(DrawElement room) {
        if (room == null || room.pts == null || room.pts.length < 4) return;
        model.beginEdit();
        addRectWalls(room.pts[0], room.pts[1], room.pts[2], room.pts[3]);
        selected = null; changed();
    }

    /** «ضبط الطول والعرض»: resize a ROOM rectangle to an exact metric length×width, keeping its top-left corner
     * fixed. Needs a calibrated scale. */
    public void resizeRoom(DrawElement room, double lengthM, double widthM) {
        if (room == null || room.pts == null || room.pts.length < 4 || !model.isCalibrated() || lengthM <= 0 || widthM <= 0) return;
        double x1 = Math.min(room.pts[0], room.pts[2]), y1 = Math.min(room.pts[1], room.pts[3]);
        model.beginEdit();
        room.pts = new double[]{x1, y1, x1 + lengthM * model.pxPerMeter, y1 + widthM * model.pxPerMeter};
        selected = room; changed();
    }

    private float endpointTolWorld() { return 24 * density / scale(); } // A-series: wider so corners are easy to catch
    private float minLenWorld() { return 8 * density / scale(); }
    private float hitTolWorld() { return 22 * density / scale(); }

    private DrawElement hitTest(float wx, float wy) {
        float tol = hitTolWorld();
        for (int i = model.elements.size() - 1; i >= 0; i--) { // topmost first
            DrawElement e = model.elements.get(i);
            if (e.pts == null || !model.isLayerVisible(e.layer)) continue;
            if (isOpening(e)) { // A2/A3: doors & windows selectable by tapping near their centre
                double[] g = model.openingGeom(e);
                if (g != null && DrawGeo.dist(wx, wy, g[0], g[1]) <= Math.max(tol, g[5] / 2)) return e;
                continue;
            }
            if (DrawElement.ROOM.equals(e.type) || DrawElement.STAIRS.equals(e.type)) {
                if (nearRect(wx, wy, e.pts, tol)) return e;
            } else if (e.pts.length >= 4) {
                if (DrawGeo.pointToSegment(wx, wy, e.pts[0], e.pts[1], e.pts[2], e.pts[3]) <= tol) return e;
            }
        }
        return null;
    }

    private boolean nearRect(float x, float y, double[] p, float tol) {
        double x1 = p[0], y1 = p[1], x2 = p[2], y2 = p[3];
        double d = Math.min(Math.min(DrawGeo.pointToSegment(x, y, x1, y1, x2, y1), DrawGeo.pointToSegment(x, y, x2, y1, x2, y2)),
                Math.min(DrawGeo.pointToSegment(x, y, x2, y2, x1, y2), DrawGeo.pointToSegment(x, y, x1, y2, x1, y1)));
        if (d <= tol) return true;
        return x >= x1 && x <= x2 && y >= y1 && y <= y2; // also selectable by tapping inside
    }

    // ---- render ----
    @Override protected void onDraw(Canvas canvas) {
        float sc = scale();
        canvas.save();
        canvas.concat(matrix);
        if (showGrid) drawGrid(canvas, sc);
        grid.setStrokeWidth(1f / sc);
        for (DrawElement e : model.elements) {
            if (e.pts == null || !model.isLayerVisible(e.layer)) continue;
            if (DrawElement.WALL.equals(e.type)) { drawWall(canvas, e, sc, e == selected); continue; }
            if (DrawElement.STAIRS.equals(e.type)) { drawStairs(canvas, e, sc, e == selected); continue; }
            Paint p = DrawElement.ROOM.equals(e.type) ? roomP : wallP;
            p.setStrokeWidth(2.5f / sc);
            drawElement(canvas, e, p);
            if (e == selected) { selP.setStrokeWidth(6f / sc); drawElement(canvas, e, selP); }
        }
        // A2/A3: doors + windows drawn in a second pass so they always sit on top of the wall band
        for (DrawElement e : model.elements) {
            if (!model.isLayerVisible(e.layer)) continue;
            if (DrawElement.DOOR.equals(e.type)) drawDoor(canvas, e, sc, e == selected);
            else if (DrawElement.WINDOW.equals(e.type)) drawWindow(canvas, e, sc, e == selected);
        }
        if (mode == MODE_DRAW) {
            ghost.setStrokeWidth(3f / sc);
            if (tool == TOOL_ROOM || tool == TOOL_STAIRS) canvas.drawRect(Math.min(startWX, curWX), Math.min(startWY, curWY), Math.max(startWX, curWX), Math.max(startWY, curWY), ghost);
            else {
                canvas.drawLine(startWX, startWY, curWX, curWY, ghost);
                float r = 5.5f / sc;
                canvas.drawCircle(startWX, startWY, r, snapDot);            // start anchor / connection point
                if (endSnapped) canvas.drawCircle(curWX, curWY, r, snapDot); // end locked onto an existing corner
            }
        }
        canvas.restore();
        // Alignment guides: full-screen dashed lines through the caught corner's X/Y, so the engineer SEES the
        // wall lining up with the parallel/opposite wall before releasing. Drawn in screen space to stay crisp.
        if (mode == MODE_DRAW && (hasGuideX || hasGuideY)) {
            if (hasGuideX) { float gx = worldToScreen(guideX, 0)[0]; canvas.drawLine(gx, 0, gx, getHeight(), guideP); }
            if (hasGuideY) { float gy = worldToScreen(0, guideY)[1]; canvas.drawLine(0, gy, getWidth(), gy, guideP); }
        }
        // B4: live length readout above the finger while drawing a wall/line, so the engineer sees the real size.
        if (mode == MODE_DRAW && (tool == TOOL_WALL || tool == TOOL_LINE) && model.isCalibrated()) {
            double lenM = model.meters(DrawGeo.dist(startWX, startWY, curWX, curWY));
            float[] p = worldToScreen(curWX, curWY);
            String s = String.format(java.util.Locale.US, "الطول: %.2f م", lenM);
            float tx = p[0], ty = p[1] - 26 * density;
            canvas.drawText(s, tx, ty, roomNameHalo);
            canvas.drawText(s, tx, ty, roomNameFill);
        }
        drawDimensions(canvas);
        drawRoomLabels(canvas);
        drawNorthAndScale(canvas, sc);
        drawAreaSummary(canvas);
    }

    /** A standalone total-area readout, pinned top-start of the screen once closed rooms exist — the area is shown
     * on its own chip, separate from the drawing, so numbers never tangle with the plan. */
    private void drawAreaSummary(Canvas canvas) {
        if (!model.isCalibrated()) return;
        double total = 0; int rooms = 0;
        for (DrawElement e : model.elements) {
            if (!DrawElement.ROOM.equals(e.type) || e.pts == null || e.pts.length < 4) continue;
            total += model.meters(Math.abs(e.pts[2] - e.pts[0])) * model.meters(Math.abs(e.pts[3] - e.pts[1]));
            rooms++;
        }
        String s;
        if (rooms > 0) {
            s = String.format(java.util.Locale.US, "المساحة الكلية: %.1f م²  •  %d غرفة", total, rooms);
        } else { // no «غرفة» rectangles → measure the area enclosed by a closed run of walls (shoelace)
            double wa = wallLoopAreaM2();
            if (wa <= 0) return;
            s = String.format(java.util.Locale.US, "المساحة: %.1f م²", wa);
        }
        float pad = 8 * density, x = 10 * density, y = 12 * density;
        float w = bannerText.measureText(s), h = 20 * density;
        canvas.drawRoundRect(x, y, x + w + pad * 2, y + h + pad, 8 * density, 8 * density, bannerBg);
        canvas.drawText(s, x + pad, y + h, bannerText);
    }

    /** Area (m²) enclosed by the walls when they form ONE closed loop — walks wall-to-wall by shared (snapped)
     * corners, then applies the shoelace formula. Returns 0 if the walls don't close into a single ring, so a
     * half-drawn sketch shows no misleading number. */
    private double wallLoopAreaM2() {
        java.util.List<double[]> segs = new java.util.ArrayList<>();
        for (DrawElement e : model.elements)
            if (DrawElement.WALL.equals(e.type) && e.pts != null && e.pts.length >= 4)
                segs.add(new double[]{e.pts[0], e.pts[1], e.pts[2], e.pts[3]});
        if (segs.size() < 3) return 0;
        double tol = 2.0; // world units — snapped corners share (near) exact coordinates
        boolean[] used = new boolean[segs.size()];
        java.util.List<double[]> ring = new java.util.ArrayList<>();
        double[] s0 = segs.get(0); used[0] = true;
        double startX = s0[0], startY = s0[1], cx = s0[2], cy = s0[3];
        ring.add(new double[]{startX, startY}); ring.add(new double[]{cx, cy});
        for (int guard = 0; guard < segs.size(); guard++) {
            boolean found = false;
            for (int i = 0; i < segs.size(); i++) {
                if (used[i]) continue;
                double[] s = segs.get(i);
                if (Math.hypot(s[0] - cx, s[1] - cy) <= tol) { cx = s[2]; cy = s[3]; used[i] = true; found = true; }
                else if (Math.hypot(s[2] - cx, s[3] - cy) <= tol) { cx = s[0]; cy = s[1]; used[i] = true; found = true; }
                if (found) { ring.add(new double[]{cx, cy}); break; }
            }
            if (!found) break;
        }
        for (boolean u : used) if (!u) return 0;                       // every wall must be part of the ring
        if (ring.size() < 4 || Math.hypot(cx - startX, cy - startY) > tol * 3) return 0; // must return to start
        double cross = 0;
        for (int i = 0; i < ring.size() - 1; i++) {
            double[] a = ring.get(i), b = ring.get(i + 1);
            cross += a[0] * b[1] - b[0] * a[1];
        }
        double areaPx2 = Math.abs(cross) / 2.0;
        double metersPerUnit = model.meters(1.0);
        double axisArea = areaPx2 * metersPerUnit * metersPerUnit;
        if (!netDimensions()) return axisArea;
        // Interior area: inset the closed loop inward by half the wall thickness (≈ axisArea − perimeter·t/2).
        double perimW = 0;
        for (int i = 0; i < ring.size() - 1; i++) {
            double[] a = ring.get(i), b = ring.get(i + 1);
            perimW += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        double interior = axisArea - model.meters(perimW) * (averageWallThicknessM() / 2.0);
        return Math.max(0, interior);
    }

    /** Representative wall thickness (m) — the mean over the drawn walls, or the default when none carry one. */
    private double averageWallThicknessM() {
        double sum = 0; int n = 0;
        for (DrawElement e : model.elements) if (DrawElement.WALL.equals(e.type)) { sum += model.wallThicknessM(e); n++; }
        return n == 0 ? DrawModel.DEFAULT_WALL_THICKNESS_M : sum / n;
    }

    /** A1: the room's name (bold) and, once calibrated, its area on the line below — drawn centred in the room in
     * SCREEN space so both stay legible at any zoom, with a white halo to read over walls and the grid. */
    private void drawRoomLabels(Canvas canvas) {
        if (!model.isLayerVisible("roomnames")) return; // B3: room names follow their own layer, not the boundary
        for (DrawElement e : model.elements) {
            if (!DrawElement.ROOM.equals(e.type) || e.pts == null || e.pts.length < 4) continue;
            float[] c = worldToScreen((e.pts[0] + e.pts[2]) / 2, (e.pts[1] + e.pts[3]) / 2);
            String name = e.label == null ? "" : e.label.trim();
            String area = model.isCalibrated() ? roomAreaStr(e) : "";
            if (!name.isEmpty() && !area.isEmpty()) {
                haloText(canvas, name, c[0], c[1] - 3 * density, roomNameHalo, roomNameFill);
                haloText(canvas, area, c[0], c[1] + 15 * density, dimHalo, dimFill);
            } else if (!name.isEmpty()) {
                haloText(canvas, name, c[0], c[1] + 4 * density, roomNameHalo, roomNameFill);
            } else if (!area.isEmpty()) {
                haloText(canvas, area, c[0], c[1] + 4 * density, dimHalo, dimFill);
            }
        }
    }

    private String roomAreaStr(DrawElement e) {
        double w = model.meters(Math.abs(e.pts[2] - e.pts[0])), h = model.meters(Math.abs(e.pts[3] - e.pts[1]));
        return String.format(java.util.Locale.US, "%.1f م²", w * h);
    }

    private void haloText(Canvas c, String s, float x, float y, Paint halo, Paint fill) { c.drawText(s, x, y, halo); c.drawText(s, x, y, fill); }

    /** Real-length labels, drawn in SCREEN space so they stay legible at any zoom. Only appear once the model
     * is calibrated and the "dims" layer is visible — an uncalibrated sketch shows no misleading numbers. */
    private void drawDimensions(Canvas canvas) {
        if (!model.isCalibrated() || !model.isLayerVisible("dims")) return;
        for (DrawElement e : model.elements) {
            if (e.pts == null || e.pts.length < 4 || !model.isLayerVisible(e.layer)) continue;
            if (DrawElement.ROOM.equals(e.type)) {
                double w = model.meters(Math.abs(e.pts[2] - e.pts[0]));
                double h = model.meters(Math.abs(e.pts[3] - e.pts[1]));
                float[] top = worldToScreen((e.pts[0] + e.pts[2]) / 2, Math.min(e.pts[1], e.pts[3]));
                dimText(canvas, fmt(w), top[0], top[1] - 6 * density);
                float[] side = worldToScreen(Math.max(e.pts[0], e.pts[2]), (e.pts[1] + e.pts[3]) / 2);
                dimText(canvas, fmt(h), side[0] + 20 * density, side[1]);
            } else {
                // Walls show the INTERIOR clear length (net) by default — what the engineer measures on site —
                // instead of axis-to-axis. LINE elements and axis mode keep the plain centre-line length.
                double m = (DrawElement.WALL.equals(e.type) && netDimensions()) ? interiorMeters(e)
                        : model.meters(DrawGeo.dist(e.pts[0], e.pts[1], e.pts[2], e.pts[3]));
                if (m <= 0) continue;
                // Offset the length label PERPENDICULAR to the wall (to its upper side) instead of sitting on the
                // mid-point — so adjacent walls' numbers sit clear of the lines and of each other.
                float[] a = worldToScreen(e.pts[0], e.pts[1]);
                float[] bp = worldToScreen(e.pts[2], e.pts[3]);
                float mx = (a[0] + bp[0]) / 2f, my = (a[1] + bp[1]) / 2f;
                float ddx = bp[0] - a[0], ddy = bp[1] - a[1];
                double L = Math.hypot(ddx, ddy);
                float nx = L > 1e-3 ? (float) (-ddy / L) : 0f, ny = L > 1e-3 ? (float) (ddx / L) : -1f;
                if (ny > 0) { nx = -nx; ny = -ny; } // keep the label above the wall so it never dives under the line
                float off = 15 * density;
                dimText(canvas, fmt(m), mx + nx * off, my + ny * off - 2 * density);
            }
        }
    }

    private String fmt(double meters) { return String.format(java.util.Locale.US, "%.2f م", meters); }

    private void dimText(Canvas canvas, String s, float x, float y) {
        canvas.drawText(s, x, y, dimHalo); // white halo for legibility over lines/grid
        canvas.drawText(s, x, y, dimFill);
    }

    private float[] worldToScreen(double x, double y) { tmp[0] = (float) x; tmp[1] = (float) y; matrix.mapPoints(tmp); return new float[]{tmp[0], tmp[1]}; }

    /** A wall renders as a closed area with two parallel edges (its axis offset by ±thickness/2). Thickness is
     * metric, so it only draws to scale once calibrated; before that it falls back to a plain centre line. The
     * axis (e.pts) is unchanged, so dimensions still measure the centre-line and future net-dims stay possible. */
    private void drawWall(Canvas canvas, DrawElement e, float sc, boolean sel) {
        if (e.pts.length < 4) return;
        double x1 = e.pts[0], y1 = e.pts[1], x2 = e.pts[2], y2 = e.pts[3];
        double dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy);
        if (len < 1e-6) return;
        double tWorld = model.isCalibrated() ? model.wallThicknessM(e) * model.pxPerMeter : 0;
        if (tWorld <= 0) {
            wallP.setStrokeWidth(4f / sc); canvas.drawLine((float) x1, (float) y1, (float) x2, (float) y2, wallP);
            if (sel) { selP.setStrokeWidth(6f / sc); canvas.drawLine((float) x1, (float) y1, (float) x2, (float) y2, selP); }
            return;
        }
        double nx = -dy / len * (tWorld / 2), ny = dx / len * (tWorld / 2);
        wallPath.reset();
        wallPath.moveTo((float) (x1 + nx), (float) (y1 + ny));
        wallPath.lineTo((float) (x2 + nx), (float) (y2 + ny));
        wallPath.lineTo((float) (x2 - nx), (float) (y2 - ny));
        wallPath.lineTo((float) (x1 - nx), (float) (y1 - ny));
        wallPath.close();
        wallFill.setColor(sel ? 0x22C9851C : 0x180B2F52);
        canvas.drawPath(wallPath, wallFill);
        Paint stroke = sel ? selP : wallP;
        stroke.setStrokeWidth((sel ? 2.5f : 2f) / sc);
        canvas.drawPath(wallPath, stroke);
    }

    /** A2: draw a field door symbol (like the reference croquis) — a white opening across the wall band, two jamb
     * ticks, the leaf line, and a quarter-circle swing arc. All in world space; geometry follows the host wall. */
    private void drawDoor(Canvas canvas, DrawElement e, float sc, boolean sel) {
        double[] g = model.doorGeom(e); if (g == null) return;
        double cx = g[0], cy = g[1], ux = g[2], uy = g[3], th = g[4], w = g[5];
        double half = w / 2, nx = -uy, ny = ux;
        double e0x = cx - ux * half, e0y = cy - uy * half, e1x = cx + ux * half, e1y = cy + uy * half;
        boolean hingeE0 = (e.swing & 1) == 0; double side = (e.swing & 2) == 0 ? 1 : -1;
        double hx = hingeE0 ? e0x : e1x, hy = hingeE0 ? e0y : e1y;
        double fx = hingeE0 ? e1x : e0x, fy = hingeE0 ? e1y : e0y;
        double lx = hx + nx * side * w, ly = hy + ny * side * w;
        // open the wall: a white band over the opening
        doorPath.reset();
        doorPath.moveTo((float) (e0x + nx * th / 2), (float) (e0y + ny * th / 2));
        doorPath.lineTo((float) (e1x + nx * th / 2), (float) (e1y + ny * th / 2));
        doorPath.lineTo((float) (e1x - nx * th / 2), (float) (e1y - ny * th / 2));
        doorPath.lineTo((float) (e0x - nx * th / 2), (float) (e0y - ny * th / 2));
        doorPath.close();
        canvas.drawPath(doorPath, doorGap);
        doorP.setColor(sel ? 0xffC9851C : 0xff0B2F52); doorP.setStrokeWidth((sel ? 2.6f : 1.8f) / sc);
        canvas.drawLine((float) (e0x + nx * th / 2), (float) (e0y + ny * th / 2), (float) (e0x - nx * th / 2), (float) (e0y - ny * th / 2), doorP);
        canvas.drawLine((float) (e1x + nx * th / 2), (float) (e1y + ny * th / 2), (float) (e1x - nx * th / 2), (float) (e1y - ny * th / 2), doorP);
        boolean folding = "أكورديون".equals(e.kind) || "مطوي".equals(e.kind);
        boolean dbl = "ضرفتين".equals(e.kind) || "مصراعين".equals(e.kind);
        if (dbl) {
            // Double-leaf: a leaf hinged at EACH jamb, both half the opening wide, swinging to the same side.
            double halfw = w / 2;
            double laX = e0x + nx * side * halfw, laY = e0y + ny * side * halfw;   // leaf A tip (hinged at e0)
            double lbX = e1x + nx * side * halfw, lbY = e1y + ny * side * halfw;   // leaf B tip (hinged at e1)
            canvas.drawLine((float) e0x, (float) e0y, (float) laX, (float) laY, doorP);
            canvas.drawLine((float) e1x, (float) e1y, (float) lbX, (float) lbY, doorP);
            android.graphics.RectF ra = new android.graphics.RectF((float) (e0x - halfw), (float) (e0y - halfw), (float) (e0x + halfw), (float) (e0y + halfw));
            float a0a = (float) Math.toDegrees(Math.atan2(laY - e0y, laX - e0x));
            float a1a = (float) Math.toDegrees(Math.atan2(cy - e0y, cx - e0x));
            canvas.drawArc(ra, a0a, (float) DrawGeo.normalizeDeg(a1a - a0a), false, doorP);
            android.graphics.RectF rb = new android.graphics.RectF((float) (e1x - halfw), (float) (e1y - halfw), (float) (e1x + halfw), (float) (e1y + halfw));
            float a0b = (float) Math.toDegrees(Math.atan2(lbY - e1y, lbX - e1x));
            float a1b = (float) Math.toDegrees(Math.atan2(cy - e1y, cx - e1x));
            canvas.drawArc(rb, a0b, (float) DrawGeo.normalizeDeg(a1b - a0b), false, doorP);
        } else if (folding) {
            // Accordion / bi-fold: a zigzag across the opening instead of a hinged leaf + swing arc.
            int n = 6; double amp = w * 0.28;
            for (int i = 0; i < n; i++) {
                double ax = e0x + (e1x - e0x) * i / n, ay = e0y + (e1y - e0y) * i / n;
                double bx = e0x + (e1x - e0x) * (i + 1) / n, by = e0y + (e1y - e0y) * (i + 1) / n;
                double px = (ax + bx) / 2 + nx * side * (i % 2 == 0 ? amp : -amp);
                double py = (ay + by) / 2 + ny * side * (i % 2 == 0 ? amp : -amp);
                canvas.drawLine((float) ax, (float) ay, (float) px, (float) py, doorP);
                canvas.drawLine((float) px, (float) py, (float) bx, (float) by, doorP);
            }
        } else { // hinged door (داخلي / خارجي): leaf line + quarter-circle swing arc
            canvas.drawLine((float) hx, (float) hy, (float) lx, (float) ly, doorP);
            android.graphics.RectF r = new android.graphics.RectF((float) (hx - w), (float) (hy - w), (float) (hx + w), (float) (hy + w));
            float a0 = (float) Math.toDegrees(Math.atan2(ly - hy, lx - hx));
            float a1 = (float) Math.toDegrees(Math.atan2(fy - hy, fx - hx));
            canvas.drawArc(r, a0, (float) DrawGeo.normalizeDeg(a1 - a0), false, doorP);
        }
    }

    /** Simple stairs symbol: the rectangle outline, evenly-spaced step treads across the run, and a single arrow
     * up the run showing the ascent direction (tap flips it). Step count follows the real length when calibrated
     * (~0.27 m per step) else a sensible fixed spacing. */
    private void drawStairs(Canvas canvas, DrawElement e, float sc, boolean sel) {
        if (e.pts == null || e.pts.length < 4) return;
        float x1 = (float) Math.min(e.pts[0], e.pts[2]), y1 = (float) Math.min(e.pts[1], e.pts[3]);
        float x2 = (float) Math.max(e.pts[0], e.pts[2]), y2 = (float) Math.max(e.pts[1], e.pts[3]);
        float w = x2 - x1, h = y2 - y1;
        Paint p = sel ? selP : wallP; p.setStrokeWidth((sel ? 2.5f : 2f) / sc);
        canvas.drawRect(x1, y1, x2, y2, p);
        boolean runX = w >= h;                       // steps run across the longer side
        float runLen = runX ? w : h;
        int n = model.isCalibrated() ? (int) Math.round(model.meters(runLen) / 0.27) : (int) (runLen / (26 * density));
        n = Math.max(2, Math.min(n, 40));
        for (int i = 1; i < n; i++) {
            float t = (float) i / n;
            if (runX) { float x = x1 + w * t; canvas.drawLine(x, y1, x, y2, p); }
            else { float y = y1 + h * t; canvas.drawLine(x1, y, x2, y, p); }
        }
        // ascent arrow up the run (swing&1 flips which end is "up")
        boolean rev = (e.swing & 1) == 1;
        float ax, ay, bx, by;
        if (runX) { float ym = (y1 + y2) / 2; ay = by = ym; ax = rev ? x2 : x1; bx = rev ? x1 : x2; }
        else { float xm = (x1 + x2) / 2; ax = bx = xm; ay = rev ? y2 : y1; by = rev ? y1 : y2; }
        doorP.setColor(sel ? 0xffC9851C : 0xff149176); doorP.setStrokeWidth((sel ? 2.6f : 2f) / sc);
        canvas.drawLine(ax, ay, bx, by, doorP);
        double ang = Math.atan2(by - ay, bx - ax), hl = Math.min(w, h) * 0.22 + 6 / sc;
        canvas.drawLine(bx, by, (float) (bx - hl * Math.cos(ang - 0.4)), (float) (by - hl * Math.sin(ang - 0.4)), doorP);
        canvas.drawLine(bx, by, (float) (bx - hl * Math.cos(ang + 0.4)), (float) (by - hl * Math.sin(ang + 0.4)), doorP);
    }

    private boolean isOpening(DrawElement e) { return e != null && (DrawElement.DOOR.equals(e.type) || DrawElement.WINDOW.equals(e.type)); }

    /** A3: window symbol — two jamb ticks across the wall band plus two glazing lines along the axis (the wall
     * stays; a window doesn't cut a hole). Matches the reference croquis double-line window. */
    private void drawWindow(Canvas canvas, DrawElement e, float sc, boolean sel) {
        double[] g = model.openingGeom(e); if (g == null) return;
        double cx = g[0], cy = g[1], ux = g[2], uy = g[3], th = g[4], w = g[5];
        double half = w / 2, nx = -uy, ny = ux, gl = th * 0.25;
        double e0x = cx - ux * half, e0y = cy - uy * half, e1x = cx + ux * half, e1y = cy + uy * half;
        windowP.setColor(sel ? 0xffC9851C : 0xff0E7490); windowP.setStrokeWidth((sel ? 2.6f : 1.8f) / sc);
        canvas.drawLine((float) (e0x + nx * th / 2), (float) (e0y + ny * th / 2), (float) (e0x - nx * th / 2), (float) (e0y - ny * th / 2), windowP);
        canvas.drawLine((float) (e1x + nx * th / 2), (float) (e1y + ny * th / 2), (float) (e1x - nx * th / 2), (float) (e1y - ny * th / 2), windowP);
        canvas.drawLine((float) (e0x + nx * gl), (float) (e0y + ny * gl), (float) (e1x + nx * gl), (float) (e1y + ny * gl), windowP);
        canvas.drawLine((float) (e0x - nx * gl), (float) (e0y - ny * gl), (float) (e1x - nx * gl), (float) (e1y - ny * gl), windowP);
    }

    // ---- A2/A3: opening placement (door or window, anchored to a wall with snap) ----
    private void placeOpening(float wx, float wy, String type) {
        double[] tOut = new double[1];
        DrawElement w = nearestWall(wx, wy, wallPickTol(), tOut);
        if (w == null) { if (listener != null) listener.onOpeningMissedWall(); invalidate(); return; } // must sit on a wall
        model.ensureId(w);
        DrawElement op = new DrawElement(type, DrawElement.WINDOW.equals(type) ? "windows" : "doors", new double[]{wx, wy});
        op.hostId = w.id; op.t = tOut[0];
        model.add(op); selected = op;
        double[] g = model.openingGeom(op); if (g != null) op.pts = new double[]{g[0], g[1]};
        changed();
        // Discoverability: opening the editor right away means the width/type/swing choices always appear on the
        // first placement — the engineer never has to guess that they must switch to «تحديد» and tap the door.
        if (listener != null) {
            if (DrawElement.DOOR.equals(type)) listener.onDoorTap(op);
            else listener.onWindowTap(op);
        }
    }

    private void reanchorOpening(DrawElement op, float wx, float wy) {
        double[] tOut = new double[1];
        DrawElement w = nearestWall(wx, wy, wallPickTol() * 1.6f, tOut);
        if (w == null) return; // dragged off any wall → keep it on its current wall
        model.ensureId(w);
        op.hostId = w.id; op.t = tOut[0];
        double[] g = model.openingGeom(op); if (g != null) op.pts = new double[]{g[0], g[1]};
    }

    private DrawElement nearestWall(float wx, float wy, float tol, double[] outT) {
        DrawElement best = null; double bestD = tol;
        for (DrawElement e : model.elements) {
            if (!DrawElement.WALL.equals(e.type) || e.pts == null || e.pts.length < 4 || !model.isLayerVisible(e.layer)) continue;
            double d = DrawGeo.pointToSegment(wx, wy, e.pts[0], e.pts[1], e.pts[2], e.pts[3]);
            if (d <= bestD) { bestD = d; best = e; }
        }
        if (best != null && outT != null) {
            double x1 = best.pts[0], y1 = best.pts[1], x2 = best.pts[2], y2 = best.pts[3];
            double vx = x2 - x1, vy = y2 - y1, len2 = vx * vx + vy * vy;
            double t = len2 == 0 ? 0 : ((wx - x1) * vx + (wy - y1) * vy) / len2;
            outT[0] = Math.max(0, Math.min(1, t));
        }
        return best;
    }

    private float wallPickTol() { return 30 * density / scale(); }

    // ---- wall thickness (W1) ----
    public double newWallThickness() { return currentThicknessM; }
    public void setNewWallThickness(double m) { if (m > 0) currentThicknessM = m; }
    public boolean hasSelectedWall() { return selected != null && DrawElement.WALL.equals(selected.type); }
    public void setSelectedWallThickness(double m) { if (m > 0 && hasSelectedWall()) { model.beginEdit(); selected.thicknessM = m; changed(); } }

    private void drawElement(Canvas canvas, DrawElement e, Paint p) {
        if (DrawElement.ROOM.equals(e.type)) canvas.drawRect((float) e.pts[0], (float) e.pts[1], (float) e.pts[2], (float) e.pts[3], p);
        else if (e.pts.length >= 4) canvas.drawLine((float) e.pts[0], (float) e.pts[1], (float) e.pts[2], (float) e.pts[3], p);
    }

    private void drawGrid(Canvas canvas, float sc) {
        float[] tl = toWorld(0, 0), br = toWorld(getWidth(), getHeight());
        float step = 40f;
        if ((br[0] - tl[0]) / step > 220 || (br[1] - tl[1]) / step > 220) return; // too dense when zoomed far out
        grid.setStrokeWidth(1f / sc);
        float x0 = (float) (Math.floor(tl[0] / step) * step), x1 = br[0];
        for (float x = x0; x <= x1; x += step) canvas.drawLine(x, tl[1], x, br[1], grid);
        float y0 = (float) (Math.floor(tl[1] / step) * step), y1 = br[1];
        for (float y = y0; y <= y1; y += step) canvas.drawLine(tl[0], y, br[0], y, grid);
    }

    private void drawNorthAndScale(Canvas canvas, float sc) {
        float m = 16 * density, cx = getWidth() - m - 12 * density, top = m + 6 * density;
        float pivotY = top + 13 * density; // rotate the arrow about its own centre so it swings to real north
        canvas.save();
        canvas.rotate((float) (-model.northDeg), cx, pivotY);          // orient the arrow to the captured compass north
        canvas.drawLine(cx, top + 26 * density, cx, top, ui);          // north arrow shaft
        canvas.drawLine(cx, top, cx - 6 * density, top + 8 * density, ui);
        canvas.drawLine(cx, top, cx + 6 * density, top + 8 * density, ui);
        canvas.restore();
        uiText.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(model.northDeg != 0 ? "شمال ✓" : "شمال", cx, top + 42 * density, uiText);
        uiText.setTextAlign(Paint.Align.LEFT);
        boolean cal = model.isCalibrated();
        int saveColor = uiText.getColor();
        uiText.setColor(cal ? 0xff149176 : 0xffC9851C);
        String s = cal ? String.format(java.util.Locale.US, "معاير • 1م = %.0f px", model.pxPerMeter) : "غير معاير — اختر «معايرة»";
        canvas.drawText(s, m, getHeight() - m, uiText);
        uiText.setColor(saveColor);
    }
}
