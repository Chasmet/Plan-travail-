package com.chasmet.plantravail;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
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
  private AutoCompleteTextView searchBox;
  private StreetRepository repository;
  private OrsayBoundaryRepository boundaryRepository;
  private WorkDatabase db;
  private List<Street> streets = new ArrayList<>();
  private final List<Polyline> lines = new ArrayList<>();
  private boolean remainingOnly;
  private String selectedWeek = DayColor.today();
  private boolean followCurrentWeek = true;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private MapZoomControls zoom;
  private ManualTraceControls traces;
  private String selectedName;
  private boolean fitted, exporting, exportRemaining;
  private final Runnable hideUndo = () -> findViewById(R.id.undoPanel).setVisibility(View.GONE);

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
    map.setMaxZoomLevel(OrsayMapView.MAX_ZOOM);
    map.getController().setCenter(new GeoPoint(48.6993, 2.1875));
    map.getController().setZoom(14.7);
    zoom = new MapZoomControls(this, map);
    traces = findViewById(R.id.manualTraceControls);
    map.setTapListener(this::mapTapped);
    findViewById(R.id.btnOptions).setOnClickListener(this::options);
    findViewById(R.id.btnPlan).setOnClickListener(v -> afterDraftCheck(this::closeSelection));
    findViewById(R.id.btnProgress)
        .setOnClickListener(
            v -> {
              if (selectedName != null) showStreet(selectedName);
            });
    findViewById(R.id.btnStreetNote)
        .setOnClickListener(
            v -> {
              if (selectedName != null)
                startActivity(
                    new Intent(this, LexiqueActivity.class).putExtra("street", selectedName));
            });
    findViewById(R.id.btnCloseSelection).setOnClickListener(v -> closeSelection());
    findViewById(R.id.btnUndo)
        .setOnClickListener(
            v -> {
              db.undoLastToday();
              findViewById(R.id.undoPanel).setVisibility(View.GONE);
              onDataChanged();
            });
    searchBox.setOnItemClickListener(
        (parent, view, position, id) -> focus((String) parent.getItemAtPosition(position)));
    if (state != null) {
      zoom.setFullScreen(state.getBoolean("fullscreen"));
      String draft = state.getString("draft");
      traces.post(() -> traces.restoreDraft(draft));
    }

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
        .setOnClickListener(
            v -> afterDraftCheck(() -> startActivity(new Intent(this, LexiqueActivity.class))));
    findViewById(R.id.btnHistory).setOnClickListener(v -> afterDraftCheck(this::chooseWeek));
    findViewById(R.id.btnSettings)
        .setOnClickListener(
            v -> afterDraftCheck(() -> startActivity(new Intent(this, SettingsActivity.class))));
    findViewById(R.id.btnToday)
        .setOnClickListener(
            v -> afterDraftCheck(() -> startActivity(new Intent(this, TodayActivity.class))));
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
                "<font color='#60A5FA'>■ Lun</font> <font color='#86EFAC'>■ Mar</font> <font"
                    + " color='#FDBA74'>■ Mer</font> <font color='#D8B4FE'>■ Jeu</font> <font"
                    + " color='#FCA5A5'>■ Ven</font> <font color='#67E8F9'>■ Sam</font> <font"
                    + " color='#CBD5E1'>■ Dim</font>",
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
    if (!fitted && !zoom.hasRestoredCamera())
      map.post(() -> map.zoomToBoundingBox(box, false, dp(18)));
    fitted = true;
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
                  searchBox.setAdapter(
                      new StreetSuggestions(MainActivity.this, StreetResolver.names(streets)));
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
    if (exporting) return;
    if (db != null && map != null) {
      render();
      refreshStatus();
      if (traces != null) traces.refresh();
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
            + " terminées");
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
          selectStreet(name);
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
      if (remainingOnly)
        for (List<GeoPoint> p : paths) addLine(p, name, Color.argb(90, 60, 60, 60), dp(2));
      int previous = 0;
      for (String[] row : history) {
        if (!name.equals(row[0])) continue;
        int end = Math.min(current, Integer.parseInt(row[4]));
        if (end <= previous) continue;
        for (List<GeoPoint> part : RouteGeometry.slice(paths, previous, end))
          addLine(part, name, Integer.parseInt(row[2]), dp(3));
        previous = end;
      }
    }
    if (selectedName != null)
      for (List<GeoPoint> path : RouteGeometry.ordered(streets, selectedName))
        addLine(path, selectedName, 0xaa0891b2, dp(4));
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
                showUndo("Avancement enregistré");
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
    searchBox.setText(name, false);
    searchBox.dismissDropDown();
    searchBox.clearFocus();
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
    selectStreet(name);
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
              showUndo("Marquage effacé");
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
    if (zoom != null) zoom.stop();
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
    zoom.saveCamera();
    out.putBoolean("fullscreen", zoom.isFullScreen());
    out.putString("draft", traces.snapshotDraft());
    out.putBoolean("remaining", remainingOnly);
    out.putBoolean("follow_current_week", followCurrentWeek);
    out.putString("week", selectedWeek);
    out.putString("query", searchBox.getText().toString());
  }

  public String getDrawingDate() {
    return followCurrentWeek ? DayColor.today() : selectedWeek;
  }

  void showOverview() {
    closeSelection();
    if (map.hasBoundary()) map.zoomToBoundingBox(bounds(map.boundaryPoints(), 0), false, dp(18));
  }

  private void options(View anchor) {
    PopupMenu menu = new PopupMenu(this, anchor);
    String[] titles = {
      "Dessiner au doigt",
      remainingOnly ? "Tout afficher" : "Rues à faire",
      "Exporter PNG + PDF",
      "Annuler la dernière modification",
      "Synchroniser avec ChatGPT",
      "Actualiser les rues",
      "Fond de plan"
    };
    for (int i = 0; i < titles.length; i++) menu.getMenu().add(0, i, i, titles[i]);
    menu.setOnMenuItemClickListener(
        item -> {
          switch (item.getItemId()) {
            case 0:
              closeSelection();
              traces.startDrawing();
              break;
            case 1:
              findViewById(R.id.btnRemaining).performClick();
              break;
            case 2:
              afterDraftCheck(() -> findViewById(R.id.btnExport).performClick());
              break;
            case 3:
              if (db.getLastTodayStreet() == null) toast("Aucune modification à annuler");
              else {
                db.undoLastToday();
                onDataChanged();
              }
              break;
            case 4:
              sync();
              break;
            case 5:
              loadBoundary(true);
              load(true);
              break;
            case 6:
              new AlertDialog.Builder(this)
                  .setTitle("Fond de plan")
                  .setSingleChoiceItems(
                      new String[] {
                        "Classique · détails nets à fort zoom",
                        "Détaillé · disponible hors connexion"
                      },
                      map.prefersDetailed() ? 1 : 0,
                      (d, w) -> {
                        map.setDetailed(w == 1);
                        d.dismiss();
                      })
                  .show();
              break;
          }
          return true;
        });
    menu.show();
  }

  private void selectStreet(String name) {
    if (traces.isDrawing()) return;
    selectedName = name;
    ((TextView) findViewById(R.id.selectedStreet))
        .setText(name + " · " + db.getWeekProgress(name, selectedWeek) + " %");
    findViewById(R.id.selectionPanel).setVisibility(View.VISIBLE);
    render();
  }

  private void closeSelection() {
    selectedName = null;
    findViewById(R.id.selectionPanel).setVisibility(View.GONE);
    render();
  }

  private void mapTapped(GeoPoint point) {
    if (traces.isDrawing()) return;
    double max =
        dp(28)
            * 40075016.69
            * Math.cos(Math.toRadians(point.getLatitude()))
            / map.getProjection().getWorldMapSize();
    String name = null;
    for (Street s : streets) {
      double distance = RouteGeometry.distanceTo(point, s.getPoints());
      if (distance < max) {
        max = distance;
        name = s.getName();
      }
    }
    if (name == null) closeSelection();
    else selectStreet(name);
  }

  void showUndo(String message) {
    ((TextView) findViewById(R.id.undoMessage)).setText(message);
    findViewById(R.id.undoPanel).setVisibility(View.VISIBLE);
    handler.removeCallbacks(hideUndo);
    handler.postDelayed(hideUndo, 8000);
  }

  private void afterDraftCheck(Runnable action) {
    if (traces.hasDraft())
      new AlertDialog.Builder(this)
          .setTitle("Conserver le dessin en cours ?")
          .setMessage("Valide le dessin pour l’enregistrer avant de quitter ce mode.")
          .setNegativeButton("Continuer le dessin", null)
          .setPositiveButton(
              "Abandonner",
              (d, w) -> {
                traces.cancelDraft();
                action.run();
              })
          .show();
    else {
      traces.cancelDraft();
      action.run();
    }
  }

  void prepareExport() {
    exportRemaining = remainingOnly;
    remainingOnly = false;
    closeSelection();
    render();
    traces.refresh();
    exporting = true;
  }

  void finishExport() {
    exporting = false;
    remainingOnly = exportRemaining;
    onDataChanged();
  }

  @Override
  public void onBackPressed() {
    if (traces.isDrawing()) afterDraftCheck(() -> {});
    else if (selectedName != null) closeSelection();
    else if (zoom.isFullScreen()) zoom.setFullScreen(false);
    else super.onBackPressed();
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }
}
