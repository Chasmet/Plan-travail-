package com.chasmet.plantravail;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.osmdroid.util.GeoPoint;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReliabilityTest {
  private Context context;
  private WorkDatabase db;
  private final List<Street> streets =
      Arrays.asList(
          street("Rue de Paris", 0), street("Rue Victor Hugo", 1), street("Avenue Victor Hugo", 2));

  static Street street(String name, int offset) {
    return new Street(
        name,
        Arrays.asList(
            new GeoPoint(48.70, 2.18 + offset * .001), new GeoPoint(48.701, 2.18 + offset * .001)));
  }

  @Before
  public void prepare() {
    context = RuntimeEnvironment.getApplication();
    context.deleteDatabase("plan_travail.db");
    db =
        new WorkDatabase(context) {
          @Override
          public void notifyChanged() {}
        };
  }

  @After
  public void close() {
    db.close();
  }

  private JSONObject apply(String id, String action, JSONObject args) throws Exception {
    JSONObject command = new JSONObject(args.toString()).put("id", id).put("action", action);
    db.receiveCommand(command);
    return new CommandProcessor(db).apply(command, streets);
  }

  @Test
  public void migrationKeepsExistingV3WorkAndLexicon() {
    SQLiteDatabase old = context.openOrCreateDatabase("plan_travail.db", 0, null);
    old.execSQL(
        "CREATE TABLE work_entries(id INTEGER PRIMARY KEY AUTOINCREMENT,street TEXT NOT"
            + " NULL,work_date TEXT NOT NULL,color INTEGER NOT NULL,source TEXT NOT NULL,progress"
            + " INTEGER NOT NULL DEFAULT 100,UNIQUE(street,work_date))");
    old.execSQL(
        "CREATE TABLE lexicon_entries(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT"
            + " NULL,details TEXT NOT NULL,created_at TEXT NOT NULL)");
    old.execSQL(
        "INSERT INTO work_entries(street,work_date,color,source,progress) VALUES('Rue de"
            + " Paris','2026-09-07',0,'manuel',50)");
    old.execSQL(
        "INSERT INTO lexicon_entries(title,details,created_at) VALUES('Ancienne note','À"
            + " conserver','2026-09-07')");
    old.setVersion(3);
    old.close();
    assertEquals(50, db.getWeekProgress("Rue de Paris", "2026-09-07"));
    assertEquals("À conserver", db.getLexicon().get(0)[2]);
    assertEquals(4, db.getReadableDatabase().getVersion());
    assertEquals(0, db.pendingCount());
  }

  @Test
  public void sameDayCorrectionAndUndoRestorePreviousProgress() {
    String today = DayColor.today();
    db.addOrUpdate("Rue de Paris", today, 0, "manuel", 25);
    db.addOrUpdate("Rue de Paris", today, 0, "manuel", 75);
    assertEquals(75, db.getCurrentWeekProgress("Rue de Paris"));
    db.undoLastToday();
    assertEquals(25, db.getCurrentWeekProgress("Rue de Paris"));
    db.addOrUpdate("Rue de Paris", today, 0, "manuel", 100);
    db.addOrUpdate("Rue de Paris", today, 0, "manuel", 50);
    assertEquals(50, db.getCurrentWeekProgress("Rue de Paris"));
  }

  @Test
  public void historicalEntryDoesNotUseCurrentWeekProgress() {
    db.addOrUpdate("Rue de Paris", DayColor.today(), 0, "manuel", 100);
    String old = DayColor.shift(DayColor.today(), -14);
    db.addOrUpdate("Rue de Paris", old, 0, "manuel", 25);
    assertEquals(25, db.getWeekProgress("Rue de Paris", old));
    assertEquals(100, db.getCurrentWeekProgress("Rue de Paris"));
  }

  @Test
  public void resetCanBeUndoneAndKeepsLexicon() {
    db.addOrUpdate("Rue de Paris", DayColor.today(), 0, "manuel", 75);
    db.addLexicon("Note", "Conserver");
    assertEquals(1, db.clearCurrentWeek());
    assertEquals(0, db.getCurrentWeekCount());
    assertEquals(1, db.getLexicon().size());
    db.undoLastToday();
    assertEquals(75, db.getCurrentWeekProgress("Rue de Paris"));
  }

  @Test
  public void unknownStreetRejectsWholeBatch() throws Exception {
    JSONObject result =
        apply(
            "unknown",
            "mark_streets",
            new JSONObject()
                .put("streets", new JSONArray().put("Rue de Paris").put("Zzz rue inexistante")));
    assertFalse(result.getBoolean("success"));
    assertEquals(0, db.getCurrentWeekCount());
    assertEquals(0, db.pendingCommands().size());
    assertEquals(1, db.pendingResults().size());
  }

  @Test
  public void ambiguousNameDoesNotChooseAnArbitraryStreet() throws Exception {
    JSONObject result =
        apply("ambiguous", "mark_streets", new JSONObject().put("street", "Victor Hugo"));
    assertFalse(result.getBoolean("success"));
    assertEquals(0, db.getCurrentWeekCount());
    assertEquals("Rue Victor Hugo", StreetResolver.resolve("Rue Victor Hugo", streets));
  }

  @Test
  public void missingCatalogDefersCommandWithoutLosingIt() throws Exception {
    JSONObject command =
        new JSONObject()
            .put("id", "wait")
            .put("action", "mark_streets")
            .put("street", "Rue de Paris");
    db.receiveCommand(command);
    assertNull(new CommandProcessor(db).apply(command, Collections.emptyList()));
    assertEquals(1, db.pendingCommands().size());
    JSONObject done = new CommandProcessor(db).apply(command, streets);
    assertTrue(done.getBoolean("success"));
    assertTrue(done.getJSONArray("applied").getJSONObject(0).getBoolean("verified"));
  }

  @Test
  public void replayDoesNotDuplicateLexiconAndResultSurvivesReopen() throws Exception {
    JSONObject args =
        new JSONObject().put("title", "Rue de Paris").put("details", "Attention aux sacs");
    JSONObject first = apply("once", "add_lexicon", args);
    db.close();
    db =
        new WorkDatabase(context) {
          @Override
          public void notifyChanged() {}
        };
    JSONObject again = apply("once", "add_lexicon", args);
    assertEquals(first.toString(), again.toString());
    assertEquals(1, db.getLexicon().size());
    assertEquals(1, db.pendingResults().size());
    db.acknowledge("once");
    assertTrue(db.pendingResults().isEmpty());
    apply("once", "add_lexicon", args);
    assertEquals(1, db.getLexicon().size());
  }

  @Test
  public void malformedDateOrProgressNeverWrites() throws Exception {
    assertFalse(
        apply(
                "bad-date",
                "mark_streets",
                new JSONObject().put("street", "Rue de Paris").put("date", "2026-02-30"))
            .getBoolean("success"));
    assertFalse(
        apply(
                "bad-progress",
                "mark_streets",
                new JSONObject().put("street", "Rue de Paris").put("progress", 60))
            .getBoolean("success"));
    assertEquals(0, db.getCurrentWeekCount());
    assertFalse(
        apply(
                "fraction",
                "mark_streets",
                new JSONObject().put("street", "Rue de Paris").put("progress", 25.5))
            .getBoolean("success"));
    assertFalse(
        apply(
                "wrong-type",
                "mark_streets",
                new JSONObject().put("street", "Rue de Paris").put("progress", "bad"))
            .getBoolean("success"));
  }

  @Test
  public void backupRoundTripAndInvalidRestoreAreAtomic() throws Exception {
    db.addOrUpdate("Rue de Paris", DayColor.today(), 0, "manuel", 50);
    long note = db.addLexicon("Rue de Paris", "Texte\navec accents : école");
    String backup = BackupManager.encode(db.snapshot());
    db.clearCurrentWeek();
    db.deleteLexicon(note);
    db.restoreSnapshot(BackupManager.decode(backup));
    assertEquals(50, db.getCurrentWeekProgress("Rue de Paris"));
    assertEquals("Texte\navec accents : école", db.getLexicon().get(0)[2]);
    JSONObject invalid = BackupManager.decode(backup);
    invalid
        .getJSONArray("lexicon_entries")
        .put(
            new JSONObject()
                .put("title", "bad")
                .put("details", "bad")
                .put("created_at", DayColor.today()));
    assertThrows(Exception.class, () -> db.restoreSnapshot(invalid));
    assertEquals(1, db.getLexicon().size());
    assertEquals(50, db.getCurrentWeekProgress("Rue de Paris"));
    assertThrows(Exception.class, () -> BackupManager.decode(backup.replace("école", "autre")));
  }

  @Test
  public void restoringDataDoesNotReplayAcknowledgedCommands() throws Exception {
    apply("note", "add_lexicon", new JSONObject().put("title", "Note").put("details", "unique"));
    db.acknowledge("note");
    JSONObject snapshot = db.snapshot();
    db.restoreSnapshot(snapshot);
    apply("note", "add_lexicon", new JSONObject().put("title", "Note").put("details", "unique"));
    assertEquals(1, db.getLexicon().size());
    assertEquals(1, db.pendingResults().size());
  }

  @Test
  public void strictDatesRemainCorrectAcrossThreads() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> DayColor.requireDate("2026-02-30"));
    assertThrows(IllegalArgumentException.class, () -> DayColor.requireDate("2026-09-10x"));
    assertArrayEquals(new String[] {"2026-09-07", "2026-09-13"}, DayColor.weekRange("2026-09-13"));
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        jobs.add(
            pool.submit(
                () -> {
                  for (int j = 0; j < 200; j++) {
                    assertEquals("Lundi", DayColor.dayName("2026-09-07"));
                    assertEquals("Dimanche", DayColor.dayName("2026-09-13"));
                  }
                }));
      for (Future<?> job : jobs) job.get();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void percentageIsOverWholeRouteAndNeverBridgesGaps() {
    Street first =
        new Street("Test", Arrays.asList(new GeoPoint(48.7, 2.18), new GeoPoint(48.701, 2.18)));
    Street second =
        new Street("Test", Arrays.asList(new GeoPoint(48.71, 2.18), new GeoPoint(48.713, 2.18)));
    List<List<GeoPoint>> route = RouteGeometry.ordered(Arrays.asList(first, second), "Test");
    List<List<GeoPoint>> part = RouteGeometry.slice(route, 0, 50);
    assertEquals(2, part.size());
    double length = 0;
    for (List<GeoPoint> path : part)
      for (int i = 1; i < path.size(); i++)
        length += RouteGeometry.meters(path.get(i - 1), path.get(i));
    assertEquals(222.39, length, .1);
    assertEquals(48.701, part.get(0).get(part.get(0).size() - 1).getLatitude(), .000001);
    assertEquals(48.71, part.get(1).get(0).getLatitude(), .000001);
    assertEquals(0, RouteGeometry.distanceTo(new GeoPoint(48.7005, 2.18), first.getPoints()), .01);
  }

  @Test
  public void lostAcknowledgmentIsRetriedWithoutReapplying() throws Exception {
    JSONObject command =
        new JSONObject()
            .put("id", "network-retry")
            .put("action", "add_lexicon")
            .put("title", "Rue de Paris")
            .put("details", "Une seule note");
    java.util.concurrent.atomic.AtomicInteger attempts =
        new java.util.concurrent.atomic.AtomicInteger();
    McpBridgeClient client =
        new McpBridgeClient(
            context,
            db,
            (method, address, body) -> {
              if (address.contains("/command-result")) {
                if (attempts.incrementAndGet() == 1)
                  throw new java.io.IOException("Connexion coupée après écriture locale");
                return "{}";
              }
              if (method.equals("GET"))
                return new JSONObject()
                    .put(
                        "commands",
                        attempts.get() == 0 ? new JSONArray().put(command) : new JSONArray())
                    .toString();
              return "{}";
            });
    CountDownLatch first = new CountDownLatch(1);
    client.sync(
        streets,
        new McpBridgeClient.Callback() {
          public void onDone(int count) {
            first.countDown();
          }

          public void onError(String error) {
            first.countDown();
          }
        });
    assertTrue(first.await(20, TimeUnit.SECONDS));
    assertEquals(1, db.getLexicon().size());
    assertEquals(1, db.pendingResults().size());
    CountDownLatch retry = new CountDownLatch(1);
    client.sync(
        streets,
        new McpBridgeClient.Callback() {
          public void onDone(int count) {
            retry.countDown();
          }

          public void onError(String error) {
            retry.countDown();
          }
        });
    assertTrue(retry.await(20, TimeUnit.SECONDS));
    assertEquals(2, attempts.get());
    assertEquals(1, db.getLexicon().size());
    assertTrue(db.pendingResults().isEmpty());
  }

  @Test
  public void corruptCacheFallsBackToBundledCatalog() throws Exception {
    try (java.io.FileOutputStream out = context.openFileOutput("orsay_streets.json", 0)) {
      out.write("broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    List<Street> data = MapDataCache.local(context, "orsay_streets.json", StreetRepository::parse);
    assertTrue(data.size() > 900);
    assertFalse(StreetResolver.names(data).isEmpty());
    assertTrue(
        MapDataCache.local(context, "orsay_boundary.geojson", OrsayBoundaryRepository::parse).size()
            > 3);
  }
}
