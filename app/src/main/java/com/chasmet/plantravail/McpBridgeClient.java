package com.chasmet.plantravail;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class McpBridgeClient {
    public interface Callback {
        void onDone(int count);
        void onError(String message);
    }

    private static final String DEVICE_ID = "orsay-main";
    private final Context context;
    private final WorkDatabase database;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public McpBridgeClient(Context context, WorkDatabase database) {
        this.context = context.getApplicationContext();
        this.database = database;
    }

    public void sync(List<Street> streets, Callback callback) {
        executor.execute(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
                String baseUrl = prefs.getString("mcp_url", "");
                if (baseUrl == null || baseUrl.trim().isEmpty()) {
                    callback.onError("adresse du pont MCP non configurée dans Réglages");
                    return;
                }
                baseUrl = baseUrl.trim();
                while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

                prefs.edit().putString("device_id", DEVICE_ID).apply();
                URL url = new URL(baseUrl + "/commands?device_id=" + URLEncoder.encode(DEVICE_ID, "UTF-8"));
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setRequestMethod("GET");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);

                StringBuilder json = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) json.append(line);
                }

                JSONObject root = new JSONObject(json.toString());
                JSONArray commands = root.optJSONArray("commands");
                if (commands == null) {
                    callback.onDone(0);
                    return;
                }

                int count = 0;
                for (int i = 0; i < commands.length(); i++) {
                    JSONObject command = commands.optJSONObject(i);
                    if (command == null) continue;
                    String date = command.optString("date", DayColor.today());
                    JSONArray names = command.optJSONArray("streets");
                    if (names == null) continue;
                    for (int j = 0; j < names.length(); j++) {
                        String requested = names.optString(j, "");
                        String actual = matchStreetName(requested, streets);
                        if (actual != null) {
                            database.addOrUpdate(actual, date, DayColor.forDate(date), "MCP");
                            count++;
                        }
                    }
                }
                callback.onDone(count);
            } catch (Exception e) {
                callback.onError(e.getMessage() == null ? "synchronisation impossible" : e.getMessage());
            }
        });
    }

    static String matchStreetName(String requested, List<Street> streets) {
        String wanted = normalize(requested);
        if (wanted.isEmpty()) return null;
        String partial = null;
        for (Street street : streets) {
            String candidate = normalize(street.getName());
            if (candidate.equals(wanted)) return street.getName();
            if (candidate.contains(wanted) || wanted.contains(candidate)) partial = street.getName();
        }
        return partial;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.FRANCE)
                .replace("avenue", "")
                .replace("boulevard", "")
                .replace("rue", "")
                .replace("chemin", "")
                .replace("allee", "")
                .replace("allée", "")
                .replace("-", " ")
                .replace("'", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
