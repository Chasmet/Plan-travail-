package com.chasmet.plantravail;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public class WorkDatabase extends SQLiteOpenHelper {
  private static WorkDatabase instance;
  private final Context context;

  public static synchronized WorkDatabase getInstance(Context context) {
    if (instance == null) instance = new WorkDatabase(context.getApplicationContext());
    return instance;
  }

  public WorkDatabase(Context context) {
    super(context, "plan_travail.db", null, 4);
    this.context = context.getApplicationContext();
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE work_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, street TEXT NOT NULL,"
            + " work_date TEXT NOT NULL, color INTEGER NOT NULL, source TEXT NOT NULL, progress"
            + " INTEGER NOT NULL DEFAULT 100, UNIQUE(street,work_date))");
    db.execSQL(
        "CREATE TABLE lexicon_entries (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT"
            + " NULL,details TEXT NOT NULL,created_at TEXT NOT NULL)");
    createReliabilityTables(db);
  }

  private void createReliabilityTables(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS command_inbox (id TEXT PRIMARY KEY,body TEXT NOT NULL,result"
            + " TEXT,acknowledged INTEGER NOT NULL DEFAULT 0,received_at INTEGER NOT NULL)");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS work_actions (id INTEGER PRIMARY KEY AUTOINCREMENT,action_date"
            + " TEXT NOT NULL,label TEXT NOT NULL,payload TEXT NOT NULL)");
    db.execSQL("CREATE INDEX IF NOT EXISTS work_date_idx ON work_entries(work_date)");
    db.execSQL("CREATE INDEX IF NOT EXISTS inbox_pending_idx ON command_inbox(acknowledged)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2)
      db.execSQL(
          "CREATE TABLE IF NOT EXISTS lexicon_entries (id INTEGER PRIMARY KEY AUTOINCREMENT,title"
              + " TEXT NOT NULL,details TEXT NOT NULL,created_at TEXT NOT NULL)");
    if (oldVersion < 3)
      db.execSQL("ALTER TABLE work_entries ADD COLUMN progress INTEGER NOT NULL DEFAULT 100");
    if (oldVersion < 4) createReliabilityTables(db);
  }

  private static String requireText(String text, String name) {
    if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException(name + " vide");
    if (text.length() > 20000) throw new IllegalArgumentException(name + " trop long");
    return text.trim();
  }

  public void notifyChanged() {
    if (getWritableDatabase().inTransaction()) return;
    context.sendBroadcast(
        new Intent(McpBridgeClient.ACTION_DATA_CHANGED).setPackage(context.getPackageName()));
    BackupManager.schedule(context, this);
  }

  public synchronized void addOrUpdate(String street, String date, int color, String source) {
    addOrUpdate(street, date, color, source, 100);
  }

  public synchronized void addOrUpdate(
      String street, String date, int color, String source, int progress) {
    street = requireText(street, "Rue");
    DayColor.requireDate(date);
    DayColor.requireProgress(progress);
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      JSONArray before = workRows("street=? AND work_date=?", new String[] {street, date});
      JSONArray keys =
          new JSONArray().put(new JSONObject().put("street", street).put("work_date", date));
      if (progress == 0)
        db.delete("work_entries", "street=? AND work_date=?", new String[] {street, date});
      else {
        ContentValues values = new ContentValues();
        values.put("street", street);
        values.put("work_date", date);
        values.put("color", DayColor.forDate(date));
        values.put("source", requireText(source, "Source"));
        values.put("progress", progress);
        if (db.insertWithOnConflict("work_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            < 0) throw new IllegalStateException("Écriture de la rue refusée");
      }
      recordAction(street, before, keys);
      db.setTransactionSuccessful();
    } catch (Exception e) {
      throw failure(e);
    } finally {
      db.endTransaction();
    }
    notifyChanged();
  }

  private void recordAction(String label, JSONArray before, JSONArray keys) throws Exception {
    ContentValues action = new ContentValues();
    action.put("action_date", DayColor.today());
    action.put("label", label);
    action.put("payload", new JSONObject().put("before", before).put("keys", keys).toString());
    getWritableDatabase().insertOrThrow("work_actions", null, action);
  }

  public synchronized String[] getEntry(String street, String date) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT street,work_date,color,source,progress FROM work_entries WHERE street=? AND"
                    + " work_date=?",
                new String[] {street, date})) {
      return c.moveToFirst()
          ? new String[] {
            c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)
          }
          : null;
    }
  }

  public synchronized int getWeekProgress(String street, String date) {
    String[] range = DayColor.weekRange(date);
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT progress FROM work_entries WHERE street=? AND work_date BETWEEN ? AND ?"
                    + " ORDER BY work_date DESC,id DESC LIMIT 1",
                new String[] {street, range[0], range[1]})) {
      return c.moveToFirst() ? c.getInt(0) : 0;
    }
  }

  public int getCurrentWeekProgress(String street) {
    return getWeekProgress(street, DayColor.today());
  }

  public boolean isDoneThisWeek(String street) {
    return getCurrentWeekProgress(street) == 100;
  }

  public synchronized Map<String, Integer> getWeekProgress(String date) {
    Map<String, Integer> out = new HashMap<>();
    for (String[] row : getWeekEntriesDetailed(date)) out.put(row[0], Integer.parseInt(row[4]));
    return out;
  }

  public Map<String, Integer> getCurrentWeekProgress() {
    return getWeekProgress(DayColor.today());
  }

  public Map<String, Integer> getCurrentWeekColors() {
    Map<String, Integer> out = new HashMap<>();
    for (String[] row : getCurrentWeekEntriesDetailed()) out.put(row[0], Integer.parseInt(row[2]));
    return out;
  }

  public synchronized List<String[]> getWeekEntriesDetailed(String date) {
    List<String[]> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT street,work_date,color,source,progress FROM work_entries WHERE work_date"
                    + " BETWEEN ? AND ? ORDER BY work_date ASC,id ASC",
                DayColor.weekRange(date))) {
      while (c.moveToNext())
        out.add(
            new String[] {
              c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)
            });
    }
    return out;
  }

  public List<String[]> getCurrentWeekEntriesDetailed() {
    return getWeekEntriesDetailed(DayColor.today());
  }

  public int getCurrentWeekCount() {
    return getCurrentWeekProgress().size();
  }

  public int getCompletedCount(String date) {
    int n = 0;
    for (int p : getWeekProgress(date).values()) if (p == 100) n++;
    return n;
  }

  public int getTodayCount() {
    return getTodayStreets().size();
  }

  public synchronized List<String> getTodayStreets() {
    List<String> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT street FROM work_entries WHERE work_date=? ORDER BY street COLLATE NOCASE",
                new String[] {DayColor.today()})) {
      while (c.moveToNext()) out.add(c.getString(0));
    }
    return out;
  }

  public synchronized String getLastTodayStreet() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT label FROM work_actions WHERE action_date=? ORDER BY id DESC LIMIT 1",
                new String[] {DayColor.today()})) {
      return c.moveToFirst() ? c.getString(0) : null;
    }
  }

  public synchronized void undoLastToday() {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try (Cursor c =
        db.rawQuery(
            "SELECT id,payload FROM work_actions WHERE action_date=? ORDER BY id DESC LIMIT 1",
            new String[] {DayColor.today()})) {
      if (c.moveToFirst()) {
        JSONObject payload = new JSONObject(c.getString(1));
        JSONArray keys = payload.getJSONArray("keys");
        for (int i = 0; i < keys.length(); i++) {
          JSONObject key = keys.getJSONObject(i);
          db.delete(
              "work_entries",
              "street=? AND work_date=?",
              new String[] {key.getString("street"), key.getString("work_date")});
        }
        JSONArray before = payload.getJSONArray("before");
        for (int i = 0; i < before.length(); i++) insertWork(before.getJSONObject(i));
        db.delete("work_actions", "id=?", new String[] {c.getString(0)});
      }
      db.setTransactionSuccessful();
    } catch (Exception e) {
      throw failure(e);
    } finally {
      db.endTransaction();
    }
    notifyChanged();
  }

  public int deleteCurrentWeekStreet(String street) {
    return deleteWeekStreet(street, DayColor.today());
  }

  public synchronized int deleteWeekStreet(String street, String date) {
    String[] range = DayColor.weekRange(date);
    return deleteWork(
        "street=? AND work_date BETWEEN ? AND ?",
        new String[] {street, range[0], range[1]},
        street);
  }

  public synchronized int clearCurrentWeek() {
    return deleteWork(
        "work_date BETWEEN ? AND ?",
        DayColor.weekRange(DayColor.today()),
        "Remise à zéro de la semaine");
  }

  private int deleteWork(String where, String[] args, String label) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    int count;
    try {
      JSONArray before = workRows(where, args), keys = new JSONArray();
      for (int i = 0; i < before.length(); i++) {
        JSONObject r = before.getJSONObject(i);
        keys.put(
            new JSONObject()
                .put("street", r.getString("street"))
                .put("work_date", r.getString("work_date")));
      }
      count = db.delete("work_entries", where, args);
      if (count > 0) recordAction(label, before, keys);
      db.setTransactionSuccessful();
    } catch (Exception e) {
      throw failure(e);
    } finally {
      db.endTransaction();
    }
    notifyChanged();
    return count;
  }

  public String findCurrentWeekStreet(String typed) {
    List<String> found =
        StreetResolver.candidates(typed, new ArrayList<>(getCurrentWeekProgress().keySet()));
    return found.size() == 1 ? found.get(0) : null;
  }

  public String getCurrentWeekStart() {
    return DayColor.weekRange(DayColor.today())[0];
  }

  public synchronized List<String> getHistory(int limit) {
    List<String> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT work_date,street,source,progress FROM work_entries ORDER BY work_date"
                    + " DESC,id DESC LIMIT ?",
                new String[] {String.valueOf(limit)})) {
      while (c.moveToNext())
        out.add(
            c.getString(0)
                + " • "
                + c.getString(1)
                + " • "
                + c.getInt(3)
                + " % • "
                + c.getString(2));
    }
    return out;
  }

  public synchronized long addLexicon(String title, String details) {
    ContentValues values = lexiconValues(title, details);
    values.put("created_at", DayColor.today());
    long id = getWritableDatabase().insertOrThrow("lexicon_entries", null, values);
    notifyChanged();
    return id;
  }

  private ContentValues lexiconValues(String title, String details) {
    ContentValues v = new ContentValues();
    v.put("title", requireText(title, "Titre"));
    v.put("details", requireText(details, "Détails"));
    return v;
  }

  public synchronized void updateLexicon(long id, String title, String details) {
    if (getWritableDatabase()
            .update(
                "lexicon_entries",
                lexiconValues(title, details),
                "id=?",
                new String[] {String.valueOf(id)})
        != 1) throw new IllegalStateException("Note introuvable");
    notifyChanged();
  }

  public synchronized String[] findLexiconById(long id) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,title,details,created_at FROM lexicon_entries WHERE id=?",
                new String[] {String.valueOf(id)})) {
      return c.moveToFirst()
          ? new String[] {c.getString(0), c.getString(1), c.getString(2), c.getString(3)}
          : null;
    }
  }

  public synchronized List<String[]> getLexicon() {
    List<String[]> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,title,details,created_at FROM lexicon_entries ORDER BY title COLLATE"
                    + " NOCASE,id",
                null)) {
      while (c.moveToNext())
        out.add(new String[] {c.getString(0), c.getString(1), c.getString(2), c.getString(3)});
    }
    return out;
  }

  public synchronized void deleteLexicon(long id) {
    getWritableDatabase().delete("lexicon_entries", "id=?", new String[] {String.valueOf(id)});
    notifyChanged();
  }

  public synchronized void receiveCommand(JSONObject body) throws Exception {
    String id = requireText(body.optString("id"), "Identifiant de commande");
    ContentValues v = new ContentValues();
    v.put("id", id);
    v.put("body", body.toString());
    v.put("received_at", System.currentTimeMillis());
    long inserted =
        getWritableDatabase()
            .insertWithOnConflict("command_inbox", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    if (inserted < 0) {
      try (Cursor c =
          getReadableDatabase()
              .rawQuery("SELECT result FROM command_inbox WHERE id=?", new String[] {id})) {
        if (!c.moveToFirst())
          throw new IllegalStateException("Commande non conservée sur le téléphone");
        if (!c.isNull(0)) {
          ContentValues retry = new ContentValues();
          retry.put("acknowledged", 0);
          getWritableDatabase().update("command_inbox", retry, "id=?", new String[] {id});
        }
      }
    }
  }

  public synchronized List<JSONObject> pendingCommands() throws Exception {
    List<JSONObject> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT body FROM command_inbox WHERE result IS NULL ORDER BY received_at,id",
                null)) {
      while (c.moveToNext()) out.add(new JSONObject(c.getString(0)));
    }
    return out;
  }

  public synchronized List<JSONObject> pendingResults() throws Exception {
    List<JSONObject> out = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT result FROM command_inbox WHERE result IS NOT NULL AND acknowledged=0 ORDER"
                    + " BY received_at,id",
                null)) {
      while (c.moveToNext()) out.add(new JSONObject(c.getString(0)));
    }
    return out;
  }

  public synchronized JSONObject commandResult(String id) throws Exception {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT result FROM command_inbox WHERE id=?", new String[] {id})) {
      return c.moveToFirst() && !c.isNull(0) ? new JSONObject(c.getString(0)) : null;
    }
  }

  public synchronized void saveResult(String id, JSONObject result) {
    ContentValues v = new ContentValues();
    v.put("result", result.toString());
    v.put("acknowledged", 0);
    if (getWritableDatabase().update("command_inbox", v, "id=?", new String[] {id}) != 1)
      throw new IllegalStateException("Reçu de commande absent");
  }

  public synchronized void acknowledge(String id) {
    ContentValues v = new ContentValues();
    v.put("acknowledged", 1);
    getWritableDatabase().update("command_inbox", v, "id=?", new String[] {id});
  }

  public synchronized int pendingCount() {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery("SELECT COUNT(*) FROM command_inbox WHERE acknowledged=0", null)) {
      return c.moveToFirst() ? c.getInt(0) : 0;
    }
  }

  private JSONArray workRows(String where, String[] args) throws Exception {
    return rows("work_entries", where, args);
  }

  private JSONArray rows(String table, String where, String[] args) throws Exception {
    JSONArray out = new JSONArray();
    try (Cursor c = getReadableDatabase().query(table, null, where, args, null, null, "id ASC")) {
      while (c.moveToNext()) {
        JSONObject r = new JSONObject();
        for (int i = 0; i < c.getColumnCount(); i++)
          r.put(
              c.getColumnName(i),
              c.getType(i) == Cursor.FIELD_TYPE_INTEGER ? c.getLong(i) : c.getString(i));
        out.put(r);
      }
    }
    return out;
  }

  public synchronized JSONObject snapshot() throws Exception {
    SQLiteDatabase db = getReadableDatabase();
    db.beginTransaction();
    try {
      JSONObject out =
          new JSONObject()
              .put("work_entries", rows("work_entries", null, null))
              .put("lexicon_entries", rows("lexicon_entries", null, null));
      db.setTransactionSuccessful();
      return out;
    } finally {
      db.endTransaction();
    }
  }

  private void validateWork(JSONObject r) throws Exception {
    requireText(r.getString("street"), "Rue");
    DayColor.requireDate(r.getString("work_date"));
    requireText(r.getString("source"), "Source");
    DayColor.requireProgress(r.getInt("progress"));
  }

  private void insertWork(JSONObject r) throws Exception {
    validateWork(r);
    ContentValues v = new ContentValues();
    v.put("street", r.getString("street"));
    v.put("work_date", r.getString("work_date"));
    v.put("color", DayColor.forDate(r.getString("work_date")));
    v.put("source", r.getString("source"));
    v.put("progress", r.getInt("progress"));
    if (getWritableDatabase()
            .insertWithOnConflict("work_entries", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        < 0) throw new IllegalStateException("Restauration de rue refusée");
  }

  public synchronized void restoreSnapshot(JSONObject data) throws Exception {
    JSONArray work = data.getJSONArray("work_entries"),
        lexicon = data.getJSONArray("lexicon_entries");
    if (work.length() > 100000 || lexicon.length() > 10000)
      throw new IllegalArgumentException("Sauvegarde trop volumineuse");
    for (int i = 0; i < work.length(); i++) validateWork(work.getJSONObject(i));
    for (int i = 0; i < lexicon.length(); i++) {
      JSONObject r = lexicon.getJSONObject(i);
      lexiconValues(r.getString("title"), r.getString("details"));
      DayColor.requireDate(r.getString("created_at"));
    }
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      db.delete("work_entries", null, null);
      db.delete("lexicon_entries", null, null);
      db.delete("work_actions", null, null);
      // Keep command receipts: restoring a backup must never re-execute an acknowledged command.
      for (int i = 0; i < work.length(); i++) insertWork(work.getJSONObject(i));
      for (int i = 0; i < lexicon.length(); i++) {
        JSONObject r = lexicon.getJSONObject(i);
        ContentValues v = lexiconValues(r.getString("title"), r.getString("details"));
        v.put("id", r.getLong("id"));
        v.put("created_at", r.getString("created_at"));
        db.insertOrThrow("lexicon_entries", null, v);
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    notifyChanged();
  }

  private static RuntimeException failure(Exception e) {
    return e instanceof RuntimeException
        ? (RuntimeException) e
        : new IllegalStateException(e.getMessage(), e);
  }
}
