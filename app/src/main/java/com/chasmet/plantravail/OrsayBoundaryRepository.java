package com.chasmet.plantravail;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OrsayBoundaryRepository {
    public interface Callback {
        void onLoaded(List<GeoPoint> boundary, boolean fromCache);
        void onError(String message);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final File cacheFile;

    public OrsayBoundaryRepository(Context context) {
        cacheFile = new File(context.getApplicationContext().getFilesDir(), "orsay_boundary.geojson");
    }

    public void load(boolean forceRefresh, Callback callback) {
        executor.execute(() -> {
            try {
                if (!forceRefresh && cacheFile.exists()) {
                    List<GeoPoint> cached = parse(readFile(cacheFile));
                    if (cached.size() >= 3) {
                        callback.onLoaded(cached, true);
                        return;
                    }
                }

                URL url = new URL("https://geo.api.gouv.fr/communes/91471?geometry=contour&format=geojson");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("Accept", "application/geo+json, application/json");
                connection.setRequestProperty("User-Agent", "PlanTravailOrsay/1.0");
                if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                    throw new IllegalStateException("Contour Orsay HTTP " + connection.getResponseCode());
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                String geoJson = sb.toString();
                List<GeoPoint> boundary = parse(geoJson);
                if (boundary.size() < 3) throw new IllegalStateException("Contour d'Orsay invalide");
                writeFile(cacheFile, geoJson);
                callback.onLoaded(boundary, false);
            } catch (Exception e) {
                try {
                    if (cacheFile.exists()) {
                        List<GeoPoint> cached = parse(readFile(cacheFile));
                        if (cached.size() >= 3) {
                            callback.onLoaded(cached, true);
                            return;
                        }
                    }
                } catch (Exception ignored) {}
                callback.onError(e.getMessage() == null ? "Contour d'Orsay indisponible" : e.getMessage());
            }
        });
    }

    private List<GeoPoint> parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject geometry = root.optJSONObject("geometry");
        if (geometry == null && root.optString("type").equalsIgnoreCase("FeatureCollection")) {
            JSONArray features = root.optJSONArray("features");
            if (features != null && features.length() > 0) {
                JSONObject feature = features.optJSONObject(0);
                if (feature != null) geometry = feature.optJSONObject("geometry");
            }
        }
        if (geometry == null) throw new IllegalStateException("Géométrie absente");

        String type = geometry.optString("type", "");
        JSONArray coordinates = geometry.optJSONArray("coordinates");
        if (coordinates == null) throw new IllegalStateException("Coordonnées absentes");

        JSONArray ring;
        if ("Polygon".equals(type)) {
            ring = coordinates.optJSONArray(0);
        } else if ("MultiPolygon".equals(type)) {
            JSONArray polygon = coordinates.optJSONArray(0);
            ring = polygon == null ? null : polygon.optJSONArray(0);
        } else {
            throw new IllegalStateException("Type de contour non pris en charge : " + type);
        }

        List<GeoPoint> result = new ArrayList<>();
        if (ring == null) return result;
        for (int i = 0; i < ring.length(); i++) {
            JSONArray point = ring.optJSONArray(i);
            if (point != null && point.length() >= 2) {
                result.add(new GeoPoint(point.optDouble(1), point.optDouble(0)));
            }
        }
        return result;
    }

    private static String readFile(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static void writeFile(File file, String content) throws Exception {
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write(content);
        }
    }
}
