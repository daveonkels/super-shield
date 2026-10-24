package com.onk.homepanel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The server, token and the entities the panel shows, in the app's own storage (backups are off). They arrive as
 * config.json pushed by install.sh, read once and deleted. A pushed file sets only the keys it has, so the
 * entities can be changed without pushing the token again:
 * {"url": "http://192.168.1.10:8123", "token": "...", "temperature": "sensor.x",
 *  "scenes": ["scene.a"], "cameras": ["camera.b"]}
 */
final class Config {
    static final String PUSHED = "config.json";

    final String url;
    final String token;
    final String temperature;
    final List<String> scenes;
    final List<String> cameras;

    private Config(String url, String token, String temperature, List<String> scenes, List<String> cameras) {
        this.url = url;
        this.token = token;
        this.temperature = temperature;
        this.scenes = scenes;
        this.cameras = cameras;
    }

    boolean complete() {
        return !url.isEmpty() && !token.isEmpty();
    }

    HaClient client() {
        return new HaClient(url, token);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("server", Context.MODE_PRIVATE);
    }

    static Config load(Context c) {
        SharedPreferences p = prefs(c);
        return new Config(p.getString("url", ""), p.getString("token", ""), p.getString("temperature", ""),
                list(p.getString("scenes", "[]")), list(p.getString("cameras", "[]")));
    }

    private static List<String> list(String json) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                String id = a.optString(i, "").trim();
                if (id.matches("[a-z_]+\\.[a-z0-9_]+")) out.add(id);
            }
        } catch (Exception ignored) {
            // not a list: none
        }
        return Collections.unmodifiableList(out);
    }

    /** "http://host:8123/" or "host:8123" as "http://host:8123". */
    static String normalizeUrl(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (!u.isEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        return u;
    }

    /** Takes a pushed config.json if there is one, and deletes it. True when taken. */
    static boolean importPushed(Context c) {
        File dir = c.getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, PUSHED);
        if (f == null || !f.isFile()) return false;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            SharedPreferences.Editor e = prefs(c).edit();
            if (o.has("url")) e.putString("url", normalizeUrl(o.optString("url")));
            if (o.has("token")) e.putString("token", o.optString("token").trim());
            if (o.has("temperature")) e.putString("temperature", o.optString("temperature").trim());
            if (o.has("scenes")) e.putString("scenes", String.valueOf(o.optJSONArray("scenes")));
            if (o.has("cameras")) e.putString("cameras", String.valueOf(o.optJSONArray("cameras")));
            e.apply();
            Weather.clear(c);   // the sensor or the server may have changed
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
