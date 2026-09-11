package com.chasmet.plantravail;

import android.graphics.Color;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class DayColor {
  private DayColor() {}

  private static SimpleDateFormat formatter() {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE);
    f.setLenient(false);
    return f;
  }

  public static String today() {
    return formatter().format(new Date());
  }

  public static String requireDate(String date) {
    if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}"))
      throw new IllegalArgumentException("Date attendue : AAAA-MM-JJ");
    ParsePosition position = new ParsePosition(0);
    Date parsed = formatter().parse(date, position);
    if (parsed == null || position.getIndex() != date.length())
      throw new IllegalArgumentException("Date impossible : " + date);
    return date;
  }

  private static Calendar calendar(String date) {
    requireDate(date);
    Calendar c = Calendar.getInstance(Locale.FRANCE);
    c.setTime(formatter().parse(date, new ParsePosition(0)));
    return c;
  }

  public static String[] weekRange(String date) {
    Calendar c = calendar(date);
    int day = c.get(Calendar.DAY_OF_WEEK);
    c.add(Calendar.DAY_OF_MONTH, day == Calendar.SUNDAY ? -6 : Calendar.MONDAY - day);
    String first = formatter().format(c.getTime());
    c.add(Calendar.DAY_OF_MONTH, 6);
    return new String[] {first, formatter().format(c.getTime())};
  }

  public static String shift(String date, int days) {
    Calendar c = calendar(date);
    c.add(Calendar.DAY_OF_MONTH, days);
    return formatter().format(c.getTime());
  }

  public static int forDate(String date) {
    return forDay(calendar(date).get(Calendar.DAY_OF_WEEK));
  }

  public static int forDay(int day) {
    switch (day) {
      case Calendar.MONDAY:
        return Color.rgb(21, 101, 192);
      case Calendar.TUESDAY:
        return Color.rgb(46, 125, 50);
      case Calendar.WEDNESDAY:
        return Color.rgb(239, 108, 0);
      case Calendar.THURSDAY:
        return Color.rgb(106, 27, 154);
      case Calendar.FRIDAY:
        return Color.rgb(198, 40, 40);
      case Calendar.SATURDAY:
        return Color.rgb(0, 131, 143);
      default:
        return Color.rgb(97, 97, 97);
    }
  }

  public static String dayName(String date) {
    return new String[] {"Dimanche", "Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi"}
        [calendar(date).get(Calendar.DAY_OF_WEEK) - 1];
  }

  public static int requireProgress(int progress) {
    if (progress != 0 && progress != 25 && progress != 50 && progress != 75 && progress != 100)
      throw new IllegalArgumentException("Avancement attendu : 0, 25, 50, 75 ou 100 %");
    return progress;
  }
}
