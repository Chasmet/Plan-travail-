package com.chasmet.plantravail;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class McpServerService extends Service {
    public static final String ACTION_START = "com.chasmet.plantravail.MCP_START";
    public static final String ACTION_STOP = "com.chasmet.plantravail.MCP_STOP";
    private static final String CHANNEL_ID = "mcp_server";
    private EmbeddedMcpServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(8765, buildNotification("Serveur MCP actif sur 127.0.0.1:8765"));
        startServer();
        return START_STICKY;
    }

    private void startServer() {
        if (server != null && server.isAlive()) return;
        try {
            server = new EmbeddedMcpServer(this);
            server.start(5000, false);
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("mcp_server_running", true).apply();
        } catch (Exception e) {
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("mcp_server_running", false).apply();
            stopSelf();
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("mcp_server_running", false).apply();
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Serveur MCP", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Maintient le serveur MCP local de Plan Travail Orsay actif");
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
