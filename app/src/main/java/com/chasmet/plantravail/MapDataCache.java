package com.chasmet.plantravail;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Validated, atomic map cache with a bundled first-launch/offline fallback. */
final class MapDataCache {
  static final ExecutorService IO = Executors.newSingleThreadExecutor();

  interface Parser<T> {
    T parse(String json) throws Exception;
  }

  static <T> T local(Context context, String name, Parser<T> parser) throws Exception {
    AtomicFile cache = new AtomicFile(new File(context.getFilesDir(), name));
    try (InputStream in = cache.openRead()) {
      return parser.parse(read(in));
    } catch (Exception invalidCache) {
      try (InputStream in = context.getAssets().open(name)) {
        return parser.parse(read(in));
      }
    }
  }

  static void save(Context context, String name, String text) throws Exception {
    AtomicFile cache = new AtomicFile(new File(context.getFilesDir(), name));
    FileOutputStream out = null;
    try {
      out = cache.startWrite();
      out.write(text.getBytes(StandardCharsets.UTF_8));
      cache.finishWrite(out);
    } catch (Exception e) {
      if (out != null) cache.failWrite(out);
      throw e;
    }
  }

  static String read(InputStream in) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) != -1) {
      if (out.size() + n > 16 * 1024 * 1024)
        throw new IOException("Données cartographiques trop volumineuses");
      out.write(buffer, 0, n);
    }
    return out.toString("UTF-8");
  }

  static String request(String url, byte[] body) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    try {
      c.setConnectTimeout(12000);
      c.setReadTimeout(40000);
      c.setRequestProperty("User-Agent", "PlanTravailOrsay/" + BuildConfig.VERSION_NAME);
      if (body != null) {
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        try (OutputStream out = c.getOutputStream()) {
          out.write(body);
        }
      }
      int status = c.getResponseCode();
      if (status < 200 || status >= 300)
        throw new IOException("Serveur cartographique HTTP " + status);
      try (InputStream in = c.getInputStream()) {
        return read(in);
      }
    } finally {
      c.disconnect();
    }
  }
}
