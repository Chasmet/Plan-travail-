package com.chasmet.plantravail;

import android.app.Activity;
import android.content.*;
import android.util.AttributeSet;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.*;
import org.json.*;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Polyline;

/** Separate strokes remain intact while the map is pinched or panned. */
public class ManualTraceControls extends LinearLayout {
  private final List<List<GeoPoint>> draft = new ArrayList<>();
  private final List<Polyline> previews = new ArrayList<>(), saved = new ArrayList<>();
  private List<GeoPoint> activeStroke;
  private Button move, undo, save;
  private TextView hint;
  private MainActivity activity;
  private OrsayMapView map;
  private ManualTraceStore store;
  private boolean drawing, navigating, multiGesture;
  private float lastX, lastY;
  private String date;

  public ManualTraceControls(Context c) {
    super(c);
    init();
  }

  public ManualTraceControls(Context c, AttributeSet a) {
    super(c, a);
    init();
  }

  public ManualTraceControls(Context c, AttributeSet a, int style) {
    super(c, a, style);
    init();
  }

  private void init() {
    setOrientation(VERTICAL);
    setPadding(dp(6), dp(4), dp(6), dp(4));
    hint = new TextView(getContext());
    hint.setTextColor(0xffdce8f7);
    hint.setTextSize(12);
    hint.setPadding(dp(6), dp(4), dp(6), dp(4));
    addView(hint);
    LinearLayout row = new LinearLayout(getContext());
    addView(row, new LayoutParams(-1, dp(48)));
    move = button("Déplacer", row, 1);
    undo = button("Retirer", row, 1);
    save = button("Valider", row, 1);
    Button cancel = button("×", row, 0);
    cancel.setContentDescription("Annuler le dessin");
    move.setOnClickListener(
        v -> {
          navigating = !navigating;
          updateButtons();
        });
    undo.setOnClickListener(
        v -> {
          if (!draft.isEmpty()) draft.remove(draft.size() - 1);
          updatePreview();
          updateButtons();
        });
    save.setOnClickListener(v -> saveDraft());
    cancel.setOnClickListener(
        v -> {
          if (draft.isEmpty()) {
            cancelDraft();
            return;
          }
          new AlertDialog.Builder(activity)
              .setTitle("Abandonner le dessin en cours ?")
              .setNegativeButton("Continuer", null)
              .setPositiveButton("Abandonner", (d, w) -> cancelDraft())
              .show();
        });
  }

  private Button button(String text, LinearLayout row, float weight) {
    Button b = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
    b.setText(text);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setTextColor(0xfff3f7fd);
    b.setMinWidth(dp(48));
    b.setPadding(dp(3), 0, dp(3), 0);
    row.addView(b, new LayoutParams(weight == 0 ? dp(48) : 0, dp(48), weight));
    return b;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    Context c = getContext();
    while (c instanceof ContextWrapper && !(c instanceof Activity))
      c = ((ContextWrapper) c).getBaseContext();
    if (!(c instanceof MainActivity)) return;
    activity = (MainActivity) c;
    map = activity.findViewById(R.id.map);
    store = new ManualTraceStore(activity);
    map.setOnTouchListener((v, e) -> touch(e));
    refresh();
  }

  boolean isDrawing() {
    return drawing;
  }

  boolean hasDraft() {
    return !draft.isEmpty();
  }

  public void startDrawing() {
    if (map == null || drawing) return;
    drawing = true;
    navigating = false;
    date = activity.getDrawingDate();
    setVisibility(VISIBLE);
    map.setMultiTouchControls(true);
    updateButtons();
  }

  public void cancelDraft() {
    drawing = false;
    navigating = false;
    multiGesture = false;
    activeStroke = null;
    draft.clear();
    clear(previews);
    setVisibility(GONE);
    if (map != null) map.invalidate();
  }

  private boolean touch(MotionEvent e) {
    if (!drawing || navigating) return false;
    if (e.getPointerCount() > 1) {
      if (!multiGesture) {
        multiGesture = true;
        discardActiveStroke();
        MotionEvent start = MotionEvent.obtain(e);
        start.setAction(MotionEvent.ACTION_DOWN);
        map.onTouchEvent(start);
        start.recycle();
      }
      return false;
    }
    if (multiGesture) {
      if (e.getActionMasked() == MotionEvent.ACTION_UP
          || e.getActionMasked() == MotionEvent.ACTION_CANCEL) multiGesture = false;
      return false;
    }
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        if (draft.size() >= 200) {
          Toast.makeText(activity, "Valide ce dessin avant de continuer", Toast.LENGTH_SHORT)
              .show();
          return true;
        }
        activeStroke = new ArrayList<>();
        draft.add(activeStroke);
        addPoint(e.getX(), e.getY());
        lastX = e.getX();
        lastY = e.getY();
        return true;
      case MotionEvent.ACTION_MOVE:
        if (activeStroke != null && Math.hypot(e.getX() - lastX, e.getY() - lastY) >= dp(3)) {
          addPoint(e.getX(), e.getY());
          lastX = e.getX();
          lastY = e.getY();
          updatePreview();
          updateButtons();
        }
        return true;
      case MotionEvent.ACTION_UP:
        if (activeStroke != null) {
          addPoint(e.getX(), e.getY());
          if (activeStroke.size() < 2) draft.remove(activeStroke);
          activeStroke = null;
        }
        updatePreview();
        updateButtons();
        return true;
      case MotionEvent.ACTION_CANCEL:
        discardActiveStroke();
        return true;
      default:
        return true;
    }
  }

  private void discardActiveStroke() {
    if (activeStroke != null) draft.remove(activeStroke);
    activeStroke = null;
    updatePreview();
    updateButtons();
  }

  private void addPoint(float x, float y) {
    if (activeStroke == null) return;
    int total = 0;
    for (List<GeoPoint> p : draft) total += p.size();
    if (total >= 8000) return;
    org.osmdroid.api.IGeoPoint p = map.getProjection().fromPixels((int) x, (int) y);
    GeoPoint point = new GeoPoint(p.getLatitude(), p.getLongitude());
    if (map.contains(point)
        && (activeStroke.isEmpty()
            || RouteGeometry.meters(activeStroke.get(activeStroke.size() - 1), point) > .05))
      activeStroke.add(point);
  }

  private void updateButtons() {
    move.setText(navigating ? "Dessiner" : "Déplacer");
    undo.setEnabled(!draft.isEmpty());
    boolean valid = false;
    for (List<GeoPoint> p : draft) valid |= p.size() >= 2;
    save.setEnabled(valid);
    hint.setText(
        (navigating ? "Déplace et zoome le plan" : "Dessine à un doigt · zoome à deux doigts")
            + " · "
            + date);
  }

  private void clear(List<Polyline> list) {
    if (map != null) for (Polyline p : list) map.getOverlays().remove(p);
    list.clear();
  }

  private Polyline line(List<GeoPoint> points, int color) {
    Polyline p = new Polyline(map);
    p.setPoints(new ArrayList<>(points));
    p.getOutlinePaint().setColor(color);
    p.getOutlinePaint().setStrokeWidth(dp(3.5f));
    return p;
  }

  private void updatePreview() {
    if (map == null) return;
    clear(previews);
    for (List<GeoPoint> p : draft)
      if (p.size() >= 2) {
        Polyline poly = line(p, DayColor.forDate(date));
        previews.add(poly);
        map.getOverlays().add(poly);
      }
    map.invalidate();
  }

  private void saveDraft() {
    List<List<GeoPoint>> valid = new ArrayList<>();
    for (List<GeoPoint> p : draft) if (p.size() >= 2) valid.add(p);
    try {
      store.saveParts(null, date, valid);
      cancelDraft();
      refresh();
      activity.showUndo("Dessin enregistré");
    } catch (Exception e) {
      Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  public void refresh() {
    if (map == null || store == null) return;
    clear(saved);
    for (ManualTraceStore.Trace trace : store.forWeek(activity.getSelectedWeek()))
      for (List<GeoPoint> path : trace.parts) {
        Polyline p = line(path, trace.color);
        p.setTitle("Dessin manuel");
        p.setOnClickListener(
            (l, m, e) -> {
              if (drawing) return true;
              new AlertDialog.Builder(activity)
                  .setTitle("Dessin manuel")
                  .setMessage(DayColor.dayName(trace.date) + " · " + trace.date)
                  .setNegativeButton("Fermer", null)
                  .setPositiveButton(
                      "Supprimer",
                      (d, w) -> {
                        store.delete(trace.id);
                        refresh();
                        activity.showUndo("Dessin supprimé");
                      })
                  .show();
              return true;
            });
        saved.add(p);
        map.getOverlays().add(p);
      }
    updatePreview();
    map.invalidate();
  }

  String snapshotDraft() {
    if (!drawing) return null;
    try {
      JSONArray parts = new JSONArray();
      for (List<GeoPoint> path : draft) {
        if (path.size() < 2) continue;
        JSONArray p = new JSONArray();
        for (GeoPoint point : path)
          p.put(new JSONArray().put(point.getLatitude()).put(point.getLongitude()));
        parts.put(p);
      }
      return new JSONObject()
          .put("date", date)
          .put("parts", parts)
          .put("navigate", navigating)
          .toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  void restoreDraft(String state) {
    if (state == null) return;
    try {
      JSONObject data = new JSONObject(state);
      date = data.getString("date");
      DayColor.requireDate(date);
      draft.clear();
      JSONArray parts = data.getJSONArray("parts");
      for (int i = 0; i < parts.length(); i++) {
        List<GeoPoint> path = new ArrayList<>();
        JSONArray points = parts.getJSONArray(i);
        for (int j = 0; j < points.length(); j++) {
          JSONArray p = points.getJSONArray(j);
          path.add(new GeoPoint(p.getDouble(0), p.getDouble(1)));
        }
        draft.add(path);
      }
      drawing = true;
      navigating = data.optBoolean("navigate");
      setVisibility(VISIBLE);
      updatePreview();
      updateButtons();
    } catch (Exception e) {
      Toast.makeText(getContext(), "Le brouillon n’a pas pu être repris", Toast.LENGTH_LONG).show();
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    if (map != null) {
      map.setOnTouchListener(null);
      clear(saved);
      clear(previews);
    }
    super.onDetachedFromWindow();
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }
}
