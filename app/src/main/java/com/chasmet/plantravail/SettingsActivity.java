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

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

public class SettingsActivity extends AppCompatActivity {
    private SharedPreferences prefs;
    private TextView mcpStatus;
    private TextView publicMcpUrl;
    private TextView tunnelStatus;
    private Button mcpStartStop;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override public void run() {
            refreshMcpStatus();
            refreshPublicUrl();
            handler.postDelayed(this, 1000);
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
        Button restartPublic = findViewById(R.id.btnRestartPublicTunnel);
        Button check = findViewById(R.id.btnCheckUpdate);

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
            String url = prefs.getString("mcp_public_url", "");
            if (url == null || url.trim().isEmpty()) {
                Toast.makeText(this, "Adresse publique encore en création", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("URL MCP Plan Travail Orsay", url));
            Toast.makeText(this, "URL MCP copiée", Toast.LENGTH_SHORT).show();
        });

        restartPublic.setOnClickListener(v -> {
            prefs.edit().putString("mcp_public_url", "").putString("mcp_public_sse", "").putString("mcp_tunnel_status", "Recréation de l'adresse publique…").apply();
            stopMcpServer();
            handler.postDelayed(this::startMcpServer, 700);
            Toast.makeText(this, "Nouvelle adresse publique en cours de création", Toast.LENGTH_SHORT).show();
        });

        check.setOnClickListener(v -> UpdateManager.check(this, progress, updateStatus, true));

        if (mcpEnabled) startMcpServer();
        refreshMcpStatus();
        refreshPublicUrl();
        if (autoEnabled) UpdateManager.check(this, progress, updateStatus, false);
    }

    private void refreshPublicUrl() {
        String endpoint = prefs.getString("mcp_public_url", "");
        String state = prefs.getString("mcp_tunnel_status", "Préparation de l'adresse publique…");
        tunnelStatus.setText(state == null ? "" : state);
        if (endpoint == null || endpoint.trim().isEmpty()) {
            publicMcpUrl.setText("Création en cours…");
        } else {
            publicMcpUrl.setText(endpoint);
        }
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
        mcpStatus.setText(running ? "Serveur actif • http://127.0.0.1:8765/mcp" : "Serveur arrêté");
        mcpStartStop.setText(running ? "Arrêter le serveur" : "Démarrer le serveur");
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
        UpdateManager.resumePendingInstall(this);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }
}
