package com.chasmet.plantravail;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class McpPublicTunnel {
    private static final Pattern PUBLIC_URL = Pattern.compile("https://[a-zA-Z0-9-]+\\.trycloudflare\\.com");
    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Process process;

    public McpPublicTunnel(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public synchronized void start() {
        if (process != null && process.isAlive()) return;
        prefs.edit()
                .putString("mcp_public_url", "")
                .putString("mcp_tunnel_status", "Démarrage de l'adresse publique…")
                .apply();
        executor.execute(() -> {
            try {
                File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libcloudflared.so");
                if (!binary.exists()) throw new IllegalStateException("module de tunnel absent de l'APK");
                ProcessBuilder pb = new ProcessBuilder(
                        binary.getAbsolutePath(),
                        "tunnel",
                        "--no-autoupdate",
                        "--url",
                        "http://127.0.0.1:8765"
                );
                pb.redirectErrorStream(true);
                process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    boolean found = false;
                    while ((line = reader.readLine()) != null) {
                        Matcher m = PUBLIC_URL.matcher(line);
                        if (m.find()) {
                            String base = m.group();
                            prefs.edit()
                                    .putString("mcp_public_url", base + "/mcp")
                                    .putString("mcp_public_sse", base + "/sse")
                                    .putString("mcp_tunnel_status", "Adresse publique active")
                                    .apply();
                            found = true;
                        }
                    }
                    if (!found && (process == null || !process.isAlive())) {
                        prefs.edit().putString("mcp_tunnel_status", "Tunnel public arrêté").apply();
                    }
                }
            } catch (Exception e) {
                prefs.edit()
                        .putString("mcp_public_url", "")
                        .putString("mcp_tunnel_status", "Erreur tunnel : " + (e.getMessage() == null ? "inconnue" : e.getMessage()))
                        .apply();
            }
        });
    }

    public synchronized void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
        prefs.edit()
                .putString("mcp_public_url", "")
                .putString("mcp_public_sse", "")
                .putString("mcp_tunnel_status", "Tunnel public arrêté")
                .apply();
    }
}
