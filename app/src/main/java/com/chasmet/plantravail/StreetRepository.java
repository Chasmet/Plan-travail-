package com.chasmet.plantravail;

import android.content.Context;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

public class StreetRepository {
  public interface Callback {
    void onLoaded(List<Street> streets, boolean fromCache);

    void onError(String message);
  }

  private final Context context;

  public StreetRepository(Context context) {
    this.context = context.getApplicationContext();
  }

  public void load(boolean forceRefresh, Callback callback) {
    MapDataCache.IO.execute(
        () -> {
          try {
            if (forceRefresh) {
              try {
                String query =
                    "[out:json][timeout:30];area[\"ref:INSEE\"=\"91471\"][\"boundary\"=\"administrative\"]->.a;way[\"highway\"][\"name\"](area.a);out"
                        + " geom;";
                String json =
                    MapDataCache.request(
                        "https://overpass-api.de/api/interpreter",
                        ("data=" + URLEncoder.encode(query, "UTF-8"))
                            .getBytes(StandardCharsets.UTF_8));
                List<Street> streets = parse(json);
                MapDataCache.save(context, "orsay_streets.json", json);
                callback.onLoaded(streets, false);
                return;
              } catch (Exception e) {
                android.util.Log.w(
                    "StreetRepository", "Actualisation indisponible, catalogue local utilisé", e);
              }
            }
            callback.onLoaded(
                MapDataCache.local(context, "orsay_streets.json", StreetRepository::parse), true);
          } catch (Exception e) {
            callback.onError(e.getMessage());
          }
        });
  }

  static List<Street> parse(String json) throws Exception {
    List<Street> result = new ArrayList<>();
    JSONObject root = new JSONObject(json);
    JSONArray elements = root.optJSONArray("elements");
    if (elements == null) throw new IllegalArgumentException("Catalogue de rues invalide");
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
          double lat = p.getDouble("lat"), lon = p.getDouble("lon");
          if (Double.isNaN(lat)
              || Double.isInfinite(lat)
              || Double.isNaN(lon)
              || Double.isInfinite(lon)
              || lat < 48.65
              || lat > 48.75
              || lon < 2.1
              || lon > 2.25) throw new IllegalArgumentException("Coordonnée hors d’Orsay");
          points.add(new GeoPoint(lat, lon));
        }
      }
      if (points.size() >= 2) result.add(new Street(name, points));
    }
    if (result.isEmpty()) throw new IllegalArgumentException("Catalogue vide");
    return result;
  }
}
