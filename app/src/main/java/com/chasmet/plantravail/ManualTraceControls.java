package com.chasmet.plantravail;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
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

/**
 * Outil d'annotation libre du plan.
 *
 * <p>Important : un dessin manuel n'est jamais une rue, une route ou une entrée de travail. Il ne
 * modifie ni le pourcentage d'une rue, ni le nombre de rues commencées/terminées. Il sert uniquement
 * à dessiner précisément une petite zone compliquée directement sur la carte.
 */
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
    traceButton = button("✏ Dessiner");
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
    validateButton.setOnClickListener(v -> saveToday());
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
                if (draft.size() >= 2) saveToday();
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
    draft.clear();
    traceButton.setText("Dessin actif");
    toast(
        "Dessine seulement la petite section voulue sur le plan. La couleur est automatiquement celle d'aujourd'hui : "
            + DayColor.dayName(DayColor.today())
            + ".");
    updateButtons();
  }

  private void undoPoint() {
    if (!draft.isEmpty()) draft.remove(draft.size() - 1);
    updatePreview();
    updateButtons();
  }

  private void cancelDraft() {
    drawing = false;
    draft.clear();
    removePreview();
    traceButton.setText("✏ Dessiner");
    updateButtons();
  }

  /** Enregistre uniquement une annotation graphique, datée d'aujourd'hui. */
  private void saveToday() {
    if (draft.size() < 2) {
      toast("Place au moins 2 points : départ et arrivée.");
      return;
    }
    String today = DayColor.today();
    try {
      // editingId est volontairement null : une annotation validée est un dessin indépendant.
      // Aucune rue n'est créée et WorkDatabase n'est jamais modifiée ici.
      store.save(null, today, draft);
      drawing = false;
      draft.clear();
      removePreview();
      traceButton.setText("✏ Dessiner");
      updateButtons();
      renderSaved();
      toast("Dessin ajouté au plan avec la couleur du " + DayColor.dayName(today) + ".");
    } catch (Exception e) {
      toast(e.getMessage());
    }
  }

  private void updatePreview() {
    if (map == null) return;
    removePreview();
    if (draft.size() >= 2) {
      preview = new Polyline(map);
      preview.setPoints(new ArrayList<>(draft));
      preview.getOutlinePaint().setColor(DayColor.forDate(DayColor.today()));
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
      line.setTitle("Dessin sur le plan • " + trace.date);
      line.setOnClickListener(
          (polyline, mapView, eventPos) -> {
            showDrawingActions(trace);
            return true;
          });
      savedLines.add(line);
      map.getOverlays().add(line);
    }
    map.invalidate();
  }

  private void showDrawingActions(ManualTraceStore.Trace trace) {
    new AlertDialog.Builder(activity)
        .setTitle("Dessin du " + DayColor.dayName(trace.date) + " " + trace.date)
        .setMessage(
            "Ceci est uniquement un dessin posé sur le plan. Il ne crée aucune rue et ne change aucun pourcentage.")
        .setNegativeButton("Fermer", null)
        .setPositiveButton(
            "Supprimer ce dessin",
            (d, w) -> {
              store.delete(trace.id);
              renderSaved();
            })
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
