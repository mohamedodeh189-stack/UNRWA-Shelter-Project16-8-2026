import org.unrwa.yarmoukfield.drawing.*;

/** A2 door logic smoke test (pure JVM): a wall + a door on it → verifies host linkage, geometry resolve, DXF
 * (Doors layer + ARC + DOOR code), and JSON round-trip of the door fields. No Android needed. */
public class DoorSmoke {
    static int fail = 0;
    static void ok(boolean c, String m) { System.out.println((c ? "OK: " : "FAIL: ") + m); if (!c) fail++; }
    static int count(String s, String sub) { int n = 0, i = 0; while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); } return n; }

    public static void main(String[] a) {
        DrawModel m = new DrawModel();
        m.pxPerMeter = 100;
        DrawElement wall = new DrawElement(DrawElement.WALL, "walls", new double[]{0, 0, 1000, 0});
        wall.thicknessM = 0.20; m.add(wall);
        ok(wall.id != null && !wall.id.isEmpty(), "wall got a stable id, got '" + wall.id + "'");

        DrawElement door = new DrawElement(DrawElement.DOOR, "doors", new double[]{500, 0});
        door.hostId = wall.id; door.t = 0.5; door.swing = 0; m.add(door);

        double[] g = m.doorGeom(door);
        ok(g != null, "door geometry resolves from host");
        ok(g != null && Math.abs(g[0] - 500) < 1e-6 && Math.abs(g[1] - 0) < 1e-6, "door centre at wall midpoint, got (" + g[0] + "," + g[1] + ")");
        ok(g != null && Math.abs(g[5] - 90) < 1e-6, "door width world = 0.9m*100 = 90, got " + g[5]);

        // move the wall — the door must follow (geometry derived from host)
        wall.pts[0] = 200; wall.pts[2] = 1200;
        double[] g2 = m.doorGeom(door);
        ok(g2 != null && Math.abs(g2[0] - 700) < 1e-6, "door follows the moved wall (centre now 700), got " + g2[0]);
        wall.pts[0] = 0; wall.pts[2] = 1000; // restore

        String dxf = DxfWriter.toDxf(m);
        ok(dxf.contains("\nDoors\n"), "DXF has a Doors layer");
        ok(count(dxf, "\nARC\n") == 1, "door swing exported as a real ARC entity, got " + count(dxf, "\nARC\n"));
        ok(dxf.contains("DOOR-01"), "door carries a Latin DOOR-01 code");
        ok(dxf.endsWith("0\nEOF\n"), "DXF terminates with EOF");

        String js = DrawJson.toJson(m);
        DrawModel m2 = DrawJson.fromJson(js);
        DrawElement d2 = null; for (DrawElement e : m2.elements) if (DrawElement.DOOR.equals(e.type)) d2 = e;
        ok(d2 != null, "door survives JSON round-trip");
        ok(d2 != null && d2.hostId.equals(wall.id) && Math.abs(d2.t - 0.5) < 1e-9 && d2.swing == 0, "door keeps hostId/t/swing");
        ok(m2.doorGeom(d2) != null, "round-tripped door still resolves to its host");

        System.out.println(fail == 0 ? "DOOR_SMOKE_OK" : "DOOR_SMOKE_FAILED:" + fail);
        if (fail > 0) System.exit(1);
    }
}
