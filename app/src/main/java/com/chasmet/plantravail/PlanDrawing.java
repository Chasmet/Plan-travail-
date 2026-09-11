package com.chasmet.plantravail;

import android.graphics.*;
import java.util.*;
import org.osmdroid.util.GeoPoint;

/** One drawing for the PNG and PDF, independent of the displayed map/filter. */
final class PlanDrawing {
  static final int WIDTH = 595, HEIGHT = 842;
  private final List<Street> streets;
  private final List<GeoPoint> boundary;
  private final List<String[]> entries;
  private final String week;
  private final Map<String, Integer> progress = new HashMap<>();

  PlanDrawing(List<Street> streets, List<GeoPoint> boundary, List<String[]> entries, String week) {
    this.streets = streets;
    this.boundary = boundary;
    this.entries = entries;
    this.week = week;
    for (String[] r : entries) progress.put(r[0], Integer.parseInt(r[4]));
  }

  void draw(Canvas c) {
    c.drawColor(Color.WHITE);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(Color.BLACK);
    p.setTextSize(17);
    p.setFakeBoldText(true);
    c.drawText("Plan Travail Orsay — semaine du " + week, 24, 32, p);
    p.setFakeBoldText(false);
    p.setTextSize(10);
    int completed = 0;
    for (int value : progress.values()) if (value == 100) completed++;
    c.drawText(
        progress.size()
            + " rues commencées • "
            + completed
            + " terminées • avancement estimé par longueur",
        24,
        51,
        p);
    String[] days = {"Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim"};
    for (int i = 0; i < 7; i++) {
      float x = 24 + i * 77;
      p.setColor(DayColor.forDate(DayColor.shift(week, i)));
      c.drawRect(x, 66, x + 9, 75, p);
      p.setColor(Color.BLACK);
      c.drawText(days[i], x + 14, 75, p);
    }
    RectF frame = new RectF(24, 96, 571, 788);
    Projection projection = new Projection(boundary, frame);
    Path outline = path(boundary, projection);
    outline.close();
    c.save();
    c.clipRect(frame);
    p.setColor(Color.rgb(246, 248, 244));
    p.setStyle(Paint.Style.FILL);
    c.drawPath(outline, p);
    c.save();
    c.clipPath(outline);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);
    List<String> names = StreetResolver.names(streets);
    for (String name : names) {
      List<List<GeoPoint>> paths = RouteGeometry.ordered(streets, name);
      p.setColor(Color.rgb(184, 190, 195));
      p.setStrokeWidth(.7f);
      for (List<GeoPoint> line : paths) c.drawPath(path(line, projection), p);
      int current = progress.containsKey(name) ? progress.get(name) : 0, previous = 0;
      for (String[] row : entries) {
        if (!name.equals(row[0])) continue;
        int end = Math.min(current, Integer.parseInt(row[4]));
        if (end <= previous) continue;
        p.setColor(Integer.parseInt(row[2]));
        p.setStrokeWidth(2.5f);
        for (List<GeoPoint> part : RouteGeometry.slice(paths, previous, end))
          c.drawPath(path(part, projection), p);
        previous = end;
      }
    }
    c.restore();
    // Labels may extend across the boundary so street names remain readable.
    // Label the longest way of each street; avoid unreadable overlapping labels.
    List<RectF> occupied = new ArrayList<>();
    p.setTextSize(5.3f);
    p.setFakeBoldText(false);
    for (String name : names) {
      List<GeoPoint> longest = null;
      double length = 0;
      for (Street street : streets)
        if (name.equals(street.getName())) {
          double n = 0;
          List<GeoPoint> points = street.getPoints();
          for (int i = 1; i < points.size(); i++)
            n += RouteGeometry.meters(points.get(i - 1), points.get(i));
          if (n > length) {
            length = n;
            longest = points;
          }
        }
      if (longest == null || length < 60) continue;
      GeoPoint point = longest.get(longest.size() / 2);
      float x = projection.x(point), y = projection.y(point);
      float textWidth = p.measureText(name);
      RectF label = new RectF(x - textWidth / 2 - 1, y - 6, x + textWidth / 2 + 1, y + 2);
      boolean overlap = !frame.contains(label);
      for (RectF other : occupied)
        if (RectF.intersects(label, other)) {
          overlap = true;
          break;
        }
      if (overlap) continue;
      occupied.add(label);
      p.setColor(Color.WHITE);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.8f);
      c.drawText(name, label.left + 1, y, p);
      p.setStyle(Paint.Style.FILL);
      p.setColor(Color.rgb(37, 45, 53));
      c.drawText(name, label.left + 1, y, p);
    }
    p.setStyle(Paint.Style.STROKE);
    p.setColor(Color.rgb(33, 89, 151));
    p.setStrokeWidth(1.3f);
    c.drawPath(outline, p);
    c.restore();
    p.setStyle(Paint.Style.FILL);
    p.setColor(Color.DKGRAY);
    p.setTextSize(8);
    c.drawText(
        "Rues : © OpenStreetMap contributors (ODbL) • Contour : geo.api.gouv.fr", 24, 810, p);
    c.drawText(
        "Plan de toutes les rues • sans dépendance aux tuiles • détails et lexique dans le PDF",
        24,
        824,
        p);
  }

  private static Path path(List<GeoPoint> points, Projection projection) {
    Path path = new Path();
    for (int i = 0; i < points.size(); i++) {
      GeoPoint p = points.get(i);
      if (i == 0) path.moveTo(projection.x(p), projection.y(p));
      else path.lineTo(projection.x(p), projection.y(p));
    }
    return path;
  }

  static final class Projection {
    final double west, north, scale;
    final float left, top;
    private static final double COS = Math.cos(Math.toRadians(48.70));

    Projection(List<GeoPoint> points, RectF frame) {
      double n = -90, s = 90, e = -180, w = 180;
      for (GeoPoint p : points) {
        n = Math.max(n, p.getLatitude());
        s = Math.min(s, p.getLatitude());
        e = Math.max(e, p.getLongitude());
        w = Math.min(w, p.getLongitude());
      }
      if (n <= s || e <= w) throw new IllegalArgumentException("Contour sans surface");
      west = w;
      north = n;
      double width = (e - w) * COS, height = n - s;
      scale = Math.min((frame.width() - 12) / width, (frame.height() - 12) / height);
      left = frame.left + (float) ((frame.width() - width * scale) / 2);
      top = frame.top + (float) ((frame.height() - height * scale) / 2);
    }

    float x(GeoPoint p) {
      return left + (float) ((p.getLongitude() - west) * COS * scale);
    }

    float y(GeoPoint p) {
      return top + (float) ((north - p.getLatitude()) * scale);
    }
  }
}
