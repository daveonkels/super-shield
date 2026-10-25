package com.gathin.tvhome;

import java.util.List;
import java.util.regex.Pattern;

/** The files ThemeProvider may write or delete under Config.dir, by the URI's path segments. */
final class ThemeFiles {
    static final String WALLPAPERS = "wallpapers";
    /** banner_files: one picture per app, named after its package, e.g. banners/com.example.app.png. */
    static final String BANNERS = "banners";
    /** The picture types a banner may have, in the order AppAdapter looks for them. */
    static final String[] BANNER_TYPES = {"png", "webp", "jpg", "jpeg"};
    /** The names tools/theme.sh accepts for a collection image, and the types WallpaperRotation reads. */
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);
    /** A package name, then one of BANNER_TYPES in lower case. */
    private static final Pattern BANNER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+\\.(png|webp|jpg|jpeg)");

    private ThemeFiles() {}

    /** The path relative to the theme directory, e.g. "config.json" or "wallpapers/dusk.jpg", or null if refused. */
    static String path(List<String> segments) {
        if (segments == null) return null;
        if (segments.size() == 1) {
            String name = segments.get(0);
            return Config.FILE_NAME.equals(name) || "wallpaper.jpg".equals(name) || "theme_revision".equals(name)
                    ? name : null;
        }
        if (segments.size() == 2 && WALLPAPERS.equals(segments.get(0))
                && (collectionImage(segments.get(1)) || WallpaperCaptions.FILE_NAME.equals(segments.get(1)))) {
            return WALLPAPERS + "/" + segments.get(1);
        }
        if (segments.size() == 2 && BANNERS.equals(segments.get(0)) && bannerImage(segments.get(1))) {
            return BANNERS + "/" + segments.get(1);
        }
        return null;
    }

    /** "wallpapers" or "banners" alone: a delete of it clears that folder's images. */
    static String collection(List<String> segments) {
        if (segments == null || segments.size() != 1) return null;
        String name = segments.get(0);
        return WALLPAPERS.equals(name) || BANNERS.equals(name) ? name : null;
    }

    static boolean collectionImage(String name) {
        return name != null && !name.startsWith(".") && IMAGE.matcher(name).matches();
    }

    static boolean bannerImage(String name) {
        return name != null && BANNER.matcher(name).matches();
    }
}
