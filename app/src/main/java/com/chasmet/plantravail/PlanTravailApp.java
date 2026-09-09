package com.chasmet.plantravail;

import android.app.Application;
import android.content.Intent;

import androidx.core.content.ContextCompat;

public class PlanTravailApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        boolean enabled = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("mcp_server_auto", true);
        if (enabled) {
            Intent intent = new Intent(this, McpServerService.class).setAction(McpServerService.ACTION_START);
            ContextCompat.startForegroundService(this, intent);
        }
    }
}
