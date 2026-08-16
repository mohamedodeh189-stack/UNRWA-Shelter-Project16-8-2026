package org.unrwa.yarmoukfield;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sector boundary polygons for the "حدود القطاعات" operational-map layer, digitized directly from the
 * calibrated camp-plan raster (yarmouk_camp_calibrated.png) — each polygon's vertices are stored as 0..1
 * fractions of that same raster's width/height, so they always line up with it regardless of the Bitmap's
 * in-memory scale. This layer does NOT need GPS map calibration to be usable: it is a visual outline of the
 * regions as printed on the plan, independent of MapCalibration. Sector names match AddressClassifier's
 * seven canonical sector values exactly. */
public final class SectorBoundaries {
    public static final class Sector {
        public final String name;
        public final float[] fracX, fracY;
        Sector(String name, float[] fracX, float[] fracY) { this.name = name; this.fracX = fracX; this.fracY = fracY; }
    }

    private static List<Sector> cached;
    private SectorBoundaries() {}

    public static synchronized List<Sector> load(Context context) throws Exception {
        if (cached != null) return cached;
        String json;
        try (InputStream input = context.getAssets().open("sector_boundaries.json"); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            json = output.toString(StandardCharsets.UTF_8.name());
        }
        JSONObject root = new JSONObject(json);
        List<Sector> sectors = new ArrayList<>();
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            JSONArray points = root.getJSONArray(name);
            float[] fx = new float[points.length()], fy = new float[points.length()];
            for (int i = 0; i < points.length(); i++) {
                JSONArray pair = points.getJSONArray(i);
                fx[i] = (float) pair.getDouble(0);
                fy[i] = (float) pair.getDouble(1);
            }
            sectors.add(new Sector(name, fx, fy));
        }
        cached = sectors;
        return sectors;
    }

    /** Point-in-polygon (ray casting) in fraction space — used to find which sector a tap landed in. */
    public static boolean contains(Sector sector, float fracX, float fracY) {
        boolean inside = false;
        int n = sector.fracX.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = sector.fracX[i], yi = sector.fracY[i];
            float xj = sector.fracX[j], yj = sector.fracY[j];
            boolean intersects = ((yi > fracY) != (yj > fracY)) &&
                    (fracX < (xj - xi) * (fracY - yi) / (yj - yi) + xi);
            if (intersects) inside = !inside;
        }
        return inside;
    }
}
