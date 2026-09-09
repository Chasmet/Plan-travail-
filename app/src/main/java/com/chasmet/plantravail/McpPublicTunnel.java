package com.chasmet.plantravail;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Conserve le nom historique pour éviter de casser le service Android,
 * mais ne crée plus aucun tunnel tiers. Cette classe publie seulement
 * les adresses réseau directement portées par le téléphone.
 */
public final class McpPublicTunnel {
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public McpPublicTunnel(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public synchronized void start() {
        prefs.edit()
                .putString("mcp_public_url", "")
                .putString("mcp_public_sse", "")
                .putString("mcp_tunnel_status", "Recherche d'une adresse Internet directe du téléphone…")
                .apply();

        executor.execute(() -> {
            String globalIpv6 = null;
            String localIpv4 = null;
            try {
                List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
                for (NetworkInterface ni : interfaces) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                        if (address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                        String host = address.getHostAddress();
                        if (host == null || host.isEmpty()) continue;
                        int scope = host.indexOf('%');
                        if (scope >= 0) host = host.substring(0, scope);

                        if (address instanceof Inet6Address) {
                            if (!address.isSiteLocalAddress() && !host.toLowerCase().startsWith("fc") && !host.toLowerCase().startsWith("fd")) {
                                globalIpv6 = host;
                                break;
                            }
                        } else if (localIpv4 == null && address.isSiteLocalAddress()) {
                            localIpv4 = host;
                        }
                    }
                    if (globalIpv6 != null) break;
                }

                SharedPreferences.Editor edit = prefs.edit();
                if (globalIpv6 != null) {
                    String endpoint = "http://[" + globalIpv6 + "]:8765/mcp";
                    edit.putString("mcp_public_url", endpoint)
                            .putString("mcp_tunnel_status", "Adresse IPv6 directe détectée. Serveur fourni uniquement par l'application.")
                            .putString("mcp_direct_ipv6", globalIpv6);
                } else if (localIpv4 != null) {
                    String endpoint = "http://" + localIpv4 + ":8765/mcp";
                    edit.putString("mcp_public_url", endpoint)
                            .putString("mcp_tunnel_status", "Serveur autonome actif. Le réseau actuel ne fournit pas d'adresse Internet directement joignable; adresse Wi‑Fi locale affichée.")
                            .putString("mcp_local_url", endpoint);
                } else {
                    edit.putString("mcp_tunnel_status", "Serveur autonome actif, mais aucune adresse réseau exploitable détectée.");
                }
                edit.apply();
            } catch (Exception e) {
                prefs.edit().putString("mcp_tunnel_status", "Détection réseau impossible : " + (e.getMessage() == null ? "erreur inconnue" : e.getMessage())).apply();
            }
        });
    }

    public synchronized void stop() {
        prefs.edit()
                .putString("mcp_public_url", "")
                .putString("mcp_public_sse", "")
                .putString("mcp_tunnel_status", "Serveur autonome arrêté")
                .apply();
    }
}
