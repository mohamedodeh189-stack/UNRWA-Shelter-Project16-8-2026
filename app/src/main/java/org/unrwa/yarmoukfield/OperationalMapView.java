package org.unrwa.yarmoukfield;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** The operational map — a calibrated camp-plan raster with sector boundaries, beneficiary points and a
 * GPS-density heatmap, now FULLY INTERACTIVE: two-finger pinch-zoom, drag to pan, double-tap to zoom, plus
 * programmatic fit-to-screen and focus-on-a-sector. The map/heatmap/sector fills are drawn under a zoom/pan
 * matrix; the beneficiary markers and sector NAME labels are drawn in screen space at a FIXED, readable size
 * so they stay crisp and legible at any zoom (fixing the «كل شيء صغير وغير مقروء» problem). Never invents a
 * precise location — a beneficiary with no real GPS fix is drawn at its sector centroid as a hollow ring. */
public final class OperationalMapView extends View {

    public static final class MapPoint {
        public final long beneficiaryId;
        public final String name, status, address, sector;
        public final float fracX, fracY;
        public final boolean estimated;
        public final int color;
        public MapPoint(long beneficiaryId, String name, String status, String address, String sector,
                         float fracX, float fracY, boolean estimated, int color) {
            this.beneficiaryId = beneficiaryId; this.name = name; this.status = status; this.address = address;
            this.sector = sector; this.fracX = fracX; this.fracY = fracY; this.estimated = estimated; this.color = color;
        }
    }

    // FINAL FIELD USABILITY FIX §6: report ALL sectors under the tapped point (not just the first) so the host can
    // let the engineer choose when polygons overlap, instead of silently guessing the wrong sector.
    public interface OnSectorTapListener { void onSectorTap(java.util.List<String> sectorNames); }
    public interface OnPointTapListener { void onPointTap(MapPoint point); }
    public interface OnMapTapListener { void onMapTap(float fracX, float fracY); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mapRect = new RectF();     // base fit rect (pre-zoom)
    private final Matrix view = new Matrix();       // zoom + pan on top of the base fit
    private final Matrix inv = new Matrix();
    private final float density;

    private Bitmap mapBitmap;
    private Bitmap overlayBitmap;      // e.g. the official plan drawn OVER a satellite photo
    private int overlayAlpha = 140;    // 0..255
    private List<SectorBoundaries.Sector> sectors = new ArrayList<>();
    private List<MapPoint> points = new ArrayList<>();
    private boolean showSectors = true, showPoints = true, showHeatmap = false, showLabels = true;
    private OnSectorTapListener sectorTapListener;
    private OnPointTapListener pointTapListener;
    private OnMapTapListener mapTapListener;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    private float lastPanX, lastPanY;
    private boolean panning;

    public OperationalMapView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(true); setFocusable(true); setBackgroundColor(Color.WHITE);
        setContentDescription("الخريطة التشغيلية الكاملة لمخيم اليرموك");
        label.setColor(Color.WHITE); label.setTextSize(13 * density); label.setFakeBoldText(true); label.setTextAlign(Paint.Align.CENTER);
        labelBg.setColor(0xCC0B2F52); labelBg.setStyle(Paint.Style.FILL);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float f = d.getScaleFactor(), cur = currentScale(), next = cur * f;
                if (next < 1f) f = 1f / cur; else if (next > 9f) f = 9f / cur;
                view.postScale(f, f, d.getFocusX(), d.getFocusY());
                clampPan(); invalidate(); return true;
            }
        });
        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { handleTap(e.getX(), e.getY()); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (currentScale() > 1.6f) fitToScreen();
                else { view.postScale(2.6f, 2.6f, e.getX(), e.getY()); clampPan(); invalidate(); }
                return true;
            }
        });
    }

    public void setMapBitmap(Bitmap bitmap) { mapBitmap = bitmap; invalidate(); }
    /** Second image drawn semi-transparently on top of the base map, aligned to the SAME rect — used to lay the
     * official street plan over a satellite photo so rooftops and street names are visible together. */
    public void setOverlay(Bitmap bitmap, int alpha0to255) { overlayBitmap = bitmap; overlayAlpha = Math.max(0, Math.min(255, alpha0to255)); invalidate(); }
    public void setOverlayAlpha(int alpha0to255) { overlayAlpha = Math.max(0, Math.min(255, alpha0to255)); invalidate(); }
    public boolean hasOverlay() { return overlayBitmap != null; }
    public void setSectors(List<SectorBoundaries.Sector> value) { sectors = value == null ? new ArrayList<>() : value; invalidate(); }
    public void setPoints(List<MapPoint> value) { points = value == null ? new ArrayList<>() : value; invalidate(); }
    public void setLayerVisibility(boolean sectorsOn, boolean pointsOn, boolean heatmapOn) {
        showSectors = sectorsOn; showPoints = pointsOn; showHeatmap = heatmapOn; invalidate();
    }
    public void setLabelsVisible(boolean on) { showLabels = on; invalidate(); }
    public void setOnSectorTapListener(OnSectorTapListener l) { sectorTapListener = l; }
    public void setOnPointTapListener(OnPointTapListener l) { pointTapListener = l; }
    public void setOnMapTapListener(OnMapTapListener l) { mapTapListener = l; }

    private long highlightId = -1;
    /** MAP V3: highlight one beneficiary's marker (an extra ring) — set from the «المستفيدون المعروضون» list. */
    public void setHighlight(long beneficiaryId) { highlightId = beneficiaryId; invalidate(); }

    /** MAP V3: centre + zoom onto a single point (fraction coords), e.g. when picked from the displayed list. */
    public void focusPoint(float fracX, float fracY) {
        if (mapRect.width() <= 0) return;
        float s2 = 4.2f;
        float bx = mapRect.left + fracX * mapRect.width(), by = mapRect.top + fracY * mapRect.height();
        view.reset(); view.postScale(s2, s2);
        view.postTranslate(getWidth() / 2f - s2 * bx, getHeight() / 2f - s2 * by);
        clampPan(); invalidate();
    }

    /** Reset zoom/pan back to the full-map fit. */
    public void fitToScreen() { view.reset(); invalidate(); }

    /** Zoom/pan so the given sector fills most of the screen (auto-fit to that sector's bounds). */
    public void focusSector(SectorBoundaries.Sector s) {
        if (s == null || mapRect.width() <= 0) { return; }
        float minx = 1, miny = 1, maxx = 0, maxy = 0;
        for (int i = 0; i < s.fracX.length; i++) { minx = Math.min(minx, s.fracX[i]); maxx = Math.max(maxx, s.fracX[i]); miny = Math.min(miny, s.fracY[i]); maxy = Math.max(maxy, s.fracY[i]); }
        float bx0 = mapRect.left + minx * mapRect.width(), by0 = mapRect.top + miny * mapRect.height();
        float bx1 = mapRect.left + maxx * mapRect.width(), by1 = mapRect.top + maxy * mapRect.height();
        float bw = Math.max(1, bx1 - bx0), bh = Math.max(1, by1 - by0);
        float pad = 24 * density;
        float s2 = Math.min((getWidth() - 2 * pad) / bw, (getHeight() - 2 * pad) / bh);
        s2 = Math.max(1f, Math.min(9f, s2));
        view.reset();
        view.postScale(s2, s2);
        view.postTranslate(getWidth() / 2f - s2 * (bx0 + bx1) / 2f, getHeight() / 2f - s2 * (by0 + by1) / 2f);
        clampPan(); invalidate();
    }

    private float currentScale() { float[] m = new float[9]; view.getValues(m); return m[Matrix.MSCALE_X]; }

    /** Keep the map from being dragged entirely off-screen. */
    private void clampPan() {
        if (mapRect.width() <= 0) return;
        float[] m = new float[9]; view.getValues(m);
        float sc = m[Matrix.MSCALE_X], tx = m[Matrix.MTRANS_X], ty = m[Matrix.MTRANS_Y];
        float w = getWidth(), h = getHeight(), margin = 80 * density;
        float minTx = w - margin - (mapRect.right) * sc, maxTx = margin - mapRect.left * sc;
        float minTy = h - margin - (mapRect.bottom) * sc, maxTy = margin - mapRect.top * sc;
        float ntx = Math.max(minTx, Math.min(maxTx, tx)), nty = Math.max(minTy, Math.min(maxTy, ty));
        if (ntx != tx || nty != ty) { m[Matrix.MTRANS_X] = ntx; m[Matrix.MTRANS_Y] = nty; view.setValues(m); }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = 6 * density, w = getWidth(), h = getHeight();
        canvas.drawColor(Color.WHITE);
        if (mapBitmap == null) return;
        float scale = Math.min((w - 2 * pad) / mapBitmap.getWidth(), (h - 2 * pad) / mapBitmap.getHeight());
        float drawW = mapBitmap.getWidth() * scale, drawH = mapBitmap.getHeight() * scale;
        float left = (w - drawW) / 2f, top = (h - drawH) / 2f;
        mapRect.set(left, top, left + drawW, top + drawH);

        // --- map raster + sector fills + heatmap: drawn UNDER the zoom/pan matrix ---
        canvas.save();
        canvas.concat(view);
        paint.setStyle(Paint.Style.FILL); paint.setAlpha(255);
        canvas.drawBitmap(mapBitmap, null, mapRect, paint);
        if (overlayBitmap != null && overlayAlpha > 0) { paint.setAlpha(overlayAlpha); canvas.drawBitmap(overlayBitmap, null, mapRect, paint); paint.setAlpha(255); }
        if (showSectors) drawSectors(canvas);
        if (showHeatmap) drawHeatmap(canvas);
        canvas.restore();

        // --- points + sector labels: SCREEN space, fixed readable size ---
        if (showPoints) drawPoints(canvas);
        if (showLabels && showSectors) drawSectorLabels(canvas);
    }

    private static final int[] SECTOR_COLORS = {0xff007F9E, 0xff2968B2, 0xff149176, 0xff7A58B5, 0xffC9851C, 0xffBD4868, 0xff5C8432};

    private void drawSectors(Canvas canvas) {
        for (int i = 0; i < sectors.size(); i++) {
            SectorBoundaries.Sector s = sectors.get(i);
            int color = SECTOR_COLORS[i % SECTOR_COLORS.length];
            Path path = toBasePath(s.fracX, s.fracY);
            paint.setStyle(Paint.Style.FILL); paint.setColor((color & 0x00ffffff) | 0x22000000);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.4f * density / Math.max(1f, currentScale())); paint.setColor(color);
            canvas.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private Path toBasePath(float[] fracX, float[] fracY) {
        Path path = new Path();
        for (int j = 0; j < fracX.length; j++) {
            float px = mapRect.left + fracX[j] * mapRect.width(), py = mapRect.top + fracY[j] * mapRect.height();
            if (j == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        return path;
    }

    /** Big, legible sector names on translucent navy chips at each sector centroid. Labels that would OVERLAP an
     * already-drawn one are skipped — so at the whole-camp zoom only a few show (no pile-up), and as the engineer
     * pinch-zooms in the sectors spread apart and the rest of the names appear («far = sector name, near = more»). */
    private void drawSectorLabels(Canvas canvas) {
        java.util.List<RectF> drawn = new ArrayList<>();
        float[] pt = new float[2];
        for (SectorBoundaries.Sector s : sectors) {
            float cx = 0, cy = 0; for (int i = 0; i < s.fracX.length; i++) { cx += s.fracX[i]; cy += s.fracY[i]; }
            cx /= s.fracX.length; cy /= s.fracY.length;
            pt[0] = mapRect.left + cx * mapRect.width(); pt[1] = mapRect.top + cy * mapRect.height();
            view.mapPoints(pt);
            if (pt[0] < 0 || pt[0] > getWidth() || pt[1] < 0 || pt[1] > getHeight()) continue; // off-screen
            String t = s.name;
            float tw = label.measureText(t), pw = 8 * density, ph = 5 * density, th = 13 * density;
            RectF bg = new RectF(pt[0] - tw / 2 - pw, pt[1] - th / 2 - ph, pt[0] + tw / 2 + pw, pt[1] + th / 2 + ph);
            boolean clash = false; for (RectF r : drawn) if (RectF.intersects(r, bg)) { clash = true; break; }
            if (clash) continue;
            drawn.add(bg);
            canvas.drawRoundRect(bg, 7 * density, 7 * density, labelBg);
            canvas.drawText(t, pt[0], pt[1] + th / 2 - 2 * density, label);
        }
    }

    private void drawHeatmap(Canvas canvas) {
        List<MapPoint> real = new ArrayList<>();
        for (MapPoint p : points) if (!p.estimated) real.add(p);
        if (real.isEmpty()) return;
        int grid = 28;
        float[][] dens = new float[grid][grid];
        float sigma = 1.6f / grid;
        for (MapPoint p : real) for (int gy = 0; gy < grid; gy++) { float cy = (gy + 0.5f) / grid, dy = cy - p.fracY;
            for (int gx = 0; gx < grid; gx++) { float cx = (gx + 0.5f) / grid, dx = cx - p.fracX, d2 = dx * dx + dy * dy; dens[gy][gx] += (float) Math.exp(-d2 / (2 * sigma * sigma)); } }
        float max = 0.0001f; for (float[] row : dens) for (float v : row) max = Math.max(max, v);
        float cellW = mapRect.width() / grid, cellH = mapRect.height() / grid;
        Paint heat = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int gy = 0; gy < grid; gy++) for (int gx = 0; gx < grid; gx++) {
            float intensity = dens[gy][gx] / max; if (intensity < 0.06f) continue;
            heat.setColor(heatColor(intensity));
            float x0 = mapRect.left + gx * cellW, y0 = mapRect.top + gy * cellH;
            canvas.drawRect(x0, y0, x0 + cellW, y0 + cellH, heat);
        }
    }

    private static int heatColor(float t) {
        t = Math.max(0f, Math.min(1f, t));
        int alpha = (int) (170 * t), r, g, b;
        if (t < 0.5f) { float k = t / 0.5f; r = (int) (60 + k * (255 - 60)); g = (int) (120 + k * (215 - 120)); b = (int) (255 - k * 255); }
        else { float k = (t - 0.5f) / 0.5f; r = 255; g = (int) (215 - k * 215); b = 0; }
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private void drawPoints(Canvas canvas) {
        float radius = 7f * density;                 // FIXED screen size — readable at any zoom
        float[] pt = new float[2];
        for (MapPoint p : points) {
            pt[0] = mapRect.left + p.fracX * mapRect.width(); pt[1] = mapRect.top + p.fracY * mapRect.height();
            view.mapPoints(pt);
            if (p.beneficiaryId == highlightId) { // MAP V3: selected-from-list highlight ring
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3f * density); paint.setColor(0xffC9851C);
                canvas.drawCircle(pt[0], pt[1], radius + 7f * density, paint); paint.setStyle(Paint.Style.FILL);
            }
            if (p.estimated) {
                // Estimate = HOLLOW ring (shape differs from a real fix, not colour alone) + a small «؟» hint dot.
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.4f * density); paint.setColor(p.color);
                canvas.drawCircle(pt[0], pt[1], radius * 1.15f, paint);
                paint.setStyle(Paint.Style.FILL); paint.setColor(p.color); canvas.drawCircle(pt[0], pt[1], 1.6f * density, paint);
            } else {
                // Real GPS = SOLID filled disc with a white halo — visually unmistakable vs the hollow estimate.
                paint.setColor(Color.WHITE); canvas.drawCircle(pt[0], pt[1], radius + 2f * density, paint);
                paint.setColor(p.color); canvas.drawCircle(pt[0], pt[1], radius, paint);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: lastPanX = event.getX(); lastPanY = event.getY(); panning = true; return true;
            case MotionEvent.ACTION_POINTER_DOWN: panning = false; return true; // pinch takes over
            case MotionEvent.ACTION_MOVE:
                if (panning && !scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                    view.postTranslate(event.getX() - lastPanX, event.getY() - lastPanY);
                    lastPanX = event.getX(); lastPanY = event.getY(); clampPan(); invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: panning = false; return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }

    private void handleTap(float screenX, float screenY) {
        if (mapRect.width() <= 0 || mapRect.height() <= 0) return;
        performClick();
        // point hit-test in actual (post-transform) screen space
        float hitRadius = 20 * density; float[] pt = new float[2];
        if (showPoints) {
            MapPoint nearest = null; float nearestDist = Float.MAX_VALUE;
            for (MapPoint p : points) {
                pt[0] = mapRect.left + p.fracX * mapRect.width(); pt[1] = mapRect.top + p.fracY * mapRect.height();
                view.mapPoints(pt);
                float d = (float) Math.hypot(pt[0] - screenX, pt[1] - screenY);
                if (d < hitRadius && d < nearestDist) { nearest = p; nearestDist = d; }
            }
            if (nearest != null) { if (pointTapListener != null) pointTapListener.onPointTap(nearest); return; }
        }
        float[] frac = screenToFraction(screenX, screenY);
        if (mapTapListener != null && frac != null) mapTapListener.onMapTap(frac[0], frac[1]);
        if (showSectors && !sectors.isEmpty() && frac != null && sectorTapListener != null) {
            java.util.List<String> hits = new java.util.ArrayList<>();
            for (SectorBoundaries.Sector s : sectors) if (SectorBoundaries.contains(s, frac[0], frac[1])) hits.add(s.name);
            if (!hits.isEmpty()) sectorTapListener.onSectorTap(hits);
        }
    }

    /** Fraction position (0..1) of a raw screen tap, accounting for the current zoom/pan. */
    public float[] screenToFraction(float screenX, float screenY) {
        if (mapRect.width() <= 0 || mapRect.height() <= 0) return null;
        view.invert(inv);
        float[] pt = {screenX, screenY}; inv.mapPoints(pt);
        float fracX = (pt[0] - mapRect.left) / mapRect.width(), fracY = (pt[1] - mapRect.top) / mapRect.height();
        if (fracX < 0 || fracX > 1 || fracY < 0 || fracY > 1) return null;
        return new float[]{fracX, fracY};
    }
}
