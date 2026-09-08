package com.chasmet.plantravail;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public final class WeeklyPdfExporter {
    private WeeklyPdfExporter() {}

    public static String save(Context context, Bitmap mapBitmap, String weekStart, int count) throws Exception {
        int pageWidth = 1240;
        int pageHeight = 1754;
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create();
        PdfDocument.Page page = document.startPage(info);
        Canvas canvas = page.getCanvas();
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(42f);
        paint.setFakeBoldText(true);
        canvas.drawText("Plan Travail Orsay", 60, 70, paint);
        paint.setTextSize(28f);
        paint.setFakeBoldText(false);
        canvas.drawText("Semaine du " + weekStart + " • " + count + " rue(s) effectuée(s)", 60, 112, paint);

        int top = 150;
        int availableW = pageWidth - 120;
        int availableH = pageHeight - top - 80;
        float scale = Math.min((float) availableW / mapBitmap.getWidth(), (float) availableH / mapBitmap.getHeight());
        int w = Math.max(1, Math.round(mapBitmap.getWidth() * scale));
        int h = Math.max(1, Math.round(mapBitmap.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(mapBitmap, w, h, true);
        canvas.drawBitmap(scaled, (pageWidth - w) / 2f, top, null);
        if (scaled != mapBitmap) scaled.recycle();
        document.finishPage(page);

        String fileName = "Plan_Travail_Orsay_Semaine_" + weekStart + ".pdf";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Plan Travail Orsay");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Création PDF impossible");
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Ouverture PDF impossible");
                document.writeTo(out);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
        } else {
            File dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Plan Travail Orsay");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Dossier PDF impossible");
            try (OutputStream out = new FileOutputStream(new File(dir, fileName))) {
                document.writeTo(out);
            }
        }
        document.close();
        return fileName;
    }
}
