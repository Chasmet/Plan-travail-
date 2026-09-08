package com.chasmet.plantravail;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Polyline;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private MapView map;
    private TextView status;
    private TextView legend;
    private EditText streetSearch;
    private final List<Polyline> streetOverlays = new ArrayList<>();
    private List<Street> streets = new ArrayList<>();
    private StreetRepository repository;
    private WorkDatabase database;
    private BoundingBox orsayBounds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        setContentView(R.layout.activity_main);

        map = findViewById(R.id.map);
        status = findViewById(R.id.tvStatus);
        legend = findViewById(R.id.tvLegend);
        streetSearch = findViewById(R.id.etStreetSearch);
        Button search = findViewById(R.id.btnSearch);
        Button refresh = findViewById(R.id.btnRefresh);
        Button sync = findViewById(R.id.btnSync);
        Button history = findViewById(R.id.btnHistory);
        Button settings = findViewById(R.id.btnSettings);

        database = new WorkDatabase(this);
        repository = new StreetRepository(this);

        map.setMultiTouchControls(true);
        map.setHorizontalMapRepetitionEnabled(false);
        map.setVerticalMapRepetitionEnabled(false);
        map.setMinZoomLevel(14.2);
        map.setMaxZoomLevel(20.0);
        map.getController().setZoom(14.8);
        map.getController().setCenter(new GeoPoint(48.6993, 2.1875));
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

        search.setOnClickListener(v -> searchStreet());
        streetSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchStreet();
                return true;
            }
            return false;
        });
        refresh.setOnClickListener(v -> loadStreets(true));
        sync.setOnClickListener(v -> syncMcp());
        history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        String today = DayColor.today();
        legend.setText(DayColor.dayName(today) + " : couleur du jour • carte limitée à Orsay • touchez une rue pour la marquer");
        loadStreets(false);

        if (getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("auto_update", true)) {
            UpdateManager.check(this, null, null, false);
        }
    }

    private void loadStreets(boolean force) {
        status.setText(force ? "Actualisation des rues d'Orsay…" : "Chargement des rues d'Orsay…");
        repository.load(force, new StreetRepository.Callback() {
            @Override
            public void onLoaded(List<Street> loaded, boolean fromCache) {
                runOnUiThread(() -> {
                    streets = loaded;
                    applyOrsayMapLimits();
                    renderStreets();
                    status.setText(uniqueStreetCount() + " rues d'Orsay chargées" + (fromCache ? " (cache)" : " (OpenStreetMap)"));
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> status.setText("Erreur : " + message));
            }
        });
    }

    private void applyOrsayMapLimits() {
        if (streets.isEmpty()) return;
        double north = -90.0;
        double south = 90.0;
        double east = -180.0;
        double west = 180.0;
        for (Street street : streets) {
            for (GeoPoint p : street.getPoints()) {
                north = Math.max(north, p.getLatitude());
                south = Math.min(south, p.getLatitude());
                east = Math.max(east, p.getLongitude());
                west = Math.min(west, p.getLongitude());
            }
        }
        if (north <= south || east <= west) return;

        // Petite marge uniquement pour ne pas couper une rue en bordure de commune.
        double latMargin = Math.max(0.0008, (north - south) * 0.025);
        double lonMargin = Math.max(0.0008, (east - west) * 0.025);
        orsayBounds = new BoundingBox(
                north + latMargin,
                east + lonMargin,
                south - latMargin,
                west - lonMargin
        );
        map.setScrollableAreaLimitDouble(orsayBounds);
        map.getController().setCenter(new GeoPoint(
                (north + south) / 2.0,
                (east + west) / 2.0
        ));
        map.getController().setZoom(14.8);
    }

    private int uniqueStreetCount() {
        Map<String, Boolean> names = new LinkedHashMap<>();
        for (Street street : streets) names.put(street.getName(), true);
        return names.size();
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

    private void searchStreet() {
        if (streets.isEmpty()) {
            Toast.makeText(this, "Les rues d'Orsay sont encore en chargement", Toast.LENGTH_SHORT).show();
            return;
        }
        String query = streetSearch.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Écrivez le nom d'une rue", Toast.LENGTH_SHORT).show();
            return;
        }

        String normalizedQuery = normalize(query);
        LinkedHashMap<String, List<Street>> grouped = new LinkedHashMap<>();
        for (Street street : streets) {
            if (normalize(street.getName()).contains(normalizedQuery)) {
                grouped.computeIfAbsent(street.getName(), key -> new ArrayList<>()).add(street);
            }
        }

        if (grouped.isEmpty()) {
            Toast.makeText(this, "Rue introuvable dans Orsay", Toast.LENGTH_SHORT).show();
            status.setText("Aucune rue d'Orsay trouvée pour « " + query + " »");
            return;
        }

        if (grouped.size() == 1) {
            Map.Entry<String, List<Street>> match = grouped.entrySet().iterator().next();
            focusStreet(match.getKey(), match.getValue());
            return;
        }

        List<String> names = new ArrayList<>(grouped.keySet());
        if (names.size() > 25) names = new ArrayList<>(names.subList(0, 25));
        String[] choices = names.toArray(new String[0]);
        List<String> finalNames = names;
        new AlertDialog.Builder(this)
                .setTitle("Rues trouvées dans Orsay")
                .setItems(choices, (dialog, which) -> {
                    String name = finalNames.get(which);
                    focusStreet(name, grouped.get(name));
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void focusStreet(String name, List<Street> matches) {
        if (matches == null || matches.isEmpty()) return;
        double north = -90.0;
        double south = 90.0;
        double east = -180.0;
        double west = 180.0;
        for (Street street : matches) {
            for (GeoPoint p : street.getPoints()) {
                north = Math.max(north, p.getLatitude());
                south = Math.min(south, p.getLatitude());
                east = Math.max(east, p.getLongitude());
                west = Math.min(west, p.getLongitude());
            }
        }
        double latPad = Math.max(0.00035, (north - south) * 0.25);
        double lonPad = Math.max(0.00035, (east - west) * 0.25);
        BoundingBox target = new BoundingBox(north + latPad, east + lonPad, south - latPad, west - lonPad);
        map.zoomToBoundingBox(target, true, 90);
        if (map.getZoomLevelDouble() > 19.0) map.getController().setZoom(19.0);
        status.setText(name + " • Orsay");
    }

    private String normalize(String value) {
        String n = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.FRANCE);
        return n.replace("'", " ").replace("-", " ").replaceAll("\\s+", " ").trim();
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
            Toast.makeText(this, "Touchez plus près d'une rue d'Orsay", Toast.LENGTH_SHORT).show();
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
