package com.chasmet.plantravail;

import android.content.SharedPreferences;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.Locale;
import org.osmdroid.events.*;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;

/** Continuous, bounded zoom and camera persistence independent of work data. */
final class MapZoomControls {
  private final MainActivity activity;
  private final OrsayMapView map;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean held, fullScreen, restored;
  private int direction;
  private final Runnable save = this::saveCamera;
  private final Runnable repeat =
      new Runnable() {
        public void run() {
          held = true;
          step(direction * .2, false);
          handler.postDelayed(this, 90);
        }
      };

  MapZoomControls(MainActivity activity, OrsayMapView map) {
    this.activity = activity;
    this.map = map;
    map.setMultiTouchControls(true);
    map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
    SharedPreferences p = activity.getSharedPreferences("map_view", 0);
    if (p.contains("lat")) {
      double lat = Double.longBitsToDouble(p.getLong("lat", 0)),
          lon = Double.longBitsToDouble(p.getLong("lon", 0)),
          z = Double.longBitsToDouble(p.getLong("zoom", 0));
      if (lat >= 48.65
          && lat <= 48.75
          && lon >= 2.1
          && lon <= 2.25
          && !Double.isNaN(z)
          && !Double.isInfinite(z)) {
        map.getController().setZoom(clamp(z));
        map.getController().setCenter(new GeoPoint(lat, lon));
        restored = true;
      }
    }
    bind(activity.findViewById(R.id.btnZoomIn), 1);
    bind(activity.findViewById(R.id.btnZoomOut), -1);
    activity.findViewById(R.id.btnZoomLevel).setOnClickListener(v -> showPrecision());
    activity.findViewById(R.id.btnOverview).setOnClickListener(v -> activity.showOverview());
    activity.findViewById(R.id.btnFullscreen).setOnClickListener(v -> setFullScreen(!fullScreen));
    map.addMapListener(
        new MapListener() {
          public boolean onScroll(ScrollEvent e) {
            changed();
            return false;
          }

          public boolean onZoom(ZoomEvent e) {
            changed();
            return false;
          }
        });
    update();
  }

  private void bind(View button, int sign) {
    button.setOnClickListener(v -> step(sign * .5, true));
    button.setOnTouchListener(
        (v, event) -> {
          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              held = false;
              direction = sign;
              handler.postDelayed(repeat, 350);
              break;
            case MotionEvent.ACTION_UP:
              handler.removeCallbacks(repeat);
              if (held) {
                v.setPressed(false);
                return true;
              }
              break;
            case MotionEvent.ACTION_CANCEL:
              handler.removeCallbacks(repeat);
              v.setPressed(false);
              return true;
          }
          return false;
        });
  }

  static double clamp(double zoom) {
    return Math.max(OrsayMapView.MIN_ZOOM, Math.min(OrsayMapView.MAX_ZOOM, zoom));
  }

  void step(double amount, boolean animate) {
    double next = clamp(map.getZoomLevelDouble() + amount);
    if (animate && !map.isAnimating()) map.getController().zoomTo(next);
    else {
      map.getController().stopAnimation(false);
      map.getController().setZoom(next);
    }
    changed();
  }

  private void changed() {
    handler.removeCallbacks(save);
    handler.postDelayed(save, 650);
    handler.post(this::update);
  }

  private void update() {
    double zoom = map.getZoomLevelDouble();
    activity.findViewById(R.id.btnZoomIn).setEnabled(zoom < OrsayMapView.MAX_ZOOM - .001);
    activity.findViewById(R.id.btnZoomOut).setEnabled(zoom > OrsayMapView.MIN_ZOOM + .001);
    TextView level = activity.findViewById(R.id.btnZoomLevel);
    level.setText(String.format(Locale.FRANCE, "%.1f", zoom));
    level.setContentDescription(
        String.format(Locale.FRANCE, "Zoom %.1f sur 24. Régler précisément", zoom));
  }

  private void showPrecision() {
    LinearLayout box = new LinearLayout(activity);
    box.setOrientation(LinearLayout.VERTICAL);
    int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
    box.setPadding(pad, pad, pad, pad);
    TextView value = new TextView(activity);
    value.setTextSize(16);
    box.addView(value);
    SeekBar slider = new SeekBar(activity);
    slider.setMax(200);
    slider.setProgress((int) Math.round((map.getZoomLevelDouble() - OrsayMapView.MIN_ZOOM) * 20));
    box.addView(
        slider,
        new LinearLayout.LayoutParams(
            -1, Math.round(56 * activity.getResources().getDisplayMetrics().density)));
    value.setText(String.format(Locale.FRANCE, "Zoom %.2f · 14 à 24", map.getZoomLevelDouble()));
    slider.setContentDescription("Réglage précis du zoom");
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int progress, boolean user) {
            double z = OrsayMapView.MIN_ZOOM + progress / 20d;
            value.setText(String.format(Locale.FRANCE, "Zoom %.2f · 14 à 24", z));
            if (user) {
              map.getController().stopAnimation(false);
              map.getController().setZoom(z);
              changed();
            }
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}
        });
    new AlertDialog.Builder(activity)
        .setTitle("Zoom précis")
        .setView(box)
        .setPositiveButton("Terminé", null)
        .setNeutralButton("Vue d’ensemble", (d, w) -> activity.showOverview())
        .show();
  }

  boolean hasRestoredCamera() {
    return restored;
  }

  void saveCamera() {
    if (map.isHistoricalExport() || HighResWeeklyExporter.isBusy()) return;
    org.osmdroid.api.IGeoPoint p = map.getMapCenter();
    activity
        .getSharedPreferences("map_view", 0)
        .edit()
        .putLong("lat", Double.doubleToLongBits(p.getLatitude()))
        .putLong("lon", Double.doubleToLongBits(p.getLongitude()))
        .putLong("zoom", Double.doubleToLongBits(map.getZoomLevelDouble()))
        .apply();
  }

  void setFullScreen(boolean value) {
    fullScreen = value;
    activity.findViewById(R.id.mapHeader).setVisibility(value ? View.GONE : View.VISIBLE);
    activity.findViewById(R.id.bottomNav).setVisibility(value ? View.GONE : View.VISIBLE);
    activity
        .findViewById(R.id.btnFullscreen)
        .setContentDescription(value ? "Réduire le plan" : "Agrandir le plan");
  }

  boolean isFullScreen() {
    return fullScreen;
  }

  void stop() {
    handler.removeCallbacksAndMessages(null);
    saveCamera();
  }
}
