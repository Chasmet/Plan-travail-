package com.chasmet.plantravail;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.util.AttributeSet;
import java.util.ArrayList;
import java.util.List;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;

public class OrsayMapView extends MapView {
  private final List<GeoPoint> boundary = new ArrayList<>();
  private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  public OrsayMapView(Context context, AttributeSet attrs) {
    super(context, attrs);
    borderPaint.setStyle(Paint.Style.STROKE);
    borderPaint.setStrokeWidth(5f);
    borderPaint.setColor(Color.rgb(20, 110, 230));
  }

  public void setBoundary(List<GeoPoint> points) {
    boundary.clear();
    if (points != null) boundary.addAll(points);
    invalidate();
  }

  public boolean hasBoundary() {
    return boundary.size() >= 3;
  }

  private Path buildBoundaryPath() {
    Path path = new Path();
    if (boundary.size() < 3) return path;
    Point pixel = new Point();
    for (int i = 0; i < boundary.size(); i++) {
      getProjection().toPixels(boundary.get(i), pixel);
      if (i == 0) path.moveTo(pixel.x, pixel.y);
      else path.lineTo(pixel.x, pixel.y);
    }
    path.close();
    return path;
  }

  @Override
  protected void dispatchDraw(Canvas canvas) {
    if (!hasBoundary()) {
      super.dispatchDraw(canvas);
      return;
    }

    Path path = buildBoundaryPath();
    int save = canvas.save();
    canvas.clipPath(path);
    super.dispatchDraw(canvas);
    canvas.restoreToCount(save);

    // Contour bleu = frontière administrative exacte de la commune d'Orsay.
    canvas.drawPath(path, borderPaint);
  }
}
