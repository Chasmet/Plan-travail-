package com.chasmet.plantravail;

import android.content.Context;
import java.util.*;
import org.json.*;
import org.osmdroid.util.GeoPoint;

final class ManualTraceStore {
  static final class Trace {
    final String id, date;
    final int color;
    final List<GeoPoint> points;
    final List<List<GeoPoint>> parts;

    Trace(JSONObject o) throws Exception {
      id = o.getString("id");
      date = o.getString("date");
      color = DayColor.forDate(date);
      JSONArray paths = o.optJSONArray("parts");
      if (paths == null) paths = new JSONArray().put(o.getJSONArray("points"));
      parts = new ArrayList<>();
      for (int i = 0; i < paths.length(); i++) {
        JSONArray p = paths.getJSONArray(i);
        List<GeoPoint> line = new ArrayList<>();
        for (int j = 0; j < p.length(); j++) {
          JSONArray xy = p.getJSONArray(j);
          line.add(new GeoPoint(xy.getDouble(0), xy.getDouble(1)));
        }
        parts.add(line);
      }
      points = parts.get(0);
    }
  }

  private final WorkDatabase db;

  ManualTraceStore(Context context) {
    db = WorkDatabase.getInstance(context);
  }

  List<Trace> all() {
    return read(null);
  }

  List<Trace> forWeek(String date) {
    return read(date);
  }

  private List<Trace> read(String date) {
    try {
      JSONArray data = db.traceRows(date);
      List<Trace> out = new ArrayList<>();
      for (int i = 0; i < data.length(); i++) out.add(new Trace(data.getJSONObject(i)));
      return out;
    } catch (Exception e) {
      throw new IllegalStateException("Impossible de lire les dessins", e);
    }
  }

  Trace save(String id, String date, List<GeoPoint> points) {
    return saveParts(id, date, Collections.singletonList(points));
  }

  Trace saveParts(String id, String date, List<List<GeoPoint>> paths) {
    try {
      JSONObject value = json(id, date, paths);
      db.writeTrace(value, null);
      return new Trace(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  static JSONObject json(String id, String date, List<List<GeoPoint>> paths) throws Exception {
    JSONArray parts = new JSONArray();
    for (List<GeoPoint> path : paths) {
      JSONArray p = new JSONArray();
      for (GeoPoint point : path)
        p.put(new JSONArray().put(point.getLatitude()).put(point.getLongitude()));
      parts.put(p);
    }
    JSONObject out =
        new JSONObject()
            .put("id", id == null ? UUID.randomUUID().toString() : id)
            .put("date", date)
            .put("color", DayColor.forDate(date))
            .put("parts", parts);
    validate(out);
    return out;
  }

  void delete(String id) {
    try {
      db.writeTrace(null, id);
    } catch (Exception e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  static void validate(JSONObject value) throws Exception {
    if (!value.getString("id").matches("[A-Za-z0-9_.:-]{1,128}"))
      throw new IllegalArgumentException("Identifiant du dessin invalide");
    DayColor.requireDate(value.getString("date"));
    JSONArray parts = value.optJSONArray("parts");
    if (parts == null) parts = new JSONArray().put(value.getJSONArray("points"));
    if (parts.length() < 1 || parts.length() > 200)
      throw new IllegalArgumentException("Dessin vide ou trop long");
    int total = 0;
    for (int i = 0; i < parts.length(); i++) {
      JSONArray points = parts.getJSONArray(i);
      if (points.length() < 2)
        throw new IllegalArgumentException("Chaque trait nécessite deux points");
      total += points.length();
      if (total > 20000) throw new IllegalArgumentException("Dessin trop détaillé");
      for (int j = 0; j < points.length(); j++) {
        JSONArray p = points.getJSONArray(j);
        double lat = p.getDouble(0), lon = p.getDouble(1);
        if (Double.isNaN(lat)
            || Double.isInfinite(lat)
            || Double.isNaN(lon)
            || Double.isInfinite(lon)
            || lat < -90
            || lat > 90
            || lon < -180
            || lon > 180) throw new IllegalArgumentException("Coordonnées invalides");
      }
    }
  }
}
