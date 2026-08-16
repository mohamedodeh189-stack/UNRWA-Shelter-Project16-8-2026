package org.unrwa.yarmoukfield;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Real street geometry from OpenStreetMap (ODbL — free to redistribute with attribution), stored as raw
 * lat/lng — unlike SectorBoundaries this is NOT tied to any one background raster's pixel fractions, so it
 * must be converted through the operational map's current MapCalibration fit before drawing. If that map
 * key has no reliable calibration yet, the caller simply has nothing to convert — this class never invents
 * a fraction position. */
public final class VerifiedStreets {
    public static final class Street {
        public final String name;
        /** One or more line segments, each a list of [lat,lng] pairs in order. */
        public final List<List<double[]>> segments;
        Street(String name, List<List<double[]>> segments) { this.name = name; this.segments = segments; }
    }

    private static List<Street> cached;
    private VerifiedStreets() {}

    public static synchronized List<Street> load(Context context) throws Exception {
        if (cached != null) return cached;
        String json;
        try (InputStream input = context.getAssets().open("verified_streets.json"); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            json = output.toString(StandardCharsets.UTF_8.name());
        }
        JSONArray root = new JSONArray(json);
        List<Street> streets = new ArrayList<>();
        for (int i = 0; i < root.length(); i++) {
            JSONObject o = root.getJSONObject(i);
            String name = o.getString("name");
            JSONArray segmentsJson = o.getJSONArray("segments");
            List<List<double[]>> segments = new ArrayList<>();
            for (int s = 0; s < segmentsJson.length(); s++) {
                JSONArray pts = segmentsJson.getJSONArray(s);
                List<double[]> seg = new ArrayList<>();
                for (int p = 0; p < pts.length(); p++) {
                    JSONArray pair = pts.getJSONArray(p);
                    seg.add(new double[]{pair.getDouble(0), pair.getDouble(1)});
                }
                segments.add(seg);
            }
            streets.add(new Street(name, segments));
        }
        cached = streets;
        return streets;
    }
}
