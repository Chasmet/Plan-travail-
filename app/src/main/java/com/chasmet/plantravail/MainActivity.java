package com.chasmet.plantravail;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private MapView map;
    private TextView status;
    private TextView legend;
    private final List<Polyline> streetOverlays = new ArrayList<>();
    private List<Street> streets = new ArrayList<>();
    private StreetRepository repository;
    private WorkDatabase database;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        setContentView(R.layout.activity_main);

        map = findViewById(R.id.map);
        status = findViewById(R.id.tvStatus);
        legend = findViewById(R.id.tvLegend);
        Button refresh = findViewById(R.id.btnRefresh);
        Button sync = findViewById(R.id.btnSync);
        Button history = findViewById(R.id.btnHistory);
        Button settings = findViewById(R.id.btnSettings);

        database = new WorkDatabase(this);
        repository = new StreetRepository(this);

        map.setMultiTouchControls(true);
        map.getController().setZoom(15.0);
        map.getController().setCenter(new GeoPoint(48.6999, 2.1874));
        map.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                selectNearestStreet(p);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                selectNearestStreet(p);
                return true;
            }
        }));

        refresh.setOnClickListener(v -> loadStreets(true));
        sync.setOnClickListener(v -> syncMcp());
        history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        String today = DayColor.today();
        legend.setText(DayColor.dayName(today) + " : couleur du jour • Touchez une rue pour la marquer");
        loadStreets(false);

        if (getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("auto_update", true)) {
            UpdateManager.check(this, null, null, false);
        }
    }

    private void loadStreets(boolean force) {
        status.setText(force ? "Actualisation de toutes les rues d'Orsay…" : "Chargement des rues d'Orsay…");
        repository.load(force, new StreetRepository.Callback() {
            @Override
            public void onLoaded(List<Street> loaded, boolean fromCache) {
                runOnUiThread(() -> {
                    streets = loaded;
                    renderStreets();
                    status.setText(loaded.size() + " tronçons nommés chargés" + (fromCache ? " (cache)" : " (OpenStreetMap)"));
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> status.setText("Erreur : " + message));
            }
        });
    }

    private void renderStreets() {
        for (Polyline overlay : streetOverlays) map.getOverlays().remove(overlay);
        streetOverlays.clear();
        Map<String, Integer> colors = database.getLatestColors();
        for (Street street : streets) {
            Polyline line = new Polyline(map);
            line.setPoints(street.getPoints());
            Integer marked = colors.get(street.getName());
            line.getOutlinePaint().setColor(marked == null ? Color.argb(95, 80, 80, 80) : marked);
            line.getOutlinePaint().setStrokeWidth(marked == null ? 3f : 9f);
            line.setTitle(street.getName());
            streetOverlays.add(line);
            map.getOverlays().add(line);
        }
        map.invalidate();
    }

    private void selectNearestStreet(GeoPoint point) {
        Street nearest = null;
        double best = Double.MAX_VALUE;
        for (Street street : streets) {
            for (GeoPoint p : street.getPoints()) {
                double d = point.distanceToAsDouble(p);
                if (d < best) {
                    best = d;
                    nearest = street;
                }
            }
        }
        if (nearest == null || best > 70.0) {
            Toast.makeText(this, "Touchez plus près d'une rue", Toast.LENGTH_SHORT).show();
            return;
        }
        Street selected = nearest;
        String today = DayColor.today();
        new AlertDialog.Builder(this)
                .setTitle(selected.getName())
                .setMessage("Marquer cette rue comme faite aujourd'hui (" + DayColor.dayName(today) + ") ?")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Marquer", (dialog, which) -> {
                    database.addOrUpdate(selected.getName(), today, DayColor.forDate(today), "manuel");
                    renderStreets();
                    status.setText(selected.getName() + " marquée pour " + today);
                })
                .show();
    }

    private void syncMcp() {
        if (streets.isEmpty()) {
            Toast.makeText(this, "Les rues ne sont pas encore chargées", Toast.LENGTH_SHORT).show();
            return;
        }
        status.setText("Synchronisation MCP…");
        new McpBridgeClient(this, database).sync(streets, new McpBridgeClient.Callback() {
            @Override
            public void onDone(int count) {
                runOnUiThread(() -> {
                    renderStreets();
                    status.setText(count + " rue(s) reçue(s) du MCP");
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> status.setText("MCP : " + message));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
        if (database != null && !streets.isEmpty()) renderStreets();
        UpdateManager.resumePendingInstall(this);
    }

    @Override
    protected void onPause() {
        if (map != null) map.onPause();
        super.onPause();
    }
}
