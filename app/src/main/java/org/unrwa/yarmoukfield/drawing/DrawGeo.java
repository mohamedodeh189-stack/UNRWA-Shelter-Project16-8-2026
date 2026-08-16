package org.unrwa.yarmoukfield.drawing;

/** Pure geometry helpers for the sketch tool: distances, hit-testing, endpoint snapping and 90°/axis
 * snapping. No Android dependency, no state — every method is deterministic and JVM-testable. All inputs are
 * WORLD units (see {@link DrawElement}); callers convert screen↔world with the view Matrix separately. */
public final class DrawGeo {
    private DrawGeo() {}

    public static double dist(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Shortest distance from point (px,py) to segment (x1,y1)-(x2,y2). Used for tap hit-testing walls/lines. */
    public static double pointToSegment(double px, double py, double x1, double y1, double x2, double y2) {
        double vx = x2 - x1, vy = y2 - y1;
        double len2 = vx * vx + vy * vy;
        if (len2 == 0) return dist(px, py, x1, y1);
        double t = ((px - x1) * vx + (py - y1) * vy) / len2;
        if (t < 0) t = 0; else if (t > 1) t = 1;
        return dist(px, py, x1 + t * vx, y1 + t * vy);
    }

    /** Snap a segment's free end (ex,ey) so the segment lies on the nearest axis (0/90/180/270°) when it is
     * within {@code tolDeg} of it — keeping walls straight without a keyboard. Anchor (ax,ay) is fixed. */
    public static double[] snapToAxis(double ax, double ay, double ex, double ey, double tolDeg) {
        double dx = ex - ax, dy = ey - ay;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len == 0) return new double[]{ex, ey};
        double ang = Math.toDegrees(Math.atan2(dy, dx));      // -180..180
        double[] axes = {0, 90, 180, -90};
        for (double a : axes) {
            double diff = Math.abs(normalizeDeg(ang - a));
            if (diff <= tolDeg) {
                double r = Math.toRadians(a);
                return new double[]{ax + len * Math.cos(r), ay + len * Math.sin(r)};
            }
        }
        return new double[]{ex, ey};
    }

    /** Nearest existing endpoint within {@code tolPx} of (x,y), or the original point if none — lets a new
     * wall clip onto an existing corner instead of leaving a tiny gap. Returns {snappedX, snappedY}. */
    public static double[] snapToEndpoint(double x, double y, Iterable<DrawElement> elements, double tolPx) {
        double bestD = tolPx; double bx = x, by = y; boolean found = false;
        for (DrawElement e : elements) {
            if (e.pts == null) continue;
            for (int i = 0; i + 1 < e.pts.length; i += 2) {
                double d = dist(x, y, e.pts[i], e.pts[i + 1]);
                if (d <= bestD) { bestD = d; bx = e.pts[i]; by = e.pts[i + 1]; found = true; }
            }
        }
        return found ? new double[]{bx, by} : new double[]{x, y};
    }

    /** Alignment ("object-snap tracking"): nudge the free end so it lines up with an existing corner's X and/or
     * Y when within {@code tol} — so a wall catches the parallel/opposite wall and rooms square up. Returns
     * {snappedX, snappedY, guideX, guideY}; guideX/guideY are {@link Double#NaN} when no alignment was found on
     * that axis. Callers should try {@link #snapToEndpoint} first (an exact corner beats a soft alignment). */
    public static double[] snapToAlignments(double x, double y, Iterable<DrawElement> elements, double tol) {
        double bestDX = tol, bestDY = tol, gx = Double.NaN, gy = Double.NaN, sx = x, sy = y;
        for (DrawElement e : elements) {
            if (e.pts == null) continue;
            for (int i = 0; i + 1 < e.pts.length; i += 2) {
                double px = e.pts[i], py = e.pts[i + 1];
                double dX = Math.abs(px - x); if (dX < bestDX) { bestDX = dX; gx = px; sx = px; }
                double dY = Math.abs(py - y); if (dY < bestDY) { bestDY = dY; gy = py; sy = py; }
            }
        }
        return new double[]{sx, sy, gx, gy};
    }

    /** FAST FIELD MODE (Extend Wall): intersection of the INFINITE lines through (x1,y1)-(x2,y2) and
     * (x3,y3)-(x4,y4). Returns {x, y, t, u} where t/u are the parametric positions along each line (0 = first
     * point, 1 = second point; outside [0,1] means the crossing lies beyond that segment's own endpoints — the
     * caller decides whether that is still an acceptable "extend to meet" target). Returns null for parallel
     * (non-intersecting) lines. */
    public static double[] lineIntersection(double x1, double y1, double x2, double y2,
                                             double x3, double y3, double x4, double y4) {
        double d1x = x2 - x1, d1y = y2 - y1, d2x = x4 - x3, d2y = y4 - y3;
        double denom = d1x * d2y - d1y * d2x;
        if (Math.abs(denom) < 1e-9) return null; // parallel or coincident — no single crossing
        double t = ((x3 - x1) * d2y - (y3 - y1) * d2x) / denom;
        double u = ((x3 - x1) * d1y - (y3 - y1) * d1x) / denom;
        return new double[]{x1 + t * d1x, y1 + t * d1y, t, u};
    }

    /** Normalize an angle to (-180, 180]. */
    public static double normalizeDeg(double a) {
        while (a <= -180) a += 360;
        while (a > 180) a -= 360;
        return a;
    }
}
