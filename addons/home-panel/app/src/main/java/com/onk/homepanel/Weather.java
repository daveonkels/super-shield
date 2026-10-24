package com.onk.homepanel;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The outside temperature from the configured Home Assistant sensor, cached so the launcher's minute tick costs
 * no network round trip. Fetched only when someone asks (the launcher's Home, or the panel) and the value is older
 * than five minutes: no service, no polling while nothing is on screen.
 */
final class Weather {
    static final Uri URI = Uri.parse("content://com.onk.homepanel.weather/now");
    static final long REFRESH_MS = 5 * 60_000L;
    /** Older than this, nothing is shown rather than a temperature that may be hours out of date. */
    static final long STALE_MS = 60 * 60_000L;

    private static final AtomicBoolean refreshing = new AtomicBoolean();

    private Weather() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /** "61°", or "" when there is no fresh reading. Starts a refresh when the reading is due. */
    static String current(Context c) {
        SharedPreferences p = prefs(c);
        long age = System.currentTimeMillis() - p.getLong("at", 0);
        if (age > REFRESH_MS) refresh(c.getApplicationContext());
        return age > STALE_MS ? "" : p.getString("text", "");
    }

    static void clear(Context c) {
        prefs(c).edit().clear().apply();
    }

    /** Reads the sensor on a thread of its own, saves it and tells the launcher (and the panel) it changed. */
    static void refresh(Context c) {
        if (!refreshing.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                Config cfg = Config.load(c);
                if (!cfg.complete() || cfg.temperature.isEmpty()) return;
                HaClient.Reply r = cfg.client().get("/api/states/" + cfg.temperature);
                if (!r.ok()) return;
                String text = format(new JSONObject(r.body).optString("state", ""));
                prefs(c).edit().putString("text", text).putLong("at", System.currentTimeMillis()).apply();
                c.getContentResolver().notifyChange(URI, null);
            } catch (Exception ignored) {
                // offline or not JSON: keep the last reading until it goes stale
            } finally {
                refreshing.set(false);
            }
        }, "weather").start();
    }

    /** A sensor state as whole degrees: "61.3" is "61°"; "unavailable" and "unknown" are "". */
    static String format(String state) {
        try {
            return Math.round(Double.parseDouble(state)) + "°";
        } catch (NumberFormatException e) {
            return "";
        }
    }
}
