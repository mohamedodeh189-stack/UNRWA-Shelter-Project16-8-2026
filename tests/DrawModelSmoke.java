import org.unrwa.yarmoukfield.drawing.DrawElement;
import org.unrwa.yarmoukfield.drawing.DrawGeo;
import org.unrwa.yarmoukfield.drawing.DrawJson;
import org.unrwa.yarmoukfield.drawing.DrawLayer;
import org.unrwa.yarmoukfield.drawing.DrawModel;

import java.util.ArrayList;
import java.util.List;

/** Standalone JVM test for the House Drawing Module M0 (pure Java, zero Android dependency — runs on a plain
 * JDK). Verifies the geometry helpers, the model's calibration/undo/bounds, and a full JSON round-trip
 * including Arabic room names, negatives and decimals. This is the strongest verification available with no
 * device: everything M0 touches is deterministic and checked here. */
public class DrawModelSmoke {
    static int failures = 0;
    static void check(boolean cond, String msg) {
        if (!cond) { System.out.println("FAIL: " + msg); failures++; } else System.out.println("OK: " + msg);
    }
    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }

    public static void main(String[] args) {
        System.out.println("=== Geometry ===");
        check(near(DrawGeo.dist(0, 0, 3, 4), 5), "dist(0,0,3,4)=5");
        check(near(DrawGeo.pointToSegment(5, 5, 0, 0, 10, 0), 5), "point above midpoint of horizontal segment = 5");
        check(near(DrawGeo.pointToSegment(-3, 0, 0, 0, 10, 0), 3), "point past an endpoint clamps to that endpoint");
        double[] snapped = DrawGeo.snapToAxis(0, 0, 100, 4, 5); // ~2.29° off horizontal, within 5°
        check(near(snapped[1], 0) && near(snapped[0], Math.sqrt(100 * 100 + 4 * 4)), "near-horizontal wall snaps flat, preserving length");
        double[] noSnap = DrawGeo.snapToAxis(0, 0, 100, 40, 5); // ~21.8° off, outside tol
        check(!near(noSnap[1], 0), "a clearly diagonal wall is NOT axis-snapped");
        List<DrawElement> one = new ArrayList<>();
        one.add(new DrawElement(DrawElement.WALL, "walls", new double[]{0, 0, 10, 0}));
        double[] ep = DrawGeo.snapToEndpoint(0.5, 0.4, one, 2);
        check(near(ep[0], 0) && near(ep[1], 0), "a point near an existing corner snaps onto it");
        double[] farEp = DrawGeo.snapToEndpoint(5, 5, one, 2);
        check(near(farEp[0], 5) && near(farEp[1], 5), "a point far from any corner is left unchanged");

        System.out.println("\n=== Calibration & dimensions ===");
        DrawModel m = new DrawModel();
        check(!m.isCalibrated(), "a fresh model is NOT calibrated");
        check(near(m.meters(114), 0), "uncalibrated meters() returns 0, never a guessed value");
        check(!m.calibrate(380, 0), "calibrate refuses zero real meters");
        check(!m.calibrate(0, 10), "calibrate refuses zero drawn length");
        check(!m.isCalibrated(), "still uncalibrated after refused attempts");
        check(m.calibrate(380, 10), "calibrate(380 world = 10 m) succeeds");
        check(near(m.pxPerMeter, 38), "pxPerMeter = 380/10 = 38");
        check(near(m.meters(114), 3), "a 114-world segment now reads 3.00 m");
        DrawElement wall = new DrawElement(DrawElement.WALL, "walls", new double[]{0, 0, 76, 0});
        check(near(m.segmentMeters(wall), 2), "segmentMeters of a 76-world wall = 2.00 m");

        System.out.println("\n=== Elements, move, undo/redo, bounds ===");
        DrawModel d = new DrawModel();
        DrawElement room = new DrawElement(DrawElement.ROOM, "rooms", new double[]{0, 0, 120, 90});
        room.label = "غرفة نوم"; room.code = "R1";
        d.add(room);
        d.add(new DrawElement(DrawElement.WALL, "walls", new double[]{0, 0, 120, 0}));
        check(d.elements.size() == 2, "two elements added");
        double[] b1 = d.bounds();
        check(b1 != null && near(b1[0], 0) && near(b1[1], 0) && near(b1[2], 120) && near(b1[3], 90), "bounds cover both elements");
        d.moveBy(room, 10, 5);
        check(near(room.pts[0], 10) && near(room.pts[1], 5), "moveBy shifts the room's corner");
        d.undo();
        check(near(room == d.elements.get(0) ? d.elements.get(0).pts[0] : 999, 999) || near(d.elements.get(0).pts[0], 0), "undo restores pre-move coordinates");
        check(near(d.elements.get(0).pts[0], 0) && near(d.elements.get(0).pts[1], 0), "undo put the room corner back to (0,0)");
        d.redo();
        check(near(d.elements.get(0).pts[0], 10), "redo re-applies the move");
        int before = d.elements.size();
        d.undo(); // undo the move again to a known state
        d.remove(d.elements.get(0));
        check(d.elements.size() == before - 1, "remove drops one element");
        d.undo();
        check(d.elements.size() == before, "undo brings the removed element back");

        System.out.println("\n=== JSON round-trip (Arabic, negatives, decimals) ===");
        DrawModel src = new DrawModel();
        src.calibrate(190, 5); // pxPerMeter = 38
        src.northDeg = 12.5;
        DrawElement r = new DrawElement(DrawElement.ROOM, "rooms", new double[]{-12.5, 0, 107.5, 90.25});
        r.label = "غرفة الجلوس \"الكبيرة\""; r.code = "R2"; r.damaged = true; r.damageNote = "سقف متضرر";
        src.add(r);
        src.layer("dims").visible = false;
        String json = DrawJson.toJson(src);
        DrawModel back = DrawJson.fromJson(json);
        check(near(back.pxPerMeter, 38), "pxPerMeter survives round-trip");
        check(near(back.northDeg, 12.5), "northDeg survives round-trip");
        check(back.elements.size() == 1, "element count survives");
        DrawElement rb = back.elements.get(0);
        check(rb.label.equals("غرفة الجلوس \"الكبيرة\""), "Arabic label with an embedded quote round-trips exactly");
        check(rb.code.equals("R2") && rb.damaged && rb.damageNote.equals("سقف متضرر"), "code/damage/note round-trip");
        check(near(rb.pts[0], -12.5) && near(rb.pts[3], 90.25), "negative and decimal coordinates round-trip");
        check(!back.isLayerVisible("dims"), "hidden layer visibility survives round-trip");

        System.out.println("\n=== Parsing a hand-written JSON ===");
        String hand = "{ \"pxPerMeter\": 40, \"northDeg\": 0, \"layers\": [], \"elements\": [ "
                + "{\"type\":\"line\",\"layer\":\"rooms\",\"pts\":[0,0,80,0],\"label\":\"\",\"code\":\"\",\"damaged\":false,\"damageNote\":\"\"} ] }";
        DrawModel hm = DrawJson.fromJson(hand);
        check(near(hm.pxPerMeter, 40), "hand JSON: scale parsed");
        check(!hm.layers.isEmpty(), "empty layer list falls back to defaults so the editor is never layerless");
        check(hm.elements.size() == 1 && near(hm.segmentMeters(hm.elements.get(0)), 2), "hand JSON: an 80-world line reads 2.00 m at 40 px/m");

        System.out.println("\n" + (failures == 0 ? "DRAW_MODEL_SMOKE_OK" : "SMOKE_TEST_FAILED: " + failures + " failure(s)"));
        if (failures > 0) System.exit(1);
    }
}
