import org.unrwa.yarmoukfield.drawing.*;
public class StairsDxf {
    public static void main(String[] a) throws Exception {
        DrawModel m = new DrawModel();
        m.pxPerMeter = 100;                       // calibrated: 1m = 100 world units
        m.add(new DrawElement(DrawElement.STAIRS, "stairs", new double[]{0,0,300,120})); // 3.0 x 1.2 m
        String dxf = DxfWriter.toDxf(m);
        java.nio.file.Files.write(new java.io.File(a[0]).toPath(), dxf.getBytes("windows-1256"));
        System.out.println("WROTE " + a[0] + " | contains Stairs=" + dxf.contains("Stairs"));
    }
}
