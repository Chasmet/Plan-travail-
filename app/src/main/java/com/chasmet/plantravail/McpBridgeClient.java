package com.chasmet.plantravail;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

public class McpBridgeClient {
  public interface Callback {
    void onDone(int count);

    void onError(String message);
  }

  public static final String PUBLIC_BASE_URL =
      "https://sync30-paddle-api.onrender.com/plan-travail";
  public static final String PUBLIC_MCP_URL = PUBLIC_BASE_URL + "/mcp";
  public static final String ACTION_DATA_CHANGED = "com.chasmet.plantravail.DATA_CHANGED";
  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
  private static final AtomicBoolean BUSY = new AtomicBoolean();

  interface Transport {
    String request(String method, String address, JSONObject body) throws Exception;
  }

  private final Context context;
  private final WorkDatabase db;
  private final Transport transport;

  public McpBridgeClient(Context context, WorkDatabase db) {
    this(context, db, McpBridgeClient::request);
  }

  McpBridgeClient(Context context, WorkDatabase db, Transport transport) {
    this.context = context.getApplicationContext();
    this.db = db;
    this.transport = transport;
  }

  public void sync(List<Street> streets, Callback callback) {
    if (!BUSY.compareAndSet(false, true)) {
      callback.onError("Synchronisation déjà en cours");
      return;
    }
    EXECUTOR.execute(
        () -> {
          SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
          int appliedCount = 0;
          String syncError = null;
          try {
            CommandProcessor processor = new CommandProcessor(db);
            int changed = processPending(processor, streets, prefs);
            sendResults();
            postState(processor);
            JSONObject batch =
                new JSONObject(
                    transport.request(
                        "GET", PUBLIC_BASE_URL + "/commands?device_id=orsay-main", null));
            JSONArray commands = batch.optJSONArray("commands");
            if (commands == null)
              throw new IllegalStateException("Réponse du serveur sans liste de commandes");
            for (int i = 0; i < commands.length(); i++)
              db.receiveCommand(commands.getJSONObject(i));
            if (commands.length() > 0) {
              changed += processPending(processor, streets, prefs);
              sendResults();
              postState(processor);
            }
            prefs
                .edit()
                .putBoolean("render_connected", true)
                .putLong("render_last_sync_ms", System.currentTimeMillis())
                .putString("render_last_error", "")
                .putInt("pending_local_commands", db.pendingCount())
                .apply();
            appliedCount = changed;
          } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            prefs
                .edit()
                .putBoolean("render_connected", false)
                .putString("render_last_error", message)
                .putInt("pending_local_commands", db.pendingCount())
                .apply();
            syncError = message;
          } finally {
            BUSY.set(false);
          }
          if (syncError == null) callback.onDone(appliedCount);
          else callback.onError(syncError);
        });
  }

  private int processPending(
      CommandProcessor processor, List<Street> streets, SharedPreferences prefs) throws Exception {
    int changed = 0;
    for (JSONObject command : db.pendingCommands()) {
      JSONObject result = processor.apply(command, streets);
      if (result == null) {
        prefs
            .edit()
            .putString("last_command_status", "Commande conservée : chargement des rues")
            .apply();
        continue;
      }
      changed += result.optInt("changed");
      prefs
          .edit()
          .putString(
              "last_command_status",
              result.optBoolean("success")
                  ? "Enregistré et relu sur Android : "
                      + result.optInt("changed")
                      + " modification(s)"
                  : "Commande refusée : " + result.optString("error"))
          .putLong("last_command_ms", System.currentTimeMillis())
          .apply();
    }
    return changed;
  }

  private void sendResults() throws Exception {
    for (JSONObject result : db.pendingResults()) {
      transport.request("POST", PUBLIC_BASE_URL + "/command-result", result);
      db.acknowledge(result.getString("command_id"));
    }
  }

  private void postState(CommandProcessor processor) throws Exception {
    JSONObject state;
    synchronized (db) {
      state =
          new JSONObject()
              .put("device_id", "orsay-main")
              .put("app_version", BuildConfig.VERSION_NAME)
              .put("week_start", db.getCurrentWeekStart())
              .put("current_week_count", db.getCurrentWeekCount())
              .put("today_count", db.getTodayCount())
              .put("completed_week_count", db.getCompletedCount(DayColor.today()))
              .put("pending_local_commands", db.pendingCount());
      state.put("today_streets", new JSONArray(db.getTodayStreets()));
      JSONArray entries = new JSONArray();
      for (String[] row : db.getCurrentWeekEntriesDetailed())
        entries.put(
            new JSONObject()
                .put("street", row[0])
                .put("date", row[1])
                .put("day", DayColor.dayName(row[1]))
                .put("color", Integer.parseInt(row[2]))
                .put("source", row[3])
                .put("progress", Integer.parseInt(row[4])));
      state.put("current_week_entries", entries).put("lexicon", processor.lexicon());
    }
    transport.request("POST", PUBLIC_BASE_URL + "/device-state", state);
  }

  private static String request(String method, String address, JSONObject body) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
    try {
      c.setConnectTimeout(12000);
      c.setReadTimeout(20000);
      c.setRequestMethod(method);
      c.setRequestProperty("Accept", "application/json");
      c.setRequestProperty("Cache-Control", "no-cache");
      if (body != null) {
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (OutputStream out = c.getOutputStream()) {
          out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
      }
      int code = c.getResponseCode();
      if (code < 200 || code >= 300)
        throw new IllegalStateException(
            method + " " + new URL(address).getPath() + " : HTTP " + code);
      StringBuilder result = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (result.length() > 4 * 1024 * 1024)
            throw new IllegalStateException("Réponse trop volumineuse");
          result.append(line);
        }
      }
      return result.toString();
    } finally {
      c.disconnect();
    }
  }
}
