package com.chasmet.plantravail;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import java.util.ArrayList;
import java.util.List;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Polyline;

/** Commandes de traçage libre directement sur la carte. */
public class ManualTraceControls extends LinearLayout {
  private final List<GeoPoint> draft = new ArrayList<>();
  private final List<Polyline> savedLines = new ArrayList<>();
  private Button traceButton, undoButton, validateButton, cancelButton;
  private Activity activity;
  private OrsayMapView map;
  private ManualTraceStore store;
  private MapEventsOverlay eventOverlay;
  private Polyline preview;
  private boolean drawing;
  private String editingId;

  public ManualTraceControls(Context context) {
    super(context);
    init();
  }

  public ManualTraceControls(Context context, AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public ManualTraceControls(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  private void init() {
    setOrientation(HORIZONTAL);
    traceButton = button("✏ Tracer");
    undoButton = button("↶ Point");
    validateButton = button("✓ Valider");
    cancelButton = button("✕ Annuler");
    addView(traceButton);
    addView(undoButton);
    addView(validateButton);
    addView(cancelButton);
    undoButton.setEnabled(false);
    validateButton.setEnabled(false);
    cancelButton.setEnabled(false);
    traceButton.setOnClickListener(v -> toggleDrawing());
    undoButton.setOnClickListener(v -> undoPoint());
    validateButton.setOnClickListener(v -> chooseDayAndSave());
    cancelButton.setOnClickListener(v -> cancelDraft());
  }

  private Button button(String text) {
    Button b = new Button(getContext());
    b.setText(text);
    LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    lp.setMarginEnd(dp(5));
    b.setLayoutParams(lp);
    return b;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    activity = findActivity(getContext());
    if (activity == null) return;
    map = activity.findViewById(R.id.map);
    store = new ManualTraceStore(activity);
    if (map == null) return;
    eventOverlay =
        new MapEventsOverlay(
            new MapEventsReceiver() {
              @Override
              public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (!drawing) return false;
                draft.add(new GeoPoint(p.getLatitude(), p.getLongitude()));
                updatePreview();
                updateButtons();
                return true;
              }

              @Override
              public boolean longPressHelper(GeoPoint p) {
                if (!drawing) return false;
                if (draft.size() >= 2) chooseDayAndSave();
                return true;
              }
            });
    map.getOverlays().add(0, eventOverlay);
    renderSaved();
  }

  @Override
  protected void onDetachedFromWindow() {
    if (map != null) {
      if (eventOverlay != null) map.getOverlays().remove(eventOverlay);
      if (preview != null) map.getOverlays().remove(preview);
      for (Polyline p : savedLines) map.getOverlays().remove(p);
      map.invalidate();
    }
    super.onDetachedFromWindow();
  }

  private void toggleDrawing() {
    if (map == null) return;
    if (drawing) {
      cancelDraft();
      return;
    }
    drawing = true;
    editingId = null;
    draft.clear();
    traceButton.setText("Traçage actif");
    toast("Touchez la carte pour poser le départ, puis les points du petit tronçon. Appui long = valider.");
    updateButtons();
  }

  private void undoPoint() {
    if (!draft.isEmpty()) draft.remove(draft.size() - 1);
    updatePreview();
    updateButtons();
  }

  private void cancelDraft() {
    drawing = false;
    editingId = null;
    draft.clear();
    removePreview();
    traceButton.setText("✏ Tracer");
    updateButtons();
  }

  private void chooseDayAndSave() {
    if (draft.size() < 2) {
      toast("Place au moins 2 points : départ et arrivée.");
      return;
    }
    String week = currentWeek();
    String[] labels = new String[7];
    String[] dates = new String[7];
    for (int i = 0; i < 7; i++) {
      dates[i] = DayColor.shift(week, i);
      labels[i] = DayColor.dayName(dates[i]) + " — " + dates[i];
    }
    int todayIndex = 0;
    String today = DayColor.today();
    for (int i = 0; i < 7; i++) if (today.equals(dates[i])) todayIndex = i;
    final int defaultIndex = todayIndex;
    new AlertDialog.Builder(activity)
        .setTitle(editingId == null ? "Enregistrer le tracé manuel" : "Modifier le tracé manuel")
        .setSingleChoiceItems(labels, defaultIndex, null)
        .setNegativeButton("Annuler", null)
        .setPositiveButton(
            "Enregistrer",
            (d, w) -> {
              AlertDialog dialog = (AlertDialog) d;
              int checked = dialog.getListView().getCheckedItemPosition();
              if (checked < 0) checked = defaultIndex;
              try {
                store.save(editingId, dates[checked], draft);
                drawing = false;
                editingId = null;
                draft.clear();
                removePreview();
                traceButton.setText("✏ Tracer");
                updateButtons();
                renderSaved();
                toast("Petit tronçon enregistré en " + DayColor.dayName(dates[checked]) + ".");
              } catch (Exception e) {
                toast(e.getMessage());
              }
            })
        .show();
  }

  private void updatePreview() {
    if (map == null) return;
    removePreview();
    if (draft.size() >= 2) {
      preview = new Polyline(map);
      preview.setPoints(new ArrayList<>(draft));
      preview.getOutlinePaint().setColor(Color.WHITE);
      preview.getOutlinePaint().setStrokeWidth(9f);
      map.getOverlays().add(preview);
    }
    map.invalidate();
  }

  private void removePreview() {
    if (map != null && preview != null) map.getOverlays().remove(preview);
    preview = null;
  }

  private void renderSaved() {
    if (map == null || store == null) return;
    for (Polyline p : savedLines) map.getOverlays().remove(p);
    savedLines.clear();
    for (ManualTraceStore.Trace trace : store.forWeek(currentWeek())) {
      Polyline line = new Polyline(map);
      line.setPoints(new ArrayList<>(trace.points));
      line.getOutlinePaint().setColor(trace.color);
      line.getOutlinePaint().setStrokeWidth(9f);
      line.setTitle("Tracé manuel • " + trace.date);
      line.setOnClickListener(
          (polyline, mapView, eventPos) -> {
            showTraceActions(trace);
            return true;
          });
      savedLines.add(line);
      map.getOverlays().add(line);
    }
    map.invalidate();
  }

  private void showTraceActions(ManualTraceStore.Trace trace) {
    String[] actions = {"Modifier ce petit tracé", "Supprimer ce petit tracé"};
    new AlertDialog.Builder(activity)
        .setTitle("Tracé manuel • " + DayColor.dayName(trace.date) + " " + trace.date)
        .setItems(
            actions,
            (d, which) -> {
              if (which == 0) {
                drawing = true;
                editingId = trace.id;
                draft.clear();
                draft.addAll(trace.points);
                traceButton.setText("Modification active");
                updatePreview();
                updateButtons();
                toast("Ajoute des points, retire le dernier si besoin, puis valide.");
              } else {
                new AlertDialog.Builder(activity)
                    .setTitle("Supprimer ce petit tracé ?")
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton(
                        "Supprimer",
                        (x, y) -> {
                          store.delete(trace.id);
                          if (trace.id.equals(editingId)) cancelDraft();
                          renderSaved();
                        })
                    .show();
              }
            })
        .setNegativeButton("Fermer", null)
        .show();
  }

  private void updateButtons() {
    undoButton.setEnabled(drawing && !draft.isEmpty());
    validateButton.setEnabled(drawing && draft.size() >= 2);
    cancelButton.setEnabled(drawing);
  }

  private String currentWeek() {
    if (activity instanceof MainActivity) return ((MainActivity) activity).getSelectedWeek();
    return DayColor.weekRange(DayColor.today())[0];
  }

  private static Activity findActivity(Context context) {
    while (context instanceof ContextWrapper) {
      if (context instanceof Activity) return (Activity) context;
      context = ((ContextWrapper) context).getBaseContext();
    }
    return null;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void toast(String text) {
    Toast.makeText(getContext(), text, Toast.LENGTH_LONG).show();
  }
}
