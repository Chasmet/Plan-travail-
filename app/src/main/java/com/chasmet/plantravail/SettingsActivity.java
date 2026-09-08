package com.chasmet.plantravail;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends AppCompatActivity {
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE);
        TextView version = findViewById(R.id.tvVersion);
        TextView updateStatus = findViewById(R.id.tvUpdateStatus);
        ProgressBar progress = findViewById(R.id.progressUpdate);
        SwitchCompat autoUpdate = findViewById(R.id.switchAutoUpdate);
        EditText mcpUrl = findViewById(R.id.etMcpUrl);
        Button save = findViewById(R.id.btnSave);
        Button check = findViewById(R.id.btnCheckUpdate);

        version.setText("Version installée : " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        boolean autoEnabled = prefs.getBoolean("auto_update", true);
        autoUpdate.setChecked(autoEnabled);
        mcpUrl.setText(prefs.getString("mcp_url", ""));

        autoUpdate.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.edit().putBoolean("auto_update", isChecked).apply());
        save.setOnClickListener(v -> {
            prefs.edit().putString("mcp_url", mcpUrl.getText().toString().trim()).apply();
            Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show();
        });
        check.setOnClickListener(v -> UpdateManager.check(this, progress, updateStatus, true));

        if (autoEnabled) UpdateManager.check(this, progress, updateStatus, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        UpdateManager.resumePendingInstall(this);
    }
}
