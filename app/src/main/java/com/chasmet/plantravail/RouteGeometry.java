package com.chasmet.plantravail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.osmdroid.util.GeoPoint;

/** One percentage applies to the whole named route, never separately to each way. */
public final class RouteGeometry {
  private RouteGeometry() {}

  public static double meters(GeoPoint a, GeoPoint b) {
    double cos = Math.cos(Math.toRadians((a.getLatitude() + b.getLatitude()) / 2));
    return Math.hypot(
            (a.getLongitude() - b.getLongitude()) * cos, a.getLatitude() - b.getLatitude())
        * 111195;
  }

  public static List<List<GeoPoint>> ordered(List<Street> streets, String name) {
    List<List<GeoPoint>> pending = new ArrayList<>(), out = new ArrayList<>();
    for (Street s : streets) if (s.getName().equals(name)) pending.add(s.getPoints());
    GeoPoint end = null;
    while (!pending.isEmpty()) {
      int choice = 0;
      boolean reverse = false;
      double best = Double.MAX_VALUE;
      for (int i = 0; i < pending.size(); i++) {
        List<GeoPoint> p = pending.get(i);
        for (int side = 0; side < 2; side++) {
          GeoPoint q = p.get(side == 0 ? 0 : p.size() - 1);
          double value = end == null ? q.getLatitude() * 1000 + q.getLongitude() : meters(end, q);
          if (value < best) {
            best = value;
            choice = i;
            reverse = side == 1;
          }
        }
      }
      List<GeoPoint> selected = pending.remove(choice);
      if (reverse) Collections.reverse(selected);
      out.add(selected);
      end = selected.get(selected.size() - 1);
    }
    return out;
  }

  public static List<List<GeoPoint>> slice(List<List<GeoPoint>> paths, int from, int to) {
    List<List<GeoPoint>> out = new ArrayList<>();
    if (to <= from) return out;
    double total = 0;
    for (List<GeoPoint> path : paths)
      for (int i = 1; i < path.size(); i++) total += meters(path.get(i - 1), path.get(i));
    double start = total * from / 100.0, finish = total * to / 100.0, walk = 0;
    for (List<GeoPoint> path : paths) {
      List<GeoPoint> part = new ArrayList<>();
      for (int i = 1; i < path.size(); i++) {
        GeoPoint a = path.get(i - 1), b = path.get(i);
        double length = meters(a, b), next = walk + length;
        if (length > 0 && next > start && walk < finish) {
          part.add(interpolate(a, b, Math.max(0, (start - walk) / length)));
          part.add(interpolate(a, b, Math.min(1, (finish - walk) / length)));
        }
        walk = next;
      }
      if (part.size() > 1) out.add(part);
    }
    return out;
  }

  private static GeoPoint interpolate(GeoPoint a, GeoPoint b, double t) {
    return new GeoPoint(
        a.getLatitude() + (b.getLatitude() - a.getLatitude()) * t,
        a.getLongitude() + (b.getLongitude() - a.getLongitude()) * t);
  }

  public static double distanceTo(GeoPoint p, List<GeoPoint> path) {
    double best = Double.MAX_VALUE, cos = Math.cos(Math.toRadians(p.getLatitude()));
    for (int i = 1; i < path.size(); i++) {
      GeoPoint a = path.get(i - 1), b = path.get(i);
      double ax = (a.getLongitude() - p.getLongitude()) * cos,
          ay = a.getLatitude() - p.getLatitude();
      double dx = (b.getLongitude() - a.getLongitude()) * cos,
          dy = b.getLatitude() - a.getLatitude();
      double length = dx * dx + dy * dy,
          t = length == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / length));
      best = Math.min(best, Math.hypot(ax + t * dx, ay + t * dy) * 111195);
    }
    return best;
  }
}
