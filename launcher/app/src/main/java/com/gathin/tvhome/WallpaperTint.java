package com.gathin.tvhome;

/**
 * wallpaper_tint (a theme's config.json): a colour matrix that recolours the wallpaper's light toward the focused
 * app's colour. The picture's luminance is painted in that colour and mixed with the original, so a dark sky stays
 * dark while its bright parts (an aurora's bands) take the app's hue. WallpaperPlayer applies it as a colour filter
 * on the GPU: no second bitmap, no repaint on the CPU.
 */
final class WallpaperTint {
    /** Lifts the light to the app's full colour: the aurora wallpaper's brightest bands sit near 55 % luminance. */
    static final float GAIN = 1.8f;
    private static final float[] LUMA = {0.2126f, 0.7152f, 0.0722f};

    private WallpaperTint() {}

    /** The 4x5 {@link android.graphics.ColorMatrix} array for {@code color} at {@code percent}; 0 is the identity. */
    static float[] matrix(int color, int percent) {
        float k = Math.max(0, Math.min(100, percent)) / 100f;
        float[] rgb = {((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f};
        float[] m = new float[20];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) m[i * 5 + j] = k * GAIN * rgb[i] * LUMA[j] + (i == j ? 1f - k : 0f);
        }
        m[18] = 1f;   // alpha unchanged
        return m;
    }
}
