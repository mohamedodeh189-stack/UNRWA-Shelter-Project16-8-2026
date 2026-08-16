import org.unrwa.yarmoukfield.drawing.DrawModel;
import org.unrwa.yarmoukfield.drawing.DrawElement;
import org.unrwa.yarmoukfield.drawing.DrawJson;

/** Emits the exact ~95 m² reference house (same model as DrawScaleSmoke) as the app's own drawing JSON, so it
 * can be seeded into files/drawings/house_<key>.json and exported to PDF by the real app. Pure data generation
 * — no app code is touched. Prints the JSON to stdout. */
public class GenHouseJson {
    static void wall(DrawModel m, double x1, double y1, double x2, double y2, double t) {
        DrawElement e = new DrawElement(DrawElement.WALL, "walls", new double[]{x1, y1, x2, y2});
        e.thicknessM = t; m.elements.add(e);
    }
    static void room(DrawModel m, double x1, double y1, double x2, double y2, String label) {
        DrawElement e = new DrawElement(DrawElement.ROOM, "rooms", new double[]{x1, y1, x2, y2});
        e.label = label; m.elements.add(e);
    }
    public static void main(String[] a) {
        DrawModel m = new DrawModel();
        m.pxPerMeter = 100;            // calibrated: 100 world units = 1 m
        m.ceilingHeightM = 3.20;
        wall(m, 0, 0, 1000, 0, 0.20);
        wall(m, 1000, 0, 1000, 1000, 0.20);
        wall(m, 1000, 1000, 0, 1000, 0.20);
        wall(m, 0, 1000, 0, 0, 0.20);
        wall(m, 0, 333, 1000, 333, 0.15);
        wall(m, 0, 666, 1000, 666, 0.15);
        wall(m, 500, 0, 500, 1000, 0.10);
        room(m, 5, 5, 495, 328, "غرفة نوم");
        room(m, 505, 5, 995, 328, "غرفة جلوس");
        room(m, 5, 338, 495, 661, "مطبخ");
        room(m, 505, 338, 995, 661, "صالة");
        room(m, 5, 671, 495, 995, "حمّام");
        room(m, 505, 671, 995, 995, "غرفة نوم 2");
        String json = DrawJson.toJson(m);
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(a[0]), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { throw new RuntimeException(e); }
        System.out.println("WROTE " + a[0] + " len=" + json.length());
    }
}
