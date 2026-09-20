package com.chasmet.plantravail;

import android.graphics.*;
import java.util.*;

/** Device-resolution vector drawing. No raster upscaling or tile downloads. */
final class VectorMapRenderer {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();

  void draw(Canvas canvas, VectorMapData data, VectorViewport view, float unit) {
    canvas.drawColor(Color.rgb(247, 248, 245));
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    List<VectorMapData.Feature> roads = new ArrayList<>();
    for (VectorMapData.Feature f : data.features) {
      if (!view.visible(f, 32 * unit)) continue;
      if ("road".equals(f.kind)) {
        roads.add(f);
        continue;
      }
      if ("building".equals(f.kind) && view.zoom < 15) continue;
      path(f, view);
      paint.setStyle(Paint.Style.FILL);
      paint.setPathEffect(null);
      switch (f.kind) {
        case "wood":
          paint.setColor(0xffbdd7b4);
          break;
        case "green":
          paint.setColor(0xffd3e7c5);
          break;
        case "water":
          paint.setColor(0xffa9d6e5);
          break;
        case "land":
          paint.setColor("residential".equals(f.type) ? 0xfff1f1ed : 0xffe9e6e2);
          break;
        case "building":
          paint.setColor(0xffd7dde2);
          break;
        case "waterline":
          paint.setColor(0xff83bbd2);
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(2 * unit);
          break;
        case "rail":
          paint.setColor(0xff88929b);
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(2 * unit);
          paint.setPathEffect(new DashPathEffect(new float[] {5 * unit, 3 * unit}, 0));
          break;
        default:
          continue;
      }
      canvas.drawPath(path, paint);
      if ("building".equals(f.kind) && view.zoom >= 16.5) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.65f * unit);
        paint.setColor(0xffb5bec7);
        canvas.drawPath(path, paint);
      }
    }
    for (int pass = 0; pass < 2; pass++)
      for (VectorMapData.Feature road : roads) {
        path(road, view);
        paint.setPathEffect(null);
        paint.setStyle(Paint.Style.STROKE);
        boolean foot = footway(road.type);
        if (foot && pass == 0) continue;
        float width = roadWidth(road.type, view, unit);
        if (foot) {
          paint.setPathEffect(new DashPathEffect(new float[] {3 * unit, 3 * unit}, 0));
          paint.setColor(0xff9cae8e);
        } else paint.setColor(pass == 0 ? 0xffb8c2c8 : major(road.type) ? 0xffffe5a6 : Color.WHITE);
        paint.setStrokeWidth(width + (pass == 0 ? 1.4f * unit : 0));
        canvas.drawPath(path, paint);
      }
    paint.setPathEffect(null);
    labels(canvas, roads, view, unit);
  }

  private void path(VectorMapData.Feature f, VectorViewport v) {
    path.rewind();
    for (int i = 0; i < f.x.length; i++) {
      if (i == 0) path.moveTo(v.x(f.x[i]), v.y(f.y[i]));
      else path.lineTo(v.x(f.x[i]), v.y(f.y[i]));
    }
    if (!"road".equals(f.kind) && !"rail".equals(f.kind) && !"waterline".equals(f.kind))
      path.close();
  }

  private static boolean major(String t) {
    return t.startsWith("motorway")
        || t.startsWith("trunk")
        || t.startsWith("primary")
        || t.startsWith("secondary")
        || t.startsWith("tertiary");
  }

  private static boolean footway(String t) {
    return "footway".equals(t)
        || "path".equals(t)
        || "steps".equals(t)
        || "cycleway".equals(t)
        || "bridleway".equals(t)
        || "track".equals(t);
  }

  private static float roadWidth(String type, VectorViewport v, float unit) {
    double meters = major(type) ? 8 : "service".equals(type) ? 3 : 5;
    if (footway(type)) return 1.2f * unit;
    return (float)
        Math.max(
            1.4 * unit,
            Math.min(36 * unit, meters * v.scale / (40075016.69 * Math.cos(Math.toRadians(48.7)))));
  }

  private void labels(
      Canvas canvas, List<VectorMapData.Feature> roads, VectorViewport v, float unit) {
    if (v.zoom < 14.7) return;
    Set<String> labelled = new HashSet<>();
    List<RectF> occupied = new ArrayList<>();
    paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    paint.setTextSize((v.zoom < 16 ? 11 : 13) * unit);
    paint.setTextAlign(Paint.Align.CENTER);
    for (VectorMapData.Feature f : roads) {
      if (f.name.isEmpty() || labelled.contains(f.name) || (footway(f.type) && v.zoom < 17))
        continue;
      float best = -1, x = 0, y = 0, angle = 0;
      for (int i = 1; i < f.x.length; i++) {
        float ax = v.x(f.x[i - 1]), ay = v.y(f.y[i - 1]), bx = v.x(f.x[i]), by = v.y(f.y[i]);
        float[] clipped = clippedSegment(ax, ay, bx, by, v.frame);
        if (clipped == null) continue;
        float cx = (clipped[0] + clipped[2]) / 2, cy = (clipped[1] + clipped[3]) / 2;
        float length = (float) Math.hypot(clipped[2] - clipped[0], clipped[3] - clipped[1]);
        if (length > best) {
          best = length;
          x = cx;
          y = cy;
          angle = (float) Math.toDegrees(Math.atan2(by - ay, bx - ax));
        }
      }
      if (best < 0) continue;
      if (angle > 90) angle -= 180;
      if (angle < -90) angle += 180;
      float width = paint.measureText(f.name) + 8 * unit, height = 19 * unit;
      double r = Math.toRadians(angle);
      float w = (float) (Math.abs(Math.cos(r)) * width + Math.abs(Math.sin(r)) * height);
      float h = (float) (Math.abs(Math.sin(r)) * width + Math.abs(Math.cos(r)) * height);
      RectF box = new RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
      if (!v.frame.contains(box)) continue;
      boolean clash = false;
      for (RectF other : occupied)
        if (RectF.intersects(other, box)) {
          clash = true;
          break;
        }
      if (clash) continue;
      occupied.add(box);
      labelled.add(f.name);
      canvas.save();
      canvas.rotate(angle, x, y);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(3.5f * unit);
      paint.setColor(0xf5ffffff);
      canvas.drawText(f.name, x, y + 4 * unit, paint);
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(0xff243849);
      canvas.drawText(f.name, x, y + 4 * unit, paint);
      canvas.restore();
    }
    paint.setTextAlign(Paint.Align.LEFT);
    paint.setTypeface(Typeface.DEFAULT);
  }

  private static float[] clippedSegment(float ax, float ay, float bx, float by, RectF frame) {
    float dx = bx - ax, dy = by - ay, lo = 0, hi = 1;
    float[] p = {-dx, dx, -dy, dy},
        q = {ax - frame.left, frame.right - ax, ay - frame.top, frame.bottom - ay};
    for (int i = 0; i < 4; i++) {
      if (p[i] == 0) {
        if (q[i] < 0) return null;
        continue;
      }
      float t = q[i] / p[i];
      if (p[i] < 0) lo = Math.max(lo, t);
      else hi = Math.min(hi, t);
      if (lo > hi) return null;
    }
    return new float[] {ax + lo * dx, ay + lo * dy, ax + hi * dx, ay + hi * dy};
  }
}
