package com.chasmet.plantravail;

import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class LexiqueActivity extends AppCompatActivity {
    private WorkDatabase database;
    private ListView listView;
    private final List<String[]> rows = new ArrayList<>();
    private int selectedPosition = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lexique);
        database = new WorkDatabase(this);
        listView = findViewById(R.id.listLexique);
        Button add = findViewById(R.id.btnAddLexique);
        Button delete = findViewById(R.id.btnDeleteLexique);

        add.setOnClickListener(v -> showAddDialog());
        delete.setOnClickListener(v -> deleteSelected());

        listView.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            listView.setItemChecked(position, true);
            Toast.makeText(this, "Sélectionné : " + rows.get(position)[1], Toast.LENGTH_SHORT).show();
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            deleteSelected();
            return true;
        });
        refresh();
    }

    private void deleteSelected() {
        if (selectedPosition < 0 || selectedPosition >= rows.size()) {
            Toast.makeText(this, "Sélectionne d'abord une entrée", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] row = rows.get(selectedPosition);
        new AlertDialog.Builder(this)
                .setTitle(row[1])
                .setMessage("Supprimer cette spécificité ?")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Supprimer", (d, w) -> {
                    database.deleteLexicon(Long.parseLong(row[0]));
                    selectedPosition = -1;
                    refresh();
                })
                .show();
    }

    private void showAddDialog() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        EditText title = new EditText(this);
        title.setHint("Titre : ex. Rue étroite, sacs, stationnement...");
        EditText details = new EditText(this);
        details.setHint("Détails / spécificités");
        details.setMinLines(4);
        details.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(title);
        box.addView(details);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Nouvelle spécificité")
                .setView(box)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Enregistrer", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String t = title.getText().toString().trim();
            String d = details.getText().toString().trim();
            if (t.isEmpty() || d.isEmpty()) {
                Toast.makeText(this, "Titre et détails obligatoires", Toast.LENGTH_SHORT).show();
                return;
            }
            database.addLexicon(t, d);
            dialog.dismiss();
            refresh();
        }));
        dialog.show();
    }

    private void refresh() {
        rows.clear();
        rows.addAll(database.getLexicon());
        List<String> display = new ArrayList<>();
        for (String[] r : rows) display.add(r[1] + "\n" + r[2] + "\nAjouté le " + r[3]);
        listView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, display));
        listView.clearChoices();
    }
}
