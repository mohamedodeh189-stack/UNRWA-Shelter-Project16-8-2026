import org.unrwa.yarmoukfield.drawing.*;

/** Builds a representative sketch (walls with thickness incl. an oblique wall, a named room, a door, a window)
 * and writes the DXF to the path given as arg[0], so an external DXF parser can confirm it is a valid CAD file. */
public class DxfGen {
    public static void main(String[] a) throws Exception {
        DrawModel m = new DrawModel();
        m.pxPerMeter = 100;
        DrawElement top = wall(m, 0, 0, 500, 0, 0.20);
        DrawElement right = wall(m, 500, 0, 500, 400, 0.20);
        wall(m, 500, 400, 0, 400, 0.20);
        wall(m, 0, 400, 0, 0, 0.20);
        wall(m, 0, 0, 180, -120, 0.15);           // oblique wall
        DrawElement room = new DrawElement(DrawElement.ROOM, "rooms", new double[]{20, 20, 480, 380});
        room.label = "غرفة نوم"; m.add(room);
        DrawElement door = new DrawElement(DrawElement.DOOR, "doors", new double[]{250, 0});
        door.hostId = top.id; door.t = 0.5; door.swing = 1; m.add(door);
        DrawElement win = new DrawElement(DrawElement.WINDOW, "windows", new double[]{500, 200});
        win.hostId = right.id; win.t = 0.5; m.add(win);

        String dxf = DxfWriter.toDxf(m);
        java.nio.file.Files.write(java.nio.file.Paths.get(a[0]), dxf.getBytes(java.nio.charset.Charset.forName("windows-1256")));
        System.out.println("WROTE " + a[0] + " (" + dxf.length() + " chars, windows-1256)");
    }

    static DrawElement wall(DrawModel m, double x1, double y1, double x2, double y2, double t) {
        DrawElement w = new DrawElement(DrawElement.WALL, "walls", new double[]{x1, y1, x2, y2});
        w.thicknessM = t; m.add(w); return w;
    }
}
