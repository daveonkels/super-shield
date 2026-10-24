package com.onk.homepanel;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Home panel: the outside temperature, the configured scenes (OK runs one) and cameras (OK shows it full
 * screen), in the launcher's glass panel style, over Home or whatever is playing. Back, or opening it again,
 * closes it; so does anything else taking the screen.
 */
public final class PanelActivity extends Activity {
    /** How long a scene's "Done" stays before the panel closes. */
    private static final long DONE_MS = 700;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, TextView> titles = new HashMap<>();
    private TextView temperature;
    private Config cfg;

    private final ContentObserver weatherChanged = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            temperature.setText(Weather.current(PanelActivity.this));
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_panel);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        temperature = findViewById(R.id.temperature);
        LinearLayout rows = findViewById(R.id.rows);
        cfg = Config.load(this);
        if (!cfg.complete()) {
            addRow(rows, R.drawable.ic_home, getString(R.string.not_set_up), getString(R.string.not_set_up_hint), null);
            rows.getChildAt(0).requestFocus();
            return;
        }
        Map<String, String> names = cachedNames();
        addSection(rows, R.string.scenes, cfg.scenes, R.drawable.ic_scene, names, this::runScene);
        List<String> cameras = cfg.cameras;
        addSection(rows, R.string.cameras, cameras, R.drawable.ic_camera, names, (id, row) -> startActivity(
                new Intent(this, CameraActivity.class).putExtra(CameraActivity.EXTRA_INDEX, cameras.indexOf(id))));
        View first = null;
        for (int i = 0; i < rows.getChildCount(); i++) {
            if (rows.getChildAt(i).isFocusable()) {
                first = rows.getChildAt(i);
                break;
            }
        }
        if (first != null) first.requestFocus();
        io.execute(this::loadNames);
    }

    @Override
    protected void onResume() {
        super.onResume();
        temperature.setText(Weather.current(this));
        getContentResolver().registerContentObserver(Weather.URI, false, weatherChanged);
    }

    /** Opened again (the remote's button held a second time) while up: close. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a camera, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        getContentResolver().unregisterContentObserver(weatherChanged);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private interface RowAction {
        void run(String entityId, View row);
    }

    private void addSection(LinearLayout rows, int heading, List<String> ids, int icon, Map<String, String> names,
                            RowAction action) {
        if (ids.isEmpty()) return;
        TextView label = (TextView) getLayoutInflater().inflate(R.layout.item_section, rows, false);
        label.setText(heading);
        rows.addView(label);
        for (String id : ids) {
            String name = shortName(names.containsKey(id) ? names.get(id) : fallbackName(id));
            View row = addRow(rows, icon, name, "", null);
            row.setOnClickListener(v -> action.run(id, v));
            titles.put(id, row.findViewById(R.id.title));
        }
    }

    private View addRow(LinearLayout rows, int icon, String title, String status, View.OnClickListener click) {
        View row = getLayoutInflater().inflate(R.layout.item_row, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.status)).setText(status);
        if (click != null) row.setOnClickListener(click);
        rows.addView(row);
        return row;
    }

    private void runScene(String id, View row) {
        TextView status = row.findViewById(R.id.status);
        io.execute(() -> {
            HaClient.Reply r = cfg.client().post("/api/services/scene/turn_on",
                    "{\"entity_id\":" + JSONObject.quote(id) + "}");
            main.post(() -> {
                status.setText(r.ok() ? R.string.done : r.code == 0 ? R.string.offline : R.string.failed);
                if (r.ok()) main.postDelayed(this::finish, DONE_MS);
            });
        });
    }

    /** Each entity's friendly name from Home Assistant, saved for the next time the panel opens. */
    private void loadNames() {
        Map<String, String> found = new HashMap<>();
        for (String id : titles.keySet()) {
            HaClient.Reply r = cfg.client().get("/api/states/" + id);
            if (!r.ok()) continue;
            try {
                String name = new JSONObject(r.body).optJSONObject("attributes").optString("friendly_name", "");
                if (!name.isEmpty()) found.put(id, name);
            } catch (Exception ignored) {
                // keep the fallback name
            }
        }
        if (found.isEmpty()) return;
        SharedPreferences.Editor e = getSharedPreferences("names", MODE_PRIVATE).edit();
        for (Map.Entry<String, String> f : found.entrySet()) e.putString(f.getKey(), f.getValue());
        e.apply();
        main.post(() -> {
            for (Map.Entry<String, String> f : found.entrySet()) {
                TextView t = titles.get(f.getKey());
                if (t != null) t.setText(shortName(f.getValue()));
            }
        });
    }

    private Map<String, String> cachedNames() {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, ?> e : getSharedPreferences("names", MODE_PRIVATE).getAll().entrySet()) {
            out.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return out;
    }

    /** UniFi Protect names its camera entities "Driveway High resolution channel"; the panel says "Driveway". */
    static String shortName(String name) {
        String s = name.replaceFirst("(?i)\\s+(high|medium|low|package) resolution channel$", "").trim();
        return s.isEmpty() ? name : s;
    }

    /** "scene.movie_night" as "Movie night", until Home Assistant answers with its friendly name. */
    static String fallbackName(String id) {
        String s = id.substring(id.indexOf('.') + 1).replace('_', ' ').trim();
        return s.isEmpty() ? id : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
