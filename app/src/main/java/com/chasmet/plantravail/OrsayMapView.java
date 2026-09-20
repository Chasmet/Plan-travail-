package com.chasmet.plantravail;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import java.util.*;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;

public class OrsayMapView extends MapView {
  public static final double MIN_ZOOM = 14, MAX_ZOOM = 24;
  private final List<GeoPoint> boundary = new ArrayList<>();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final VectorMapRenderer renderer = new VectorMapRenderer();
  private VectorMapData vectorData;
  private boolean detailed, historicalExport;

  public interface TapListener {
    void onTap(GeoPoint point);
  }

  private TapListener tapListener;

  public OrsayMapView(Context context, AttributeSet attrs) {
    super(context, attrs);
    detailed = context.getSharedPreferences("settings", 0).getBoolean("detailed_map", false);
    setMinZoomLevel(MIN_ZOOM);
    setMaxZoomLevel(MAX_ZOOM);
    getOverlays()
        .add(
            new Overlay() {
              @Override
              public void draw(Canvas c, MapView m, boolean shadow) {
                if (!shadow && isDetailed() && vectorData != null)
                  renderer.draw(
                      c,
                      vectorData,
                      VectorViewport.forMap(OrsayMapView.this),
                      getResources().getDisplayMetrics().density);
              }

              @Override
              public boolean onSingleTapConfirmed(MotionEvent event, MapView m) {
                if (tapListener == null) return false;
                org.osmdroid.api.IGeoPoint p =
                    getProjection().fromPixels((int) event.getX(), (int) event.getY());
                GeoPoint point = new GeoPoint(p.getLatitude(), p.getLongitude());
                if (contains(point)) {
                  tapListener.onTap(point);
                  return true;
                }
                return false;
              }
            });
    MapDataCache.IO.execute(
        () -> {
          try {
            VectorMapData data = VectorMapData.load(context.getApplicationContext());
            new Handler(Looper.getMainLooper())
                .post(
                    () -> {
                      vectorData = data;
                      invalidate();
                    });
          } catch (Exception e) {
            android.util.Log.e("OrsayMap", "Plan détaillé indisponible", e);
          }
        });
  }

  public boolean isDetailed() {
    return !historicalExport && (detailed || getZoomLevelDouble() > 19);
  }

  public boolean prefersDetailed() {
    return detailed;
  }

  public void setDetailed(boolean value) {
    detailed = value;
    getContext()
        .getSharedPreferences("settings", 0)
        .edit()
        .putBoolean("detailed_map", value)
        .apply();
    invalidate();
  }

  void setHistoricalExport(boolean value) {
    historicalExport = value;
    invalidate();
  }

  boolean isHistoricalExport() {
    return historicalExport;
  }

  public void setTapListener(TapListener listener) {
    tapListener = listener;
  }

  public void setBoundary(List<GeoPoint> points) {
    boundary.clear();
    if (points != null) boundary.addAll(points);
    invalidate();
  }

  public List<GeoPoint> boundaryPoints() {
    return new ArrayList<>(boundary);
  }

  public boolean hasBoundary() {
    return boundary.size() >= 3;
  }

  public boolean contains(GeoPoint point) {
    boolean inside = false;
    for (int i = 0, j = boundary.size() - 1; i < boundary.size(); j = i++) {
      GeoPoint a = boundary.get(i), b = boundary.get(j);
      if ((a.getLatitude() > point.getLatitude()) != (b.getLatitude() > point.getLatitude())
          && point.getLongitude()
              < (b.getLongitude() - a.getLongitude())
                      * (point.getLatitude() - a.getLatitude())
                      / (b.getLatitude() - a.getLatitude())
                  + a.getLongitude()) inside = !inside;
    }
    return inside;
  }

  private Path boundaryPath() {
    Path path = new Path();
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
    getOverlayManager().getTilesOverlay().setEnabled(!isDetailed() || vectorData == null);
    canvas.drawColor(0xffe6ebf1);
    if (hasBoundary()) {
      Path path = boundaryPath();
      int save = canvas.save();
      canvas.clipPath(path);
      super.dispatchDraw(canvas);
      canvas.restoreToCount(save);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(2 * getResources().getDisplayMetrics().density);
      paint.setColor(0xff146ee6);
      canvas.drawPath(path, paint);
    } else super.dispatchDraw(canvas);
    if (!historicalExport) drawScale(canvas);
  }

  private void drawScale(Canvas canvas) {
    float unit = getResources().getDisplayMetrics().density;
    double metersPerPixel =
        40075016.69
            * Math.cos(Math.toRadians(getMapCenter().getLatitude()))
            / getProjection().getWorldMapSize();
    double target = 80 * unit * metersPerPixel,
        power = Math.pow(10, Math.floor(Math.log10(target))),
        value = power;
    for (double factor : new double[] {1, 2, 5})
      if (power * factor <= target) value = power * factor;
    String label =
        value >= 1000
            ? String.format(Locale.FRANCE, "%.1f km", value / 1000)
            : value >= 1
                ? String.format(Locale.FRANCE, "%.0f m", value)
                : String.format(Locale.FRANCE, "%.1f m", value);
    float width = (float) (value / metersPerPixel), x = 12 * unit, y = getHeight() - 12 * unit;
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xeefaffff);
    canvas.drawRoundRect(
        new RectF(
            x - 5 * unit, y - 29 * unit, x + Math.max(width, 48 * unit) + 5 * unit, y + 5 * unit),
        5 * unit,
        5 * unit,
        paint);
    paint.setColor(0xff223548);
    paint.setTextSize(11 * unit);
    paint.setTypeface(Typeface.DEFAULT);
    paint.setStrokeWidth(1.5f * unit);
    canvas.drawText(label, x, y - 12 * unit, paint);
    canvas.drawLine(x, y, x + width, y, paint);
    canvas.drawLine(x, y - 5 * unit, x, y + unit, paint);
    canvas.drawLine(x + width, y - 5 * unit, x + width, y + unit, paint);
  }
}
