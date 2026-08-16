package org.unrwa.yarmoukfield.drawing;

import java.util.List;

/** B1 — corner cleanup for OUTPUT only (DXF/PDF). Given the drawn walls, it returns a cleaned axis for each
 * wall where near-meeting endpoints are snapped to the TRUE intersection of the two wall axis-lines, so corners
 * close with no gap and a slightly-short finger-drawn wall is extended to meet its neighbour.
 *
 * Guarantees the module depends on:
 *  - It NEVER mutates the input elements — it returns fresh coordinate arrays; the drawing model stays as drawn.
 *  - It moves an endpoint only ALONG its own wall's axis line (the intersection point lies on that line), so a
 *    wall's DIRECTION/ANGLE is preserved exactly — oblique corners stay oblique, 90° is never forced.
 *  - Parallel/collinear walls (no unique intersection) and endpoints whose intersection is farther than
 *    {@code maxSnap} are left exactly as drawn, so unrelated walls are never yanked together.
 *
 * Pure Java (no Android) → fully JVM-testable. */
public final class WallJoin {
    private WallJoin() {}

    /** Cleaned axes for every WALL in {@code walls} (same order, same length). {@code tol} = how close an
     * endpoint must be to another wall to be considered a joint; {@code maxSnap} = the farthest an endpoint may
     * be moved to reach the intersection (caps extension so distant/near-parallel walls are left alone). */
    public static double[][] cleanedAxes(List<DrawElement> walls, double tol, double maxSnap) {
        int n = walls.size();
        double[][] ax = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] p = walls.get(i).pts;
            ax[i] = new double[]{p[0], p[1], p[2], p[3]};
        }
        for (int i = 0; i < n; i++) {
            for (int e = 0; e < 2; e++) {
                double ex = ax[i][e * 2], ey = ax[i][e * 2 + 1];
                int best = -1; double bestD = tol;
                for (int j = 0; j < n; j++) {
                    if (j == i) continue;
                    double[] q = walls.get(j).pts;
                    double d = DrawGeo.pointToSegment(ex, ey, q[0], q[1], q[2], q[3]);
                    if (d <= bestD) { bestD = d; best = j; }
                }
                if (best < 0) continue;
                double[] x = intersect(ax[i][0], ax[i][1], ax[i][2], ax[i][3],
                        ax[best][0], ax[best][1], ax[best][2], ax[best][3]);
                if (x == null) continue; // parallel / collinear → leave as drawn
                if (DrawGeo.dist(ex, ey, x[0], x[1]) <= maxSnap) { ax[i][e * 2] = x[0]; ax[i][e * 2 + 1] = x[1]; }
            }
        }
        return ax;
    }

    /** Intersection point of the two infinite lines AB and CD, or null when they are parallel. */
    static double[] intersect(double ax, double ay, double bx, double by,
                              double cx, double cy, double dx, double dy) {
        double rX = bx - ax, rY = by - ay, sX = dx - cx, sY = dy - cy;
        double denom = rX * sY - rY * sX;
        if (Math.abs(denom) < 1e-9) return null;
        double t = ((cx - ax) * sY - (cy - ay) * sX) / denom;
        return new double[]{ax + t * rX, ay + t * rY};
    }
}
