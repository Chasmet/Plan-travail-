package com.chasmet.plantravail;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;

/**
 * Export hebdomadaire.
 *
 * La page 1 reprend volontairement le rendu cartographique historique :
 * fond OpenStreetMap réel, découpe exacte d'Orsay et tracés visibles sur la carte.
 * Les pages suivantes conservent le détail de semaine et le lexique de la version actuelle.
 */
public final class HighResWeeklyExporter {
  public interface Callback {
    void onProgress(String text);

    void onDone(String pngUri, String pdfUri);

    void onError(String message);
  }

  private static final int HEADER = 180;
  private static final int TARGET_ZOOM = 17;
  private static final long MAX_PIXELS = 18_000_000L;
  private static final java.util.concurrent.ExecutorService IO =
      Executors.newSingleThreadExecutor();
  private static final AtomicBoolean BUSY = new AtomicBoolean();

  private HighResWeeklyExporter() {}

  public static void export(Activity activity, OrsayMapView map, Callback callback) {
    if (!BUSY.compareAndSet(false, true)) {
      callback.onError("Un export est déjà en cours");
      return;
    }
    if (map == null || map.getWidth() <= 0 || map.getHeight() <= 0) {
      BUSY.set(false);
      callback.onError("La carte n'est pas encore prête");
      return;
    }

    String week =
        activity instanceof MainActivity
            ? ((MainActivity) activity).getSelectedWeek()
            : DayColor.weekRange(DayColor.today())[0];

    OrsayBoundaryRepository repo = new OrsayBoundaryRepository(activity);
    repo.load(
        false,
        new OrsayBoundaryRepository.Callback() {
          @Override
          public void onLoaded(List<GeoPoint> boundary, boolean fromCache) {
            activity.runOnUiThread(
                () -> {
                  if (activity.isFinishing() || activity.isDestroyed()) {
                    BUSY.set(false);
                    callback.onError("L'application a été fermée pendant l'export");
                    return;
                  }
                  begin(activity, map, boundary, week, callback);
                });
          }

          @Override
          public void onError(String message) {
            activity.runOnUiThread(
                () -> {
                  BUSY.set(false);
                  callback.onError(message);
                });
          }
        });
  }

  private static void begin(
      Activity activity,
      OrsayMapView map,
      List<GeoPoint> boundary,
      String week,
      Callback callback) {
    if (boundary == null || boundary.size() < 3) {
      BUSY.set(false);
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
    while ((long) grid.outputWidth * (long) (grid.outputHeight + HEADER) > MAX_PIXELS
        && zoom > 15) {
      zoom--;
      grid = makeGrid(north, east, south, west, map.getWidth(), map.getHeight(), zoom);
    }

    WorkDatabase db = WorkDatabase.getInstance(activity);
    List<String[]> work = db.getWeekEntriesDetailed(week);
    List<String[]> lexicon = db.getLexicon();
    int manualCount = new ManualTraceStore(activity).forWeek(week).size();

    double oldZoom = map.getZoomLevelDouble();
    GeoPoint oldCenter = (GeoPoint) map.getMapCenter();
    List<OverlayStyle> overlayStyles = prepareReadableRoutes(map);
    map.setScrollableAreaLimitDouble(new BoundingBox(85.0, 179.0, -85.0, -179.0));

    Bitmap result;
    try {
      result =
          Bitmap.createBitmap(
              grid.outputWidth, grid.outputHeight + HEADER, Bitmap.Config.ARGB_8888);
    } catch (OutOfMemoryError e) {
      restoreOverlayStyles(overlayStyles);
      map.setScrollableAreaLimitDouble(originalBounds);
      BUSY.set(false);
      callback.onError("Mémoire insuffisante pour l'export HD");
      return;
    }

    Canvas canvas = new Canvas(result);
    canvas.drawColor(Color.WHITE);
    drawHeader(canvas, week, work, manualCount, grid.outputWidth);

    ExportState state =
        new ExportState(
            activity,
            map,
            callback,
            result,
            canvas,
            grid,
            boundary,
            week,
            work,
            lexicon,
            oldZoom,
            oldCenter,
            zoom,
            originalBounds,
            overlayStyles);
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
      if (Color.alpha(color) >= 180 || width >= 8f) {
        line
            .getOutlinePaint()
            .setColor(
                Color.argb(
                    118, Color.red(color), Color.green(color), Color.blue(color)));
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
      finishCapture(s);
      return;
    }

    Cell cell = s.grid.centers.get(s.index);
    s.map.getController().setZoom(s.zoom);
    s.map.getController().setCenter(new GeoPoint(cell.lat, cell.lon));
    int pct = (int) ((s.index * 100L) / Math.max(1, s.grid.centers.size()));
    s.callback.onProgress("HD " + pct + " %");

    s.map.postDelayed(
        () -> {
          try {
            if (s.activity.isFinishing() || s.activity.isDestroyed())
              throw new IllegalStateException("L'application a été fermée pendant l'export");
            Bitmap shot =
                Bitmap.createBitmap(
                    s.map.getWidth(), s.map.getHeight(), Bitmap.Config.ARGB_8888);
            try {
              Canvas c = new Canvas(shot);
              s.map.draw(c);
              s.canvas.drawBitmap(shot, cell.destX, HEADER + cell.destY, null);
            } finally {
              shot.recycle();
            }
            s.index++;
            captureNext(s);
          } catch (Throwable t) {
            failCapture(s, t);
          }
        },
        560);
  }

  private static void finishCapture(ExportState s) {
    try {
      drawBoundary(s.canvas, s.boundary, s.grid, s.zoom);
      restore(s);
      s.callback.onProgress("Création du PDF…");

      IO.execute(
          () -> {
            File png = null, pdf = null;
            try {
              Context context = s.activity.getApplicationContext();
              File dir = new File(context.getFilesDir(), "exports");
              if (!dir.exists() && !dir.mkdirs())
                throw new IOException("Stockage inaccessible");

              String name =
                  "Plan_Travail_Orsay_"
                      + s.week
                      + "_"
                      + System.currentTimeMillis();
              png = new File(dir, name + ".png");
              pdf = new File(dir, name + ".pdf");

              try (OutputStream out = new FileOutputStream(png)) {
                if (!s.result.compress(Bitmap.CompressFormat.PNG, 100, out))
                  throw new IOException("Écriture PNG refusée");
              }

              writePdfWithHistoricalMap(pdf, s.result, s.week, s.work, s.lexicon);

              Uri pngUri =
                  FileProvider.getUriForFile(
                      context, context.getPackageName() + ".files", png);
              Uri pdfUri =
                  FileProvider.getUriForFile(
                      context, context.getPackageName() + ".files", pdf);

              if (Build.VERSION.SDK_INT >= 29) {
                pngUri = publish(context, png, "image/png", true);
                try {
                  pdfUri = publish(context, pdf, "application/pdf", false);
                } catch (Exception e) {
                  context.getContentResolver().delete(pngUri, null, null);
                  throw e;
                }
              }

              String a = pngUri.toString();
              String b = pdfUri.toString();
              s.activity.runOnUiThread(
                  () -> {
                    s.callback.onProgress("HD 100 %");
                    s.callback.onDone(a, b);
                  });
            } catch (Exception | OutOfMemoryError e) {
              if (png != null) png.delete();
              if (pdf != null) pdf.delete();
              String message =
                  e.getMessage() == null ? "Mémoire insuffisante" : e.getMessage();
              s.activity.runOnUiThread(() -> s.callback.onError(message));
            } finally {
              s.result.recycle();
              BUSY.set(false);
            }
          });
    } catch (Throwable t) {
      failCapture(s, t);
    }
  }

  private static void failCapture(ExportState s, Throwable t) {
    try {
      restore(s);
    } catch (Throwable ignored) {
    }
    if (!s.result.isRecycled()) s.result.recycle();
    BUSY.set(false);
    String message =
        t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    s.callback.onError(message);
  }

  private static void restore(ExportState s) {
    restoreOverlayStyles(s.overlayStyles);
    s.map.setScrollableAreaLimitDouble(s.originalBounds);
    s.map.getController().setZoom(s.oldZoom);
    s.map.getController().setCenter(s.oldCenter);
    s.map.invalidate();
  }

  private static void drawBoundary(
      Canvas canvas, List<GeoPoint> boundary, Grid grid, int zoom) {
    Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    border.setColor(Color.rgb(20, 110, 230));
    border.setStyle(Paint.Style.STROKE);
    border.setStrokeWidth(7f);
    Path path = new Path();
    boolean first = true;
    for (GeoPoint p : boundary) {
      float x = (float) (worldX(p.getLongitude(), zoom) - grid.leftWorld);
      float y = (float) (worldY(p.getLatitude(), zoom) - grid.topWorld + HEADER);
      if (first) {
        path.moveTo(x, y);
        first = false;
      } else {
        path.lineTo(x, y);
      }
    }
    path.close();
    canvas.drawPath(path, border);
  }

  private static void drawHeader(
      Canvas canvas,
      String week,
      List<String[]> work,
      int manualCount,
      int width) {
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(Color.BLACK);
    p.setTextSize(Math.max(30f, width / 90f));
    p.setFakeBoldText(true);
    canvas.drawText("Plan Travail Orsay — semaine du " + week, 28, 55, p);

    Map<String, Integer> progress = new HashMap<>();
    for (String[] row : work) {
      try {
        progress.put(row[0], Integer.parseInt(row[4]));
      } catch (Exception ignored) {
      }
    }
    int completed = 0;
    for (int value : progress.values()) if (value == 100) completed++;

    p.setFakeBoldText(false);
    p.setTextSize(Math.max(22f, width / 120f));
    canvas.drawText(
        progress.size()
            + " rues commencées • "
            + completed
            + " terminées • "
            + manualCount
            + " tracé(s) manuel(s)",
        28,
        98,
        p);
    drawLegend(canvas, week, 28, 142, Math.max(20f, width / 135f));
  }

  private static void drawLegend(
      Canvas canvas, String week, float x, float y, float textSize) {
    String[] days = {"Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim"};
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setTextSize(textSize);
    float cursor = x;
    for (int i = 0; i < days.length; i++) {
      p.setColor(DayColor.forDate(DayColor.shift(week, i)));
      canvas.drawRect(cursor, y - 22, cursor + 24, y + 2, p);
      cursor += 34;
      p.setColor(Color.BLACK);
      canvas.drawText(days[i], cursor, y, p);
      cursor += p.measureText(days[i]) + 28;
    }
  }

  private static Grid makeGrid(
      double north,
      double east,
      double south,
      double west,
      int viewW,
      int viewH,
      int zoom) {
    double left = worldX(west, zoom);
    double right = worldX(east, zoom);
    double top = worldY(north, zoom);
    double bottom = worldY(south, zoom);
    int cols = Math.max(1, (int) Math.ceil((right - left) / viewW));
    int rows = Math.max(1, (int) Math.ceil((bottom - top) / viewH));
    int outW = cols * viewW;
    int outH = rows * viewH;
    double leftWorld = (left + right - outW) / 2.0;
    double topWorld = (top + bottom - outH) / 2.0;
    List<Cell> centers = new ArrayList<>();
    for (int row = 0; row < rows; row++) {
      for (int col = 0; col < cols; col++) {
        double cx = leftWorld + col * viewW + viewW / 2.0;
        double cy = topWorld + row * viewH + viewH / 2.0;
        centers.add(
            new Cell(
                worldToLat(cy, zoom),
                worldToLon(cx, zoom),
                col * viewW,
                row * viewH));
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
    double r =
        Math.toRadians(
            Math.max(-85.05112878, Math.min(85.05112878, lat)));
    return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI)
        / 2.0
        * world;
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

  private static void writePdfWithHistoricalMap(
      File target,
      Bitmap mapImage,
      String week,
      List<String[]> work,
      List<String[]> lexicon)
      throws Exception {
    PdfDocument document = new PdfDocument();
    try {
      int pageW = 2480;
      int pageH =
          Math.max(
              3508,
              (int)
                  Math.round(
                      pageW * (mapImage.getHeight() / (double) mapImage.getWidth())));
      PdfDocument.Page page =
          document.startPage(new PdfDocument.PageInfo.Builder(pageW, pageH, 1).create());
      try {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        page
            .getCanvas()
            .drawBitmap(mapImage, null, new Rect(0, 0, pageW, pageH), paint);
      } finally {
        document.finishPage(page);
      }

      PdfTextLayout text = textLayout(week, work, lexicon);
      for (PdfTextLayout.Page layout : text.pages) {
        PdfDocument.Page details =
            document.startPage(
                new PdfDocument.PageInfo.Builder(
                        PdfTextLayout.WIDTH, PdfTextLayout.HEIGHT, layout.number)
                    .create());
        try {
          layout.draw(details.getCanvas());
        } finally {
          document.finishPage(details);
        }
      }

      try (OutputStream out = new FileOutputStream(target)) {
        document.writeTo(out);
      }
    } finally {
      document.close();
    }
  }

  /**
   * Conservé pour les tests et pour un éventuel export vectoriel interne.
   * L'export utilisateur emploie writePdfWithHistoricalMap afin de garder le plan historique.
   */
  static void writePdf(
      File target,
      PlanDrawing drawing,
      String week,
      List<String[]> work,
      List<String[]> lexicon)
      throws Exception {
    PdfDocument document = new PdfDocument();
    try {
      PdfDocument.Page page =
          document.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create());
      try {
        drawing.draw(page.getCanvas());
      } finally {
        document.finishPage(page);
      }
      PdfTextLayout text = textLayout(week, work, lexicon);
      for (PdfTextLayout.Page layout : text.pages) {
        PdfDocument.Page details =
            document.startPage(
                new PdfDocument.PageInfo.Builder(
                        PdfTextLayout.WIDTH, PdfTextLayout.HEIGHT, layout.number)
                    .create());
        try {
          layout.draw(details.getCanvas());
        } finally {
          document.finishPage(details);
        }
      }
      try (OutputStream out = new FileOutputStream(target)) {
        document.writeTo(out);
      }
    } finally {
      document.close();
    }
  }

  static PdfTextLayout textLayout(
      String week, List<String[]> work, List<String[]> lexicon) {
    PdfTextLayout text =
        new PdfTextLayout(2, "Détail de la semaine du " + week);
    if (work.isEmpty())
      text.paragraph(
          "Aucune rue enregistrée cette semaine.", false, Color.BLACK);

    for (String[] row : work)
      text.paragraph(
          DayColor.dayName(row[1])
              + " "
              + row[1]
              + " • "
              + row[0]
              + " • "
              + row[4]
              + " %",
          false,
          DayColor.forDate(row[1]));

    text.section("Lexique / spécificités");
    if (lexicon.isEmpty())
      text.paragraph("Aucune note enregistrée.", false, Color.BLACK);

    for (String[] row : lexicon) {
      text.paragraph(row[1], true, Color.BLACK);
      text.paragraph(row[2], false, Color.BLACK);
      text.paragraph("Créée le " + row[3], false, Color.DKGRAY);
    }
    return text;
  }

  @android.annotation.TargetApi(29)
  private static Uri publish(
      Context context, File file, String mime, boolean image) throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
    values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
    values.put(
        MediaStore.MediaColumns.RELATIVE_PATH,
        (image
                ? Environment.DIRECTORY_PICTURES
                : Environment.DIRECTORY_DOWNLOADS)
            + "/Plan Travail Orsay");
    values.put(MediaStore.MediaColumns.IS_PENDING, 1);

    Uri uri =
        context
            .getContentResolver()
            .insert(
                image
                    ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values);
    if (uri == null) throw new IOException("Fichier exporté inaccessible");

    try {
      try (InputStream in = new FileInputStream(file);
          OutputStream out = context.getContentResolver().openOutputStream(uri)) {
        if (out == null) throw new IOException("Écriture de l’export refusée");
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      }
      values.clear();
      values.put(MediaStore.MediaColumns.IS_PENDING, 0);
      context.getContentResolver().update(uri, values, null, null);
      return uri;
    } catch (Exception e) {
      context.getContentResolver().delete(uri, null, null);
      throw e;
    }
  }

  private static final class Grid {
    final int outputWidth, outputHeight;
    final double leftWorld, topWorld;
    final List<Cell> centers;

    Grid(
        int outputWidth,
        int outputHeight,
        double leftWorld,
        double topWorld,
        List<Cell> centers) {
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
    final String week;
    final List<String[]> work;
    final List<String[]> lexicon;
    final double oldZoom;
    final GeoPoint oldCenter;
    final int zoom;
    final BoundingBox originalBounds;
    final List<OverlayStyle> overlayStyles;
    int index;

    ExportState(
        Activity activity,
        OrsayMapView map,
        Callback callback,
        Bitmap result,
        Canvas canvas,
        Grid grid,
        List<GeoPoint> boundary,
        String week,
        List<String[]> work,
        List<String[]> lexicon,
        double oldZoom,
        GeoPoint oldCenter,
        int zoom,
        BoundingBox originalBounds,
        List<OverlayStyle> overlayStyles) {
      this.activity = activity;
      this.map = map;
      this.callback = callback;
      this.result = result;
      this.canvas = canvas;
      this.grid = grid;
      this.boundary = boundary;
      this.week = week;
      this.work = work;
      this.lexicon = lexicon;
      this.oldZoom = oldZoom;
      this.oldCenter = oldCenter;
      this.zoom = zoom;
      this.originalBounds = originalBounds;
      this.overlayStyles = overlayStyles;
    }
  }
}
