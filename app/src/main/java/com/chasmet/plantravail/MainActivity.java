package com.chasmet.plantravail;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Html;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private OrsayMapView map;
    private TextView status;
    private TextView legend;
    private EditText streetSearch;
    private final List<Polyline> streetOverlays = new ArrayList<>();
    private List<Street> streets = new ArrayList<>();
    private StreetRepository repository;
    private OrsayBoundaryRepository boundaryRepository;
    private WorkDatabase database;
    private BoundingBox orsayBounds;
    private List<GeoPoint> orsayBoundary = new ArrayList<>();

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
        Button export = findViewById(R.id.btnExport);
        Button sync = findViewById(R.id.btnSync);
        Button history = findViewById(R.id.btnHistory);
        Button settings = findViewById(R.id.btnSettings);

        database = new WorkDatabase(this);
        repository = new StreetRepository(this);
        boundaryRepository = new OrsayBoundaryRepository(this);

        map.setMultiTouchControls(true);
        map.setHorizontalMapRepetitionEnabled(false);
        map.setVerticalMapRepetitionEnabled(false);
        map.setMinZoomLevel(14.0);
        map.setMaxZoomLevel(20.0);
        map.getController().setZoom(14.7);
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
        refresh.setOnClickListener(v -> {
            loadBoundary(true);
            loadStreets(true);
        });
        export.setOnClickListener(v -> exportWeeklyMap());
        sync.setOnClickListener(v -> syncMcp());
        history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        updateLegend();
        loadBoundary(false);
        loadStreets(false);

        if (getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("auto_update", true)) {
            UpdateManager.check(this, null, null, false);
        }
    }

    private void updateLegend() {
        String html = "<b>Semaine en cours</b> • " +
                "<font color='#1565C0'>■ Lundi</font>  " +
                "<font color='#2E7D32'>■ Mardi</font>  " +
                "<font color='#EF6C00'>■ Mercredi</font>  " +
                "<font color='#6A1B9A'>■ Jeudi</font>  " +
                "<font color='#C62828'>■ Vendredi</font>";
        legend.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
    }

    private void loadBoundary(boolean force) {
        boundaryRepository.load(force, new OrsayBoundaryRepository.Callback() {
            @Override
            public void onLoaded(List<GeoPoint> boundary, boolean fromCache) {
                runOnUiThread(() -> {
                    orsayBoundary = boundary;
                    map.setBoundary(boundary);
                    applyOrsayBoundaryLimits();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> status.setText("Contour d'Orsay : " + message));
            }
        });
    }

    private void loadStreets(boolean force) {
        status.setText(force ? "Actualisation des rues d'Orsay…" : "Chargement des rues d'Orsay…");
        repository.load(force, new StreetRepository.Callback() {
            @Override
            public void onLoaded(List<Street> loaded, boolean fromCache) {
                runOnUiThread(() -> {
                    streets = loaded;
                    if (orsayBoundary.isEmpty()) applyStreetFallbackLimits();
                    renderStreets();
                    status.setText(uniqueStreetCount() + " rues d'Orsay • " + database.getCurrentWeekCount() + " effectuée(s) cette semaine" + (fromCache ? " (cache)" : ""));
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> status.setText("Erreur : " + message));
            }
        });
    }

    private void applyOrsayBoundaryLimits() {
        if (orsayBoundary.size() < 3) return;
        double north = -90.0;
        double south = 90.0;
        double east = -180.0;
        double west = 180.0;
        for (GeoPoint p : orsayBoundary) {
            north = Math.max(north, p.getLatitude());
            south = Math.min(south, p.getLatitude());
            east = Math.max(east, p.getLongitude());
            west = Math.min(west, p.getLongitude());
        }
        if (north <= south || east <= west) return;
        orsayBounds = new BoundingBox(north, east, south, west);
        map.setScrollableAreaLimitDouble(orsayBounds);
        map.post(() -> map.zoomToBoundingBox(orsayBounds, false, 20));
    }

    private void applyStreetFallbackLimits() {
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
        orsayBounds = new BoundingBox(north, east, south, west);
        map.setScrollableAreaLimitDouble(orsayBounds);
        map.post(() -> map.zoomToBoundingBox(orsayBounds, false, 20));
    }

    private int uniqueStreetCount() {
        Map<String, Boolean> names = new LinkedHashMap<>();
        for (Street street : streets) names.put(street.getName(), true);
        return names.size();
    }

    private void renderStreets() {
        for (Polyline overlay : streetOverlays) map.getOverlays().remove(overlay);
        streetOverlays.clear();
        Map<String, Integer> colors = database.getCurrentWeekColors();
        for (Street street : streets) {
            Polyline line = new Polyline(map);
            line.setPoints(street.getPoints());
            Integer marked = colors.get(street.getName());
            line.getOutlinePaint().setColor(marked == null ? Color.argb(90, 70, 70, 70) : marked);
            line.getOutlinePaint().setStrokeWidth(marked == null ? 3f : 10f);
            line.setTitle(street.getName());
            streetOverlays.add(line);
            map.getOverlays().add(line);
        }
        map.invalidate();
    }

    private void searchStreet() {
        hideKeyboard();
        if (streets.isEmpty()) {
            Toast.makeText(this, "Les rues d'Orsay sont encore en chargement", Toast.LENGTH_SHORT).show();
            return;
        }
        String rawQuery = streetSearch.getText().toString().trim();
        if (rawQuery.isEmpty()) {
            Toast.makeText(this, "Écrivez le nom d'une rue", Toast.LENGTH_SHORT).show();
            return;
        }

        String normalizedQuery = normalize(rawQuery);
        List<String> queryTokens = usefulTokens(normalizedQuery);
        if (queryTokens.isEmpty()) queryTokens = Arrays.asList(normalizedQuery.split(" "));

        LinkedHashMap<String, List<Street>> grouped = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> scores = new LinkedHashMap<>();
        for (Street street : streets) {
            String name = street.getName();
            int score = fuzzyScore(name, normalizedQuery, queryTokens);
            if (score > 0) {
                grouped.computeIfAbsent(name, key -> new ArrayList<>()).add(street);
                Integer old = scores.get(name);
                if (old == null || score > old) scores.put(name, score);
            }
        }

        if (grouped.isEmpty()) {
            Toast.makeText(this, "Rue introuvable dans Orsay", Toast.LENGTH_SHORT).show();
            status.setText("Aucun résultat pour « " + rawQuery + " »");
            return;
        }

        List<String> names = new ArrayList<>(grouped.keySet());
        names.sort((a, b) -> Integer.compare(scores.get(b), scores.get(a)));

        if (names.size() == 1 || scores.get(names.get(0)) >= 90) {
            String best = names.get(0);
            focusStreet(best, grouped.get(best));
            return;
        }

        if (names.size() > 20) names = new ArrayList<>(names.subList(0, 20));
        String[] choices = names.toArray(new String[0]);
        List<String> finalNames = names;
        new AlertDialog.Builder(this)
                .setTitle("Résultats dans Orsay")
                .setItems(choices, (dialog, which) -> {
                    String name = finalNames.get(which);
                    focusStreet(name, grouped.get(name));
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private int fuzzyScore(String streetName, String normalizedQuery, List<String> queryTokens) {
        String street = normalize(streetName);
        String simplifiedStreet = removeStreetWords(street);
        String simplifiedQuery = removeStreetWords(normalizedQuery);

        if (street.equals(normalizedQuery) || simplifiedStreet.equals(simplifiedQuery)) return 120;
        if (street.contains(normalizedQuery) || normalizedQuery.contains(street)) return 105;
        if (!simplifiedQuery.isEmpty() && (simplifiedStreet.contains(simplifiedQuery) || simplifiedQuery.contains(simplifiedStreet))) return 100;

        String[] streetTokens = simplifiedStreet.split(" ");
        int total = 0;
        int matched = 0;
        for (String queryToken : queryTokens) {
            if (queryToken.length() < 2) continue;
            int best = 0;
            for (String streetToken : streetTokens) {
                if (streetToken.equals(queryToken)) best = Math.max(best, 30);
                else if (streetToken.startsWith(queryToken) || queryToken.startsWith(streetToken)) best = Math.max(best, 24);
                else if (streetToken.contains(queryToken) || queryToken.contains(streetToken)) best = Math.max(best, 18);
                else {
                    int distance = levenshtein(streetToken, queryToken);
                    int maxAllowed = queryToken.length() >= 7 ? 2 : 1;
                    if (distance <= maxAllowed) best = Math.max(best, 14);
                }
            }
            if (best > 0) {
                total += best;
                matched++;
            }
        }
        if (matched == 0) return 0;
        if (queryTokens.size() > 1 && matched < Math.max(1, queryTokens.size() - 1)) return 0;
        return total;
    }

    private String removeStreetWords(String value) {
        List<String> ignored = Arrays.asList("orsay", "rue", "avenue", "av", "boulevard", "bd", "route", "chemin", "allee", "impasse", "place", "square", "sentier", "passage", "de", "du", "des", "la", "le", "les", "d");
        StringBuilder sb = new StringBuilder();
        for (String token : value.split(" ")) {
            if (token.length() > 0 && !ignored.contains(token)) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(token);
            }
        }
        return sb.toString();
    }

    private List<String> usefulTokens(String normalized) {
        String clean = removeStreetWords(normalized);
        List<String> result = new ArrayList<>();
        for (String token : clean.split(" ")) if (token.length() >= 2) result.add(token);
        return result;
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }
        return prev[b.length()];
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
        streetSearch.setText(name);
        streetSearch.setSelection(name.length());
        status.setText("Trouvé : " + name + " • Orsay");
    }

    private String normalize(String value) {
        String n = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.FRANCE);
        return n.replace("'", " ").replace("-", " ").replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private void hideKeyboard() {
        View current = getCurrentFocus();
        if (current != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(current.getWindowToken(), 0);
            current.clearFocus();
        }
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
        int color = DayColor.forDate(today);
        new AlertDialog.Builder(this)
                .setTitle(selected.getName())
                .setMessage("Marquer cette rue comme faite aujourd'hui (" + DayColor.dayName(today) + ") ?")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Marquer", (dialog, which) -> {
                    database.addOrUpdate(selected.getName(), today, color, "manuel");
                    renderStreets();
                    status.setText(selected.getName() + " • " + DayColor.dayName(today) + " • enregistrée");
                })
                .show();
    }

    private void exportWeeklyMap() {
        if (orsayBounds == null || map.getWidth() <= 0 || map.getHeight() <= 0) {
            Toast.makeText(this, "La carte d'Orsay n'est pas encore prête", Toast.LENGTH_SHORT).show();
            return;
        }
        status.setText("Préparation de la carte de la semaine…");
        map.zoomToBoundingBox(orsayBounds, false, 20);
        map.postDelayed(this::captureAndSaveMap, 900);
    }

    private void captureAndSaveMap() {
        try {
            int mapWidth = map.getWidth();
            int mapHeight = map.getHeight();
            int header = 150;
            Bitmap bitmap = Bitmap.createBitmap(mapWidth, mapHeight + header, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTextSize(34f);
            paint.setFakeBoldText(true);
            canvas.drawText("Plan Travail Orsay — semaine du " + database.getCurrentWeekStart(), 24, 44, paint);
            paint.setTextSize(23f);
            paint.setFakeBoldText(false);
            canvas.drawText(database.getCurrentWeekCount() + " rue(s) effectuée(s)", 24, 78, paint);

            drawLegendItem(canvas, paint, 24, 118, DayColor.forDate(mondayDate()), "Lundi");
            drawLegendItem(canvas, paint, 155, 118, DayColor.forDate(offsetDate(1)), "Mardi");
            drawLegendItem(canvas, paint, 286, 118, DayColor.forDate(offsetDate(2)), "Mercredi");
            drawLegendItem(canvas, paint, 455, 118, DayColor.forDate(offsetDate(3)), "Jeudi");
            drawLegendItem(canvas, paint, 575, 118, DayColor.forDate(offsetDate(4)), "Vendredi");

            canvas.save();
            canvas.translate(0, header);
            map.draw(canvas);
            canvas.restore();

            String fileName = "Plan_Travail_Orsay_Semaine_" + database.getCurrentWeekStart() + ".png";
            saveBitmap(bitmap, fileName);
            bitmap.recycle();
            status.setText("Carte téléchargée : " + fileName);
            Toast.makeText(this, "Carte enregistrée dans Images", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            status.setText("Export impossible : " + e.getMessage());
            Toast.makeText(this, "Impossible d'enregistrer la carte", Toast.LENGTH_LONG).show();
        }
    }

    private void drawLegendItem(Canvas canvas, Paint paint, float x, float y, int color, String label) {
        paint.setColor(color);
        canvas.drawRect(x, y - 20, x + 22, y + 2, paint);
        paint.setColor(Color.BLACK);
        paint.setTextSize(20f);
        canvas.drawText(label, x + 30, y, paint);
    }

    private String mondayDate() {
        return database.getCurrentWeekStart();
    }

    private String offsetDate(int days) {
        try {
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE);
            java.util.Calendar c = java.util.Calendar.getInstance(Locale.FRANCE);
            c.setTime(format.parse(database.getCurrentWeekStart()));
            c.add(java.util.Calendar.DAY_OF_MONTH, days);
            return format.format(c.getTime());
        } catch (Exception e) {
            return DayColor.today();
        }
    }

    private void saveBitmap(Bitmap bitmap, String fileName) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Plan Travail Orsay");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Création du fichier impossible");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Écriture impossible");
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
        } else {
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Plan Travail Orsay");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Dossier impossible");
            File file = new File(dir, fileName);
            try (OutputStream out = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Écriture impossible");
            }
        }
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
                    status.setText(count + " rue(s) reçue(s) du MCP • semaine en cours");
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
        updateLegend();
        UpdateManager.resumePendingInstall(this);
    }

    @Override
    protected void onPause() {
        if (map != null) map.onPause();
        super.onPause();
    }
}
