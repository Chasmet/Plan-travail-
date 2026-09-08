package com.chasmet.plantravail;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WorkDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "plan_travail.db";
    private static final int DB_VERSION = 2;
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE);

    public WorkDatabase(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE work_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, street TEXT NOT NULL, work_date TEXT NOT NULL, color INTEGER NOT NULL, source TEXT NOT NULL, UNIQUE(street, work_date))");
        db.execSQL("CREATE TABLE lexicon_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, details TEXT NOT NULL, created_at TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS lexicon_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, details TEXT NOT NULL, created_at TEXT NOT NULL)");
    }

    public void addOrUpdate(String street, String date, int color, String source) {
        ContentValues values = new ContentValues();
        values.put("street", street); values.put("work_date", date); values.put("color", color); values.put("source", source);
        getWritableDatabase().insertWithOnConflict("work_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Map<String, Integer> getLatestColors() {
        Map<String, Integer> result = new HashMap<>();
        String sql = "SELECT w.street, w.color FROM work_entries w JOIN (SELECT street, MAX(work_date) d FROM work_entries GROUP BY street) x ON x.street=w.street AND x.d=w.work_date";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) { while (c.moveToNext()) result.put(c.getString(0), c.getInt(1)); }
        return result;
    }

    public Map<String, Integer> getCurrentWeekColors() {
        Calendar start = Calendar.getInstance(Locale.FRANCE);
        int dow = start.get(Calendar.DAY_OF_WEEK); int delta = dow == Calendar.SUNDAY ? -6 : Calendar.MONDAY - dow;
        start.add(Calendar.DAY_OF_MONTH, delta); setMidnight(start);
        Calendar end = (Calendar) start.clone(); end.add(Calendar.DAY_OF_MONTH, 6);
        String startDate = FORMAT.format(start.getTime()), endDate = FORMAT.format(end.getTime());
        Map<String, Integer> result = new HashMap<>();
        String sql = "SELECT w.street, w.color FROM work_entries w JOIN (SELECT street, MAX(work_date) d FROM work_entries WHERE work_date BETWEEN ? AND ? GROUP BY street) x ON x.street=w.street AND x.d=w.work_date";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{startDate, endDate})) { while (c.moveToNext()) result.put(c.getString(0), c.getInt(1)); }
        return result;
    }

    public int getCurrentWeekCount() {
        Calendar start = Calendar.getInstance(Locale.FRANCE); int dow = start.get(Calendar.DAY_OF_WEEK); int delta = dow == Calendar.SUNDAY ? -6 : Calendar.MONDAY - dow;
        start.add(Calendar.DAY_OF_MONTH, delta); setMidnight(start); Calendar end = (Calendar) start.clone(); end.add(Calendar.DAY_OF_MONTH, 6);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM work_entries WHERE work_date BETWEEN ? AND ?", new String[]{FORMAT.format(start.getTime()), FORMAT.format(end.getTime())})) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    public String getCurrentWeekStart() {
        Calendar start = Calendar.getInstance(Locale.FRANCE); int dow = start.get(Calendar.DAY_OF_WEEK); int delta = dow == Calendar.SUNDAY ? -6 : Calendar.MONDAY - dow;
        start.add(Calendar.DAY_OF_MONTH, delta); return FORMAT.format(start.getTime());
    }

    private static void setMidnight(Calendar c) { c.set(Calendar.HOUR_OF_DAY,0); c.set(Calendar.MINUTE,0); c.set(Calendar.SECOND,0); c.set(Calendar.MILLISECOND,0); }

    public List<String> getHistory(int limit) {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT work_date, street, source FROM work_entries ORDER BY work_date DESC, street ASC LIMIT ?", new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) result.add(c.getString(0) + "  •  " + c.getString(1) + "  •  " + c.getString(2));
        }
        return result;
    }

    public long addLexicon(String title, String details) {
        ContentValues v = new ContentValues();
        v.put("title", title.trim()); v.put("details", details.trim()); v.put("created_at", FORMAT.format(Calendar.getInstance(Locale.FRANCE).getTime()));
        return getWritableDatabase().insert("lexicon_entries", null, v);
    }

    public List<String[]> getLexicon() {
        List<String[]> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,title,details,created_at FROM lexicon_entries ORDER BY title COLLATE NOCASE ASC", null)) {
            while (c.moveToNext()) result.add(new String[]{c.getString(0), c.getString(1), c.getString(2), c.getString(3)});
        }
        return result;
    }

    public void deleteLexicon(long id) { getWritableDatabase().delete("lexicon_entries", "id=?", new String[]{String.valueOf(id)}); }
}
