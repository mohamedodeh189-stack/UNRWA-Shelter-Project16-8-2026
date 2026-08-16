import org.unrwa.yarmoukfield.drawing.*;

/** A3 window logic smoke test (pure JVM): a wall + a window on it → host linkage, geometry, DXF (Windows layer,
 * 4 glazing/jamb LINEs, WIN code, no ARC), default 1.20 m width, and JSON round-trip. */
public class WindowSmoke {
    static int fail = 0;
    static void ok(boolean c, String m) { System.out.println((c ? "OK: " : "FAIL: ") + m); if (!c) fail++; }
    static int count(String s, String sub) { int n = 0, i = 0; while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); } return n; }

    public static void main(String[] a) {
        DrawModel m = new DrawModel();
        m.pxPerMeter = 100;
        DrawElement wall = new DrawElement(DrawElement.WALL, "walls", new double[]{0, 0, 1000, 0});
        wall.thicknessM = 0.20; m.add(wall);
        DrawElement win = new DrawElement(DrawElement.WINDOW, "windows", new double[]{500, 0});
        win.hostId = wall.id; win.t = 0.5; m.add(win);

        double[] g = m.openingGeom(win);
        ok(g != null, "window geometry resolves from host");
        ok(g != null && Math.abs(g[5] - 120) < 1e-6, "default window width 1.20m*100 = 120, got " + (g == null ? "null" : g[5]));

        String dxf = DxfWriter.toDxf(m);
        ok(dxf.contains("\nWindows\n"), "DXF has a Windows layer");
        ok(count(dxf, "WIN-01") == 1, "window carries a Latin WIN-01 code");
        ok(count(dxf, "\n8\nWindows\n") == 5, "window = 4 LINEs + 1 TEXT on Windows, got " + count(dxf, "\n8\nWindows\n"));
        ok(count(dxf, "\nARC\n") == 0, "a window has no ARC (unlike a door)");
        ok(dxf.endsWith("0\nEOF\n"), "DXF terminates with EOF");

        // custom length
        win.widthM = 0.80;
        ok(Math.abs(m.openingGeom(win)[5] - 80) < 1e-6, "custom window width 0.80m applied");

        DrawModel m2 = DrawJson.fromJson(DrawJson.toJson(m));
        DrawElement w2 = null; for (DrawElement e : m2.elements) if (DrawElement.WINDOW.equals(e.type)) w2 = e;
        ok(w2 != null && w2.hostId.equals(wall.id) && Math.abs(w2.t - 0.5) < 1e-9 && Math.abs(w2.widthM - 0.80) < 1e-9, "window round-trips host/t/width");
        ok(w2 != null && m2.openingGeom(w2) != null, "round-tripped window still resolves to its host");

        System.out.println(fail == 0 ? "WINDOW_SMOKE_OK" : "WINDOW_SMOKE_FAILED:" + fail);
        if (fail > 0) System.exit(1);
    }
}
