package com.chasmet.plantravail;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** DownloadManager owns the download; a visible screen owns the installer. */
public final class UpdateManager {
  private static final String RELEASES_URL =
      "https://api.github.com/repos/Chasmet/Plan-travail-/releases/latest";
  private static final String ID = "pending_download_id",
      TARGET = "pending_target_version",
      LAUNCHED = "pending_install_launched",
      PERMISSION = "pending_permission_launched";
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private static final AtomicBoolean CHECKING = new AtomicBoolean(),
      VERIFYING = new AtomicBoolean();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static WeakReference<Activity> visible = new WeakReference<>(null);
  private static boolean dialogShown;
  private static final Runnable POLL = UpdateManager::poll;

  private UpdateManager() {}

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
  }

  private static boolean alive(Activity a) {
    return a != null && !a.isFinishing() && !a.isDestroyed();
  }

  public static void check(
      Activity activity, ProgressBar progress, TextView status, boolean manual) {
    resumePendingInstall(activity);
    if (prefs(activity).getLong(ID, -1) >= 0) {
      publish(
          activity,
          "Mise à jour en cours : reprendre ou annuler le téléchargement dans Réglages",
          -1);
      return;
    }
    if (dialogShown || !CHECKING.compareAndSet(false, true)) return;
    if (!manual
        && System.currentTimeMillis() - prefs(activity).getLong("update_checked_ms", 0) < 60000) {
      CHECKING.set(false);
      return;
    }
    publish(activity, "Recherche d’une mise à jour…", -1);
    WeakReference<Activity> owner = new WeakReference<>(activity);
    Context app = activity.getApplicationContext();
    IO.execute(
        () -> {
          try {
            JSONObject release = new JSONObject(MapDataCache.request(RELEASES_URL, null));
            String tag = release.getString("tag_name").replaceFirst("^[vV]", "");
            if (!tag.matches("[0-9]+(\\.[0-9]+){1,3}"))
              throw new IOException("Version GitHub non reconnue");
            prefs(app).edit().putLong("update_checked_ms", System.currentTimeMillis()).apply();
            if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) {
              publish(app, "L’application est à jour.", -1);
              return;
            }
            JSONArray assets = release.getJSONArray("assets");
            String url = null;
            for (int i = 0; i < assets.length(); i++) {
              JSONObject asset = assets.getJSONObject(i);
              if (asset.getString("name").endsWith(".apk")) {
                url = asset.getString("browser_download_url");
                break;
              }
            }
            if (url == null
                || !url.startsWith("https://github.com/Chasmet/Plan-travail-/releases/download/"))
              throw new IOException("APK de la Release introuvable");
            String apkUrl = url;
            MAIN.post(
                () -> {
                  Activity a = owner.get();
                  if (!alive(a) || visible.get() != a || dialogShown) return;
                  dialogShown = true;
                  AlertDialog dialog =
                      new AlertDialog.Builder(a)
                          .setTitle("Mise à jour " + tag)
                          .setMessage("Télécharger et installer en conservant vos données ?")
                          .setNegativeButton("Plus tard", null)
                          .setPositiveButton("Mettre à jour", (d, w) -> download(a, apkUrl, tag))
                          .create();
                  dialog.setOnDismissListener(d -> dialogShown = false);
                  dialog.show();
                });
          } catch (Exception e) {
            publish(app, "Recherche impossible : " + message(e), -1);
          } finally {
            CHECKING.set(false);
          }
        });
  }

  private static void download(Activity activity, String url, String tag) {
    try {
      DownloadManager manager =
          (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
      DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
      request
          .setTitle("Plan Travail " + tag)
          .setDescription("Mise à jour de l’application")
          .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
      request.setDestinationInExternalFilesDir(
          activity,
          Environment.DIRECTORY_DOWNLOADS,
          "PlanTravail-" + tag + "-" + System.currentTimeMillis() + ".apk");
      request.setMimeType("application/vnd.android.package-archive");
      long id = manager.enqueue(request);
      prefs(activity)
          .edit()
          .putLong(ID, id)
          .putString(TARGET, tag)
          .putBoolean(LAUNCHED, false)
          .putBoolean(PERMISSION, false)
          .apply();
      publish(activity, "Téléchargement en cours…", 0);
      resumePendingInstall(activity);
    } catch (Exception e) {
      publish(activity, "Téléchargement impossible : " + message(e), -1);
    }
  }

  public static void resumePendingInstall(Activity activity) {
    visible = new WeakReference<>(activity);
    MAIN.removeCallbacks(POLL);
    render(activity);
    String target = prefs(activity).getString(TARGET, "");
    if (!target.isEmpty() && compareVersions(BuildConfig.VERSION_NAME, target) >= 0) {
      cancel(activity);
      publish(activity, "Mise à jour installée.", -1);
      return;
    }
    if (prefs(activity).getLong(ID, -1) >= 0) MAIN.post(POLL);
  }

  public static void pause(Activity activity) {
    if (visible.get() == activity) {
      visible.clear();
      MAIN.removeCallbacks(POLL);
    }
  }

  public static void retry(Activity activity) {
    prefs(activity).edit().putBoolean(LAUNCHED, false).putBoolean(PERMISSION, false).apply();
    resumePendingInstall(activity);
    if (prefs(activity).getLong(ID, -1) < 0) check(activity, null, null, true);
  }

  public static void cancel(Context context) {
    long id = prefs(context).getLong(ID, -1);
    if (id >= 0) ((DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE)).remove(id);
    prefs(context).edit().remove(ID).remove(TARGET).remove(LAUNCHED).remove(PERMISSION).apply();
    MAIN.removeCallbacks(POLL);
    publish(context, "Aucun téléchargement en cours.", -1);
  }

  private static void poll() {
    Activity owner = visible.get();
    if (!alive(owner)) return;
    Context app = owner.getApplicationContext();
    long id = prefs(app).getLong(ID, -1);
    if (id < 0) return;
    IO.execute(
        () -> {
          int state = -1, percent = 0, reason = 0;
          try (Cursor cursor =
              ((DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE))
                  .query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor != null && cursor.moveToFirst()) {
              state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
              reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
              long total =
                  cursor.getLong(
                      cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
              long done =
                  cursor.getLong(
                      cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
              percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
            }
          } catch (Exception e) {
            publish(app, "Lecture du téléchargement impossible : " + message(e), -1);
            return;
          }
          final int result = state, pct = percent, why = reason;
          MAIN.post(
              () -> {
                if (prefs(app).getLong(ID, -1) != id) return;
                Activity a = visible.get();
                if (result == -1 || result == DownloadManager.STATUS_FAILED) {
                  cancel(app);
                  publish(
                      app,
                      result == -1
                          ? "Téléchargement supprimé. Vous pouvez recommencer."
                          : "Échec du téléchargement (" + why + "). Vous pouvez recommencer.",
                      -1);
                  return;
                }
                if (result == DownloadManager.STATUS_SUCCESSFUL) {
                  publish(app, "APK téléchargé. Reprendre l’installation si nécessaire.", 100);
                  if (alive(a) && !prefs(app).getBoolean(LAUNCHED, false)) install(a, id);
                  return;
                }
                publish(
                    app,
                    result == DownloadManager.STATUS_PAUSED
                        ? "Téléchargement en pause, attente du réseau…"
                        : "Téléchargement " + pct + " %",
                    pct);
                if (alive(a)) {
                  MAIN.removeCallbacks(POLL);
                  MAIN.postDelayed(POLL, 1000);
                }
              });
        });
  }

  private static void install(Activity activity, long id) {
    if (visible.get() != activity) return;
    if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
      publish(
          activity,
          "Autorisez l’installation des mises à jour, puis revenez dans l’application.",
          -1);
      if (prefs(activity).getBoolean(PERMISSION, false)) return;
      try {
        prefs(activity).edit().putBoolean(PERMISSION, true).apply();
        activity.startActivity(
            new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())));
      } catch (Exception e) {
        publish(activity, "Ouvrez les autorisations d’installation dans les réglages Android.", -1);
      }
      return;
    }
    if (!VERIFYING.compareAndSet(false, true)) return;
    Context app = activity.getApplicationContext();
    publish(app, "Vérification de l’APK et de sa signature…", 100);
    IO.execute(
        () -> {
          try {
            Uri downloaded =
                ((DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE))
                    .getUriForDownloadedFile(id);
            if (downloaded == null) throw new IOException("Téléchargement introuvable");
            File dir = new File(app.getCacheDir(), "updates");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Stockage inaccessible");
            File apk = new File(dir, "verified-" + id + ".apk");
            try (InputStream in = app.getContentResolver().openInputStream(downloaded);
                OutputStream out = new FileOutputStream(apk)) {
              if (in == null) throw new IOException("APK illisible");
              byte[] buffer = new byte[8192];
              int n;
              long size = 0;
              while ((n = in.read(buffer)) != -1) {
                size += n;
                if (size > 128 * 1024 * 1024) throw new IOException("APK trop volumineux");
                out.write(buffer, 0, n);
              }
            }
            verifyArchive(app, apk, prefs(app).getString(TARGET, ""));
            MAIN.post(
                () -> {
                  Activity a = visible.get();
                  if (!alive(a) || prefs(app).getLong(ID, -1) != id) return;
                  try {
                    Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", apk);
                    Intent intent =
                        new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    prefs(app).edit().putBoolean(LAUNCHED, true).commit();
                    a.startActivity(intent);
                    publish(
                        app,
                        "Installateur ouvert. Si vous annulez, utilisez Reprendre dans Réglages.",
                        100);
                  } catch (Exception e) {
                    prefs(app).edit().putBoolean(LAUNCHED, false).apply();
                    publish(app, "Installation impossible : " + message(e), -1);
                  }
                });
          } catch (Exception e) {
            prefs(app).edit().putBoolean(LAUNCHED, true).apply();
            publish(app, "APK refusé : " + message(e) + ". Annulez puis retéléchargez.", -1);
          } finally {
            VERIFYING.set(false);
          }
        });
  }

  @SuppressWarnings("deprecation")
  static void verifyArchive(Context context, File apk, String expected) throws Exception {
    PackageManager pm = context.getPackageManager();
    int flags =
        Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES
            : PackageManager.GET_SIGNATURES;
    PackageInfo incoming = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
    PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
    if (incoming == null || !context.getPackageName().equals(incoming.packageName))
      throw new IOException("Ce paquet n’est pas Plan Travail");
    if (incoming.versionCode <= installed.versionCode || !expected.equals(incoming.versionName))
      throw new IOException("Version incompatible ou déjà installée");
    Signature[] current = signers(installed), next = signers(incoming);
    if (current == null || next == null || current.length == 0 || !Arrays.equals(current, next))
      throw new IOException("La signature diffère de celle de l’application installée");
  }

  @SuppressWarnings("deprecation")
  private static Signature[] signers(PackageInfo info) {
    return Build.VERSION.SDK_INT >= 28
        ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners())
        : info.signatures;
  }

  static int compareVersions(String a, String b) {
    String[] aa = a.split("\\."), bb = b.split("\\.");
    for (int i = 0; i < Math.max(aa.length, bb.length); i++) {
      long x = i < aa.length ? number(aa[i]) : 0, y = i < bb.length ? number(bb[i]) : 0;
      if (x != y) return Long.compare(x, y);
    }
    return 0;
  }

  private static long number(String part) {
    try {
      return Long.parseLong(part);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static void publish(Context app, String text, int progress) {
    prefs(app).edit().putString("update_status", text).putInt("update_progress", progress).apply();
    MAIN.post(
        () -> {
          Activity a = visible.get();
          if (alive(a)) render(a);
        });
  }

  private static void render(Activity a) {
    TextView text = a.findViewById(R.id.tvUpdateStatus);
    ProgressBar progress = a.findViewById(R.id.progressUpdate);
    if (text != null) text.setText(prefs(a).getString("update_status", ""));
    if (progress != null) {
      int p = prefs(a).getInt("update_progress", -1);
      progress.setVisibility(p >= 0 ? View.VISIBLE : View.GONE);
      progress.setProgress(Math.max(0, p));
    }
  }

  private static String message(Exception e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }
}
