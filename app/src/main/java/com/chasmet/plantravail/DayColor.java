package com.chasmet.plantravail;

import android.graphics.Color;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class DayColor {
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE);

    private DayColor() {}

    public static String today() {
        return FORMAT.format(new Date());
    }

    public static int forDate(String date) {
        Calendar calendar = Calendar.getInstance(Locale.FRANCE);
        try {
            calendar.setTime(FORMAT.parse(date));
        } catch (ParseException | NullPointerException e) {
            calendar.setTime(new Date());
        }
        switch (calendar.get(Calendar.DAY_OF_WEEK)) {
            case Calendar.MONDAY: return Color.rgb(21, 101, 192);
            case Calendar.TUESDAY: return Color.rgb(46, 125, 50);
            case Calendar.WEDNESDAY: return Color.rgb(239, 108, 0);
            case Calendar.THURSDAY: return Color.rgb(106, 27, 154);
            case Calendar.FRIDAY: return Color.rgb(198, 40, 40);
            case Calendar.SATURDAY: return Color.rgb(0, 131, 143);
            default: return Color.rgb(97, 97, 97);
        }
    }

    public static String dayName(String date) {
        Calendar calendar = Calendar.getInstance(Locale.FRANCE);
        try {
            calendar.setTime(FORMAT.parse(date));
        } catch (Exception e) {
            calendar.setTime(new Date());
        }
        String[] names = {"Dimanche", "Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi"};
        return names[calendar.get(Calendar.DAY_OF_WEEK) - 1];
    }
}
