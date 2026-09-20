package com.chasmet.plantravail;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.osmdroid.util.GeoPoint;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class MapErgonomicsTest {
  private Context context;
  private WorkDatabase db;
  private ActivityController<MainActivity> controller;
  private final List<GeoPoint> first =
      Arrays.asList(new GeoPoint(48.697, 2.185), new GeoPoint(48.6973, 2.1853));
  private final List<GeoPoint> second =
      Arrays.asList(new GeoPoint(48.6975, 2.1855), new GeoPoint(48.698, 2.186));

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    context.deleteDatabase("plan_travail.db");
    context.getSharedPreferences("manual_traces", 0).edit().clear().commit();
    context.getSharedPreferences("map_view", 0).edit().clear().commit();
    context
        .getSharedPreferences("settings", 0)
        .edit()
        .putBoolean("mcp_server_auto", false)
        .putBoolean("auto_update", false)
        .putBoolean("detailed_map", true)
        .commit();
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
    db =
        new WorkDatabase(context) {
          @Override
          public void notifyChanged() {}
        };
    ReflectionHelpers.setStaticField(WorkDatabase.class, "instance", db);
  }

  @After
  public void close() {
    if (controller != null) controller.pause().stop().destroy();
    db.close();
    ReflectionHelpers.setStaticField(WorkDatabase.class, "instance", null);
  }

  @Test
  public void legacyMigrationKeepsCoordinatesAndNeverResurrectsDeletedDrawings() throws Exception {
    JSONObject old =
        ManualTraceStore.json("old", DayColor.today(), Collections.singletonList(first));
    old.put("points", old.getJSONArray("parts").getJSONArray(0));
    old.remove("parts");
    context
        .getSharedPreferences("manual_traces", 0)
        .edit()
        .putString("items", new JSONArray().put(old).toString())
        .commit();
    assertEquals(1, db.snapshot().getJSONArray("map_traces").length());
    ManualTraceStore store = new ManualTraceStore(context);
    assertEquals(first.get(1).getLatitude(), store.all().get(0).points.get(1).getLatitude(), 1e-8);
    assertEquals(1, store.all().size());
    store.delete("old");
    assertTrue(store.all().isEmpty());
    assertEquals(0, db.snapshot().getJSONArray("map_traces").length());
    assertTrue(context.getSharedPreferences("manual_traces", 0).contains("items"));
  }

  @Test
  public void backupRestoresSeparateStrokesAndWeeksAndOldBackupKeepsDrawings() throws Exception {
    ManualTraceStore store = new ManualTraceStore(context);
    store.saveParts("current", DayColor.today(), Arrays.asList(first, second));
    store.save("past", DayColor.shift(DayColor.today(), -14), first);
    JSONObject backup = BackupManager.decode(BackupManager.encode(db.snapshot()));
    store.delete("current");
    assertTrue(store.forWeek(DayColor.today()).isEmpty());
    db.undoLastToday();
    assertEquals(2, store.forWeek(DayColor.today()).get(0).parts.size());
    store.delete("past");
    db.restoreSnapshot(backup);
    assertEquals(1, store.forWeek(DayColor.today()).size());
    assertEquals(1, store.forWeek(DayColor.shift(DayColor.today(), -14)).size());
    db.restoreSnapshot(
        new JSONObject()
            .put("work_entries", new JSONArray())
            .put("lexicon_entries", new JSONArray()));
    assertEquals(2, store.all().size());
  }

  @Test
  public void invalidDrawingRollsBackEntireRestore() throws Exception {
    db.addOrUpdate("Rue de Paris", DayColor.today(), 0, "manuel", 50);
    db.writeTrace(
        ManualTraceStore.json("valid", DayColor.today(), Collections.singletonList(first)), null);
    JSONObject backup = db.snapshot();
    backup.getJSONArray("work_entries").getJSONObject(0).put("progress", 100);
    backup.getJSONArray("map_traces").getJSONObject(0).put("parts", new JSONArray());
    assertThrows(IllegalArgumentException.class, () -> db.restoreSnapshot(backup));
    assertEquals(50, db.getCurrentWeekProgress("Rue de Paris"));
    assertEquals(1, db.traceRows(null).length());
  }

  @Test
  public void mapInPdfPreservesItsAspectRatio() {
    RectF frame = HighResWeeklyExporter.fitMapPage(4000, 3000, 595, 842);
    assertEquals(4f / 3f, frame.width() / frame.height(), 0.0001);
    assertTrue(frame.left >= 0 && frame.top >= 0 && frame.right <= 595 && frame.bottom <= 842);
  }

  private MainActivity screen() throws Exception {
    controller = Robolectric.buildActivity(MainActivity.class).setup();
    MainActivity activity = controller.get();
    OrsayMapView map = activity.findViewById(R.id.map);
    map.setUseDataConnection(false);
    MapDataCache.IO.submit(() -> {}).get(30, TimeUnit.SECONDS);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    View decor = activity.getWindow().getDecorView();
    decor.measure(
        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
    decor.layout(0, 0, 360, 800);
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    assertNotNull(
        "Les données détaillées doivent être accessibles depuis les assets de l’APK",
        ReflectionHelpers.getField(map, "vectorData"));
    map.getController().setZoom(18d);
    map.getController().setCenter(new GeoPoint(48.69685225, 2.18722025));
    return activity;
  }

  @Test
  public void actualOfflineMapRendersAtDeepZoomAndFullscreenWorks() throws Exception {
    MainActivity activity = screen();
    OrsayMapView map = activity.findViewById(R.id.map);
    assertTrue(map.getHeight() > 450);
    assertEquals(24d, map.getMaxZoomLevel(), .001);
    assertTrue("Le fond hors ligne doit contenir routes et bâtiments", colors(map) > 12);
    saveScreen(activity, "plan-portrait.png");
    map.getController().setZoom(21d);
    saveScreen(activity, "plan-zoom-21.png");
    map.getController().setZoom(24d);
    saveScreen(activity, "plan-zoom-24.png");
    assertEquals(
        "24,0",
        ((android.widget.TextView) activity.findViewById(R.id.btnZoomLevel)).getText().toString());
    assertTrue("La rue visible doit rester dessinée au zoom maximal", colors(map) > 12);
    assertTrue(map.isDetailed());
    map.setHistoricalExport(true);
    assertFalse(map.isDetailed());
    map.setHistoricalExport(false);
    assertTrue(map.isDetailed());
    activity.findViewById(R.id.btnFullscreen).performClick();
    assertEquals(View.GONE, activity.findViewById(R.id.mapHeader).getVisibility());
    activity.onBackPressed();
    assertEquals(View.VISIBLE, activity.findViewById(R.id.mapHeader).getVisibility());
  }

  @Test
  public void fractionalZoomKeepsCenterHonorsLimitsAndSavesCamera() throws Exception {
    MainActivity activity = screen();
    OrsayMapView map = activity.findViewById(R.id.map);
    MapZoomControls controls = ReflectionHelpers.getField(activity, "zoom");
    org.osmdroid.api.IGeoPoint before = map.getMapCenter();
    controls.step(.2, false);
    assertEquals(18.2, map.getZoomLevelDouble(), .001);
    assertEquals(before.getLatitude(), map.getMapCenter().getLatitude(), .00001);
    assertEquals(before.getLongitude(), map.getMapCenter().getLongitude(), .00001);
    controls.step(100, false);
    assertEquals(24, map.getZoomLevelDouble(), .001);
    controls.saveCamera();
    assertEquals(
        24,
        Double.longBitsToDouble(context.getSharedPreferences("map_view", 0).getLong("zoom", 0)),
        .001);
    controls.step(-100, false);
    assertEquals(14, map.getZoomLevelDouble(), .001);
    controller.pause().stop().destroy();
    controller = null;
    controller = Robolectric.buildActivity(MainActivity.class).setup();
    OrsayMapView restored = controller.get().findViewById(R.id.map);
    assertEquals(14, restored.getZoomLevelDouble(), .001);
  }

  @Test
  public void holdToZoomStopsWhenFingerIsReleased() throws Exception {
    MainActivity activity = screen();
    OrsayMapView map = activity.findViewById(R.id.map);
    View plus = activity.findViewById(R.id.btnZoomIn);
    MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20, 20, 0);
    plus.dispatchTouchEvent(down);
    down.recycle();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650));
    double held = map.getZoomLevelDouble();
    assertTrue(held > 18.4);
    MotionEvent up = MotionEvent.obtain(0, 650, MotionEvent.ACTION_UP, 20, 20, 0);
    plus.dispatchTouchEvent(up);
    up.recycle();
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    assertEquals(held, map.getZoomLevelDouble(), .001);
  }

  @Test
  public void separateStrokesSurvivePinchAndDraftRestoreAndFollowTheirWeek() throws Exception {
    MainActivity activity = screen();
    ManualTraceControls controls = activity.findViewById(R.id.manualTraceControls);
    controls.startDrawing();
    touch(controls, MotionEvent.ACTION_DOWN, 160, 230);
    touch(controls, MotionEvent.ACTION_MOVE, 175, 245);
    touch(controls, MotionEvent.ACTION_UP, 180, 250);
    touch(controls, MotionEvent.ACTION_DOWN, 185, 255);
    touch(controls, MotionEvent.ACTION_MOVE, 200, 265);
    touch(controls, MotionEvent.ACTION_UP, 205, 270);
    assertEquals(2, new JSONObject(controls.snapshotDraft()).getJSONArray("parts").length());
    touch(controls, MotionEvent.ACTION_DOWN, 180, 260);
    MotionEvent.PointerProperties[] p = new MotionEvent.PointerProperties[2];
    MotionEvent.PointerCoords[] c = new MotionEvent.PointerCoords[2];
    for (int i = 0; i < 2; i++) {
      p[i] = new MotionEvent.PointerProperties();
      p[i].id = i;
      p[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      c[i] = new MotionEvent.PointerCoords();
      c[i].x = 180 + i * 40;
      c[i].y = 260;
      c[i].pressure = 1;
      c[i].size = 1;
    }
    MotionEvent pinch =
        MotionEvent.obtain(
            0,
            20,
            MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            p,
            c,
            0,
            0,
            1,
            1,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0);
    boolean consumed =
        ReflectionHelpers.callInstanceMethod(
            controls, "touch", ReflectionHelpers.ClassParameter.from(MotionEvent.class, pinch));
    pinch.recycle();
    assertFalse(consumed);
    String draft = controls.snapshotDraft();
    controls.cancelDraft();
    controls.restoreDraft(draft);
    assertTrue(controls.isDrawing());
    assertEquals(2, new JSONObject(controls.snapshotDraft()).getJSONArray("parts").length());
    controls.cancelDraft();
    new ManualTraceStore(context).save("today", DayColor.today(), first);
    controls.refresh();
    List<?> lines = ReflectionHelpers.getField(controls, "saved");
    assertEquals(1, lines.size());
    ReflectionHelpers.setField(activity, "selectedWeek", DayColor.shift(DayColor.today(), -14));
    controls.refresh();
    lines = ReflectionHelpers.getField(controls, "saved");
    assertEquals(0, lines.size());
  }

  private void touch(ManualTraceControls controls, int action, float x, float y) {
    MotionEvent e = MotionEvent.obtain(0, 10, action, x, y, 0);
    ReflectionHelpers.callInstanceMethod(
        controls, "touch", ReflectionHelpers.ClassParameter.from(MotionEvent.class, e));
    e.recycle();
  }

  private int colors(View view) {
    Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(image));
    Set<Integer> colors = new HashSet<>();
    for (int y = 0; y < image.getHeight(); y += 4)
      for (int x = 0; x < image.getWidth(); x += 4) colors.add(image.getPixel(x, y));
    image.recycle();
    return colors.size();
  }

  private void saveScreen(MainActivity activity, String name) throws Exception {
    Shadows.shadowOf(Looper.getMainLooper()).idle();
    View decor = activity.getWindow().getDecorView();
    Bitmap image = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888);
    decor.draw(new Canvas(image));
    File dir = new File("build/verification");
    dir.mkdirs();
    try (OutputStream out = new FileOutputStream(new File(dir, name))) {
      image.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    image.recycle();
  }
}
