package com.chasmet.plantravail;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class HistoryActivity extends DataActivity {
  private String date = DayColor.today();
  private WorkDatabase db;
  private TextView title;
  private ListView list;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    if (state != null) date = state.getString("date", date);
    db = WorkDatabase.getInstance(this);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(16, 16, 16, 16);
    box.setBackgroundColor(Color.BLACK);
    setContentView(box);
    title = new TextView(this);
    title.setTextColor(Color.WHITE);
    title.setTextSize(20);
    box.addView(title);
    LinearLayout navigation = new LinearLayout(this);
    box.addView(navigation);
    Button previous = button("←", navigation);
    previous.setContentDescription("Semaine précédente");
    previous.setOnClickListener(
        v -> {
          date = DayColor.shift(date, -7);
          onDataChanged();
        });
    button("Choisir une date", navigation)
        .setOnClickListener(
            v -> {
              Calendar c = Calendar.getInstance();
              c.set(
                  Integer.parseInt(date.substring(0, 4)),
                  Integer.parseInt(date.substring(5, 7)) - 1,
                  Integer.parseInt(date.substring(8, 10)));
              new DatePickerDialog(
                      this,
                      (picker, y, m, d) -> {
                        date = String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d);
                        onDataChanged();
                      },
                      c.get(Calendar.YEAR),
                      c.get(Calendar.MONTH),
                      c.get(Calendar.DAY_OF_MONTH))
                  .show();
            });
    Button next = button("→", navigation);
    next.setContentDescription("Semaine suivante");
    next.setOnClickListener(
        v -> {
          date = DayColor.shift(date, 7);
          onDataChanged();
        });
    button("Afficher cette semaine sur la carte", box)
        .setOnClickListener(
            v ->
                startActivity(
                    new Intent(this, MainActivity.class)
                        .putExtra("week", date)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
    list = new ListView(this);
    box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    onDataChanged();
  }

  private Button button(String text, LinearLayout parent) {
    Button b = new Button(this);
    b.setText(text);
    parent.addView(b);
    return b;
  }

  @Override
  protected void onDataChanged() {
    if (db == null) return;
    title.setText("Semaine du " + DayColor.weekRange(date)[0]);
    List<String> display = new ArrayList<>();
    for (String[] r : db.getWeekEntriesDetailed(date))
      display.add(r[1] + " • " + r[0] + " • " + r[4] + " %");
    if (display.isEmpty()) display.add("Aucun tracé enregistré cette semaine");
    list.setAdapter(new TextRows(this, display));
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("date", date);
  }
}
