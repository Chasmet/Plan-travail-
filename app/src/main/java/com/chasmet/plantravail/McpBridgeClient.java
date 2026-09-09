package com.chasmet.plantravail;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

    public static final String PUBLIC_BASE_URL = "https://sync30-paddle-api.onrender.com/plan-travail";
    public static final String PUBLIC_MCP_URL = PUBLIC_BASE_URL + "/mcp";
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
            SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
            try {
                String baseUrl = prefs.getString("mcp_url", PUBLIC_BASE_URL);
                if (baseUrl == null || baseUrl.trim().isEmpty()) baseUrl = PUBLIC_BASE_URL;
                baseUrl = normalizeBase(baseUrl.trim());
                prefs.edit().putString("device_id", DEVICE_ID).putString("mcp_url", baseUrl).apply();

                // Heartbeat d'abord : Render sait immédiatement que le téléphone est réellement en ligne.
                postState(baseUrl);

                URL url = new URL(baseUrl + "/commands?device_id=" + URLEncoder.encode(DEVICE_ID, "UTF-8"));
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Cache-Control", "no-cache");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("GET commandes HTTP " + code);

                StringBuilder json = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) json.append(line);
                }

                JSONObject root = new JSONObject(json.toString());
                JSONArray commands = root.optJSONArray("commands");
                int count = 0;
                if (commands != null) {
                    for (int i = 0; i < commands.length(); i++) {
                        JSONObject command = commands.optJSONObject(i);
                        if (command == null) continue;
                        count += applyCommand(baseUrl, command, streets);
                    }
                }

                // Renvoie l'état final après application pour que ChatGPT voie le résultat réel.
                postState(baseUrl);
                prefs.edit()
                        .putBoolean("render_connected", true)
                        .putLong("render_last_sync_ms", System.currentTimeMillis())
                        .putString("render_last_error", "")
                        .apply();
                callback.onDone(count);
            } catch (Exception e) {
                String message = e.getMessage() == null ? "synchronisation impossible" : e.getMessage();
                prefs.edit()
                        .putBoolean("render_connected", false)
                        .putString("render_last_error", message)
                        .apply();
                callback.onError(message);
            }
        });
    }

    private int applyCommand(String baseUrl, JSONObject command, List<Street> streets) {
        String commandId = command.optString("id", "");
        String action = command.optString("action", "mark_streets");
        JSONObject result = new JSONObject();
        int changed = 0;
        try {
            result.put("device_id", DEVICE_ID);
            result.put("command_id", commandId);
            result.put("action", action);
            JSONArray applied = new JSONArray();

            if ("mark_streets".equals(action)) {
                String date = command.optString("date", DayColor.today());
                JSONArray names = command.optJSONArray("streets");
                if (names == null) throw new IllegalStateException("Aucune rue reçue");
                for (int j = 0; j < names.length(); j++) {
                    String requested = names.optString(j, "").trim();
                    String actual = matchStreetName(requested, streets);
                    if (actual == null || actual.trim().isEmpty()) actual = requested;
                    if (!actual.isEmpty()) {
                        int color = DayColor.forDate(date);
                        database.addOrUpdate(actual, date, color, "MCP Render");
                        JSONObject item = new JSONObject();
                        item.put("street", actual);
                        item.put("date", date);
                        item.put("color", color);
                        applied.put(item);
                        changed++;
                    }
                }
            } else if ("delete_street".equals(action)) {
                String requested = command.optString("street", "").trim();
                String actual = database.findCurrentWeekStreet(requested);
                int deleted = actual == null ? 0 : database.deleteCurrentWeekStreet(actual);
                JSONObject item = new JSONObject();
                item.put("street", actual == null ? requested : actual);
                item.put("deleted", deleted);
                applied.put(item);
                changed += deleted;
            } else if ("add_lexicon".equals(action)) {
                String title = command.optString("title", "").trim();
                String details = command.optString("details", "").trim();
                if (title.isEmpty() || details.isEmpty()) throw new IllegalStateException("Lexique incomplet");
                long id = database.addLexicon(title, details);
                JSONObject item = new JSONObject();
                item.put("id", id);
                item.put("title", title);
                item.put("details", details);
                applied.put(item);
                changed++;
            } else {
                throw new IllegalStateException("Action inconnue : " + action);
            }

            result.put("success", true);
            result.put("changed", changed);
            result.put("applied", applied);
            result.put("finished_at", System.currentTimeMillis());
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage() == null ? "Erreur Android" : e.getMessage());
                result.put("finished_at", System.currentTimeMillis());
            } catch (Exception ignored) {}
        }
        try { postJson(baseUrl + "/command-result", result); } catch (Exception ignored) {}
        return changed;
    }

    private void postState(String baseUrl) throws Exception {
        JSONObject state = new JSONObject();
        state.put("device_id", DEVICE_ID);
        state.put("app_version", BuildConfig.VERSION_NAME);
        state.put("week_start", database.getCurrentWeekStart());
        state.put("current_week_count", database.getCurrentWeekCount());
        state.put("today_count", database.getTodayCount());

        JSONArray today = new JSONArray();
        for (String street : database.getTodayStreets()) today.put(street);
        state.put("today_streets", today);

        JSONArray week = new JSONArray();
        for (String[] row : database.getCurrentWeekEntriesDetailed()) {
            JSONObject item = new JSONObject();
            item.put("street", row[0]);
            item.put("date", row[1]);
            item.put("color", Integer.parseInt(row[2]));
            item.put("day", dayName(row[1]));
            item.put("source", row[3]);
            week.put(item);
        }
        state.put("current_week_entries", week);

        JSONArray lexicon = new JSONArray();
        for (String[] row : database.getLexicon()) {
            JSONObject item = new JSONObject();
            item.put("id", row[0]);
            item.put("title", row[1]);
            item.put("details", row[2]);
            item.put("created_at", row[3]);
            lexicon.put(item);
        }
        state.put("lexicon", lexicon);
        postJson(baseUrl + "/device-state", state);
    }

    private static void postJson(String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json");
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) { os.write(data); }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("POST HTTP " + code);
        c.disconnect();
    }

    private static String normalizeBase(String value) {
        String base = value;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/mcp")) base = base.substring(0, base.length() - 4);
        return base;
    }

    private static String dayName(String date) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE);
            java.util.Calendar c = java.util.Calendar.getInstance(Locale.FRANCE);
            c.setTime(f.parse(date));
            String[] names = {"Dimanche","Lundi","Mardi","Mercredi","Jeudi","Vendredi","Samedi"};
            return names[c.get(java.util.Calendar.DAY_OF_WEEK) - 1];
        } catch (Exception e) { return ""; }
    }

    static String matchStreetName(String requested, List<Street> streets) {
        String wanted = normalize(requested);
        if (wanted.isEmpty()) return null;
        if (streets == null || streets.isEmpty()) return requested == null ? null : requested.trim();
        String partial = null;
        for (Street street : streets) {
            String candidate = normalize(street.getName());
            if (candidate.equals(wanted)) return street.getName();
            if (candidate.contains(wanted) || wanted.contains(candidate)) partial = street.getName();
        }
        return partial == null ? requested.trim() : partial;
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
