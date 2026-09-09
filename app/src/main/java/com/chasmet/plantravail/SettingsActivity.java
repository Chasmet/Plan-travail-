package com.chasmet.plantravail;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

public class SettingsActivity extends AppCompatActivity {
    private SharedPreferences prefs;
    private TextView mcpStatus;
    private Button mcpStartStop;

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
        mcpStartStop = findViewById(R.id.btnMcpStartStop);
        EditText mcpUrl = findViewById(R.id.etMcpUrl);
        Button save = findViewById(R.id.btnSave);
        Button check = findViewById(R.id.btnCheckUpdate);

        version.setText("Version installée : " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        boolean autoEnabled = prefs.getBoolean("auto_update", true);
        boolean mcpEnabled = prefs.getBoolean("mcp_server_auto", true);
        autoUpdate.setChecked(autoEnabled);
        mcpServer.setChecked(mcpEnabled);
        mcpUrl.setText(prefs.getString("mcp_url", ""));

        autoUpdate.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.edit().putBoolean("auto_update", isChecked).apply());
        mcpServer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("mcp_server_auto", isChecked).apply();
            if (isChecked) startMcpServer(); else stopMcpServer();
        });

        mcpStartStop.setOnClickListener(v -> {
            if (prefs.getBoolean("mcp_server_running", false)) stopMcpServer(); else startMcpServer();
        });

        save.setOnClickListener(v -> {
            prefs.edit().putString("mcp_url", mcpUrl.getText().toString().trim()).apply();
            Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show();
        });
        check.setOnClickListener(v -> UpdateManager.check(this, progress, updateStatus, true));

        if (mcpEnabled) startMcpServer();
        refreshMcpStatus();
        if (autoEnabled) UpdateManager.check(this, progress, updateStatus, false);
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

    @Override
    protected void onResume() {
        super.onResume();
        refreshMcpStatus();
        UpdateManager.resumePendingInstall(this);
    }
}
