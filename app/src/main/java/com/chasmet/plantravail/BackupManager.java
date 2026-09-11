package com.chasmet.plantravail;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public final class BackupManager {
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private static final AtomicBoolean BUSY = new AtomicBoolean(), DIRTY = new AtomicBoolean();

  private BackupManager() {}

  public interface Callback {
    void done(String message);

    void error(String message);
  }

  public static void schedule(Context context, WorkDatabase db) {
    DIRTY.set(true);
    if (!BUSY.compareAndSet(false, true)) return;
    Context app = context.getApplicationContext();
    IO.execute(
        () -> {
          try {
            while (DIRTY.getAndSet(false)) {
              writeLocal(app, "plan-travail-latest.json", encode(db.snapshot()));
              app.getSharedPreferences("settings", Context.MODE_PRIVATE)
                  .edit()
                  .putLong("backup_last_ms", System.currentTimeMillis())
                  .putString("backup_error", "")
                  .apply();
            }
          } catch (Exception e) {
            app.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit()
                .putString("backup_error", e.getMessage())
                .apply();
          } finally {
            BUSY.set(false);
            if (DIRTY.get()) schedule(app, db);
          }
        });
  }

  static String encode(JSONObject data) throws Exception {
    String payload = data.toString();
    return new JSONObject()
        .put("format", "plan-travail-backup")
        .put("version", 1)
        .put("created_at", DayColor.today())
        .put("payload", payload)
        .put("sha256", sha(payload))
        .toString(2);
  }

  static JSONObject decode(String text) throws Exception {
    JSONObject root = new JSONObject(text);
    if (!"plan-travail-backup".equals(root.optString("format")) || root.optInt("version") != 1)
      throw new IllegalArgumentException("Format de sauvegarde incompatible");
    String payload = root.getString("payload");
    if (!sha(payload).equals(root.getString("sha256")))
      throw new IllegalArgumentException("Sauvegarde endommagée : empreinte incorrecte");
    return new JSONObject(payload);
  }

  private static String sha(String value) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder out = new StringBuilder();
    for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return out.toString();
  }

  private static void writeLocal(Context context, String name, String data) throws Exception {
    File dir = new File(context.getFilesDir(), "backups");
    if (!dir.exists() && !dir.mkdirs())
      throw new IllegalStateException("Dossier de sauvegarde inaccessible");
    AtomicFile file = new AtomicFile(new File(dir, name));
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      out.write(data.getBytes(StandardCharsets.UTF_8));
      file.finishWrite(out);
    } catch (Exception e) {
      if (out != null) file.failWrite(out);
      throw e;
    }
  }

  public static void exportTo(Context context, Uri uri, Callback callback) {
    IO.execute(
        () -> {
          try {
            String data = encode(WorkDatabase.getInstance(context).snapshot());
            try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new IllegalStateException("Fichier inaccessible");
              out.write(data.getBytes(StandardCharsets.UTF_8));
            }
            callback.done("Sauvegarde complète enregistrée");
          } catch (Exception e) {
            callback.error(e.getMessage());
          }
        });
  }

  public static void restorePrevious(Context context, Callback callback) {
    IO.execute(
        () -> {
          try {
            android.util.AtomicFile previous =
                new android.util.AtomicFile(
                    new File(context.getFilesDir(), "backups/plan-travail-before-restore.json"));
            JSONObject data;
            try (InputStream in = previous.openRead()) {
              data = decode(MapDataCache.read(in));
            }
            WorkDatabase db = WorkDatabase.getInstance(context);
            synchronized (db) {
              String current = encode(db.snapshot());
              db.restoreSnapshot(data);
              writeLocal(context, "plan-travail-before-restore.json", current);
            }
            callback.done("État précédent rétabli");
          } catch (Exception e) {
            callback.error(e.getMessage());
          }
        });
  }

  public static void importFrom(Context context, Uri uri, Callback callback) {
    IO.execute(
        () -> {
          try {
            String text;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
              if (in == null) throw new IllegalArgumentException("Sauvegarde inaccessible");
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              byte[] buf = new byte[8192];
              int n;
              while ((n = in.read(buf)) != -1) {
                if (out.size() + n > 32 * 1024 * 1024)
                  throw new IllegalArgumentException("Sauvegarde trop volumineuse");
                out.write(buf, 0, n);
              }
              text = out.toString("UTF-8");
            }
            JSONObject data = decode(text);
            WorkDatabase db = WorkDatabase.getInstance(context);
            synchronized (db) {
              writeLocal(context, "plan-travail-before-restore.json", encode(db.snapshot()));
              db.restoreSnapshot(data);
            }
            callback.done("Rues et lexique restaurés");
          } catch (Exception e) {
            callback.error(e.getMessage());
          }
        });
  }
}
