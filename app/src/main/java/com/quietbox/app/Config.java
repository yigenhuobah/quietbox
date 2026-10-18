package com.quietbox.app;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 开关读取：有 PROP_CAP_REMOTE 才读 getRemotePreferences("config")，
 * 否则（含读取抛异常）回退到 Feature 的编译期内置默认值。
 * 必须在 onModuleLoaded 之后调用，之前任何 API 都会抛 IllegalStateException。
 */
public final class Config {

    public static final String GROUP = "config";

    /** P1-9 子选项：默认只删纯追踪参数，xsec_token 可能影响链接可打开性。 */
    public static final String KEY_STRIP_XSEC_TOKEN = "p1_9_strip_xsec_token";
    public static final boolean DEFAULT_STRIP_XSEC_TOKEN = false;

    /** AB 覆盖表：每行 key=value，由「测试」页写入，目标进程每 3 秒重读。 */
    public static final String KEY_AB_OVERRIDES = "ab_overrides";

    private static final String TAG = "QuietBox";

    private final Map<Feature, Boolean> mEnabled = new EnumMap<>(Feature.class);
    private final Map<String, String> mAbOverrides = new LinkedHashMap<>();
    private boolean mStripXsecToken = DEFAULT_STRIP_XSEC_TOKEN;

    private Config() {
        for (Feature f : Feature.values()) {
            mEnabled.put(f, f.defaultEnabled);
        }
    }

    public static Config load(XposedModule module) {
        Config config = new Config();
        boolean remote = (module.getFrameworkProperties() & XposedInterface.PROP_CAP_REMOTE) != 0;
        if (!remote) {
            module.log(Log.WARN, TAG, "no PROP_CAP_REMOTE: use built-in defaults");
            return config;
        }
        try {
            SharedPreferences sp = module.getRemotePreferences(GROUP);
            for (Feature f : Feature.values()) {
                config.mEnabled.put(f, sp.getBoolean(f.key, f.defaultEnabled));
            }
            config.mStripXsecToken = sp.getBoolean(KEY_STRIP_XSEC_TOKEN, DEFAULT_STRIP_XSEC_TOKEN);
            parseOverrides(sp.getString(KEY_AB_OVERRIDES, ""), config.mAbOverrides);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "remote prefs unavailable: use built-in defaults", t);
        }
        return config;
    }

    public boolean enabled(Feature f) {
        return Boolean.TRUE.equals(mEnabled.get(f));
    }

    public boolean anyEnabled() {
        for (Map.Entry<Feature, Boolean> e : mEnabled.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && !e.getKey().blocked()) {
                return true;
            }
        }
        return false;
    }

    public boolean stripXsecToken() {
        return mStripXsecToken;
    }

    public Map<String, String> abOverrides() {
        return mAbOverrides;
    }

    public String abOverride(String key) {
        return mAbOverrides.get(key);
    }

    private static void parseOverrides(String raw, Map<String, String> out) {
        if (raw == null) {
            return;
        }
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                out.put(key, value);
            }
        }
    }
}
