package com.chasmet.plantravail;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public final class HighResWeeklyExporter {
    public interface Callback {
        void onProgress(String text);
        void onDone(String pngName, String pdfName);
        void onError(String message);
    }

    private static final int HEADER = 180;
    private static final int TARGET_ZOOM = 17;
    private static final long MAX_PIXELS = 18_000_000L;

    private HighResWeeklyExporter() {}

    public static void export(Activity activity, OrsayMapView map, Callback callback) {
        OrsayBoundaryRepository repo = new OrsayBoundaryRepository(activity);
        repo.load(false, new OrsayBoundaryRepository.Callback() {
            @Override
            public void onLoaded(List<GeoPoint> boundary, boolean fromCache) {
                activity.runOnUiThread(() -> begin(activity, map, boundary, callback));
            }

            @Override
            public void onError(String message) {
                activity.runOnUiThread(() -> callback.onError(message));
            }
        });
    }

    private static void begin(Activity activity, OrsayMapView map, List<GeoPoint> boundary, Callback callback) {
        if (boundary == null || boundary.size() < 3) {
            callback.onError("Contour d'Orsay indisponible");
            return;
        }

        double north = -90, south = 90, east = -180, west = 180;
        for (GeoPoint p : boundary) {
            north = Math.max(north, p.getLatitude());
            south = Math.min(south, p.getLatitude());
            east = Math.max(east, p.getLongitude());
            west = Math.min(west, p.getLongitude());
        }
        BoundingBox originalBounds = new BoundingBox(north, east, south, west);

        int zoom = TARGET_ZOOM;
        Grid grid = makeGrid(north, east, south, west, map.getWidth(), map.getHeight(), zoom);
        while ((long) grid.outputWidth * (long) (grid.outputHeight + HEADER) > MAX_PIXELS && zoom > 15) {
            zoom--;
            grid = makeGrid(north, east, south, west, map.getWidth(), map.getHeight(), zoom);
        }

        WorkDatabase db = new WorkDatabase(activity);
        double oldZoom = map.getZoomLevelDouble();
        GeoPoint oldCenter = (GeoPoint) map.getMapCenter();
        List<OverlayStyle> overlayStyles = prepareReadableRoutes(map);

        // Pendant l'assemblage HD, on libère temporairement la limite de déplacement.
        // Sinon osmdroid recale certaines cellules sur la même position et duplique des morceaux de carte.
        map.setScrollableAreaLimitDouble(new BoundingBox(85.0, 179.0, -85.0, -179.0));

        Bitmap result;
        try {
            result = Bitmap.createBitmap(grid.outputWidth, grid.outputHeight + HEADER, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            restoreOverlayStyles(overlayStyles);
            map.setScrollableAreaLimitDouble(originalBounds);
            callback.onError("Mémoire insuffisante pour l'export HD");
            return;
        }

        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.WHITE);
        drawHeader(canvas, db, grid.outputWidth, zoom);

        ExportState state = new ExportState(activity, map, callback, result, canvas, grid, boundary, db,
                oldZoom, oldCenter, zoom, originalBounds, overlayStyles);
        callback.onProgress("HD 0 %");
        captureNext(state);
    }

    private static List<OverlayStyle> prepareReadableRoutes(OrsayMapView map) {
        List<OverlayStyle> saved = new ArrayList<>();
        for (Overlay overlay : map.getOverlays()) {
            if (!(overlay instanceof Polyline)) continue;
            Polyline line = (Polyline) overlay;
            int color = line.getOutlinePaint().getColor();
            float width = line.getOutlinePaint().getStrokeWidth();
            saved.add(new OverlayStyle(line, color, width));

            // Les tracés de travail restent visibles, mais deviennent translucides et plus fins.
            // Ainsi le nom OpenStreetMap (ex. Rue de Versailles) reste lisible sous le tracé.
            if (Color.alpha(color) >= 180 || width >= 8f) {
                line.getOutlinePaint().setColor(Color.argb(118, Color.red(color), Color.green(color), Color.blue(color)));
                line.getOutlinePaint().setStrokeWidth(Math.min(width, 6f));
            }
        }
        map.invalidate();
        return saved;
    }

    private static void restoreOverlayStyles(List<OverlayStyle> styles) {
        for (OverlayStyle style : styles) {
            style.line.getOutlinePaint().setColor(style.color);
            style.line.getOutlinePaint().setStrokeWidth(style.width);
        }
    }

    private static void captureNext(ExportState s) {
        if (s.index >= s.grid.centers.size()) {
            finish(s);
            return;
        }

        Cell cell = s.grid.centers.get(s.index);
        s.map.getController().setZoom(s.zoom);
        s.map.getController().setCenter(new GeoPoint(cell.lat, cell.lon));

        int pct = (int) ((s.index * 100L) / Math.max(1, s.grid.centers.size()));
        s.callback.onProgress("HD " + pct + " %");

        s.map.postDelayed(() -> {
            try {
                Bitmap shot = Bitmap.createBitmap(s.map.getWidth(), s.map.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(shot);
                s.map.draw(c);
                s.canvas.drawBitmap(shot, cell.destX, HEADER + cell.destY, null);
                shot.recycle();
                s.index++;
                captureNext(s);
            } catch (Throwable t) {
                restore(s);
                s.result.recycle();
                s.callback.onError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
        }, 560);
    }

    private static void finish(ExportState s) {
        try {
            Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            border.setColor(Color.rgb(20, 110, 230));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(7f);
            android.graphics.Path path = new android.graphics.Path();
            boolean first = true;
            for (GeoPoint p : s.boundary) {
                float x = (float) (worldX(p.getLongitude(), s.zoom) - s.grid.leftWorld);
                float y = (float) (worldY(p.getLatitude(), s.zoom) - s.grid.topWorld + HEADER);
                if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
            }
            path.close();
            s.canvas.drawPath(path, border);

            String week = s.db.getCurrentWeekStart();
            String pngName = "Plan_Travail_Orsay_HD_Semaine_" + week + ".png";
            String pdfName = "Plan_Travail_Orsay_HD_Semaine_" + week + ".pdf";
            savePng(s.activity, s.result, pngName);
            savePdf(s.activity, s.result, pdfName);
            restore(s);
            s.result.recycle();
            s.callback.onProgress("HD 100 %");
            s.callback.onDone(pngName, pdfName);
        } catch (Exception e) {
            restore(s);
            s.result.recycle();
            s.callback.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static void restore(ExportState s) {
        restoreOverlayStyles(s.overlayStyles);
        s.map.setScrollableAreaLimitDouble(s.originalBounds);
        s.map.getController().setZoom(s.oldZoom);
        s.map.getController().setCenter(s.oldCenter);
        s.map.invalidate();
    }

    private static void drawHeader(Canvas canvas, WorkDatabase db, int width, int zoom) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(Math.max(30f, width / 90f));
        p.setFakeBoldText(true);
        canvas.drawText("Plan Travail Orsay — semaine du " + db.getCurrentWeekStart(), 28, 55, p);
        p.setFakeBoldText(false);
        p.setTextSize(Math.max(22f, width / 120f));
        canvas.drawText(db.getCurrentWeekCount() + " rue(s) effectuée(s) • export haute définition • zoom cartographique " + zoom, 28, 98, p);
        p.setTextSize(Math.max(20f, width / 135f));
        canvas.drawText("Lundi bleu • Mardi vert • Mercredi orange • Jeudi violet • Vendredi rouge", 28, 142, p);
    }

    private static Grid makeGrid(double north, double east, double south, double west, int viewW, int viewH, int zoom) {
        double left = worldX(west, zoom);
        double right = worldX(east, zoom);
        double top = worldY(north, zoom);
        double bottom = worldY(south, zoom);

        int cols = Math.max(1, (int) Math.ceil((right - left) / viewW));
        int rows = Math.max(1, (int) Math.ceil((bottom - top) / viewH));
        int outW = cols * viewW;
        int outH = rows * viewH;

        double usedW = outW;
        double usedH = outH;
        double leftWorld = (left + right - usedW) / 2.0;
        double topWorld = (top + bottom - usedH) / 2.0;

        List<Cell> centers = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                double cx = leftWorld + col * viewW + viewW / 2.0;
                double cy = topWorld + row * viewH + viewH / 2.0;
                centers.add(new Cell(worldToLat(cy, zoom), worldToLon(cx, zoom), col * viewW, row * viewH));
            }
        }
        return new Grid(outW, outH, leftWorld, topWorld, centers);
    }

    private static double worldX(double lon, int zoom) {
        double world = 256.0 * (1 << zoom);
        return (lon + 180.0) / 360.0 * world;
    }

    private static double worldY(double lat, int zoom) {
        double world = 256.0 * (1 << zoom);
        double r = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, lat)));
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * world;
    }

    private static double worldToLon(double x, int zoom) {
        double world = 256.0 * (1 << zoom);
        return x / world * 360.0 - 180.0;
    }

    private static double worldToLat(double y, int zoom) {
        double world = 256.0 * (1 << zoom);
        double n = Math.PI - 2.0 * Math.PI * y / world;
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private static void savePng(Activity activity, Bitmap bitmap, String fileName) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Plan Travail Orsay");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Création PNG impossible");
            try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Écriture PNG impossible");
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            activity.getContentResolver().update(uri, values, null, null);
        } else {
            File dir = new File(activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Plan Travail Orsay");
            if (!dir.exists()) dir.mkdirs();
            try (OutputStream out = new FileOutputStream(new File(dir, fileName))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        }
    }

    private static void savePdf(Activity activity, Bitmap bitmap, String fileName) throws Exception {
        PdfDocument document = new PdfDocument();
        int pageW = 2480;
        int pageH = Math.max(3508, (int) (pageW * (bitmap.getHeight() / (double) bitmap.getWidth())));
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pageW, pageH, 1).create();
        PdfDocument.Page page = document.startPage(info);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        page.getCanvas().drawBitmap(bitmap, null, new android.graphics.Rect(0, 0, pageW, pageH), p);
        document.finishPage(page);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Plan Travail Orsay");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Création PDF impossible");
            try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                document.writeTo(out);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            activity.getContentResolver().update(uri, values, null, null);
        } else {
            File dir = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Plan Travail Orsay");
            if (!dir.exists()) dir.mkdirs();
            try (OutputStream out = new FileOutputStream(new File(dir, fileName))) {
                document.writeTo(out);
            }
        }
        document.close();
    }

    private static final class Grid {
        final int outputWidth, outputHeight;
        final double leftWorld, topWorld;
        final List<Cell> centers;
        Grid(int outputWidth, int outputHeight, double leftWorld, double topWorld, List<Cell> centers) {
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.leftWorld = leftWorld;
            this.topWorld = topWorld;
            this.centers = centers;
        }
    }

    private static final class Cell {
        final double lat, lon;
        final int destX, destY;
        Cell(double lat, double lon, int destX, int destY) {
            this.lat = lat;
            this.lon = lon;
            this.destX = destX;
            this.destY = destY;
        }
    }

    private static final class OverlayStyle {
        final Polyline line;
        final int color;
        final float width;
        OverlayStyle(Polyline line, int color, float width) {
            this.line = line;
            this.color = color;
            this.width = width;
        }
    }

    private static final class ExportState {
        final Activity activity;
        final OrsayMapView map;
        final Callback callback;
        final Bitmap result;
        final Canvas canvas;
        final Grid grid;
        final List<GeoPoint> boundary;
        final WorkDatabase db;
        final double oldZoom;
        final GeoPoint oldCenter;
        final int zoom;
        final BoundingBox originalBounds;
        final List<OverlayStyle> overlayStyles;
        int index;

        ExportState(Activity activity, OrsayMapView map, Callback callback, Bitmap result, Canvas canvas,
                    Grid grid, List<GeoPoint> boundary, WorkDatabase db, double oldZoom, GeoPoint oldCenter,
                    int zoom, BoundingBox originalBounds, List<OverlayStyle> overlayStyles) {
            this.activity = activity;
            this.map = map;
            this.callback = callback;
            this.result = result;
            this.canvas = canvas;
            this.grid = grid;
            this.boundary = boundary;
            this.db = db;
            this.oldZoom = oldZoom;
            this.oldCenter = oldCenter;
            this.zoom = zoom;
            this.originalBounds = originalBounds;
            this.overlayStyles = overlayStyles;
        }
    }
}
