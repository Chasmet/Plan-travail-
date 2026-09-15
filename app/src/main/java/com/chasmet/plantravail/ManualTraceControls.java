package com.chasmet.plantravail;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import java.util.ArrayList;
import java.util.List;
import org.osmdroid.api.IGeoPoint;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Polyline;

/** Outil de dessin libre posé sur le plan. Il ne crée aucune rue et ne modifie aucun avancement. */
public class ManualTraceControls extends LinearLayout {
  private static final float MIN_POINT_DISTANCE_PX = 7f;

  private final List<GeoPoint> draft = new ArrayList<>();
  private final List<Polyline> savedLines = new ArrayList<>();
  private Button traceButton, undoButton, validateButton, cancelButton;
  private Activity activity;
  private OrsayMapView map;
  private ManualTraceStore store;
  private Polyline preview;
  private boolean drawing;
  private float lastX, lastY;

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
    traceButton = button("✏ DESSINER");
    undoButton = button("↶ POINT");
    validateButton = button("✓ VALIDER");
    cancelButton = button("✕ ANNULER");
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

    // Le dessin se fait réellement au doigt. Hors mode dessin, la carte garde son comportement normal.
    map.setOnTouchListener(
        (v, event) -> {
          if (!drawing) return false;
          if (event.getPointerCount() > 1) return true;

          switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              getParent().requestDisallowInterceptTouchEvent(true);
              draft.clear();
              addPoint(event.getX(), event.getY(), true);
              lastX = event.getX();
              lastY = event.getY();
              updatePreview();
              updateButtons();
              return true;

            case MotionEvent.ACTION_MOVE:
              float dx = event.getX() - lastX;
              float dy = event.getY() - lastY;
              if (dx * dx + dy * dy >= MIN_POINT_DISTANCE_PX * MIN_POINT_DISTANCE_PX) {
                addPoint(event.getX(), event.getY(), false);
                lastX = event.getX();
                lastY = event.getY();
                updatePreview();
                updateButtons();
              }
              return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              addPoint(event.getX(), event.getY(), false);
              updatePreview();
              updateButtons();
              getParent().requestDisallowInterceptTouchEvent(false);
              return true;

            default:
              return true;
          }
        });
    renderSaved();
  }

  private void addPoint(float x, float y, boolean force) {
    if (map == null) return;
    if (!force && !draft.isEmpty()) {
      float dx = x - lastX;
      float dy = y - lastY;
      if (dx * dx + dy * dy < 4f) return;
    }
    IGeoPoint p = map.getProjection().fromPixels((int) x, (int) y);
    if (p != null) draft.add(new GeoPoint(p.getLatitude(), p.getLongitude()));
  }

  @Override
  protected void onDetachedFromWindow() {
    if (map != null) {
      map.setOnTouchListener(null);
      if (preview != null) map.getOverlays().remove(preview);
      for (Polyline p : savedLines) map.getOverlays().remove(p);
      map.setMultiTouchControls(true);
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
    map.setMultiTouchControls(false);
    traceButton.setText("DESSIN ACTIF");
    toast("Maintiens le doigt sur la carte et dessine directement la petite section. La couleur est celle d’aujourd’hui.");
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
    if (map != null) map.setMultiTouchControls(true);
    traceButton.setText("✏ DESSINER");
    updateButtons();
  }

  private void saveToday() {
    if (draft.size() < 2) {
      toast("Dessine d’abord une petite section sur la carte.");
      return;
    }
    try {
      String today = DayColor.today();
      store.save(null, today, draft);
      drawing = false;
      draft.clear();
      removePreview();
      if (map != null) map.setMultiTouchControls(true);
      traceButton.setText("✏ DESSINER");
      updateButtons();
      renderSaved();
      toast("Dessin enregistré en " + DayColor.dayName(today) + ", avec la couleur du jour.");
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
      line.setTitle("Dessin manuel • " + trace.date);
      line.setOnClickListener(
          (polyline, mapView, eventPos) -> {
            if (drawing) return true;
            new AlertDialog.Builder(activity)
                .setTitle("Dessin manuel • " + DayColor.dayName(trace.date))
                .setMessage("Ce trait est uniquement un dessin sur le plan. Il ne correspond à aucune rue.")
                .setNegativeButton("Fermer", null)
                .setPositiveButton(
                    "Supprimer",
                    (d, w) -> {
                      store.delete(trace.id);
                      renderSaved();
                    })
                .show();
            return true;
          });
      savedLines.add(line);
      map.getOverlays().add(line);
    }
    map.invalidate();
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
