package com.chasmet.plantravail;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

final class ManualTraceStore {
  static final class Trace {
    final String id;
    final String date;
    final int color;
    final List<GeoPoint> points;

    Trace(String id, String date, int color, List<GeoPoint> points) {
      this.id = id;
      this.date = date;
      this.color = color;
      this.points = points;
    }
  }

  private static final String PREFS = "manual_traces";
  private static final String KEY = "items";
  private final SharedPreferences prefs;

  ManualTraceStore(Context context) {
    prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  synchronized List<Trace> all() {
    List<Trace> out = new ArrayList<>();
    try {
      JSONArray a = new JSONArray(prefs.getString(KEY, "[]"));
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.getJSONObject(i);
        JSONArray pts = o.getJSONArray("points");
        List<GeoPoint> points = new ArrayList<>();
        for (int j = 0; j < pts.length(); j++) {
          JSONArray p = pts.getJSONArray(j);
          points.add(new GeoPoint(p.getDouble(0), p.getDouble(1)));
        }
        if (points.size() >= 2)
          out.add(new Trace(o.getString("id"), o.getString("date"), o.getInt("color"), points));
      }
    } catch (Exception ignored) {
    }
    return out;
  }

  synchronized List<Trace> forWeek(String anyDateInWeek) {
    String[] range = DayColor.weekRange(anyDateInWeek);
    List<Trace> out = new ArrayList<>();
    for (Trace t : all()) if (t.date.compareTo(range[0]) >= 0 && t.date.compareTo(range[1]) <= 0) out.add(t);
    return out;
  }

  synchronized Trace save(String editingId, String date, List<GeoPoint> input) {
    if (input == null || input.size() < 2) throw new IllegalArgumentException("Place au moins 2 points");
    DayColor.requireDate(date);
    String id = editingId == null ? UUID.randomUUID().toString() : editingId;
    Trace trace = new Trace(id, date, DayColor.forDate(date), copy(input));
    List<Trace> items = all();
    boolean replaced = false;
    for (int i = 0; i < items.size(); i++) {
      if (items.get(i).id.equals(id)) {
        items.set(i, trace);
        replaced = true;
        break;
      }
    }
    if (!replaced) items.add(trace);
    write(items);
    return trace;
  }

  synchronized void delete(String id) {
    List<Trace> items = all();
    for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).id.equals(id)) items.remove(i);
    write(items);
  }

  private void write(List<Trace> items) {
    JSONArray a = new JSONArray();
    try {
      for (Trace t : items) {
        JSONObject o = new JSONObject();
        o.put("id", t.id);
        o.put("date", t.date);
        o.put("color", t.color);
        JSONArray pts = new JSONArray();
        for (GeoPoint p : t.points) pts.put(new JSONArray().put(p.getLatitude()).put(p.getLongitude()));
        o.put("points", pts);
        a.put(o);
      }
      prefs.edit().putString(KEY, a.toString()).apply();
    } catch (Exception e) {
      throw new IllegalStateException("Impossible d’enregistrer le tracé manuel", e);
    }
  }

  private static List<GeoPoint> copy(List<GeoPoint> src) {
    List<GeoPoint> out = new ArrayList<>();
    for (GeoPoint p : src) out.add(new GeoPoint(p.getLatitude(), p.getLongitude()));
    return out;
  }
}
