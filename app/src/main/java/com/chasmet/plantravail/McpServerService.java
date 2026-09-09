package com.chasmet.plantravail;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

public class McpServerService extends Service {
    public static final String ACTION_START = "com.chasmet.plantravail.MCP_START";
    public static final String ACTION_STOP = "com.chasmet.plantravail.MCP_STOP";
    private static final String CHANNEL_ID = "mcp_server";
    private EmbeddedMcpServer server;
    private McpPublicTunnel tunnel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WorkDatabase database;
    private StreetRepository streetRepository;
    private volatile List<Street> cachedStreets = new ArrayList<>();
    private volatile boolean polling = false;

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            List<Street> snapshot = cachedStreets;
            new McpBridgeClient(McpServerService.this, database).sync(snapshot, new McpBridgeClient.Callback() {
                @Override public void onDone(int count) {
                    if (polling) handler.postDelayed(pollTask, 3500);
                }
                @Override public void onError(String message) {
                    if (polling) handler.postDelayed(pollTask, 6000);
                }
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        tunnel = new McpPublicTunnel(this);
        database = new WorkDatabase(this);
        streetRepository = new StreetRepository(this);
        streetRepository.load(false, new StreetRepository.Callback() {
            @Override public void onLoaded(List<Street> streets, boolean fromCache) {
                cachedStreets = streets == null ? new ArrayList<>() : streets;
            }
            @Override public void onError(String message) { }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(8765, buildNotification("MCP Render connecté • synchronisation automatique"));
        startServer();
        startPolling();
        return START_STICKY;
    }

    private void startServer() {
        if (server != null && server.isAlive()) return;
        try {
            server = new EmbeddedMcpServer(this);
            server.start(5000, false);
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("mcp_server_running", true)
                    .putString("mcp_url", McpBridgeClient.PUBLIC_BASE_URL)
                    .putString("mcp_public_url", McpBridgeClient.PUBLIC_MCP_URL)
                    .putString("mcp_tunnel_status", "Serveur Render permanent actif")
                    .apply();
        } catch (Exception e) {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("mcp_server_running", false)
                    .putString("mcp_tunnel_status", "Serveur MCP local impossible à démarrer")
                    .apply();
        }
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        handler.removeCallbacks(pollTask);
        handler.post(pollTask);
    }

    private void stopServer() {
        polling = false;
        handler.removeCallbacks(pollTask);
        if (tunnel != null) tunnel.stop();
        if (server != null) {
            server.stop();
            server = null;
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("mcp_server_running", false)
                .apply();
    }

    @Override
    public void onDestroy() {
        stopServer();
        if (database != null) database.close();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Serveur MCP", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Synchronise Plan Travail Orsay avec le serveur MCP Render");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Plan Travail Orsay")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
