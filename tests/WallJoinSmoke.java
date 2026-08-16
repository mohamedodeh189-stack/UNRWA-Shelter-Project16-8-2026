import org.unrwa.yarmoukfield.drawing.*;
import java.util.*;

/** B1 corner-cleanup geometry test (pure JVM): right-angle join, oblique join (angle preserved), finger-gap
 * near-miss (extended to meet), and the safety cases (parallel / far-apart walls left untouched). */
public class WallJoinSmoke {
    static int fail = 0;
    static void ok(boolean c, String m) { System.out.println((c ? "OK: " : "FAIL: ") + m); if (!c) fail++; }
    static DrawElement w(double x1, double y1, double x2, double y2) { return new DrawElement(DrawElement.WALL, "walls", new double[]{x1, y1, x2, y2}); }
    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }

    public static void main(String[] a) {
        double tol = 30, max = 60;

        // 1) right-angle near-miss corner: horizontal ends at 500, vertical axis at x=490 → both meet at (490,0)
        List<DrawElement> t1 = Arrays.asList(w(0, 0, 500, 0), w(490, 10, 490, 400));
        double[][] c1 = WallJoin.cleanedAxes(t1, tol, max);
        ok(near(c1[0][2], 490) && near(c1[0][3], 0), "90°: horizontal end trimmed to (490,0), got (" + c1[0][2] + "," + c1[0][3] + ")");
        ok(near(c1[1][0], 490) && near(c1[1][1], 0), "90°: vertical start extended to (490,0), got (" + c1[1][0] + "," + c1[1][1] + ")");

        // 2) oblique join: shared corner (500,0); the diagonal wall must KEEP its 45° direction (never squared)
        List<DrawElement> t2 = Arrays.asList(w(0, 0, 500, 0), w(500, 0, 700, 200));
        double[][] c2 = WallJoin.cleanedAxes(t2, tol, max);
        double ddx = c2[1][2] - c2[1][0], ddy = c2[1][3] - c2[1][1];
        ok(near(ddx, ddy) && ddx > 0, "oblique: diagonal wall stays 45° (dx==dy), got dx=" + ddx + " dy=" + ddy);
        ok(near(c2[0][2], 500) && near(c2[1][0], 500), "oblique: corner stays at the drawn meeting point (500,0)");

        // 3) finger-gap: horizontal ends 20 short of the vertical wall → extended to close the gap
        List<DrawElement> t3 = Arrays.asList(w(0, 0, 480, 0), w(500, 0, 500, 300));
        double[][] c3 = WallJoin.cleanedAxes(t3, tol, max);
        ok(near(c3[0][2], 500) && near(c3[0][3], 0), "gap: short wall extended from 480 to 500, got x=" + c3[0][2]);

        // 4) parallel walls (never intersect) → left exactly as drawn
        List<DrawElement> t4 = Arrays.asList(w(0, 0, 500, 0), w(0, 300, 500, 300));
        double[][] c4 = WallJoin.cleanedAxes(t4, 500, 1000); // huge tol, still must not move parallels
        ok(near(c4[0][2], 500) && near(c4[1][1], 300), "parallel walls untouched");

        // 5) far-apart walls (beyond tol) → not yanked together
        List<DrawElement> t5 = Arrays.asList(w(0, 0, 100, 0), w(900, 0, 900, 300));
        double[][] c5 = WallJoin.cleanedAxes(t5, tol, max);
        ok(near(c5[0][2], 100) && near(c5[0][3], 0), "far wall endpoint not moved, got (" + c5[0][2] + "," + c5[0][3] + ")");

        System.out.println(fail == 0 ? "WALLJOIN_SMOKE_OK" : "WALLJOIN_SMOKE_FAILED:" + fail);
        if (fail > 0) System.exit(1);
    }
}
