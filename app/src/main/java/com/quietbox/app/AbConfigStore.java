package com.quietbox.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AB 面板的 App 侧存储。
 *
 * 覆盖表同时写两份：框架 remote prefs 供宿主启动时读，本地 prefs 供
 * {@link AbReportProvider} 在运行中返回给宿主（remote prefs 在宿主里是启动快照）。
 * 探测结果也写在本地 prefs。
 */
final class AbConfigStore {

    private final Context mContext;
    private final SharedPreferences mRemote;
    private final SharedPreferences mLocal;
    private final SharedPreferences mReport;

    AbConfigStore(Context context) {
        mContext = context.getApplicationContext();
        mRemote = ServiceBridge.remotePreferences(Config.GROUP);
        mLocal = context.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE);
        mReport = context.getSharedPreferences(AbReportProvider.PREFS, Context.MODE_PRIVATE);
    }

    boolean remote() {
        return mRemote != null;
    }

    /** 面板总开关：remote 给宿主启动时读，本地给运行中的拉取通道读。 */
    void setPanelEnabled(boolean enabled) {
        new ConfigStore(mContext).put(Feature.AB_TEST_PANEL.key, enabled);
        mReport.edit().putBoolean(AbReportProvider.KEY_PANEL_ENABLED, enabled).apply();
    }

    boolean panelEnabled() {
        return mReport.getBoolean(AbReportProvider.KEY_PANEL_ENABLED, false);
    }

    /** 把本地副本对齐到 remote 的真实值（升级或清数据后可能不一致）。 */
    void syncPanelEnabled(boolean enabled) {
        if (panelEnabled() != enabled) {
            mReport.edit().putBoolean(AbReportProvider.KEY_PANEL_ENABLED, enabled).apply();
        }
    }

    Map<String, String> overrides() {
        return parse(read(Config.KEY_AB_OVERRIDES));
    }

    boolean isForced(String key) {
        return overrides().containsKey(key);
    }

    void setForced(String key, boolean forced, String value) {
        Map<String, String> map = overrides();
        if (forced) {
            map.put(key, value);
        } else {
            map.remove(key);
        }
        write(serialize(map));
    }

    Map<String, String> report() {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : mReport.getAll().entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("v:") || !(e.getValue() instanceof String)) {
                continue;
            }
            map.put(key.substring(2), (String) e.getValue());
        }
        return map;
    }

    long lastReport() {
        return mReport.getLong("t", 0L);
    }

    private String read(String key) {
        try {
            return (mRemote != null ? mRemote : mLocal).getString(key, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private void write(String value) {
        try {
            SharedPreferences sp = mRemote != null ? mRemote : mLocal;
            sp.edit().putString(Config.KEY_AB_OVERRIDES, value).apply();
            // 宿主里的 remote prefs 是启动快照，运行中要靠本地副本 + provider 拉取。
            mReport.edit().putString(AbReportProvider.KEY_OVERRIDES, value).apply();
        } catch (Throwable ignored) {
        }
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null) {
            return map;
        }
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                map.put(key, value);
            }
        }
        return map;
    }

    private static String serialize(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
