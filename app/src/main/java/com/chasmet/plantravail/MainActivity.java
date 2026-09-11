package com.chasmet.plantravail;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.text.HtmlCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.osmdroid.config.Configuration;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Polyline;

public class MainActivity extends DataActivity {
  private OrsayMapView map;
  private TextView status;
  private EditText searchBox;
  private StreetRepository repository;
  private OrsayBoundaryRepository boundaryRepository;
  private WorkDatabase db;
  private List<Street> streets = new ArrayList<>();
  private final List<Polyline> lines = new ArrayList<>();
  private boolean remainingOnly;
  private String selectedWeek = DayColor.today();
  private boolean followCurrentWeek = true;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private LocationListener locationListener;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    Configuration.getInstance().setUserAgentValue(getPackageName());
    setContentView(R.layout.activity_main);
    map = findViewById(R.id.map);
    status = findViewById(R.id.tvStatus);
    searchBox = findViewById(R.id.etStreetSearch);
    db = WorkDatabase.getInstance(this);
    repository = new StreetRepository(this);
    boundaryRepository = new OrsayBoundaryRepository(this);
    if (state != null) {
      remainingOnly = state.getBoolean("remaining");
      followCurrentWeek = state.getBoolean("follow_current_week", true);
      selectedWeek = state.getString("week", DayColor.today());
      searchBox.setText(state.getString("query", ""));
    }
    if (state == null && getIntent().hasExtra("week")) {
      selectedWeek = getIntent().getStringExtra("week");
      followCurrentWeek = DayColor.weekRange(selectedWeek)[0].equals(db.getCurrentWeekStart());
    }
    map.setMultiTouchControls(true);
    map.setMinZoomLevel(14.0);
    map.setMaxZoomLevel(20.0);
    map.getController().setCenter(new GeoPoint(48.6993, 2.1875));
    map.getController().setZoom(14.7);
    findViewById(R.id.btnSearch).setOnClickListener(v -> searchStreet());
    searchBox.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == EditorInfo.IME_ACTION_SEARCH) {
            searchStreet();
            return true;
          }
          return false;
        });
    findViewById(R.id.btnRefresh)
        .setOnClickListener(
            v -> {
              loadBoundary(true);
              load(true);
            });
    findViewById(R.id.btnExport).setOnClickListener(v -> {});
    findViewById(R.id.btnDeleteTrace).setOnClickListener(v -> deleteSelected());
    findViewById(R.id.btnLexique)
        .setOnClickListener(v -> startActivity(new Intent(this, LexiqueActivity.class)));
    findViewById(R.id.btnHistory).setOnClickListener(v -> chooseWeek());
    findViewById(R.id.btnSettings)
        .setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    findViewById(R.id.btnToday)
        .setOnClickListener(v -> startActivity(new Intent(this, TodayActivity.class)));
    findViewById(R.id.btnMyLocation).setOnClickListener(v -> locate());
    Button remaining = findViewById(R.id.btnRemaining);
    remaining.setText(remainingOnly ? "Tout afficher" : "À faire");
    remaining.setOnClickListener(
        v -> {
          remainingOnly = !remainingOnly;
          remaining.setText(remainingOnly ? "Tout afficher" : "À faire");
          render();
        });
    findViewById(R.id.btnSync).setOnClickListener(v -> sync());
    ((TextView) findViewById(R.id.tvLegend))
        .setText(
            HtmlCompat.fromHtml(
                "<font color='#1565C0'>■ Lun</font> <font color='#2E7D32'>■ Mar</font> <font"
                    + " color='#EF6C00'>■ Mer</font> <font color='#6A1B9A'>■ Jeu</font> <font"
                    + " color='#C62828'>■ Ven</font> <font color='#00838F'>■ Sam</font> <font"
                    + " color='#616161'>■ Dim</font>",
                HtmlCompat.FROM_HTML_MODE_LEGACY));
    loadBoundary(false);
    load(false);
    if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("mcp_server_auto", true)) {
      try {
        ContextCompat.startForegroundService(
            this,
            new Intent(this, McpServerService.class).setAction(McpServerService.ACTION_START));
      } catch (Exception e) {
        status.setText("Service de synchronisation : " + e.getMessage());
      }
    }
    if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("auto_update", true))
      UpdateManager.check(this, null, null, false);
  }

  public String getSelectedWeek() {
    return DayColor.weekRange(selectedWeek)[0];
  }

  private void chooseWeek() {
    String[] choices = new String[13];
    for (int i = 0; i < 12; i++)
      choices[i] =
          (i == 0 ? "Cette semaine — " : "")
              + DayColor.weekRange(DayColor.shift(DayColor.today(), -7 * i))[0];
    choices[12] = "Autre date / historique";
    new AlertDialog.Builder(this)
        .setTitle("Semaine affichée et exportée")
        .setItems(
            choices,
            (d, which) -> {
              if (which == 12) {
                startActivity(new Intent(this, HistoryActivity.class));
                return;
              }
              followCurrentWeek = which == 0;
              selectedWeek = DayColor.shift(DayColor.today(), -7 * which);
              onDataChanged();
            })
        .show();
  }

  private void loadBoundary(boolean force) {
    boundaryRepository.load(
        force,
        new OrsayBoundaryRepository.Callback() {
          public void onLoaded(List<GeoPoint> points, boolean cache) {
            runOnUiThread(
                () -> {
                  if (isFinishing() || isDestroyed()) return;
                  map.setBoundary(points);
                  fit(points);
                });
          }

          public void onError(String message) {
            runOnUiThread(() -> status.setText("Contour indisponible : " + message));
          }
        });
  }

  private void fit(List<GeoPoint> points) {
    if (points == null || points.size() < 3) return;
    BoundingBox box = bounds(points, 0);
    map.setScrollableAreaLimitDouble(box);
    map.post(() -> map.zoomToBoundingBox(box, false, 20));
  }

  private BoundingBox bounds(List<GeoPoint> points, double margin) {
    double n = -90, s = 90, e = -180, w = 180;
    for (GeoPoint p : points) {
      n = Math.max(n, p.getLatitude());
      s = Math.min(s, p.getLatitude());
      e = Math.max(e, p.getLongitude());
      w = Math.min(w, p.getLongitude());
    }
    return new BoundingBox(n + margin, e + margin, s - margin, w - margin);
  }

  private void load(boolean force) {
    status.setText("Chargement des rues d’Orsay…");
    repository.load(
        force,
        new StreetRepository.Callback() {
          public void onLoaded(List<Street> result, boolean cache) {
            runOnUiThread(
                () -> {
                  if (isFinishing() || isDestroyed()) return;
                  streets = result;
                  onDataChanged();
                });
          }

          public void onError(String message) {
            runOnUiThread(() -> status.setText("Rues indisponibles : " + message));
          }
        });
  }

  @Override
  protected void onDataChanged() {
    if (db != null && map != null) {
      render();
      refreshStatus();
    }
  }

  private void refreshStatus() {
    Map<String, Integer> progress = db.getWeekProgress(selectedWeek);
    int completed = db.getCompletedCount(selectedWeek);
    status.setText(
        "Semaine du "
            + getSelectedWeek()
            + " • "
            + progress.size()
            + " commencées • "
            + completed
            + " terminées\n"
            + StreetResolver.names(streets).size()
            + " rues • pourcentage = longueur estimée");
  }

  private void addLine(List<GeoPoint> points, String name, int color, float width) {
    if (points.size() < 2) return;
    Polyline line = new Polyline(map);
    line.setPoints(points);
    line.setTitle(name);
    line.getOutlinePaint().setColor(color);
    line.getOutlinePaint().setStrokeWidth(width);
    line.setOnClickListener(
        (p, m, e) -> {
          showStreet(name);
          return true;
        });
    lines.add(line);
    map.getOverlays().add(line);
  }

  private void render() {
    for (Polyline line : lines) map.getOverlays().remove(line);
    lines.clear();
    Map<String, Integer> progress = db.getWeekProgress(selectedWeek);
    List<String[]> history = db.getWeekEntriesDetailed(selectedWeek);
    for (String name : StreetResolver.names(streets)) {
      int current = progress.containsKey(name) ? progress.get(name) : 0;
      if (remainingOnly && current == 100) continue;
      List<List<GeoPoint>> paths = RouteGeometry.ordered(streets, name);
      for (List<GeoPoint> p : paths) addLine(p, name, Color.argb(90, 60, 60, 60), 3f);
      int previous = 0;
      for (String[] row : history) {
        if (!name.equals(row[0])) continue;
        int end = Math.min(current, Integer.parseInt(row[4]));
        if (end <= previous) continue;
        for (List<GeoPoint> part : RouteGeometry.slice(paths, previous, end))
          addLine(part, name, Integer.parseInt(row[2]), 7f);
        previous = end;
      }
    }
    map.invalidate();
  }

  private void showStreet(String name) {
    int current = db.getWeekProgress(name, selectedWeek);
    String date =
        DayColor.weekRange(selectedWeek)[0].equals(db.getCurrentWeekStart())
            ? DayColor.today()
            : selectedWeek;
    String[] choices = {
      "25 % — un quart",
      "50 % — moitié",
      "75 % — trois quarts",
      "100 % — terminé",
      "Effacer le tracé de cette semaine"
    };
    new AlertDialog.Builder(this)
        .setTitle(name + " • " + current + " %")
        .setItems(
            choices,
            (dialog, which) -> {
              if (which == 4) {
                confirmDelete(name);
                return;
              }
              try {
                db.addOrUpdate(
                    name,
                    date,
                    DayColor.forDate(date),
                    "manuel",
                    new int[] {25, 50, 75, 100}[which]);
                onDataChanged();
              } catch (Exception e) {
                toast(e.getMessage());
              }
            })
        .setNeutralButton(
            "Lexique",
            (d, w) ->
                startActivity(new Intent(this, LexiqueActivity.class).putExtra("street", name)))
        .setNegativeButton("Fermer", null)
        .show();
  }

  private void searchStreet() {
    String query = searchBox.getText().toString();
    List<String> found = StreetResolver.candidates(query, StreetResolver.names(streets));
    if (found.isEmpty()) {
      toast("Rue introuvable dans Orsay");
      return;
    }
    if (found.size() == 1) {
      focus(found.get(0));
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Choisir la rue")
        .setItems(found.toArray(new String[0]), (d, i) -> focus(found.get(i)))
        .show();
  }

  private void focus(String name) {
    List<GeoPoint> points = new ArrayList<>();
    for (Street s : streets) if (s.getName().equals(name)) points.addAll(s.getPoints());
    if (points.isEmpty()) return;
    remainingOnly = false;
    ((Button) findViewById(R.id.btnRemaining)).setText("À faire");
    render();
    map.zoomToBoundingBox(bounds(points, .0004), true, 60);
    searchBox.setText(name);
    showStreet(name);
  }

  private void deleteSelected() {
    List<String> found =
        StreetResolver.candidates(
            searchBox.getText().toString(),
            new ArrayList<>(db.getWeekProgress(selectedWeek).keySet()));
    if (found.size() != 1) {
      toast("Choisis une rue tracée avec un nom précis");
      return;
    }
    confirmDelete(found.get(0));
  }

  private void confirmDelete(String name) {
    new AlertDialog.Builder(this)
        .setTitle("Effacer " + name + " ?")
        .setMessage(
            "Le tracé de la semaine affichée sera retiré. L’annulation reste disponible dans"
                + " Aujourd’hui.")
        .setNegativeButton("Annuler", null)
        .setPositiveButton(
            "Effacer",
            (d, w) -> {
              db.deleteWeekStreet(name, selectedWeek);
              onDataChanged();
            })
        .show();
  }

  private void sync() {
    new McpBridgeClient(this, db)
        .sync(
            streets,
            new McpBridgeClient.Callback() {
              public void onDone(int n) {
                runOnUiThread(
                    () -> {
                      onDataChanged();
                      toast(
                          n
                              + " modification(s) reçue(s) • "
                              + db.pendingCount()
                              + " confirmation(s) en attente");
                    });
              }

              public void onError(String message) {
                runOnUiThread(() -> toast(message));
              }
            });
  }

  private void locate() {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(
          this,
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          41);
      return;
    }
    LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
    try {
      Location last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
      if (last != null
          && System.currentTimeMillis() - last.getTime() < 120000
          && last.getAccuracy() <= 80) {
        useLocation(last);
        return;
      }
      stopLocation();
      String provider =
          manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
              ? LocationManager.GPS_PROVIDER
              : LocationManager.NETWORK_PROVIDER;
      locationListener =
          new LocationListener() {
            public void onLocationChanged(Location value) {
              stopLocation();
              useLocation(value);
            }

            public void onStatusChanged(String p, int s, Bundle b) {}

            public void onProviderEnabled(String p) {}

            public void onProviderDisabled(String p) {}
          };
      manager.requestSingleUpdate(provider, locationListener, Looper.getMainLooper());
      status.setText("Recherche d’une position récente…");
      handler.postDelayed(
          () -> {
            if (locationListener != null) {
              stopLocation();
              toast("Position indisponible, recherche la rue par son nom");
            }
          },
          15000);
    } catch (Exception e) {
      toast("Position indisponible : " + e.getMessage());
    }
  }

  private void useLocation(Location value) {
    if (!value.hasAccuracy() || value.getAccuracy() > 80) {
      toast("Position trop imprécise, recherche la rue par son nom");
      return;
    }
    GeoPoint point = new GeoPoint(value.getLatitude(), value.getLongitude());
    Street nearest = null;
    double best = 60;
    for (Street street : streets) {
      double distance = RouteGeometry.distanceTo(point, street.getPoints());
      if (distance < best) {
        best = distance;
        nearest = street;
      }
    }
    if (nearest == null) {
      toast("Aucune rue d’Orsay assez proche de cette position");
      return;
    }
    focus(nearest.getName());
  }

  private void stopLocation() {
    if (locationListener != null) {
      try {
        ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(locationListener);
      } catch (Exception ignored) {
      }
      locationListener = null;
    }
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code == 41 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
      locate();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (intent.hasExtra("week")) {
      selectedWeek = intent.getStringExtra("week");
      followCurrentWeek = DayColor.weekRange(selectedWeek)[0].equals(db.getCurrentWeekStart());
      onDataChanged();
    }
  }

  @Override
  protected void onResume() {
    if (followCurrentWeek) selectedWeek = DayColor.today();
    super.onResume();
    if (map != null) map.onResume();
    UpdateManager.resumePendingInstall(this);
  }

  @Override
  protected void onPause() {
    UpdateManager.pause(this);
    stopLocation();
    if (map != null) map.onPause();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (map != null) map.onDetach();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putBoolean("remaining", remainingOnly);
    out.putBoolean("follow_current_week", followCurrentWeek);
    out.putString("week", selectedWeek);
    out.putString("query", searchBox.getText().toString());
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }
}
