package com.quietbox.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AB 面板的 App 侧存储。
 *
 * 覆盖表同时写两份：框架 remote prefs 供宿主启动时读，本地 prefs 供
 * {@link AbReportProvider} 在运行中返回给宿主（remote prefs 在宿主里是启动快照）。
 * 探测结果也写在本地 prefs。
 *
 * 写入语义与 {@link ConfigStore} 一致：remote 不可用直接拒绝；commit() 排到
 * {@link ConfigWrites} 的后台单线程并检查真实结果；本地副本只在 remote 成功后
 * 跟随更新，避免「UI 已回滚但活通道仍读新值」。
 */
final class AbConfigStore {

    private static final String TAG = "QuietBox";

    private final SharedPreferences mRemote;
    private final SharedPreferences mLocal;
    private final SharedPreferences mReport;

    AbConfigStore(Context context) {
        this(ServiceBridge.remotePreferences(Config.GROUP),
                context.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE),
                context.getSharedPreferences(AbReportProvider.PREFS, Context.MODE_PRIVATE));
    }

    /** 测试入口：直接注入三份存储，不经过 ServiceBridge。 */
    AbConfigStore(SharedPreferences remote, SharedPreferences local, SharedPreferences report) {
        mRemote = remote;
        mLocal = local;
        mReport = report;
    }

    boolean remote() {
        return mRemote != null;
    }

    /** 面板总开关：remote 给宿主启动时读，本地给运行中的拉取通道读。 */
    boolean setPanelEnabled(boolean enabled) {
        return setPanelEnabled(enabled, null);
    }

    /**
     * @return false = remote 不可用或没排进后台队列（调用方必须回滚 UI）；
     *         true = 已排入后台提交，真实结果由 callback 通知
     */
    boolean setPanelEnabled(boolean enabled, ConfigWrites.Callback callback) {
        if (!ConfigWritePolicy.accepted(ConfigWritePolicy.decide(mRemote != null))) {
            Log.w(TAG, "panel write rejected (remote unavailable)");
            return false;
        }
        final SharedPreferences remote = mRemote;
        boolean queued = ConfigWrites.submit(() -> {
            boolean committed = commitBoolean(remote, Feature.AB_TEST_PANEL.key, enabled);
            if (committed) {
                mirrorBoolean(AbReportProvider.KEY_PANEL_ENABLED, enabled);
            }
            ConfigWrites.notifyResult(callback, committed);
        });
        if (!queued) {
            Log.w(TAG, "panel write queue rejected");
        }
        return queued;
    }

    boolean panelEnabled() {
        return mReport.getBoolean(AbReportProvider.KEY_PANEL_ENABLED, false);
    }

    /** 把本地副本对齐到 remote 的真实值（升级或清数据后可能不一致）。 */
    void syncPanelEnabled(boolean enabled) {
        if (mRemote == null) {
            // 没有 remote 说明写不进宿主，冷启动时本地回退值不能覆盖活通道。
            return;
        }
        if (panelEnabled() != enabled) {
            mReport.edit().putBoolean(AbReportProvider.KEY_PANEL_ENABLED, enabled).apply();
        }
    }

    /** 把 remote 里的覆盖表同步到本地副本，供宿主运行中通过 provider 拉取。 */
    void syncOverrides() {
        if (mRemote == null) {
            return;
        }
        try {
            String raw = mRemote.getString(Config.KEY_AB_OVERRIDES, "");
            if (raw == null) {
                raw = "";
            }
            if (!raw.equals(mReport.getString(AbReportProvider.KEY_OVERRIDES, ""))) {
                mReport.edit().putString(AbReportProvider.KEY_OVERRIDES, raw).apply();
            }
        } catch (Throwable t) {
            Log.w(TAG, "sync overrides failed", t);
        }
    }

    Map<String, String> overrides() {
        return parse(read(Config.KEY_AB_OVERRIDES));
    }

    boolean isForced(String key) {
        return overrides().containsKey(key);
    }

    boolean setForced(String key, boolean forced, String value) {
        return setForced(key, forced, value, null);
    }

    /**
     * read-modify-write 整段在后台单线程执行：既避免主线程 commit，
     * 也保证连续两次开关不会互相覆盖。
     *
     * @return false = remote 不可用或没排进后台队列（调用方必须回滚 UI）；
     *         true = 已排入后台提交，真实结果由 callback 通知
     */
    boolean setForced(String key, boolean forced, String value, ConfigWrites.Callback callback) {
        if (!ConfigWritePolicy.accepted(ConfigWritePolicy.decide(mRemote != null))) {
            Log.w(TAG, "ab write rejected (remote unavailable)");
            return false;
        }
        final SharedPreferences remote = mRemote;
        boolean queued = ConfigWrites.submit(() -> {
            String raw;
            try {
                raw = remote.getString(Config.KEY_AB_OVERRIDES, "");
            } catch (Throwable t) {
                Log.w(TAG, "read ab config failed: " + Config.KEY_AB_OVERRIDES, t);
                raw = null;
            }
            if (raw == null) {
                // 读失败时不能拿空表回写，否则会把其他已强开的项清掉。
                ConfigWrites.notifyResult(callback, false);
                return;
            }
            Map<String, String> map = parse(raw);
            if (forced) {
                map.put(key, value);
            } else {
                map.remove(key);
            }
            String next = serialize(map);
            boolean committed = commitString(remote, Config.KEY_AB_OVERRIDES, next);
            if (committed) {
                mirrorString(AbReportProvider.KEY_OVERRIDES, next);
            }
            ConfigWrites.notifyResult(callback, committed);
        });
        if (!queued) {
            Log.w(TAG, "ab write queue rejected");
        }
        return queued;
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
            Log.w(TAG, "read ab config failed: " + key, t);
            return null;
        }
    }

    private static boolean commitBoolean(SharedPreferences sp, String key, boolean value) {
        try {
            return sp.edit().putBoolean(key, value).commit();
        } catch (Throwable t) {
            Log.w(TAG, "write failed: " + key, t);
            return false;
        }
    }

    private static boolean commitString(SharedPreferences sp, String key, String value) {
        try {
            return sp.edit().putString(key, value).commit();
        } catch (Throwable t) {
            Log.w(TAG, "write failed: " + key, t);
            return false;
        }
    }

    /**
     * 本地副本是运行中拉取通道的数据源，必须写；但它的失败不改变 remote 已成功的事实，
     * 所以只记日志，避免 UI 回滚到与 remote 不一致的状态。
     */
    private void mirrorBoolean(String key, boolean value) {
        try {
            if (!mReport.edit().putBoolean(key, value).commit()) {
                Log.w(TAG, "ab mirror commit failed: " + key);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ab mirror failed: " + key, t);
        }
    }

    private void mirrorString(String key, String value) {
        try {
            if (!mReport.edit().putString(key, value).commit()) {
                Log.w(TAG, "ab mirror commit failed: " + key);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ab mirror failed: " + key, t);
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
