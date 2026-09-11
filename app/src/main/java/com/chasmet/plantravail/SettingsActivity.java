package com.chasmet.plantravail;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends DataActivity {
  private SharedPreferences prefs;
  private TextView mcpStatus;
  private TextView publicMcpUrl;
  private TextView tunnelStatus;
  private Button mcpStartStop;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable statusLoop =
      new Runnable() {
        @Override
        public void run() {
          refreshMcpStatus();
          refreshRenderStatus();
          refreshBackupStatus();
          handler.postDelayed(this, 3000);
        }
      };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_settings);

    prefs = getSharedPreferences("settings", Context.MODE_PRIVATE);
    TextView version = findViewById(R.id.tvVersion);
    TextView updateStatus = findViewById(R.id.tvUpdateStatus);
    ProgressBar progress = findViewById(R.id.progressUpdate);
    SwitchCompat autoUpdate = findViewById(R.id.switchAutoUpdate);
    SwitchCompat mcpServer = findViewById(R.id.switchMcpServer);
    mcpStatus = findViewById(R.id.tvMcpServerStatus);
    publicMcpUrl = findViewById(R.id.tvPublicMcpUrl);
    tunnelStatus = findViewById(R.id.tvMcpTunnelStatus);
    mcpStartStop = findViewById(R.id.btnMcpStartStop);
    Button copyPublic = findViewById(R.id.btnCopyPublicMcpUrl);
    Button testPublic = findViewById(R.id.btnRestartPublicTunnel);
    Button check = findViewById(R.id.btnCheckUpdate);
    Button resetWeek = findViewById(R.id.btnResetWeek);

    prefs.edit().putString("mcp_url", McpBridgeClient.PUBLIC_BASE_URL).apply();
    version.setText(
        "Version installée : " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
    boolean autoEnabled = prefs.getBoolean("auto_update", true);
    boolean mcpEnabled = prefs.getBoolean("mcp_server_auto", true);
    autoUpdate.setChecked(autoEnabled);
    mcpServer.setChecked(mcpEnabled);

    autoUpdate.setOnCheckedChangeListener(
        (buttonView, isChecked) -> prefs.edit().putBoolean("auto_update", isChecked).apply());
    mcpServer.setOnCheckedChangeListener(
        (buttonView, isChecked) -> {
          prefs.edit().putBoolean("mcp_server_auto", isChecked).apply();
          if (isChecked) startMcpServer();
          else stopMcpServer();
        });

    mcpStartStop.setOnClickListener(
        v -> {
          mcpServer.setChecked(!mcpServer.isChecked());
        });

    copyPublic.setOnClickListener(
        v -> {
          ClipboardManager clipboard =
              (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
          clipboard.setPrimaryClip(
              ClipData.newPlainText("URL MCP Plan Travail Orsay", McpBridgeClient.PUBLIC_MCP_URL));
          Toast.makeText(this, "URL MCP Render copiée", Toast.LENGTH_SHORT).show();
        });

    testPublic.setOnClickListener(v -> testPublicServer());
    check.setOnClickListener(v -> UpdateManager.check(this, progress, updateStatus, true));
    resetWeek.setOnClickListener(v -> confirmResetWeek());
    findViewById(R.id.btnResumeUpdate).setOnClickListener(v -> UpdateManager.retry(this));
    findViewById(R.id.btnCancelUpdate).setOnClickListener(v -> UpdateManager.cancel(this));
    findViewById(R.id.btnBackupExport)
        .setOnClickListener(
            v ->
                startActivityForResult(
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(
                            Intent.EXTRA_TITLE,
                            "PlanTravail-sauvegarde-" + DayColor.today() + ".json"),
                    71));
    findViewById(R.id.btnBackupImport)
        .setOnClickListener(
            v ->
                startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"),
                    72));
    findViewById(R.id.btnRestorePrevious)
        .setOnClickListener(
            v ->
                new AlertDialog.Builder(this)
                    .setTitle("Revenir avant la dernière restauration ?")
                    .setMessage(
                        "Les rues et le lexique seront remplacés par leur état conservé avant la"
                            + " restauration précédente.")
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton(
                        "Rétablir", (d, w) -> BackupManager.restorePrevious(this, backupCallback()))
                    .show());
    refreshBackupStatus();

    if (mcpEnabled) startMcpServer();
    refreshMcpStatus();
    refreshPublicUrl();
    refreshRenderStatus();
    if (autoEnabled) UpdateManager.check(this, progress, updateStatus, false);
  }

  private void confirmResetWeek() {
    new AlertDialog.Builder(this)
        .setTitle("Remettre la semaine à zéro ?")
        .setMessage(
            "Tous les traçages et compteurs de la semaine en cours seront supprimés. Le lexique"
                + " sera conservé.")
        .setNegativeButton("Annuler", null)
        .setPositiveButton(
            "RESET",
            (dialog, which) -> {
              WorkDatabase database = WorkDatabase.getInstance(this);
              int deleted = database.clearCurrentWeek();

              Toast.makeText(
                      this, deleted + " traçage(s) supprimé(s) • semaine à zéro", Toast.LENGTH_LONG)
                  .show();
            })
        .show();
  }

  private void refreshPublicUrl() {
    publicMcpUrl.setText(McpBridgeClient.PUBLIC_MCP_URL);
  }

  private void refreshRenderStatus() {
    boolean connected = prefs.getBoolean("render_connected", false);
    long last = prefs.getLong("render_last_sync_ms", 0L);
    String error = prefs.getString("render_last_error", "");
    long age = last > 0 ? System.currentTimeMillis() - last : Long.MAX_VALUE;
    if (connected && age < 15000) {
      String time = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(new Date(last));
      tunnelStatus.setText("Téléphone synchronisé avec Render ✓ • dernière synchro " + time);
    } else if (error != null && !error.trim().isEmpty()) {
      tunnelStatus.setText("Render non connecté ✗ • " + error);
    } else if (!prefs.getBoolean("mcp_server_running", false)) {
      tunnelStatus.setText("Synchronisation arrêtée");
    } else {
      tunnelStatus.setText("En attente d’une synchronisation confirmée…");
    }
    tunnelStatus.append(
        "\nConfirmations locales en attente : "
            + WorkDatabase.getInstance(this).pendingCount()
            + "\n"
            + prefs.getString("last_command_status", "Aucune commande reçue")
            + "\n"
            + prefs.getString("health_test", ""));
  }

  private void testPublicServer() {
    tunnelStatus.setText("Test du serveur Render…");
    executor.execute(
        () -> {
          String result;
          try {
            MapDataCache.request(McpBridgeClient.PUBLIC_BASE_URL + "/health", null);
            result = "Serveur public joignable";
          } catch (Exception e) {
            result = "Serveur public : " + e.getMessage();
          }
          prefs.edit().putString("health_test", result).apply();
          runOnUiThread(
              () -> {
                if (!isDestroyed()) refreshRenderStatus();
              });
        });
  }

  private void startMcpServer() {
    Intent intent =
        new Intent(this, McpServerService.class).setAction(McpServerService.ACTION_START);
    ContextCompat.startForegroundService(this, intent);

    refreshMcpStatus();
  }

  private void stopMcpServer() {
    Intent intent =
        new Intent(this, McpServerService.class).setAction(McpServerService.ACTION_STOP);
    startService(intent);

    refreshMcpStatus();
  }

  private void refreshMcpStatus() {
    boolean running = prefs.getBoolean("mcp_server_running", false);
    mcpStatus.setText(
        running ? "Service Android actif • synchronisation automatique" : "Service Android arrêté");
    mcpStartStop.setText(running ? "Arrêter le service Android" : "Démarrer le service Android");
  }

  @Override
  protected void onResume() {
    super.onResume();
    handler.removeCallbacks(statusLoop);
    handler.post(statusLoop);
    refreshPublicUrl();
    UpdateManager.resumePendingInstall(this);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(statusLoop);
    UpdateManager.pause(this);
    super.onPause();
  }

  private void refreshBackupStatus() {
    long last = prefs.getLong("backup_last_ms", 0);
    ((TextView) findViewById(R.id.tvBackupStatus))
        .setText(
            (last == 0
                    ? "Une copie locale sera créée à la prochaine modification."
                    : "Copie locale : "
                        + new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
                            .format(new Date(last)))
                + "\n"
                + prefs.getString("backup_error", "")
                + "\nExportez une sauvegarde pour la conserver hors du téléphone.");
  }

  private BackupManager.Callback backupCallback() {
    return new BackupManager.Callback() {
      public void done(String message) {
        runOnUiThread(
            () -> {
              if (!isDestroyed()) {
                refreshBackupStatus();
                Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
              }
            });
      }

      public void error(String message) {
        runOnUiThread(
            () -> {
              if (!isDestroyed())
                new AlertDialog.Builder(SettingsActivity.this)
                    .setTitle("Sauvegarde / restauration impossible")
                    .setMessage(message)
                    .setPositiveButton("Fermer", null)
                    .show();
            });
      }
    };
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    android.net.Uri uri = data.getData();
    if (request == 71) BackupManager.exportTo(this, uri, backupCallback());
    else if (request == 72)
      new AlertDialog.Builder(this)
          .setTitle("Restaurer cette sauvegarde ?")
          .setMessage(
              "Les rues et le lexique actuels seront remplacés. Une copie de leur état actuel sera"
                  + " conservée pour permettre un retour en arrière.")
          .setNegativeButton("Annuler", null)
          .setPositiveButton(
              "Restaurer", (d, w) -> BackupManager.importFrom(this, uri, backupCallback()))
          .show();
  }

  @Override
  protected void onDataChanged() {
    if (prefs != null) {
      refreshBackupStatus();
      refreshRenderStatus();
    }
  }

  @Override
  protected void onDestroy() {
    executor.shutdownNow();
    super.onDestroy();
  }
}
