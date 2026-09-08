package com.chasmet.plantravail;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateManager {
    private static final String RELEASES_URL = "https://api.github.com/repos/Chasmet/Plan-travail-/releases/latest";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private UpdateManager() {}

    public static void check(Activity activity, ProgressBar progress, TextView status, boolean showUpToDate) {
        setStatus(activity, progress, status, 0, "Recherche d'une mise à jour…", false);
        EXECUTOR.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "PlanTravail-Android");
                int code = connection.getResponseCode();
                if (code == 404) {
                    if (showUpToDate) setStatus(activity, progress, status, 0, "Aucune version publiée pour le moment.", false);
                    return;
                }
                if (code < 200 || code >= 300) throw new IllegalStateException("GitHub HTTP " + code);

                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject release = new JSONObject(body.toString());
                String tag = release.optString("tag_name", "").replaceFirst("^[vV]", "");
                String apkUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset != null && asset.optString("name", "").toLowerCase().endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                if (apkUrl == null || apkUrl.isEmpty()) {
                    setStatus(activity, progress, status, 0, "Version publiée sans APK installable.", false);
                    return;
                }
                if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) {
                    if (showUpToDate) setStatus(activity, progress, status, 0, "L'application est à jour.", false);
                    return;
                }

                String finalApkUrl = apkUrl;
                activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                        .setTitle("Mise à jour disponible")
                        .setMessage("Version " + tag + " disponible. Télécharger et installer sans effacer vos données ?")
                        .setNegativeButton("Plus tard", null)
                        .setPositiveButton("Mettre à jour", (dialog, which) -> download(activity, finalApkUrl, tag, progress, status))
                        .show());
            } catch (Exception e) {
                if (showUpToDate) setStatus(activity, progress, status, 0, "Erreur de mise à jour : " + safeMessage(e), false);
            }
        });
    }

    private static void download(Activity activity, String url, String tag, ProgressBar progress, TextView status) {
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle("Plan Travail " + tag);
        request.setDescription("Téléchargement de la mise à jour");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "PlanTravail-" + tag + ".apk");
        request.setMimeType("application/vnd.android.package-archive");
        long id = manager.enqueue(request);
        activity.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putLong("pending_download_id", id).apply();
        setStatus(activity, progress, status, 0, "Téléchargement 0 %", true);
        pollDownload(activity, id, progress, status);
    }

    private static void pollDownload(Activity activity, long id, ProgressBar progress, TextView status) {
        EXECUTOR.execute(() -> {
            DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            boolean done = false;
            while (!done) {
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        long current = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        int pct = total > 0 ? (int) ((current * 100L) / total) : 0;
                        setStatus(activity, progress, status, pct, "Téléchargement " + pct + " %", true);
                        if (state == DownloadManager.STATUS_SUCCESSFUL) {
                            done = true;
                            setStatus(activity, progress, status, 100, "Téléchargement terminé. Installation…", true);
                            install(activity, id);
                        } else if (state == DownloadManager.STATUS_FAILED) {
                            done = true;
                            setStatus(activity, progress, status, 0, "Échec du téléchargement.", false);
                        }
                    }
                } catch (Exception e) {
                    done = true;
                    setStatus(activity, progress, status, 0, "Erreur : " + safeMessage(e), false);
                }
                if (!done) {
                    try { Thread.sleep(700); } catch (InterruptedException e) { Thread.currentThread().interrupt(); done = true; }
                }
            }
        });
    }

    public static void resumePendingInstall(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE);
        long id = prefs.getLong("pending_download_id", -1L);
        if (id < 0) return;
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor != null && cursor.moveToFirst()) {
                int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (state == DownloadManager.STATUS_SUCCESSFUL) install(activity, id);
            }
        }
    }

    private static void install(Activity activity, long id) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(permission);
            Toast.makeText(activity, "Autorisez Plan Travail à installer sa mise à jour, puis revenez dans l'application.", Toast.LENGTH_LONG).show();
            return;
        }
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = manager.getUriForDownloadedFile(id);
        if (uri == null) return;
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(install);
    }

    private static int compareVersions(String a, String b) {
        String[] aa = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] bb = b.replaceAll("[^0-9.]", "").split("\\.");
        int max = Math.max(aa.length, bb.length);
        for (int i = 0; i < max; i++) {
            int ai = i < aa.length && !aa[i].isEmpty() ? Integer.parseInt(aa[i]) : 0;
            int bi = i < bb.length && !bb[i].isEmpty() ? Integer.parseInt(bb[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static void setStatus(Activity activity, ProgressBar progress, TextView status, int value, String text, boolean visible) {
        activity.runOnUiThread(() -> {
            if (progress != null) {
                progress.setVisibility(visible ? View.VISIBLE : View.GONE);
                progress.setProgress(value);
            }
            if (status != null) status.setText(text);
        });
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
