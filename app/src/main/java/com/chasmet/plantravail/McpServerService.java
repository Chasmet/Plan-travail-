package com.chasmet.plantravail;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import java.util.ArrayList;
import java.util.List;

public class McpServerService extends Service {
  public static final String ACTION_START = "com.chasmet.plantravail.MCP_START",
      ACTION_STOP = "com.chasmet.plantravail.MCP_STOP";
  private static final String CHANNEL = "mcp_server";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private volatile boolean polling;
  private volatile List<Street> streets = new ArrayList<>();
  private McpBridgeClient client;
  private EmbeddedMcpServer server;
  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (!polling) return;
          client.sync(
              streets,
              new McpBridgeClient.Callback() {
                public void onDone(int count) {
                  if (polling) {
                    notifyStatus("Synchronisé avec Render");
                    handler.postDelayed(poll, 6000);
                  }
                }

                public void onError(String message) {
                  if (polling) {
                    notifyStatus("Synchronisation en attente • ouvrir Réglages");
                    handler.postDelayed(poll, 10000);
                  }
                }
              });
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel c =
          new NotificationChannel(
              CHANNEL, "Synchronisation Plan Travail", NotificationManager.IMPORTANCE_LOW);
      getSystemService(NotificationManager.class).createNotificationChannel(c);
    }
    client = new McpBridgeClient(this, WorkDatabase.getInstance(this));
    new StreetRepository(this)
        .load(
            false,
            new StreetRepository.Callback() {
              public void onLoaded(List<Street> result, boolean cache) {
                streets = result;
              }

              public void onError(String message) {
                getSharedPreferences("settings", MODE_PRIVATE)
                    .edit()
                    .putString("render_last_error", message)
                    .apply();
              }
            });
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      stopForeground(true);
      stopSelf();
      return START_NOT_STICKY;
    }
    if (intent == null
        && !getSharedPreferences("settings", MODE_PRIVATE).getBoolean("mcp_server_auto", true)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    startForeground(
        8765,
        new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Plan Travail Orsay")
            .setContentText("Connexion en cours…")
            .setOngoing(true)
            .build());
    if (server == null) {
      try {
        server = new EmbeddedMcpServer(this);
        server.start(5000, false);
      } catch (Exception e) {
        server = null;
      }
    }
    getSharedPreferences("settings", MODE_PRIVATE)
        .edit()
        .putBoolean("mcp_server_running", true)
        .apply();
    if (!polling) {
      polling = true;
      handler.post(poll);
    }
    return START_STICKY;
  }

  private void notifyStatus(String text) {
    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
        .notify(
            8765,
            new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Plan Travail Orsay")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build());
  }

  @Override
  public void onDestroy() {
    polling = false;
    handler.removeCallbacksAndMessages(null);
    if (server != null) server.stop();
    getSharedPreferences("settings", MODE_PRIVATE)
        .edit()
        .putBoolean("mcp_server_running", false)
        .putBoolean("render_connected", false)
        .apply();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
