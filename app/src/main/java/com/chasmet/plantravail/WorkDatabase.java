package com.chasmet.plantravail;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WorkDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME="plan_travail.db"; private static final int DB_VERSION=3;
    private static final SimpleDateFormat FORMAT=new SimpleDateFormat("yyyy-MM-dd",Locale.FRANCE);
    public WorkDatabase(Context context){super(context,DB_NAME,null,DB_VERSION);}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE work_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, street TEXT NOT NULL, work_date TEXT NOT NULL, color INTEGER NOT NULL, source TEXT NOT NULL, progress INTEGER NOT NULL DEFAULT 100, UNIQUE(street, work_date))");db.execSQL("CREATE TABLE lexicon_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, details TEXT NOT NULL, created_at TEXT NOT NULL)");}
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){if(oldVersion<2)db.execSQL("CREATE TABLE IF NOT EXISTS lexicon_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, details TEXT NOT NULL, created_at TEXT NOT NULL)");if(oldVersion<3){try{db.execSQL("ALTER TABLE work_entries ADD COLUMN progress INTEGER NOT NULL DEFAULT 100");}catch(Exception ignored){}}}
    public void addOrUpdate(String street,String date,int color,String source){addOrUpdate(street,date,color,source,100);}
    public void addOrUpdate(String street,String date,int color,String source,int progress){int p=normalizeProgress(progress);int previous=getCurrentWeekProgress(street);if(previous>p)p=previous;ContentValues v=new ContentValues();v.put("street",street);v.put("work_date",date);v.put("color",color);v.put("source",source);v.put("progress",p);getWritableDatabase().insertWithOnConflict("work_entries",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    public boolean isDoneThisWeek(String street){return getCurrentWeekProgress(street)>=100;}
    public int getCurrentWeekProgress(String street){String[] r=weekRange();try(Cursor c=getReadableDatabase().rawQuery("SELECT progress FROM work_entries WHERE street=? AND work_date BETWEEN ? AND ? ORDER BY work_date DESC,id DESC LIMIT 1",new String[]{street,r[0],r[1]})){return c.moveToFirst()?c.getInt(0):0;}}
    public Map<String,Integer> getCurrentWeekProgress(){String[] r=weekRange();Map<String,Integer> out=new HashMap<>();String sql="SELECT w.street,w.progress FROM work_entries w JOIN (SELECT street,MAX(work_date) d FROM work_entries WHERE work_date BETWEEN ? AND ? GROUP BY street)x ON x.street=w.street AND x.d=w.work_date";try(Cursor c=getReadableDatabase().rawQuery(sql,r)){while(c.moveToNext())out.put(c.getString(0),c.getInt(1));}return out;}
    public int deleteCurrentWeekStreet(String street){String[] r=weekRange();return getWritableDatabase().delete("work_entries","street=? AND work_date BETWEEN ? AND ?",new String[]{street,r[0],r[1]});}
    public int clearCurrentWeek(){String[] r=weekRange();return getWritableDatabase().delete("work_entries","work_date BETWEEN ? AND ?",r);}
    public String findCurrentWeekStreet(String typed){String wanted=normalize(typed);if(wanted.isEmpty())return null;String[] r=weekRange();try(Cursor c=getReadableDatabase().rawQuery("SELECT DISTINCT street FROM work_entries WHERE work_date BETWEEN ? AND ?",r)){String partial=null;while(c.moveToNext()){String street=c.getString(0),candidate=normalize(street);if(candidate.equals(wanted))return street;if(candidate.contains(wanted)||wanted.contains(candidate))partial=street;}return partial;}}
    public Map<String,Integer> getCurrentWeekColors(){String[] r=weekRange();Map<String,Integer> out=new HashMap<>();String sql="SELECT w.street,w.color FROM work_entries w JOIN (SELECT street,MAX(work_date) d FROM work_entries WHERE work_date BETWEEN ? AND ? GROUP BY street)x ON x.street=w.street AND x.d=w.work_date";try(Cursor c=getReadableDatabase().rawQuery(sql,r)){while(c.moveToNext())out.put(c.getString(0),c.getInt(1));}return out;}
    public List<String[]> getCurrentWeekEntriesDetailed(){String[] r=weekRange();List<String[]> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT street,work_date,color,source,progress FROM work_entries WHERE work_date BETWEEN ? AND ? ORDER BY work_date ASC,id ASC",r)){while(c.moveToNext())out.add(new String[]{c.getString(0),c.getString(1),String.valueOf(c.getInt(2)),c.getString(3),String.valueOf(c.getInt(4))});}return out;}
    public int getCurrentWeekCount(){String[] r=weekRange();try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(DISTINCT street) FROM work_entries WHERE work_date BETWEEN ? AND ?",r)){return c.moveToFirst()?c.getInt(0):0;}}
    public int getTodayCount(){String today=FORMAT.format(Calendar.getInstance(Locale.FRANCE).getTime());try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(DISTINCT street) FROM work_entries WHERE work_date=?",new String[]{today})){return c.moveToFirst()?c.getInt(0):0;}}
    public List<String> getTodayStreets(){String today=FORMAT.format(Calendar.getInstance(Locale.FRANCE).getTime());List<String> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT street FROM work_entries WHERE work_date=? ORDER BY street COLLATE NOCASE",new String[]{today})){while(c.moveToNext())out.add(c.getString(0));}return out;}
    public String getLastTodayStreet(){String today=FORMAT.format(Calendar.getInstance(Locale.FRANCE).getTime());try(Cursor c=getReadableDatabase().rawQuery("SELECT street FROM work_entries WHERE work_date=? ORDER BY id DESC LIMIT 1",new String[]{today})){return c.moveToFirst()?c.getString(0):null;}}
    public void undoLastToday(){String today=FORMAT.format(Calendar.getInstance(Locale.FRANCE).getTime());getWritableDatabase().execSQL("DELETE FROM work_entries WHERE id=(SELECT id FROM work_entries WHERE work_date=? ORDER BY id DESC LIMIT 1)",new Object[]{today});}
    public String getCurrentWeekStart(){return weekRange()[0];}
    private String[] weekRange(){Calendar s=Calendar.getInstance(Locale.FRANCE);int d=s.get(Calendar.DAY_OF_WEEK),delta=d==Calendar.SUNDAY?-6:Calendar.MONDAY-d;s.add(Calendar.DAY_OF_MONTH,delta);Calendar e=(Calendar)s.clone();e.add(Calendar.DAY_OF_MONTH,6);return new String[]{FORMAT.format(s.getTime()),FORMAT.format(e.getTime())};}
    public List<String> getHistory(int limit){List<String> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT work_date,street,source,progress FROM work_entries ORDER BY work_date DESC,id DESC LIMIT ?",new String[]{String.valueOf(limit)})){while(c.moveToNext())out.add(c.getString(0)+"  •  "+c.getString(1)+"  •  "+c.getInt(3)+"%  •  "+c.getString(2));}return out;}
    public long addLexicon(String title,String details){ContentValues v=new ContentValues();v.put("title",title.trim());v.put("details",details.trim());v.put("created_at",FORMAT.format(Calendar.getInstance(Locale.FRANCE).getTime()));return getWritableDatabase().insertOrThrow("lexicon_entries",null,v);}
    public String[] findLexiconById(long id){try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,details,created_at FROM lexicon_entries WHERE id=? LIMIT 1",new String[]{String.valueOf(id)})){if(c.moveToFirst())return new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3)};}return null;}
    public List<String[]> getLexicon(){List<String[]> out=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title,details,created_at FROM lexicon_entries ORDER BY title COLLATE NOCASE",null)){while(c.moveToNext())out.add(new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3)});}return out;}
    public void deleteLexicon(long id){getWritableDatabase().delete("lexicon_entries","id=?",new String[]{String.valueOf(id)});}
    private static int normalizeProgress(int progress){if(progress<=25)return 25;if(progress<=50)return 50;if(progress<=75)return 75;return 100;}
    private static String normalize(String value){if(value==null)return "";return Normalizer.normalize(value,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.FRANCE).replace("avenue","").replace("boulevard","").replace("rue","").replace("chemin","").replace("allee","").replace("allée","").replace("-"," ").replace("'"," ").replaceAll("\\s+"," ").trim();}
}
