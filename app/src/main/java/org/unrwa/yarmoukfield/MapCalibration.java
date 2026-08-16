package org.unrwa.yarmoukfield;

import java.util.List;

/** Independent geo <-> map-image-fraction transform layer. This class never touches beneficiary storage —
 * it only ever converts a real gps_lat/gps_lng into a FRACTIONAL position (0..1 of the calibration raster's
 * width/height) for drawing, and back. Nothing computed here is ever persisted onto a beneficiary row;
 * beneficiaries always keep real gps_lat/gps_lng only (see AppDatabase.Beneficiary). If the calibration
 * raster is ever replaced by a better one, only the reference points (map_calibration_points table) need
 * re-entry — no beneficiary data or migration is involved.
 *
 * Uses a similarity transform (uniform scale + rotation + translation — 4 degrees of freedom, no shear) fitted
 * by least squares from the reference points. This is the physically correct model for a small, locally-flat
 * area like a refugee camp, and — critically — it is what makes a MINIMUM of 3 points still produce a
 * meaningful, non-trivial per-point error: 3 points give 6 equations against only 4 unknowns, so the fit is
 * already over-determined and residuals are real. (A full 6-parameter affine fitted from exactly 3 points
 * would have zero residual by construction and could never reveal a bad point.)
 */
public final class MapCalibration {
    /** A single point's residual above this is flagged individually. */
    public static final double POINT_WARNING_M = 25;
    /** Overall RMS error above this means the calibration must not be silently trusted. */
    public static final double OVERALL_WARNING_M = 20;

    public static final class Point {
        public final String label;
        public final double lat, lng, fracX, fracY;
        public Point(String label, double lat, double lng, double fracX, double fracY) {
            this.label = label; this.lat = lat; this.lng = lng; this.fracX = fracX; this.fracY = fracY;
        }
    }

    public static final class FitResult {
        public boolean fitted;
        public boolean reliable;
        public double overallErrorMeters;
        public double[] perPointErrorMeters = new double[0];
        public String message = "";
        public MapCalibration calibration;
    }

    private static final double M_PER_DEG_LAT = 110540.0;
    private static double mPerDegLng(double latDeg) { return 111320.0 * Math.cos(Math.toRadians(latDeg)); }

    private final double lat0, lng0;           // local-projection origin (mean of the reference points)
    private final double aRe, aIm, bRe, bIm;   // complex similarity: fraction = a*meters + b

    private MapCalibration(double lat0, double lng0, double aRe, double aIm, double bRe, double bIm) {
        this.lat0 = lat0; this.lng0 = lng0; this.aRe = aRe; this.aIm = aIm; this.bRe = bRe; this.bIm = bIm;
    }

    /** Fits a similarity transform from >=3 reference points and reports per-point + overall error in meters.
     * Never guesses a calibration when points are missing or degenerate — those cases return fitted=false with
     * an explanatory message instead of a usable calibration. */
    public static FitResult fit(List<Point> points) {
        FitResult r = new FitResult();
        if (points == null || points.size() < 3) {
            r.message = "تحتاج 3 نقاط مرجعية على الأقل لإجراء المعايرة"; return r;
        }
        double lat0 = 0, lng0 = 0;
        for (Point p : points) { lat0 += p.lat; lng0 += p.lng; }
        int n = points.size();
        lat0 /= n; lng0 /= n;
        double mPerLng = mPerDegLng(lat0);

        double[] xm = new double[n], ym = new double[n];
        for (int i = 0; i < n; i++) {
            Point p = points.get(i);
            xm[i] = (p.lng - lng0) * mPerLng;
            ym[i] = (p.lat - lat0) * M_PER_DEG_LAT;
        }

        double zMeanRe = 0, zMeanIm = 0, wMeanRe = 0, wMeanIm = 0;
        for (int i = 0; i < n; i++) { zMeanRe += xm[i]; zMeanIm += ym[i]; wMeanRe += points.get(i).fracX; wMeanIm += points.get(i).fracY; }
        zMeanRe /= n; zMeanIm /= n; wMeanRe /= n; wMeanIm /= n;

        double numRe = 0, numIm = 0, den = 0;
        for (int i = 0; i < n; i++) {
            double zRe = xm[i] - zMeanRe, zIm = ym[i] - zMeanIm;
            double wRe = points.get(i).fracX - wMeanRe, wIm = points.get(i).fracY - wMeanIm;
            numRe += zRe * wRe + zIm * wIm;
            numIm += zRe * wIm - zIm * wRe;
            den += zRe * zRe + zIm * zIm;
        }
        if (den <= 1e-9) { r.message = "النقاط المرجعية متقاربة جداً جغرافياً — لا يمكن حساب معايرة موثوقة، اختر نقاطاً موزعة بشكل أوسع"; return r; }

        double aRe = numRe / den, aIm = numIm / den;
        double bRe = wMeanRe - (aRe * zMeanRe - aIm * zMeanIm);
        double bIm = wMeanIm - (aRe * zMeanIm + aIm * zMeanRe);
        MapCalibration calib = new MapCalibration(lat0, lng0, aRe, aIm, bRe, bIm);

        double[] errors = new double[n];
        double sumSq = 0, maxErr = 0;
        for (int i = 0; i < n; i++) {
            double[] predictedMeters = calib.fractionToMeters(points.get(i).fracX, points.get(i).fracY);
            double dx = xm[i] - predictedMeters[0], dy = ym[i] - predictedMeters[1];
            double err = Math.sqrt(dx * dx + dy * dy);
            errors[i] = err; sumSq += err * err; maxErr = Math.max(maxErr, err);
        }
        double overall = Math.sqrt(sumSq / n);

        r.fitted = true; r.calibration = calib; r.perPointErrorMeters = errors; r.overallErrorMeters = overall;
        r.reliable = overall <= OVERALL_WARNING_M && maxErr <= POINT_WARNING_M * 1.6;
        r.message = r.reliable
                ? "معايرة موثوقة — خطأ عام " + Math.round(overall) + " م"
                : "خطأ المعايرة كبير (عام " + Math.round(overall) + " م، أقصى نقطة " + Math.round(maxErr) + " م) — راجع النقاط قبل الاعتماد";
        return r;
    }

    private double[] fractionToMeters(double fracX, double fracY) {
        double wRe = fracX - bRe, wIm = fracY - bIm;
        double denom = aRe * aRe + aIm * aIm;
        double zRe = (aRe * wRe + aIm * wIm) / denom;
        double zIm = (aRe * wIm - aIm * wRe) / denom;
        return new double[]{zRe, zIm};
    }

    /** Real GPS -> fractional position (0..1) on the currently-fitted calibration raster. */
    public double[] geoToFraction(double lat, double lng) {
        double mPerLng = mPerDegLng(lat0);
        double xm = (lng - lng0) * mPerLng, ym = (lat - lat0) * M_PER_DEG_LAT;
        double fracX = aRe * xm - aIm * ym + bRe;
        double fracY = aIm * xm + aRe * ym + bIm;
        return new double[]{fracX, fracY};
    }

    /** Fractional position (0..1) on the raster -> real GPS. Used only for calibration-point entry ("tap the
     * map to set this reference point's pixel") — never to invent a beneficiary's location. */
    public double[] fractionToGeo(double fracX, double fracY) {
        double[] m = fractionToMeters(fracX, fracY);
        double mPerLng = mPerDegLng(lat0);
        double lat = lat0 + m[1] / M_PER_DEG_LAT;
        double lng = lng0 + m[0] / mPerLng;
        return new double[]{lat, lng};
    }
}
