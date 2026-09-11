package com.chasmet.plantravail;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

public class OrsayBoundaryRepository {
  public interface Callback {
    void onLoaded(List<GeoPoint> boundary, boolean fromCache);

    void onError(String message);
  }

  private final Context context;

  public OrsayBoundaryRepository(Context context) {
    this.context = context.getApplicationContext();
  }

  public void load(boolean forceRefresh, Callback callback) {
    MapDataCache.IO.execute(
        () -> {
          try {
            if (forceRefresh) {
              try {
                String json =
                    MapDataCache.request(
                        "https://geo.api.gouv.fr/communes/91471?geometry=contour&format=geojson",
                        null);
                List<GeoPoint> points = parse(json);
                MapDataCache.save(context, "orsay_boundary.geojson", json);
                callback.onLoaded(points, false);
                return;
              } catch (Exception e) {
                android.util.Log.w("BoundaryRepository", "Contour local utilisé", e);
              }
            }
            callback.onLoaded(
                MapDataCache.local(
                    context, "orsay_boundary.geojson", OrsayBoundaryRepository::parse),
                true);
          } catch (Exception e) {
            callback.onError(e.getMessage());
          }
        });
  }

  static List<GeoPoint> parse(String json) throws Exception {
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
    if (ring == null) throw new IllegalArgumentException("Contour vide");
    for (int i = 0; i < ring.length(); i++) {
      JSONArray point = ring.optJSONArray(i);
      if (point != null && point.length() >= 2) {
        double lat = point.getDouble(1), lon = point.getDouble(0);
        if (Double.isNaN(lat)
            || Double.isInfinite(lat)
            || Double.isNaN(lon)
            || Double.isInfinite(lon)
            || lat < 48.65
            || lat > 48.75
            || lon < 2.1
            || lon > 2.25) throw new IllegalArgumentException("Contour hors d’Orsay");
        result.add(new GeoPoint(lat, lon));
      }
    }
    if (result.size() < 3) throw new IllegalArgumentException("Contour incomplet");
    return result;
  }
}
