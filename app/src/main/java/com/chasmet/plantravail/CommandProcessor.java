package com.chasmet.plantravail;

import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable, atomic application of an inbox command. No network or UI work. */
public final class CommandProcessor {
  private final WorkDatabase db;

  public CommandProcessor(WorkDatabase db) {
    this.db = db;
  }

  public JSONObject apply(JSONObject command, List<Street> streets) throws Exception {
    synchronized (db) {
      String id = command.getString("id");
      JSONObject old = db.commandResult(id);
      if (old != null) return old;
      String action = command.optString("action", "mark_streets");
      boolean marking = "mark_streets".equals(action) || "mark_street_progress".equals(action);
      if (marking && (streets == null || streets.isEmpty())) return null;
      JSONObject result =
          new JSONObject()
              .put("device_id", "orsay-main")
              .put("command_id", id)
              .put("action", action);
      SQLiteDatabase sql = db.getWritableDatabase();
      sql.beginTransaction();
      try {
        JSONArray applied = new JSONArray();
        int changed = 0;
        if (marking) {
          String date = command.has("date") ? command.getString("date") : DayColor.today();
          DayColor.requireDate(date);
          Object raw = command.has("progress") ? command.get("progress") : 100;
          if (!(raw instanceof Number) || ((Number) raw).doubleValue() != ((Number) raw).intValue())
            throw new IllegalArgumentException("Pourcentage entier requis");
          int progress = DayColor.requireProgress(((Number) raw).intValue());
          JSONArray requested = command.optJSONArray("streets");
          if (requested == null) requested = new JSONArray().put(command.optString("street", ""));
          if (requested.length() == 0) throw new IllegalArgumentException("Aucune rue reçue");
          List<String> actual = new ArrayList<>();
          for (int i = 0; i < requested.length(); i++) {
            String name = StreetResolver.resolve(requested.getString(i), streets);
            if (!actual.contains(name)) actual.add(name);
          }
          for (String name : actual) {
            if (progress == 0) db.deleteWeekStreet(name, date);
            else db.addOrUpdate(name, date, DayColor.forDate(date), "MCP Render", progress);
            String[] row = db.getEntry(name, date);
            if (progress > 0 && (row == null || Integer.parseInt(row[4]) != progress))
              throw new IllegalStateException("Rue non relue après écriture : " + name);
            applied.put(
                new JSONObject()
                    .put("street", name)
                    .put("date", date)
                    .put("day", DayColor.dayName(date))
                    .put("color", DayColor.forDate(date))
                    .put("source", "MCP Render")
                    .put("progress", progress)
                    .put("verified", true));
            changed++;
          }
        } else if ("delete_street".equals(action)) {
          String requested = command.getString("street"),
              actual = db.findCurrentWeekStreet(requested);
          if (actual == null)
            throw new IllegalArgumentException("Tracé introuvable ou nom ambigu : " + requested);
          int n = db.deleteCurrentWeekStreet(actual);
          applied.put(
              new JSONObject().put("street", actual).put("deleted", n).put("verified", true));
          changed = n;
        } else if ("add_lexicon".equals(action)
            || "add_lexicon_note".equals(action)
            || "virtual_keyboard_lexicon".equals(action)) {
          String title = command.optString("title", command.optString("text", "")).trim(),
              details = command.optString("details", title).trim();
          if (title.isEmpty()) title = details;
          if (details.isEmpty()) details = title;
          long note = db.addLexicon(title, details);
          String[] row = db.findLexiconById(note);
          if (row == null || !title.equals(row[1]) || !details.equals(row[2]))
            throw new IllegalStateException("Lexique non relu après écriture");
          applied.put(
              new JSONObject()
                  .put("id", note)
                  .put("title", row[1])
                  .put("details", row[2])
                  .put("verified", true));
          changed = 1;
        } else if ("reset_week".equals(action)) {
          changed = db.clearCurrentWeek();
          applied.put(
              new JSONObject()
                  .put("deleted", changed)
                  .put("lexicon_preserved", true)
                  .put("week_start", db.getCurrentWeekStart()));
        } else throw new IllegalArgumentException("Action inconnue : " + action);
        result
            .put("success", true)
            .put("changed", changed)
            .put("applied", applied)
            .put("lexicon", lexicon())
            .put("finished_at", System.currentTimeMillis());
        db.saveResult(id, result);
        sql.setTransactionSuccessful();
      } catch (Exception e) {
        result =
            new JSONObject()
                .put("device_id", "orsay-main")
                .put("command_id", id)
                .put("action", action)
                .put("success", false)
                .put("changed", 0)
                .put(
                    "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                .put("finished_at", System.currentTimeMillis());
      } finally {
        sql.endTransaction();
      }
      if (!result.optBoolean("success")) db.saveResult(id, result);
      else db.notifyChanged();
      return result;
    }
  }

  public JSONArray lexicon() throws Exception {
    JSONArray out = new JSONArray();
    for (String[] row : db.getLexicon())
      out.put(
          new JSONObject()
              .put("id", row[0])
              .put("title", row[1])
              .put("details", row[2])
              .put("created_at", row[3]));
    return out;
  }
}
