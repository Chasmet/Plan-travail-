package com.chasmet.plantravail;

import android.content.Context;
import java.io.InputStream;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.json.*;

/** Immutable, projected local OSM geometry shared by the map and exports. */
final class VectorMapData {
  private static volatile VectorMapData cached;
  final List<Feature> features;
  final String timestamp;

  static final class Feature {
    final String kind, type, name;
    final double[] x, y;
    final double west, east, north, south;

    Feature(JSONObject o) throws Exception {
      kind = o.getString("kind");
      type = o.optString("type");
      name = o.optString("name");
      JSONArray points = o.getJSONArray("points");
      x = new double[points.length()];
      y = new double[points.length()];
      double w = 1, e = 0, n = 1, s = 0;
      for (int i = 0; i < x.length; i++) {
        JSONArray point = points.getJSONArray(i);
        x[i] = worldX(point.getDouble(1));
        y[i] = worldY(point.getDouble(0));
        w = Math.min(w, x[i]);
        e = Math.max(e, x[i]);
        n = Math.min(n, y[i]);
        s = Math.max(s, y[i]);
      }
      west = w;
      east = e;
      north = n;
      south = s;
    }

    int order() {
      switch (kind) {
        case "land":
          return 0;
        case "wood":
          return 1;
        case "green":
          return 2;
        case "water":
        case "waterline":
          return 3;
        case "building":
          return 4;
        case "road":
          return 5;
        default:
          return 6;
      }
    }
  }

  private VectorMapData(JSONObject root) throws Exception {
    timestamp = root.optString("timestamp");
    List<Feature> list = new ArrayList<>();
    JSONArray array = root.getJSONArray("features");
    for (int i = 0; i < array.length(); i++) list.add(new Feature(array.getJSONObject(i)));
    Collections.sort(list, (a, b) -> Integer.compare(a.order(), b.order()));
    features = Collections.unmodifiableList(list);
  }

  static synchronized VectorMapData load(Context context) throws Exception {
    if (cached == null) {
      try (InputStream in = new GZIPInputStream(context.getAssets().open("orsay_vector.dat"))) {
        cached = new VectorMapData(new JSONObject(MapDataCache.read(in)));
      }
    }
    return cached;
  }

  static double worldX(double longitude) {
    return (longitude + 180) / 360;
  }

  static double worldY(double latitude) {
    double sin = Math.sin(Math.toRadians(latitude));
    return .5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
  }
}
