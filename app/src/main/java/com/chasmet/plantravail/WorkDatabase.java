package com.chasmet.plantravail;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WorkDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "plan_travail.db";
    private static final int DB_VERSION = 1;

    public WorkDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE work_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, street TEXT NOT NULL, work_date TEXT NOT NULL, color INTEGER NOT NULL, source TEXT NOT NULL, UNIQUE(street, work_date))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public void addOrUpdate(String street, String date, int color, String source) {
        ContentValues values = new ContentValues();
        values.put("street", street);
        values.put("work_date", date);
        values.put("color", color);
        values.put("source", source);
        getWritableDatabase().insertWithOnConflict("work_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Map<String, Integer> getLatestColors() {
        Map<String, Integer> result = new HashMap<>();
        String sql = "SELECT w.street, w.color FROM work_entries w JOIN (SELECT street, MAX(work_date) d FROM work_entries GROUP BY street) x ON x.street=w.street AND x.d=w.work_date";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) result.put(c.getString(0), c.getInt(1));
        }
        return result;
    }

    public List<String> getHistory(int limit) {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT work_date, street, source FROM work_entries ORDER BY work_date DESC, street ASC LIMIT ?", new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) result.add(c.getString(0) + "  •  " + c.getString(1) + "  •  " + c.getString(2));
        }
        return result;
    }
}
