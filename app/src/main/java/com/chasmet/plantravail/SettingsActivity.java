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
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {
    private SharedPreferences prefs;
    private TextView mcpStatus;
    private TextView publicMcpUrl;
    private TextView tunnelStatus;
    private Button mcpStartStop;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusLoop = new Runnable() {
        @Override public void run() {
            refreshMcpStatus();
            refreshRenderStatus();
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
        version.setText("Version installée : " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        boolean autoEnabled = prefs.getBoolean("auto_update", true);
        boolean mcpEnabled = prefs.getBoolean("mcp_server_auto", true);
        autoUpdate.setChecked(autoEnabled);
        mcpServer.setChecked(mcpEnabled);

        autoUpdate.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.edit().putBoolean("auto_update", isChecked).apply());
        mcpServer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("mcp_server_auto", isChecked).apply();
            if (isChecked) startMcpServer(); else stopMcpServer();
        });

        mcpStartStop.setOnClickListener(v -> {
            if (prefs.getBoolean("mcp_server_running", false)) stopMcpServer(); else startMcpServer();
        });

        copyPublic.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("URL MCP Plan Travail Orsay", McpBridgeClient.PUBLIC_MCP_URL));
            Toast.makeText(this, "URL MCP Render copiée", Toast.LENGTH_SHORT).show();
        });

        testPublic.setOnClickListener(v -> testPublicServer());
        check.setOnClickListener(v -> UpdateManager.check(this, progress, updateStatus, true));
        resetWeek.setOnClickListener(v -> confirmResetWeek());

        if (mcpEnabled) startMcpServer();
        refreshMcpStatus();
        refreshPublicUrl();
        refreshRenderStatus();
        if (autoEnabled) UpdateManager.check(this, progress, updateStatus, false);
    }

    private void confirmResetWeek() {
        new AlertDialog.Builder(this)
                .setTitle("Remettre la semaine à zéro ?")
                .setMessage("Tous les traçages et compteurs de la semaine en cours seront supprimés. Le lexique sera conservé.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("RESET", (dialog, which) -> {
                    WorkDatabase database = new WorkDatabase(this);
                    int deleted = database.clearCurrentWeek();
                    database.close();
                    Toast.makeText(this, deleted + " traçage(s) supprimé(s) • semaine à zéro", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(this, McpServerService.class).setAction(McpServerService.ACTION_START);
                    ContextCompat.startForegroundService(this, intent);
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
            tunnelStatus.setText("Connecté à Render ✓ • ChatGPT MCP prêt • dernière synchro " + time);
        } else if (error != null && !error.trim().isEmpty()) {
            tunnelStatus.setText("Render non connecté ✗ • " + error);
        } else {
            tunnelStatus.setText("Connexion à Render en cours…");
        }
    }

    private void testPublicServer() {
        tunnelStatus.setText("Test du serveur Render…");
        executor.execute(() -> {
            try {
                URL url = new URL(McpBridgeClient.PUBLIC_BASE_URL + "/health");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(12000);
                c.setReadTimeout(12000);
                c.setRequestMethod("GET");
                int code = c.getResponseCode();
                StringBuilder body = new StringBuilder();
                if (code >= 200 && code < 300) {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                        String line; while ((line = r.readLine()) != null) body.append(line);
                    }
                }
                runOnUiThread(() -> tunnelStatus.setText(code >= 200 && code < 300 ? "Serveur Render en ligne ✓ • attente du téléphone…" : "Serveur Render indisponible • HTTP " + code));
            } catch (Exception e) {
                runOnUiThread(() -> tunnelStatus.setText("Serveur Render indisponible : " + (e.getMessage() == null ? "erreur réseau" : e.getMessage())));
            }
        });
    }

    private void startMcpServer() {
        Intent intent = new Intent(this, McpServerService.class).setAction(McpServerService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);
        prefs.edit().putBoolean("mcp_server_running", true).apply();
        refreshMcpStatus();
    }

    private void stopMcpServer() {
        Intent intent = new Intent(this, McpServerService.class).setAction(McpServerService.ACTION_STOP);
        startService(intent);
        prefs.edit().putBoolean("mcp_server_running", false).apply();
        refreshMcpStatus();
    }

    private void refreshMcpStatus() {
        boolean running = prefs.getBoolean("mcp_server_running", false);
        mcpStatus.setText(running ? "Service Android actif • synchronisation automatique" : "Service Android arrêté");
        mcpStartStop.setText(running ? "Arrêter le service Android" : "Démarrer le service Android");
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(statusLoop);
        handler.post(statusLoop);
        refreshPublicUrl();
        UpdateManager.resumePendingInstall(this);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(statusLoop);
        super.onPause();
    }
}
