package com.chasmet.plantravail;

import android.app.Activity;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatButton;

public class DeleteTraceButton extends AppCompatButton {
    public DeleteTraceButton(Context context) { super(context); init(); }
    public DeleteTraceButton(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public DeleteTraceButton(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        setOnClickListener(v -> deleteTrace());
    }

    private void deleteTrace() {
        if (!(getContext() instanceof Activity)) return;
        Activity activity = (Activity) getContext();
        EditText search = activity.findViewById(R.id.etStreetSearch);
        TextView status = activity.findViewById(R.id.tvStatus);
        String typed = search == null ? "" : search.getText().toString().trim();
        if (typed.isEmpty()) {
            Toast.makeText(activity, "Recherchez d'abord la rue à supprimer", Toast.LENGTH_LONG).show();
            return;
        }
        WorkDatabase db = new WorkDatabase(activity);
        String street = db.findCurrentWeekStreet(typed);
        if (street == null) {
            Toast.makeText(activity, "Cette rue n'est pas tracée cette semaine", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Supprimer le tracé")
                .setMessage("Retirer « " + street + " » de la semaine en cours ?")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Supprimer", (d, w) -> {
                    int deleted = db.deleteCurrentWeekStreet(street);
                    if (deleted > 0) {
                        if (status != null) status.setText(street + " • tracé supprimé");
                        Toast.makeText(activity, "Tracé supprimé", Toast.LENGTH_SHORT).show();
                        activity.recreate();
                    } else {
                        Toast.makeText(activity, "Aucun tracé supprimé", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
