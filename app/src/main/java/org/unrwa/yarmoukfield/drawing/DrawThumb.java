package org.unrwa.yarmoukfield.drawing;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/** Renders a static preview thumbnail of a {@link DrawModel} into a Bitmap for the beneficiary-file card —
 * same geometry the editor draws, fit to the thumbnail box, respecting layer visibility. No dimensions/labels
 * to keep the small preview readable. */
public final class DrawThumb {
    private DrawThumb() {}

    public static Bitmap render(DrawModel m, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        double[] b = m.bounds();
        if (b == null) return bmp;
        Paint wall = new Paint(Paint.ANTI_ALIAS_FLAG); wall.setStyle(Paint.Style.STROKE); wall.setStrokeWidth(3); wall.setColor(0xff0B2F52); wall.setStrokeCap(Paint.Cap.ROUND);
        Paint room = new Paint(Paint.ANTI_ALIAS_FLAG); room.setStyle(Paint.Style.STROKE); room.setStrokeWidth(2); room.setColor(0xff0E8A9C);
        double bw = Math.max(1e-6, b[2] - b[0]), bh = Math.max(1e-6, b[3] - b[1]);
        float pad = 10;
        float sc = (float) Math.min((w - 2 * pad) / bw, (h - 2 * pad) / bh);
        float cx = w / 2f, cy = h / 2f;
        double bcx = (b[0] + b[2]) / 2, bcy = (b[1] + b[3]) / 2;
        for (DrawElement e : m.elements) {
            if (e.pts == null || e.pts.length < 4 || !m.isLayerVisible(e.layer)) continue;
            float x1 = px(e.pts[0], bcx, cx, sc), y1 = px(e.pts[1], bcy, cy, sc), x2 = px(e.pts[2], bcx, cx, sc), y2 = px(e.pts[3], bcy, cy, sc);
            if (DrawElement.ROOM.equals(e.type)) c.drawRect(Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2), room);
            else c.drawLine(x1, y1, x2, y2, wall);
        }
        return bmp;
    }

    private static float px(double v, double bc, float cc, float sc) { return (float) (cc + (v - bc) * sc); }
}
