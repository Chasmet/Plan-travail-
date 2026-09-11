package com.chasmet.plantravail;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LexiqueActivity extends DataActivity {
  private WorkDatabase db;
  private ListView list;
  private EditText search;
  private final List<String[]> rows = new ArrayList<>();
  private long selectedId = -1;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_lexique);
    db = WorkDatabase.getInstance(this);
    list = findViewById(R.id.listLexique);
    search = findViewById(R.id.searchLexique);
    findViewById(R.id.btnAddLexique).setOnClickListener(v -> edit(null));
    findViewById(R.id.btnDeleteLexique).setOnClickListener(v -> deleteSelected());
    list.setOnItemClickListener(
        (parent, view, position, id) -> {
          String[] row = rows.get(position);
          selectedId = Long.parseLong(row[0]);
          edit(row);
        });
    list.setOnItemLongClickListener(
        (parent, view, position, id) -> {
          selectedId = Long.parseLong(rows.get(position)[0]);
          deleteSelected();
          return true;
        });
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            onDataChanged();
          }

          public void afterTextChanged(Editable e) {}
        });
    if (state == null && getIntent().hasExtra("street")) edit(null);
    onDataChanged();
  }

  private void deleteSelected() {
    String[] row = db.findLexiconById(selectedId);
    if (row == null) {
      Toast.makeText(
              this, "Ouvre une note ou garde le doigt dessus pour la supprimer", Toast.LENGTH_LONG)
          .show();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle(row[1])
        .setMessage("Supprimer cette note ?")
        .setNegativeButton("Annuler", null)
        .setPositiveButton(
            "Supprimer",
            (d, w) -> {
              db.deleteLexicon(Long.parseLong(row[0]));
              selectedId = -1;
              onDataChanged();
            })
        .show();
  }

  private void edit(String[] row) {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (16 * getResources().getDisplayMetrics().density);
    box.setPadding(pad, pad, pad, 0);
    EditText title = new EditText(this);
    title.setHint("Rue, secteur ou titre");
    title.setSingleLine(true);
    EditText details = new EditText(this);
    details.setHint("Détails / spécificités");
    details.setMinLines(4);
    details.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    if (row != null) {
      title.setText(row[1]);
      details.setText(row[2]);
    } else title.setText(getIntent().getStringExtra("street"));
    box.addView(title);
    box.addView(details);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(row == null ? "Nouvelle note" : "Modifier la note")
            .setView(box)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Enregistrer", null)
            .create();
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      try {
                        if (row == null)
                          db.addLexicon(title.getText().toString(), details.getText().toString());
                        else
                          db.updateLexicon(
                              Long.parseLong(row[0]),
                              title.getText().toString(),
                              details.getText().toString());
                        dialog.dismiss();
                        onDataChanged();
                      } catch (Exception e) {
                        details.setError(e.getMessage());
                      }
                    }));
    dialog.show();
  }

  @Override
  protected void onDataChanged() {
    if (db == null) return;
    rows.clear();
    List<String> display = new ArrayList<>();
    String query = search.getText().toString().toLowerCase(Locale.FRENCH).trim();
    for (String[] row : db.getLexicon())
      if ((row[1] + " " + row[2]).toLowerCase(Locale.FRENCH).contains(query)) {
        rows.add(row);
        display.add(row[1] + "\n" + row[2] + "\nCréée le " + row[3]);
      }
    list.setAdapter(new TextRows(this, display));
  }
}
