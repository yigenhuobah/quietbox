package com.quietbox.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 目标进程（小红书）→ 模块 App 的探测数据通道。
 *
 * 方向不能反过来：remote prefs 在目标进程只读，openRemoteFile 也是只读，
 * 所以「小红书读到了哪些 AB key/value」只能由目标进程主动 call 进来。
 *
 * 只接受 report 方法，key/value 都做长度和字符集校验；不存任何敏感信息。
 */
public final class AbReportProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.yigenhuobah.quietbox.ab";
    public static final String PREFS = "ab_report";

    /** 本地保存一份覆盖表，供目标进程运行中拉取（remote prefs 在宿主里是启动快照）。 */
    public static final String KEY_PANEL_ENABLED = "panel_enabled";
    public static final String KEY_OVERRIDES = "ab_overrides";

    private static final int MAX_KEY = 128;
    private static final int MAX_VALUE = 256;
    private static final int MAX_ITEMS = 800;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (getContext() == null) {
            return null;
        }
        if ("overrides".equals(method)) {
            SharedPreferences sp = getContext().getSharedPreferences(PREFS, 0);
            Bundle out = new Bundle();
            out.putBoolean(KEY_PANEL_ENABLED, sp.getBoolean(KEY_PANEL_ENABLED, false));
            out.putString(KEY_OVERRIDES, sp.getString(KEY_OVERRIDES, ""));
            return out;
        }
        if (!"report".equals(method) || extras == null) {
            return null;
        }
        String[] keys = extras.getStringArray("keys");
        String[] values = extras.getStringArray("values");
        if (keys == null || values == null || keys.length != values.length) {
            return null;
        }
        SharedPreferences sp = getContext().getSharedPreferences(PREFS, 0);
        SharedPreferences.Editor editor = sp.edit();
        int accepted = 0;
        for (int i = 0; i < keys.length && accepted < MAX_ITEMS; i++) {
            String key = keys[i];
            String value = values[i];
            if (!validKey(key) || value == null || value.length() > MAX_VALUE) {
                continue;
            }
            editor.putString("v:" + key, value);
            accepted++;
        }
        if (accepted > 0) {
            editor.putLong("t", System.currentTimeMillis());
            editor.apply();
        }
        return new Bundle();
    }

    private static boolean validKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
