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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StreetRepository {
    public interface Callback {
        void onLoaded(List<Street> streets, boolean fromCache);
        void onError(String message);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final File cacheFile;

    public StreetRepository(Context context) {
        this.context = context.getApplicationContext();
        this.cacheFile = new File(this.context.getFilesDir(), "orsay_streets.json");
    }

    public void load(boolean forceRefresh, Callback callback) {
        executor.execute(() -> {
            try {
                if (!forceRefresh && cacheFile.exists()) {
                    String cached = readFile(cacheFile);
                    List<Street> streets = parse(cached);
                    if (!streets.isEmpty()) {
                        callback.onLoaded(streets, true);
                        return;
                    }
                }
                String json = downloadOverpass();
                writeFile(cacheFile, json);
                callback.onLoaded(parse(json), false);
            } catch (Exception e) {
                try {
                    if (cacheFile.exists()) {
                        callback.onLoaded(parse(readFile(cacheFile)), true);
                        return;
                    }
                } catch (Exception ignored) {}
                callback.onError(e.getMessage() == null ? "Impossible de charger les rues" : e.getMessage());
            }
        });
    }

    private String downloadOverpass() throws Exception {
        String query = "[out:json][timeout:30];area[\"name\"=\"Orsay\"][\"boundary\"=\"administrative\"]->.a;(way[\"highway\"][\"name\"](area.a););out geom;";
        Exception last = null;
        String[] endpoints = {
                "https://overpass-api.de/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter"
        };
        for (String endpoint : endpoints) {
            try {
                URL url = new URL(endpoint);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(40000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                byte[] body = ("data=" + URLEncoder.encode(query, "UTF-8")).getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                }
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("Overpass HTTP " + code);
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                }
                return sb.toString();
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new IllegalStateException("Overpass indisponible") : last;
    }

    private List<Street> parse(String json) throws Exception {
        List<Street> result = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray elements = root.optJSONArray("elements");
        if (elements == null) return result;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            if (element == null) continue;
            JSONObject tags = element.optJSONObject("tags");
            JSONArray geometry = element.optJSONArray("geometry");
            if (tags == null || geometry == null) continue;
            String name = tags.optString("name", "").trim();
            if (name.isEmpty()) continue;
            List<GeoPoint> points = new ArrayList<>();
            for (int j = 0; j < geometry.length(); j++) {
                JSONObject p = geometry.optJSONObject(j);
                if (p != null && p.has("lat") && p.has("lon")) {
                    points.add(new GeoPoint(p.optDouble("lat"), p.optDouble("lon")));
                }
            }
            if (points.size() >= 2) result.add(new Street(name, points));
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
