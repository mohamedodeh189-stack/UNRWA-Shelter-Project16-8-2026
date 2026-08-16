import org.unrwa.yarmoukfield.drawing.DrawModel;
import org.unrwa.yarmoukfield.drawing.DrawElement;
import org.unrwa.yarmoukfield.drawing.DrawJson;
import org.unrwa.yarmoukfield.drawing.DxfWriter;

/** Standalone JVM "tolerance" test for the drawing engine at a real ~100 m² house scale (like the reference
 * croquis): a full outer boundary + internal walls (with thickness) + a grid of rooms. Pure Java (no Android),
 * so it verifies the model, area math, JSON round-trip, and the DXF exporter all handle a whole-house plan
 * instantly and correctly. The on-device PDF + finger-drawing ease is a separate real test by an engineer. */
public class DrawScaleSmoke {
    static int fail = 0;
    static void check(boolean c, String m) { if (!c) { System.out.println("FAIL: " + m); fail++; } else System.out.println("OK: " + m); }

    static void wall(DrawModel m, double x1, double y1, double x2, double y2, double t) {
        DrawElement e = new DrawElement(DrawElement.WALL, "walls", new double[]{x1, y1, x2, y2});
        e.thicknessM = t; m.elements.add(e);
    }
    static void room(DrawModel m, double x1, double y1, double x2, double y2) {
        m.elements.add(new DrawElement(DrawElement.ROOM, "rooms", new double[]{x1, y1, x2, y2}));
    }
    static int count(String s, String sub) { int n = 0, i = 0; while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); } return n; }

    public static void main(String[] args) {
        DrawModel m = new DrawModel();
        m.pxPerMeter = 100;               // 100 world units = 1 metre  → 1000 world = 10 m
        m.ceilingHeightM = 3.20;

        // 10 m × 10 m outer boundary (four 20 cm walls)
        wall(m, 0, 0, 1000, 0, 0.20);
        wall(m, 1000, 0, 1000, 1000, 0.20);
        wall(m, 1000, 1000, 0, 1000, 0.20);
        wall(m, 0, 1000, 0, 0, 0.20);
        // internal partitions (15/10 cm) forming a 3×2 room grid
        wall(m, 0, 333, 1000, 333, 0.15);
        wall(m, 0, 666, 1000, 666, 0.15);
        wall(m, 500, 0, 500, 1000, 0.10);
        // 6 rooms nearly filling the 3×2 grid (~15.8 m² each → ~95 m² net)
        room(m, 5, 5, 495, 328);
        room(m, 505, 5, 995, 328);
        room(m, 5, 338, 495, 661);
        room(m, 505, 338, 995, 661);
        room(m, 5, 671, 495, 995);
        room(m, 505, 671, 995, 995);

        long t0 = System.nanoTime();
        String dxf = DxfWriter.toDxf(m);
        long genUs = (System.nanoTime() - t0) / 1000;

        int walls = 7, rooms = 6;
        System.out.println("elements=" + m.elements.size() + "  rooms=" + m.roomCount()
                + "  area_m2=" + String.format(java.util.Locale.US, "%.2f", m.roomAreaM2())
                + "  dxf_bytes=" + dxf.length() + "  dxf_gen_us=" + genUs);

        check(m.elements.size() == walls + rooms, "all " + (walls + rooms) + " elements present");
        check(m.roomCount() == rooms, rooms + " rooms");
        check(m.roomAreaM2() > 85 && m.roomAreaM2() < 100, "net area is a plausible ~100 m² house, got " + m.roomAreaM2());
        check(dxf.contains("$INSUNITS"), "DXF declares units");
        check(count(dxf, "\nLWPOLYLINE\n") == walls + rooms, "every wall (thickness band) + room is a closed LWPOLYLINE, got " + count(dxf, "\nLWPOLYLINE\n"));
        check(dxf.contains("\nWalls\n") && dxf.contains("\nRooms\n") && dxf.contains("\nDimensions\n"), "layers Walls/Rooms/Dimensions present");
        check(count(dxf, "ROOM-") == rooms, "each room got a Latin-safe code, got " + count(dxf, "ROOM-"));
        check(dxf.endsWith("0\nEOF\n"), "DXF is terminated with EOF");
        check(genUs < 200000, "DXF generated well under 0.2 s (instant), took " + genUs + " us");

        // JSON round-trip must preserve the whole plan incl. wall thickness
        String js = DrawJson.toJson(m);
        DrawModel m2 = DrawJson.fromJson(js);
        check(m2.elements.size() == m.elements.size(), "JSON round-trip keeps every element");
        check(Math.abs(m2.roomAreaM2() - m.roomAreaM2()) < 0.01, "JSON round-trip preserves area");
        double th = -1; for (DrawElement e : m2.elements) if (DrawElement.WALL.equals(e.type)) { th = e.thicknessM; break; }
        check(Math.abs(th - 0.20) < 1e-9, "JSON round-trip preserves wall thickness (0.20 m), got " + th);
        check(Math.abs(m2.ceilingHeightM - 3.20) < 1e-9, "JSON round-trip preserves ceiling height");

        System.out.println(fail == 0 ? "DRAW_SCALE_SMOKE_OK" : "SMOKE_TEST_FAILED: " + fail);
        if (fail > 0) System.exit(1);
    }
}
