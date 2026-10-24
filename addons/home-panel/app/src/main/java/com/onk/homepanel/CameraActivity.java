package com.onk.homepanel;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.List;

/**
 * One camera full screen, from Home Assistant's MJPEG proxy (/api/camera_proxy_stream), which works for every
 * camera entity, UniFi Protect's included, with no video library. Left and Right switch cameras, Back closes.
 * The stream runs only while this screen is visible.
 */
public final class CameraActivity extends Activity {
    static final String EXTRA_INDEX = "index";
    /** The name and position fade this long after a camera change. */
    private static final long LABEL_MS = 3000;
    /** Frames larger than this are skipped rather than read into memory. */
    private static final int MAX_FRAME = 8 * 1024 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hideLabel = () -> findViewById(R.id.label).animate().alpha(0f).setDuration(400).start();
    private ImageView frame;
    private TextView name;
    private TextView hint;
    private Config cfg;
    private List<String> cameras;
    private int index;
    private Thread reader;
    private volatile HttpURLConnection connection;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_camera);
        frame = findViewById(R.id.frame);
        name = findViewById(R.id.name);
        hint = findViewById(R.id.hint);
        cfg = Config.load(this);
        cameras = cfg.cameras;
        index = Math.max(0, Math.min(cameras.size() - 1, getIntent().getIntExtra(EXTRA_INDEX, 0)));
        if (cameras.isEmpty()) finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!cameras.isEmpty()) show(index);
    }

    @Override
    protected void onStop() {
        stopStream();
        super.onStop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (cameras.size() > 1 && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            int step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            show((index + step + cameras.size()) % cameras.size());
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void show(int i) {
        index = i;
        String id = cameras.get(i);
        String cached = getSharedPreferences("names", MODE_PRIVATE).getString(id, PanelActivity.fallbackName(id));
        name.setText(PanelActivity.shortName(cached));
        hint.setText(cameras.size() > 1 ? (i + 1) + " / " + cameras.size() + "   ◀ ▶" : "");
        View label = findViewById(R.id.label);
        label.animate().cancel();
        label.setAlpha(1f);
        main.removeCallbacks(hideLabel);
        main.postDelayed(hideLabel, LABEL_MS);
        stopStream();
        frame.setImageDrawable(null);
        startStream(id);
    }

    private void startStream(String camera) {
        Thread t = new Thread(() -> stream(camera), "camera");
        reader = t;
        t.start();
    }

    private void stopStream() {
        Thread t = reader;
        reader = null;
        HttpURLConnection c = connection;
        if (c != null) new Thread(c::disconnect).start();   // unblocks the reader; disconnect may touch the network
        if (t != null) t.interrupt();
    }

    /** Reads multipart JPEG frames until this camera is no longer the one on screen. */
    private void stream(String camera) {
        Thread self = Thread.currentThread();
        try {
            HttpURLConnection c = cfg.client().openCameraStream(camera);
            connection = c;
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = new BufferedInputStream(c.getInputStream(), 64 * 1024)) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;   // half the memory of ARGB, no visible loss here
                opts.inSampleSize = 1;   // 0 by default, which the sizing below would divide by
                int screen = Math.max(getResources().getDisplayMetrics().widthPixels, 1280);
                boolean sized = false;
                while (reader == self) {
                    byte[] jpeg = nextFrame(in);
                    if (jpeg == null) break;
                    if (!sized) {
                        // A 4K snapshot decoded at full size is 16 MB a frame for a 1080p screen: decode at screen size.
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
                        while (bounds.outWidth / (opts.inSampleSize * 2) >= screen) opts.inSampleSize *= 2;
                        sized = bounds.outWidth > 0;
                    }
                    Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
                    if (bmp == null || reader != self) continue;
                    main.post(() -> {
                        if (reader == self) frame.setImageBitmap(bmp);
                    });
                }
            }
        } catch (IOException | RuntimeException e) {
            android.util.Log.w("HomePanel", "camera " + camera + ": " + e);
            if (reader == self) main.post(() -> {
                if (reader != self) return;
                hint.setText(R.string.camera_error);
                findViewById(R.id.label).setAlpha(1f);
                main.removeCallbacks(hideLabel);
            });
        } finally {
            if (reader == self) connection = null;
        }
    }

    /**
     * The next JPEG in a multipart/x-mixed-replace stream: part headers up to a blank line, then Content-Length
     * bytes. Home Assistant always sends Content-Length; without it, the part is read up to the JPEG end marker.
     * Null at the end of the stream.
     */
    static byte[] nextFrame(InputStream in) throws IOException {
        int length = -1;
        boolean inHeaders = false;
        for (String line; (line = readLine(in)) != null; ) {
            if (line.startsWith("--")) {
                inHeaders = true;
                continue;
            }
            if (!inHeaders) continue;
            if (line.isEmpty()) {
                if (length > 0 && length <= MAX_FRAME) return readFully(in, length);
                if (length > MAX_FRAME) {
                    skip(in, length);
                    length = -1;
                    inHeaders = false;
                    continue;
                }
                return untilEndMarker(in);
            }
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    length = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    length = -1;
                }
            }
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int b; (b = in.read()) != -1; ) {
            if (b == '\n') {
                int n = sb.length();
                return n > 0 && sb.charAt(n - 1) == '\r' ? sb.substring(0, n - 1) : sb.toString();
            }
            if (sb.length() < 1024) sb.append((char) b);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        for (int off = 0, n; off < length; off += n) {
            n = in.read(out, off, length - off);
            if (n < 0) throw new IOException("stream ended mid-frame");
        }
        return out;
    }

    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) return;
                s = 1;
            }
            n -= s;
        }
    }

    private static byte[] untilEndMarker(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(256 * 1024);
        int prev = -1;
        for (int b; (b = in.read()) != -1; ) {
            out.write(b);
            if (prev == 0xFF && b == 0xD9) return out.toByteArray();
            if (out.size() > MAX_FRAME) throw new IOException("frame too large");
            prev = b;
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
