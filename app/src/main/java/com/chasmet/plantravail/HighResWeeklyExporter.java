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

public final class HighResWeeklyExporter {
  public interface Callback {
    void onProgress(String text);

    void onDone(String pngUri, String pdfUri);

    void onError(String message);
  }

  private static final java.util.concurrent.ExecutorService IO =
      Executors.newSingleThreadExecutor();
  private static final AtomicBoolean BUSY = new AtomicBoolean();

  private HighResWeeklyExporter() {}

  public static void export(Activity activity, OrsayMapView unused, Callback callback) {
    if (!BUSY.compareAndSet(false, true)) {
      callback.onError("Un export est déjà en cours");
      return;
    }
    Context context = activity.getApplicationContext();
    String week =
        activity instanceof MainActivity
            ? ((MainActivity) activity).getSelectedWeek()
            : DayColor.weekRange(DayColor.today())[0];
    Handler main = new Handler(Looper.getMainLooper());
    IO.execute(
        () -> {
          File png = null, pdf = null;
          Bitmap image = null;
          try {
            main.post(() -> callback.onProgress("Préparation…"));
            List<Street> streets =
                MapDataCache.local(context, "orsay_streets.json", StreetRepository::parse);
            List<org.osmdroid.util.GeoPoint> boundary =
                MapDataCache.local(
                    context, "orsay_boundary.geojson", OrsayBoundaryRepository::parse);
            WorkDatabase db = WorkDatabase.getInstance(context);
            List<String[]> work, lexicon;
            synchronized (db) {
              work = db.getWeekEntriesDetailed(week);
              lexicon = db.getLexicon();
            }
            File dir = new File(context.getFilesDir(), "exports");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Stockage inaccessible");
            String name = "Plan_Travail_Orsay_" + week + "_" + System.currentTimeMillis();
            png = new File(dir, name + ".png");
            pdf = new File(dir, name + ".pdf");
            PlanDrawing drawing = new PlanDrawing(streets, boundary, work, week);
            image = Bitmap.createBitmap(2480, 3508, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.scale(2480f / PlanDrawing.WIDTH, 3508f / PlanDrawing.HEIGHT);
            drawing.draw(canvas);
            try (OutputStream out = new FileOutputStream(png)) {
              if (!image.compress(Bitmap.CompressFormat.PNG, 100, out))
                throw new IOException("Écriture PNG refusée");
            }
            image.recycle();
            image = null;
            main.post(() -> callback.onProgress("Création du PDF…"));
            writePdf(pdf, drawing, week, work, lexicon);
            Uri
                pngUri =
                    FileProvider.getUriForFile(context, context.getPackageName() + ".files", png),
                pdfUri =
                    FileProvider.getUriForFile(context, context.getPackageName() + ".files", pdf);
            if (Build.VERSION.SDK_INT >= 29) {
              pngUri = publish(context, png, "image/png", true);
              try {
                pdfUri = publish(context, pdf, "application/pdf", false);
              } catch (Exception e) {
                context.getContentResolver().delete(pngUri, null, null);
                throw e;
              }
            }
            String a = pngUri.toString(), b = pdfUri.toString();
            main.post(() -> callback.onDone(a, b));
          } catch (Exception | OutOfMemoryError e) {
            if (png != null) png.delete();
            if (pdf != null) pdf.delete();
            main.post(
                () ->
                    callback.onError(
                        e.getMessage() == null ? "Mémoire insuffisante" : e.getMessage()));
          } finally {
            if (image != null) image.recycle();
            BUSY.set(false);
          }
        });
  }

  static void writePdf(
      File target, PlanDrawing drawing, String week, List<String[]> work, List<String[]> lexicon)
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

  static PdfTextLayout textLayout(String week, List<String[]> work, List<String[]> lexicon) {
    PdfTextLayout text = new PdfTextLayout(2, "Détail de la semaine du " + week);
    if (work.isEmpty()) text.paragraph("Aucune rue enregistrée cette semaine.", false, Color.BLACK);
    for (String[] row : work)
      text.paragraph(
          DayColor.dayName(row[1])
              + " "
              + row[1]
              + " • "
              + row[0]
              + " • "
              + row[4]
              + " % • "
              + row[3],
          false,
          DayColor.forDate(row[1]));
    text.section("Lexique / spécificités");
    if (lexicon.isEmpty()) text.paragraph("Aucune note enregistrée.", false, Color.BLACK);
    for (String[] row : lexicon) {
      text.paragraph(row[1], true, Color.BLACK);
      text.paragraph(row[2], false, Color.BLACK);
      text.paragraph("Créée le " + row[3], false, Color.DKGRAY);
    }
    return text;
  }

  @android.annotation.TargetApi(29)
  private static Uri publish(Context context, File file, String mime, boolean image)
      throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
    values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
    values.put(
        MediaStore.MediaColumns.RELATIVE_PATH,
        (image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS)
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
}
