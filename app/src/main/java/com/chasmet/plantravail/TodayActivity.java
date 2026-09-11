package com.chasmet.plantravail;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.ArrayList;
import java.util.List;

public class TodayActivity extends DataActivity {
  private WorkDatabase db;
  private TextView summary;
  private ListView list;

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    setContentView(R.layout.activity_today);
    db = WorkDatabase.getInstance(this);
    summary = findViewById(R.id.tvTodaySummary);
    list = findViewById(R.id.listToday);
    ((Button) findViewById(R.id.btnUndoToday)).setText("Annuler la dernière modification");
    findViewById(R.id.btnUndoToday)
        .setOnClickListener(
            v -> {
              String last = db.getLastTodayStreet();
              if (last == null) {
                Toast.makeText(
                        this, "Aucune modification à annuler aujourd’hui", Toast.LENGTH_SHORT)
                    .show();
                return;
              }
              new AlertDialog.Builder(this)
                  .setTitle("Annuler : " + last + " ?")
                  .setMessage("L’état précédent sera rétabli, y compris le pourcentage.")
                  .setNegativeButton("Conserver", null)
                  .setPositiveButton(
                      "Annuler la modification",
                      (d, w) -> {
                        db.undoLastToday();
                        onDataChanged();
                      })
                  .show();
            });
    onDataChanged();
  }

  @Override
  protected void onDataChanged() {
    if (db == null) return;
    List<String> display = new ArrayList<>();
    int complete = 0;
    for (String[] row : db.getWeekEntriesDetailed(DayColor.today()))
      if (row[1].equals(DayColor.today())) {
        display.add(row[0] + " • " + row[4] + " %");
        if ("100".equals(row[4])) complete++;
      }
    String last = db.getLastTodayStreet();
    summary.setText(
        display.size()
            + " rues renseignées aujourd’hui • "
            + complete
            + " à 100 %"
            + (last == null ? "" : "\nDernière modification : " + last));
    list.setAdapter(new TextRows(this, display));
  }
}
