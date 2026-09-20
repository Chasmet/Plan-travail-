package com.chasmet.plantravail;

import android.graphics.Point;
import android.graphics.RectF;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;

final class VectorViewport {
  final double left, top, scale, zoom;
  final RectF frame;

  VectorViewport(double left, double top, double scale, RectF frame) {
    this.left = left;
    this.top = top;
    this.scale = scale;
    this.frame = frame;
    zoom = Math.log(scale / 256) / Math.log(2);
  }

  static VectorViewport forMap(MapView map) {
    GeoPoint anchor = new GeoPoint(48.7, 2.18);
    Point pixel = map.getProjection().toPixels(anchor, null);
    double size = map.getProjection().getWorldMapSize();
    return new VectorViewport(
        VectorMapData.worldX(anchor.getLongitude()) - pixel.x / size,
        VectorMapData.worldY(anchor.getLatitude()) - pixel.y / size,
        size,
        new RectF(0, 0, map.getWidth(), map.getHeight()));
  }

  static VectorViewport fit(BoundingBox bounds, RectF frame) {
    double w = VectorMapData.worldX(bounds.getLonWest()),
        e = VectorMapData.worldX(bounds.getLonEast());
    double n = VectorMapData.worldY(bounds.getLatNorth()),
        s = VectorMapData.worldY(bounds.getLatSouth());
    double size =
        Math.min(frame.width() / Math.max(1e-10, e - w), frame.height() / Math.max(1e-10, s - n));
    return new VectorViewport(
        (w + e) / 2 - frame.centerX() / size, (n + s) / 2 - frame.centerY() / size, size, frame);
  }

  float x(double world) {
    return (float) ((world - left) * scale);
  }

  float y(double world) {
    return (float) ((world - top) * scale);
  }

  float x(GeoPoint p) {
    return x(VectorMapData.worldX(p.getLongitude()));
  }

  float y(GeoPoint p) {
    return y(VectorMapData.worldY(p.getLatitude()));
  }

  boolean visible(VectorMapData.Feature f, float margin) {
    return x(f.east) >= frame.left - margin
        && x(f.west) <= frame.right + margin
        && y(f.south) >= frame.top - margin
        && y(f.north) <= frame.bottom + margin;
  }
}
