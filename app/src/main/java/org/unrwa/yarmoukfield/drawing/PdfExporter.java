package org.unrwa.yarmoukfield.drawing;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/** Renders the sketch to an A4-landscape PDF close to the reference croquis: a clear plan frame, north arrow,
 * scale note, metre dimensions on walls, Arabic room names, an optional "h=..." ceiling header, a faint UNRWA
 * watermark, and a bottom title block with the project identity + beneficiary fields. Arabic is drawn through
 * the platform {@link Canvas}, which shapes and orders it correctly — this is exactly why the PDF (not the DXF)
 * is the Arabic-safe output. Uses only android.graphics.pdf — no external PDF library. */
public final class PdfExporter {
    private PdfExporter() {}

    private static final int W = 842, H = 595;           // A4 landscape, points (1/72")
    private static final int MARGIN = 22;

    public static void export(Context ctx, DrawModel m, DrawExportMeta meta, File out) throws Exception {
        PdfDocument doc = new PdfDocument();
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(W, H, 1).create();
        PdfDocument.Page page = doc.startPage(info);
        Canvas c = page.getCanvas();
        c.drawColor(Color.WHITE);

        Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG); frame.setStyle(Paint.Style.STROKE); frame.setStrokeWidth(1.5f); frame.setColor(0xff0B2F52);
        Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG); thin.setStyle(Paint.Style.STROKE); thin.setStrokeWidth(0.8f); thin.setColor(0xff0B2F52);
        Paint wall = new Paint(Paint.ANTI_ALIAS_FLAG); wall.setStyle(Paint.Style.STROKE); wall.setStrokeWidth(1.4f); wall.setColor(0xff0B2F52); wall.setStrokeJoin(Paint.Join.MITER);
        Paint wallFill = new Paint(Paint.ANTI_ALIAS_FLAG); wallFill.setStyle(Paint.Style.FILL); wallFill.setColor(0x140B2F52);
        Paint room = new Paint(Paint.ANTI_ALIAS_FLAG); room.setStyle(Paint.Style.STROKE); room.setStrokeWidth(1.2f); room.setColor(0xff0E8A9C);
        Paint dim = new Paint(Paint.ANTI_ALIAS_FLAG); dim.setColor(0xff0E8A9C); dim.setTextSize(8); dim.setTextAlign(Paint.Align.CENTER);
        Paint roomName = new Paint(Paint.ANTI_ALIAS_FLAG); roomName.setColor(0xff16323F); roomName.setTextSize(10); roomName.setTextAlign(Paint.Align.CENTER);
        Paint tb = new Paint(Paint.ANTI_ALIAS_FLAG); tb.setColor(0xff16323F); tb.setTextSize(10);
        Paint tbBold = new Paint(Paint.ANTI_ALIAS_FLAG); tbBold.setColor(0xff0B2F52); tbBold.setTextSize(13); tbBold.setFakeBoldText(true);
        Paint tbEn = new Paint(Paint.ANTI_ALIAS_FLAG); tbEn.setColor(0xff0E8A9C); tbEn.setTextSize(10);

        // outer frame
        c.drawRect(MARGIN, MARGIN, W - MARGIN, H - MARGIN, frame);

        int titleTop = H - MARGIN - 130;               // title block occupies the bottom band
        c.drawLine(MARGIN, titleTop, W - MARGIN, titleTop, thin);
        RectF plan = new RectF(MARGIN + 14, MARGIN + 30, W - MARGIN - 14, titleTop - 12);

        // faint UNRWA watermark behind the plan
        Bitmap logo = decode(ctx, "unrwa_logo");
        if (logo != null) {
            Paint wm = new Paint(); wm.setAlpha(28);
            float lw = plan.width() * 0.55f, lh = lw * logo.getHeight() / logo.getWidth();
            RectF wr = new RectF(plan.centerX() - lw / 2, plan.centerY() - lh / 2, plan.centerX() + lw / 2, plan.centerY() + lh / 2);
            c.drawBitmap(logo, null, wr, wm);
        }

        // ceiling header, centered above the plan
        if (meta.ceiling != null && !meta.ceiling.isEmpty()) {
            Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG); hp.setColor(0xff0B2F52); hp.setTextSize(13); hp.setTextAlign(Paint.Align.CENTER); hp.setFakeBoldText(true);
            c.drawText(meta.ceiling, plan.centerX(), MARGIN + 22, hp);
        }

        // fit the drawing into the plan box (screen Y stays down, same as in-app)
        double[] bounds = m.bounds();
        if (bounds != null) {
            double bw = Math.max(1e-6, bounds[2] - bounds[0]), bh = Math.max(1e-6, bounds[3] - bounds[1]);
            float sc = (float) Math.min(plan.width() * 0.86 / bw, plan.height() * 0.86 / bh);
            float cx = plan.centerX(), cy = plan.centerY();
            double bcx = (bounds[0] + bounds[2]) / 2, bcy = (bounds[1] + bounds[3]) / 2;

            // B1: cleaned wall axes (corners meet, no gaps) for the wall bands — PDF output only, model untouched.
            double ppmB1 = m.pxPerMeter > 0 ? m.pxPerMeter : 100;
            java.util.List<DrawElement> wallsB1 = new java.util.ArrayList<>();
            for (DrawElement e : m.elements) if (DrawElement.WALL.equals(e.type) && e.pts != null && e.pts.length >= 4) wallsB1.add(e);
            double[][] cleanedB1 = WallJoin.cleanedAxes(wallsB1, 0.30 * ppmB1, 0.60 * ppmB1);
            java.util.IdentityHashMap<DrawElement, double[]> cleaned = new java.util.IdentityHashMap<>();
            for (int i = 0; i < wallsB1.size(); i++) cleaned.put(wallsB1.get(i), cleanedB1[i]);
            // B1: placed dimension-label boxes, so later labels dodge earlier ones (declutter at junctions).
            java.util.List<android.graphics.RectF> placedDims = new java.util.ArrayList<>();

            for (DrawElement e : m.elements) {
                if (e.pts == null || e.pts.length < 4) continue;
                if (DrawElement.STAIRS.equals(e.type)) { drawStairsPdf(c, m, e, bcx, bcy, cx, cy, sc); continue; }
                boolean isRoom = DrawElement.ROOM.equals(e.type);
                if (!isRoom && !m.isLayerVisible(e.layer)) continue; // rooms handled inside (boundary vs name split)
                if (isRoom) {
                    float x1 = px(e.pts[0], bcx, cx, sc), y1 = px(e.pts[1], bcy, cy, sc), x2 = px(e.pts[2], bcx, cx, sc), y2 = px(e.pts[3], bcy, cy, sc);
                    // B3: room boundary and name are independently toggleable (rooms / roomnames layers)
                    if (m.isLayerVisible("rooms"))
                        c.drawRect(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2), room);
                    if (m.isLayerVisible("roomnames")) {
                        boolean hasName = e.label != null && !e.label.isEmpty();
                        String areaStr = m.isCalibrated() ? String.format(Locale.US, "%.1f م²", roomAreaM2(m, e)) : "";
                        float mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
                        if (hasName && !areaStr.isEmpty()) { c.drawText(e.label, mx, my - 2, roomName); c.drawText(areaStr, mx, my + 11, dim); }
                        else if (hasName) c.drawText(e.label, mx, my + 3, roomName);
                        else if (!areaStr.isEmpty()) c.drawText(areaStr, mx, my + 3, dim);
                    }
                } else {
                    // B1: walls draw on their CLEANED axis (corners meet, no gaps); dimension VALUE stays original.
                    double[] wc = cleaned.get(e);
                    double ax1 = wc != null ? wc[0] : e.pts[0], ay1 = wc != null ? wc[1] : e.pts[1];
                    double ax2 = wc != null ? wc[2] : e.pts[2], ay2 = wc != null ? wc[3] : e.pts[3];
                    float x1 = px(ax1, bcx, cx, sc), y1 = px(ay1, bcy, cy, sc), x2 = px(ax2, bcx, cx, sc), y2 = px(ay2, bcy, cy, sc);
                    if (DrawElement.WALL.equals(e.type) && m.isCalibrated()) {
                        // Draw the wall as its real thickness band (axis ± t/2), so the PDF reads like an
                        // architectural plan. Dimensions still measure the ORIGINAL axis (below). PDF-only.
                        double t = m.wallThicknessM(e) * m.pxPerMeter; // world units
                        double dxw = ax2 - ax1, dyw = ay2 - ay1, lenw = Math.hypot(dxw, dyw);
                        if (t > 0 && lenw > 1e-6) {
                            double ox = -dyw / lenw * (t / 2), oy = dxw / lenw * (t / 2);
                            android.graphics.Path pth = new android.graphics.Path();
                            pth.moveTo(px(ax1 + ox, bcx, cx, sc), px(ay1 + oy, bcy, cy, sc));
                            pth.lineTo(px(ax2 + ox, bcx, cx, sc), px(ay2 + oy, bcy, cy, sc));
                            pth.lineTo(px(ax2 - ox, bcx, cx, sc), px(ay2 - oy, bcy, cy, sc));
                            pth.lineTo(px(ax1 - ox, bcx, cx, sc), px(ay1 - oy, bcy, cy, sc));
                            pth.close();
                            c.drawPath(pth, wallFill); c.drawPath(pth, wall);
                        } else {
                            c.drawLine(x1, y1, x2, y2, wall);
                        }
                    } else {
                        c.drawLine(x1, y1, x2, y2, wall);
                    }
                    // B2: axis-aligned walls are dimensioned by external chains; keep inline only for OBLIQUE walls.
                    boolean axisAligned = Math.abs(e.pts[2] - e.pts[0]) < 2 || Math.abs(e.pts[3] - e.pts[1]) < 2;
                    if (m.isLayerVisible("dims") && DrawElement.WALL.equals(e.type) && !axisAligned)
                        placeDim(c, placedDims, String.format(Locale.US, "%.2f م", m.wallDimM(e)), // interior (net) or axis
                                (x1 + x2) / 2, (y1 + y2) / 2, (float) (x2 - x1), (float) (y2 - y1), dim);
                    else if (m.isLayerVisible("dims") && !DrawElement.WALL.equals(e.type))
                        placeDim(c, placedDims, meters(m, DrawGeo.dist(e.pts[0], e.pts[1], e.pts[2], e.pts[3])),
                                (x1 + x2) / 2, (y1 + y2) / 2, (float) (x2 - x1), (float) (y2 - y1), dim);
                }
            }
            // A2/A3: doors + windows drawn on top of the walls, like the reference croquis
            for (DrawElement e : m.elements) {
                if (!m.isLayerVisible(e.layer)) continue;
                if (DrawElement.DOOR.equals(e.type)) drawDoorPdf(c, m, e, bcx, bcy, cx, cy, sc);
                else if (DrawElement.WINDOW.equals(e.type)) drawWindowPdf(c, m, e, bcx, bcy, cx, cy, sc);
            }
            // B2: professional external dimension chains around the plan (matches the DXF)
            if (m.isLayerVisible("dims")) drawDimChainsPdf(c, m, cleaned, bounds, bcx, bcy, cx, cy, sc, dim);
        }

        // north arrow (top-right of plan) + scale note (top-left)
        float nx = plan.right - 18, ny = plan.top + 10;
        c.drawLine(nx, ny + 26, nx, ny, wall);
        c.drawLine(nx, ny, nx - 5, ny + 7, wall); c.drawLine(nx, ny, nx + 5, ny + 7, wall);
        Paint np = new Paint(Paint.ANTI_ALIAS_FLAG); np.setColor(0xff0B2F52); np.setTextSize(10); np.setTextAlign(Paint.Align.CENTER);
        c.drawText("N", nx, ny + 40, np);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG); sp.setColor(0xff0E8A9C); sp.setTextSize(9);
        c.drawText(m.isCalibrated() ? String.format(Locale.US, "المقياس: 1م = %.0f وحدة رسم — الأبعاد بالمتر", m.pxPerMeter) : "غير معاير", plan.left, plan.top + 6, sp);

        // ---- title block (bottom band) ----
        float tX = W - MARGIN - 16;                     // right edge for RTL text
        float y = titleTop + 22;
        drawR(c, meta.projectAr, tX, y, tbBold); y += 16;
        drawL(c, meta.projectEn, MARGIN + 16, titleTop + 22, tbEn);
        y += 6;
        drawR(c, "مخطط منزل المستفيد: " + safe(meta.benefName), tX, y, tb); y += 15;
        drawR(c, "رقم الملف: " + safe(meta.fileNo), tX, y, tb); y += 15;
        drawR(c, "الطابق: " + safe(meta.floor), tX, y, tb); y += 15;
        drawR(c, "إعداد المهندس: " + safe(meta.engineer), tX, y, tb); y += 15;
        drawR(c, "التاريخ: " + safe(meta.date), tX, y, tb);
        // identity logo in the title block (left)
        if (logo != null) {
            float lw = 120, lh = lw * logo.getHeight() / logo.getWidth();
            c.drawBitmap(logo, null, new RectF(MARGIN + 16, titleTop + 40, MARGIN + 16 + lw, titleTop + 40 + lh), null);
        }

        // A1: a compact rooms table (اسم الغرفة | المساحة) in the middle of the title-block band
        drawRoomsTable(c, m, titleTop);

        doc.finishPage(page);
        try (FileOutputStream fos = new FileOutputStream(out)) { doc.writeTo(fos); }
        doc.close();
    }

    private static double roomAreaM2(DrawModel m, DrawElement e) {
        return m.meters(Math.abs(e.pts[2] - e.pts[0])) * m.meters(Math.abs(e.pts[3] - e.pts[1]));
    }

    /** Rooms summary table drawn in the free middle of the bottom title band: a header, one right-aligned row per
     * room (name + area), and a total. Unnamed rooms fall back to «غرفة N» so the count is always complete. */
    private static void drawRoomsTable(Canvas c, DrawModel m, int titleTop) {
        Paint hd = new Paint(Paint.ANTI_ALIAS_FLAG); hd.setColor(0xff0B2F52); hd.setTextSize(9); hd.setFakeBoldText(true); hd.setTextAlign(Paint.Align.RIGHT);
        Paint rw = new Paint(Paint.ANTI_ALIAS_FLAG); rw.setColor(0xff16323F); rw.setTextSize(9); rw.setTextAlign(Paint.Align.RIGHT);
        Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG); ln.setColor(0xffB9C6CF); ln.setStrokeWidth(0.6f);
        float nameRight = MARGIN + 320, areaRight = MARGIN + 165, y = titleTop + 20;
        c.drawText("الغرفة", nameRight, y, hd); c.drawText("المساحة", areaRight, y, hd);
        c.drawLine(areaRight - 55, y + 4, nameRight, y + 4, ln); y += 14;
        int n = 0; double total = 0;
        for (DrawElement e : m.elements) {
            if (!DrawElement.ROOM.equals(e.type) || e.pts == null || e.pts.length < 4) continue;
            n++;
            String name = e.label != null && !e.label.isEmpty() ? e.label : "غرفة " + n;
            double a = m.isCalibrated() ? roomAreaM2(m, e) : 0; total += a;
            if (y < titleTop + 118) { // keep inside the band
                c.drawText(name, nameRight, y, rw);
                c.drawText(m.isCalibrated() ? String.format(Locale.US, "%.1f م²", a) : "—", areaRight, y, rw);
                y += 13;
            }
        }
        c.drawLine(areaRight - 55, y - 8, nameRight, y - 8, ln);
        c.drawText("الإجمالي: " + n + " غرفة", nameRight, y + 4, hd);
        if (m.isCalibrated()) c.drawText(String.format(Locale.US, "%.1f م²", total), areaRight, y + 4, hd);
    }

    /** A2: door symbol on the PDF — white opening across the wall band, two jamb ticks, the leaf line, and the
     * quarter-circle swing arc. Geometry comes from the host wall (via doorGeom) then mapped to the page. */
    /** Stairs symbol on the PDF: rectangle + evenly-spaced treads + ascent arrow (same look as on screen/DXF). */
    private static void drawStairsPdf(Canvas c, DrawModel m, DrawElement e, double bcx, double bcy, float cx, float cy, float sc) {
        double x1 = Math.min(e.pts[0], e.pts[2]), y1 = Math.min(e.pts[1], e.pts[3]);
        double x2 = Math.max(e.pts[0], e.pts[2]), y2 = Math.max(e.pts[1], e.pts[3]);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG); sp.setColor(0xff0B2F52); sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(1.1f);
        Paint ar = new Paint(Paint.ANTI_ALIAS_FLAG); ar.setColor(0xff149176); ar.setStyle(Paint.Style.STROKE); ar.setStrokeWidth(1.2f);
        float X1 = px(x1, bcx, cx, sc), Y1 = px(y1, bcy, cy, sc), X2 = px(x2, bcx, cx, sc), Y2 = px(y2, bcy, cy, sc);
        c.drawRect(Math.min(X1, X2), Math.min(Y1, Y2), Math.max(X1, X2), Math.max(Y1, Y2), sp);
        double w = x2 - x1, h = y2 - y1; boolean runX = w >= h; double runLen = runX ? w : h;
        int n = m.isCalibrated() ? (int) Math.round(m.meters(runLen) / 0.27) : 8; n = Math.max(2, Math.min(n, 40));
        for (int i = 1; i < n; i++) {
            double t = (double) i / n;
            if (runX) { double x = x1 + w * t; c.drawLine(px(x, bcx, cx, sc), px(y1, bcy, cy, sc), px(x, bcx, cx, sc), px(y2, bcy, cy, sc), sp); }
            else { double y = y1 + h * t; c.drawLine(px(x1, bcx, cx, sc), px(y, bcy, cy, sc), px(x2, bcx, cx, sc), px(y, bcy, cy, sc), sp); }
        }
        boolean rev = (e.swing & 1) == 1;
        double ax, ay, bx, by;
        if (runX) { double ym = (y1 + y2) / 2; ay = by = ym; ax = rev ? x2 : x1; bx = rev ? x1 : x2; }
        else { double xm = (x1 + x2) / 2; ax = bx = xm; ay = rev ? y2 : y1; by = rev ? y1 : y2; }
        float Ax = px(ax, bcx, cx, sc), Ay = px(ay, bcy, cy, sc), Bx = px(bx, bcx, cx, sc), By = px(by, bcy, cy, sc);
        c.drawLine(Ax, Ay, Bx, By, ar);
        double ang = Math.atan2(By - Ay, Bx - Ax), hl = Math.min(Math.abs(X2 - X1), Math.abs(Y2 - Y1)) * 0.22 + 3;
        c.drawLine(Bx, By, (float) (Bx - hl * Math.cos(ang - 0.4)), (float) (By - hl * Math.sin(ang - 0.4)), ar);
        c.drawLine(Bx, By, (float) (Bx - hl * Math.cos(ang + 0.4)), (float) (By - hl * Math.sin(ang + 0.4)), ar);
    }

    private static void drawDoorPdf(Canvas c, DrawModel m, DrawElement e, double bcx, double bcy, float cx, float cy, float sc) {
        double[] g = m.doorGeom(e); if (g == null) return;
        double cxw = g[0], cyw = g[1], ux = g[2], uy = g[3], th = g[4], w = g[5];
        double half = w / 2, nx = -uy, ny = ux;
        double e0x = cxw - ux * half, e0y = cyw - uy * half, e1x = cxw + ux * half, e1y = cyw + uy * half;
        boolean hingeE0 = (e.swing & 1) == 0; double side = (e.swing & 2) == 0 ? 1 : -1;
        double hx = hingeE0 ? e0x : e1x, hy = hingeE0 ? e0y : e1y;
        double fx = hingeE0 ? e1x : e0x, fy = hingeE0 ? e1y : e0y;
        double lx = hx + nx * side * w, ly = hy + ny * side * w;
        Paint white = new Paint(Paint.ANTI_ALIAS_FLAG); white.setColor(Color.WHITE); white.setStyle(Paint.Style.FILL);
        Paint d = new Paint(Paint.ANTI_ALIAS_FLAG); d.setColor(0xff0B2F52); d.setStyle(Paint.Style.STROKE); d.setStrokeWidth(1.1f);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(px(e0x + nx * th / 2, bcx, cx, sc), px(e0y + ny * th / 2, bcy, cy, sc));
        p.lineTo(px(e1x + nx * th / 2, bcx, cx, sc), px(e1y + ny * th / 2, bcy, cy, sc));
        p.lineTo(px(e1x - nx * th / 2, bcx, cx, sc), px(e1y - ny * th / 2, bcy, cy, sc));
        p.lineTo(px(e0x - nx * th / 2, bcx, cx, sc), px(e0y - ny * th / 2, bcy, cy, sc));
        p.close(); c.drawPath(p, white);
        c.drawLine(px(e0x + nx * th / 2, bcx, cx, sc), px(e0y + ny * th / 2, bcy, cy, sc), px(e0x - nx * th / 2, bcx, cx, sc), px(e0y - ny * th / 2, bcy, cy, sc), d);
        c.drawLine(px(e1x + nx * th / 2, bcx, cx, sc), px(e1y + ny * th / 2, bcy, cy, sc), px(e1x - nx * th / 2, bcx, cx, sc), px(e1y - ny * th / 2, bcy, cy, sc), d);
        float hpx = px(hx, bcx, cx, sc), hpy = px(hy, bcy, cy, sc), lpx = px(lx, bcx, cx, sc), lpy = px(ly, bcy, cy, sc);
        c.drawLine(hpx, hpy, lpx, lpy, d);
        float rr = (float) (w * sc);
        android.graphics.RectF r = new android.graphics.RectF(hpx - rr, hpy - rr, hpx + rr, hpy + rr);
        float a0 = (float) Math.toDegrees(Math.atan2(lpy - hpy, lpx - hpx));
        float a1 = (float) Math.toDegrees(Math.atan2(px(fy, bcy, cy, sc) - hpy, px(fx, bcx, cx, sc) - hpx));
        c.drawArc(r, a0, (float) DrawGeo.normalizeDeg(a1 - a0), false, d);
    }

    /** A3: window symbol on the PDF — two jamb ticks across the wall band + two glazing lines along the axis. */
    private static void drawWindowPdf(Canvas c, DrawModel m, DrawElement e, double bcx, double bcy, float cx, float cy, float sc) {
        double[] g = m.openingGeom(e); if (g == null) return;
        double cxw = g[0], cyw = g[1], ux = g[2], uy = g[3], th = g[4], w = g[5];
        double half = w / 2, nx = -uy, ny = ux, gl = th * 0.25;
        double e0x = cxw - ux * half, e0y = cyw - uy * half, e1x = cxw + ux * half, e1y = cyw + uy * half;
        Paint wp = new Paint(Paint.ANTI_ALIAS_FLAG); wp.setColor(0xff0E7490); wp.setStyle(Paint.Style.STROKE); wp.setStrokeWidth(1.0f);
        c.drawLine(px(e0x + nx * th / 2, bcx, cx, sc), px(e0y + ny * th / 2, bcy, cy, sc), px(e0x - nx * th / 2, bcx, cx, sc), px(e0y - ny * th / 2, bcy, cy, sc), wp);
        c.drawLine(px(e1x + nx * th / 2, bcx, cx, sc), px(e1y + ny * th / 2, bcy, cy, sc), px(e1x - nx * th / 2, bcx, cx, sc), px(e1y - ny * th / 2, bcy, cy, sc), wp);
        c.drawLine(px(e0x + nx * gl, bcx, cx, sc), px(e0y + ny * gl, bcy, cy, sc), px(e1x + nx * gl, bcx, cx, sc), px(e1y + ny * gl, bcy, cy, sc), wp);
        c.drawLine(px(e0x - nx * gl, bcx, cx, sc), px(e0y - ny * gl, bcy, cy, sc), px(e1x - nx * gl, bcx, cx, sc), px(e1y - ny * gl, bcy, cy, sc), wp);
    }

    /** B1: place a dimension label at (ax,ay), but if its box would collide with an already-placed label, nudge
     * it PERPENDICULAR to its wall axis (both sides, growing offsets) until it clears — so numbers stop stacking
     * at wall junctions. The label stays aligned with its wall's axis; its VALUE is never changed. */
    private static void placeDim(Canvas c, java.util.List<android.graphics.RectF> placed, String s,
                                 float ax, float ay, float dirX, float dirY, Paint p) {
        float len = (float) Math.hypot(dirX, dirY); if (len < 1e-3f) { dirX = 1; dirY = 0; len = 1; }
        float pxu = -dirY / len, pyu = dirX / len;          // unit perpendicular
        float w = p.measureText(s), h = p.getTextSize();
        float[] offs = {0, 11, -11, 22, -22, 33, -33};
        for (int k = 0; k < offs.length; k++) {
            float o = offs[k], cxp = ax + pxu * o, cyp = ay + pyu * o;
            android.graphics.RectF r = new android.graphics.RectF(cxp - w / 2 - 1, cyp - h, cxp + w / 2 + 1, cyp + 2);
            boolean hit = false;
            for (android.graphics.RectF q : placed) if (android.graphics.RectF.intersects(r, q)) { hit = true; break; }
            if (!hit || k == offs.length - 1) { c.drawText(s, cxp, cyp, p); placed.add(r); return; }
        }
    }

    /** B2: external dimension chains on the PDF — a horizontal chain above the plan from every vertical-wall X
     * (+ plan sides) and a vertical chain to the left from every horizontal-wall Y, each segment with extension
     * lines, tick marks and its length. Mirrors the DXF so PDF and CAD read the same. Values are the real sizes. */
    private static void drawDimChainsPdf(Canvas c, DrawModel m, java.util.IdentityHashMap<DrawElement, double[]> cleaned,
                                         double[] bounds, double bcx, double bcy, float cx, float cy, float sc, Paint dim) {
        double ppm = m.pxPerMeter > 0 ? m.pxPerMeter : 100;
        java.util.TreeSet<Double> xs = new java.util.TreeSet<>(), ys = new java.util.TreeSet<>();
        for (DrawElement e : m.elements) {
            if (!DrawElement.WALL.equals(e.type)) continue;
            double[] p = cleaned.get(e); if (p == null) p = e.pts; if (p == null || p.length < 4) continue;
            double dx = p[2] - p[0], dy = p[3] - p[1];
            if (Math.abs(dx) < Math.abs(dy) && Math.abs(dx) < 0.02 * ppm) addSt(xs, (p[0] + p[2]) / 2);
            else if (Math.abs(dy) < 0.02 * ppm) addSt(ys, (p[1] + p[3]) / 2);
        }
        addSt(xs, bounds[0]); addSt(xs, bounds[2]); addSt(ys, bounds[1]); addSt(ys, bounds[3]);
        Double[] xa = xs.toArray(new Double[0]), ya = ys.toArray(new Double[0]);
        Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG); ln.setColor(0xff0E8A9C); ln.setStyle(Paint.Style.STROKE); ln.setStrokeWidth(0.6f);
        float off = 16, tick = 3;
        float planTop = px(bounds[1], bcy, cy, sc), planLeft = px(bounds[0], bcx, cx, sc);
        float topY = planTop - off, leftX = planLeft - off;
        for (Double x : xa) { float sx = px(x, bcx, cx, sc); c.drawLine(sx, planTop, sx, topY, ln); }
        for (int i = 0; i + 1 < xa.length; i++) {
            float x0 = px(xa[i], bcx, cx, sc), x1 = px(xa[i + 1], bcx, cx, sc);
            c.drawLine(x0, topY, x1, topY, ln);
            c.drawLine(x0 - tick, topY + tick, x0 + tick, topY - tick, ln);
            c.drawLine(x1 - tick, topY + tick, x1 + tick, topY - tick, ln);
            c.drawText(String.format(Locale.US, "%.2f م", m.chainSpanM(m.meters(xa[i + 1] - xa[i]))), (x0 + x1) / 2, topY - 2, dim);
        }
        for (Double y : ya) { float sy = px(y, bcy, cy, sc); c.drawLine(planLeft, sy, leftX, sy, ln); }
        for (int i = 0; i + 1 < ya.length; i++) {
            float y0 = px(ya[i], bcy, cy, sc), y1 = px(ya[i + 1], bcy, cy, sc);
            c.drawLine(leftX, y0, leftX, y1, ln);
            c.drawLine(leftX - tick, y0 - tick, leftX + tick, y0 + tick, ln);
            c.drawLine(leftX - tick, y1 - tick, leftX + tick, y1 + tick, ln);
            float ty = (y0 + y1) / 2, tx = leftX - 3;
            c.save(); c.rotate(-90, tx, ty); c.drawText(String.format(Locale.US, "%.2f م", m.chainSpanM(m.meters(ya[i + 1] - ya[i]))), tx, ty, dim); c.restore();
        }
    }

    private static void addSt(java.util.TreeSet<Double> set, double v) {
        for (Double e : set) if (Math.abs(e - v) < 3.0) return; // merge stations within 3 world-units
        set.add(v);
    }

    private static float px(double v, double bc, float cc, float sc) { return (float) (cc + (v - bc) * sc); }
    private static String meters(DrawModel m, double world) { return String.format(Locale.US, "%.2f م", m.meters(world)); }
    private static String safe(String s) { return s == null ? "" : s; }
    private static void drawR(Canvas c, String s, float xRight, float y, Paint p) { p.setTextAlign(Paint.Align.RIGHT); c.drawText(s, xRight, y, p); }
    private static void drawL(Canvas c, String s, float xLeft, float y, Paint p) { p.setTextAlign(Paint.Align.LEFT); c.drawText(s, xLeft, y, p); }

    private static Bitmap decode(Context ctx, String drawableName) {
        int id = ctx.getResources().getIdentifier(drawableName, "drawable", ctx.getPackageName());
        if (id == 0) return null;
        try { return BitmapFactory.decodeResource(ctx.getResources(), id); } catch (Exception e) { return null; }
    }
}
