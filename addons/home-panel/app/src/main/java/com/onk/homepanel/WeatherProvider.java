package com.onk.homepanel;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * content://com.onk.homepanel.weather/now: one row, column "text" ("61°"), or no row when there is no fresh
 * reading. Read-only and holds nothing else, so it needs no permission. The launcher reads it for the date line
 * and watches the URI for changes while Home is on screen; the launcher itself never goes online.
 */
public class WeatherProvider extends ContentProvider {
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        MatrixCursor out = new MatrixCursor(new String[]{"text"});
        String text = Weather.current(getContext());
        if (!text.isEmpty()) out.addRow(new Object[]{text});
        out.setNotificationUri(getContext().getContentResolver(), Weather.URI);
        return out;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
}
