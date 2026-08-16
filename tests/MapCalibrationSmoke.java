import org.unrwa.yarmoukfield.MapCalibration;
import java.util.ArrayList;
import java.util.List;

/** Standalone JVM test for MapCalibration (pure Java, zero Android dependency, so it runs directly with a
 * plain JDK — the strongest verification available in an environment with no device/emulator). Builds a
 * synthetic ground-truth similarity transform, derives reference points from it, and checks:
 *   1. A perfect (noise-free) 3-point calibration recovers near-zero error and is reported reliable.
 *   2. A 4th point nudged off the true transform produces a large, correctly-flagged residual (unreliable).
 *   3. geoToFraction/fractionToGeo round-trip correctly through a good calibration.
 *   4. Fewer than 3 points is refused outright (fitted=false), never silently guessed.
 */
public class MapCalibrationSmoke {
    static int failures = 0;

    static void check(boolean cond, String msg) {
        if (!cond) { System.out.println("FAIL: " + msg); failures++; }
        else System.out.println("OK: " + msg);
    }

    public static void main(String[] args) {
        // Ground truth: scale s, rotation theta, translation (tx,ty) mapping local ENU meters -> fraction space.
        double lat0 = 33.4991, lng0 = 36.2853;
        double s = 1.0 / 8000.0; // ~8000m of camp maps to 1.0 fraction unit
        double theta = Math.toRadians(12);
        double tx = 0.5, ty = 0.5;

        // Real-world local points (meters offset from lat0/lng0), spread across the camp footprint.
        double[][] metersPoints = {
                {-2000, 1500},   // north-west corner
                {2200, 1800},    // north-east corner
                {0, -2300},      // south, roughly centered
                {1800, -1200},   // south-east, extra 4th point (used only in test 2)
        };

        List<MapCalibration.Point> points3 = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double[] m = metersPoints[i];
            double fracX = s * (Math.cos(theta) * m[0] - Math.sin(theta) * m[1]) + tx;
            double fracY = s * (Math.sin(theta) * m[0] + Math.cos(theta) * m[1]) + ty;
            double lat = lat0 + m[1] / 110540.0;
            double lng = lng0 + m[0] / (111320.0 * Math.cos(Math.toRadians(lat0)));
            points3.add(new MapCalibration.Point("P" + i, lat, lng, fracX, fracY));
        }

        System.out.println("=== Test 1: perfect 3-point calibration ===");
        MapCalibration.FitResult r1 = MapCalibration.fit(points3);
        check(r1.fitted, "fit() succeeds with exactly 3 well-distributed points");
        // A small residual (well under a meter) is expected even for a noise-free ground-truth fit, because the
        // equirectangular local-meters projection used internally is only an approximation of real geodesy —
        // this is floating-point/projection noise, not a calibration error.
        check(r1.overallErrorMeters < 0.5, "near-zero overall error on a noise-free ground-truth fit, got " + r1.overallErrorMeters + "m");
        check(r1.reliable, "a near-zero-error calibration must be marked reliable");
        for (double e : r1.perPointErrorMeters) check(e < 0.5, "each per-point error near zero, got " + e + "m");

        System.out.println("\n=== Test 2: a badly-placed 4th point must be caught, not silently accepted ===");
        List<MapCalibration.Point> points4 = new ArrayList<>(points3);
        // Correct target fraction for point 4 would be computed like the others; instead we deliberately
        // mis-place it by ~150m worth of fraction offset to simulate a wrong tap on the map.
        double[] m3 = metersPoints[3];
        double trueFracX = s * (Math.cos(theta) * m3[0] - Math.sin(theta) * m3[1]) + tx;
        double trueFracY = s * (Math.sin(theta) * m3[0] + Math.cos(theta) * m3[1]) + ty;
        double wrongFracX = trueFracX + 150 * s; // offset equivalent to ~150m in map space
        double lat3 = lat0 + m3[1] / 110540.0, lng3 = lng0 + m3[0] / (111320.0 * Math.cos(Math.toRadians(lat0)));
        points4.add(new MapCalibration.Point("P3_bad", lat3, lng3, wrongFracX, trueFracY));
        MapCalibration.FitResult r2 = MapCalibration.fit(points4);
        check(r2.fitted, "fit() still succeeds with 4 points (over-determined)");
        check(r2.overallErrorMeters > 20, "a ~150m mis-tap must produce a large overall error, got " + r2.overallErrorMeters + "m");
        check(!r2.reliable, "a calibration with a badly mis-placed point must be flagged unreliable, not silently accepted");
        boolean flaggedTheBadPoint = r2.perPointErrorMeters[3] > r2.perPointErrorMeters[0]
                && r2.perPointErrorMeters[3] > r2.perPointErrorMeters[1]
                && r2.perPointErrorMeters[3] > r2.perPointErrorMeters[2];
        check(flaggedTheBadPoint, "the deliberately mis-placed point must show the largest per-point error of the four");

        System.out.println("\n=== Test 3: geoToFraction / fractionToGeo round-trip through a good calibration ===");
        MapCalibration calib = r1.calibration;
        double testLat = lat0 + 500 / 110540.0, testLng = lng0 + 700 / (111320.0 * Math.cos(Math.toRadians(lat0)));
        double[] frac = calib.geoToFraction(testLat, testLng);
        check(frac[0] >= 0 && frac[0] <= 1 && frac[1] >= 0 && frac[1] <= 1, "a point near the calibrated area lands within the 0..1 fraction range");
        double[] backToGeo = calib.fractionToGeo(frac[0], frac[1]);
        double latErr = Math.abs(backToGeo[0] - testLat), lngErr = Math.abs(backToGeo[1] - testLng);
        check(latErr < 1e-9 && lngErr < 1e-9, "round-trip geo->fraction->geo recovers the original coordinates exactly");

        System.out.println("\n=== Test 4: fewer than 3 points must be refused, never guessed ===");
        List<MapCalibration.Point> points2 = new ArrayList<>(points3.subList(0, 2));
        MapCalibration.FitResult r0 = MapCalibration.fit(points2);
        check(!r0.fitted, "fit() must refuse with only 2 points");
        check(r0.calibration == null, "no calibration object is ever produced from an insufficient point set");
        MapCalibration.FitResult rEmpty = MapCalibration.fit(new ArrayList<>());
        check(!rEmpty.fitted, "fit() must refuse with zero points");

        System.out.println("\n" + (failures == 0 ? "MAP_CALIBRATION_SMOKE_OK" : "SMOKE_TEST_FAILED: " + failures + " failure(s)"));
        if (failures > 0) System.exit(1);
    }
}
