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
 * 否则（含能力探测/读取任一步抛异常）回退到 Feature 的编译期内置默认值。
 * 必须在 onModuleLoaded 之后调用，之前任何 API 都会抛 IllegalStateException。
 */
public final class Config {

    public static final String GROUP = "config";

    /** PV-03 子选项：默认只删纯追踪参数，xsec_token 可能影响链接可打开性。 */
    public static final String KEY_STRIP_XSEC_TOKEN = "p1_9_strip_xsec_token";
    public static final boolean DEFAULT_STRIP_XSEC_TOKEN = false;

    /** AB 覆盖表：每行 key=value，由「实验」页写入，目标进程每 3 秒重读。 */
    public static final String KEY_AB_OVERRIDES = "ab_overrides";

    /** 配置结构版本：模块 App 每次写配置都带上，用于未来做迁移。 */
    public static final String KEY_CONFIG_VERSION = "config_version";
    public static final int CURRENT_CONFIG_VERSION = 1;
    /** 存储里没有 config_version 时的读取默认值：与老版本配置等价。 */
    public static final int DEFAULT_CONFIG_VERSION = 1;

    private static final String TAG = "QuietBox";

    private final Map<Feature, Boolean> mEnabled = new EnumMap<>(Feature.class);
    private final Map<String, String> mAbOverrides = new LinkedHashMap<>();
    private boolean mStripXsecToken = DEFAULT_STRIP_XSEC_TOKEN;
    private int mConfigVersion = DEFAULT_CONFIG_VERSION;

    private Config() {
        for (Feature f : Feature.values()) {
            mEnabled.put(f, f.defaultEnabled);
        }
    }

    /** 纯 JVM 可测入口：overrides 里缺的键用 Feature 编译期默认值，未知键忽略。 */
    static Config fromOverrides(Map<String, Boolean> overrides) {
        Config config = new Config();
        if (overrides != null) {
            for (Feature f : Feature.values()) {
                Boolean v = overrides.get(f.key);
                if (v != null) {
                    config.mEnabled.put(f, v);
                }
            }
        }
        return config;
    }

    /**
     * remote 开关读取器。读取失败（含返回 null）由
     * {@link #load(boolean, RemoteReader)} 统一吞掉并回退默认值。
     */
    @FunctionalInterface
    interface RemoteReader {
        Map<String, Boolean> read() throws Throwable;
    }

    /**
     * 纯 JVM 可测入口：remote=false 或读取失败时全默认；读取成功时缺键用默认值。
     * 任何异常都不向外抛，避免一次 remote 读取失败让全部 hook 不安装。
     */
    static Config load(boolean remote, RemoteReader reader) {
        if (!remote) {
            return new Config();
        }
        Map<String, Boolean> overrides;
        try {
            overrides = reader == null ? null : reader.read();
        } catch (Throwable t) {
            return new Config();
        }
        return fromOverrides(overrides);
    }

    /**
     * 逐键读取接口：真实实现直接转发 SharedPreferences。类型不匹配时
     * SharedPreferences.getBoolean 会抛 ClassCastException，正是这里要容忍的
     * 单键损坏。
     */
    interface PrefSource {
        boolean getBoolean(String key, boolean def) throws Throwable;

        int getInt(String key, int def) throws Throwable;

        String getString(String key, String def) throws Throwable;
    }

    /** 逐键失败的告警出口；Android 侧转给 XposedModule.log。 */
    @FunctionalInterface
    interface WarnLogger {
        void warn(String message, Throwable t);
    }

    /**
     * 容错读取：每个功能开关、strip 子开关、AB 覆盖表、配置版本各自独立 try/catch，
     * 单个键损坏只回退该键的默认值并告警，绝不丢弃已经成功读到的其它键。
     *
     * <p>纯 JVM 可测：不引用任何 Android 类型，供 Android 版 load 与单测共用。
     */
    static Config loadTolerant(PrefSource source, WarnLogger logger) {
        Config config = new Config();
        for (Feature f : Feature.values()) {
            try {
                config.mEnabled.put(f, source.getBoolean(f.key, f.defaultEnabled));
            } catch (Throwable t) {
                warnKey(logger, f.key, t);
            }
        }
        try {
            config.mStripXsecToken =
                    source.getBoolean(KEY_STRIP_XSEC_TOKEN, DEFAULT_STRIP_XSEC_TOKEN);
        } catch (Throwable t) {
            warnKey(logger, KEY_STRIP_XSEC_TOKEN, t);
        }
        try {
            parseOverrides(source.getString(KEY_AB_OVERRIDES, ""), config.mAbOverrides);
        } catch (Throwable t) {
            warnKey(logger, KEY_AB_OVERRIDES, t);
        }
        try {
            int stored = source.getInt(KEY_CONFIG_VERSION, DEFAULT_CONFIG_VERSION);
            config.mConfigVersion = stored;
            if (stored > CURRENT_CONFIG_VERSION && logger != null) {
                logger.warn("配置版本 v" + stored + " 高于模块支持 v"
                        + CURRENT_CONFIG_VERSION + "，按兼容模式继续", null);
            }
        } catch (Throwable t) {
            warnKey(logger, KEY_CONFIG_VERSION, t);
        }
        return config;
    }

    private static void warnKey(WarnLogger logger, String key, Throwable t) {
        if (logger == null) {
            return;
        }
        String detail = t.getClass().getName();
        if (t.getMessage() != null) {
            detail += ": " + t.getMessage();
        }
        logger.warn("config key read failed: " + key + " (" + detail + ")，已使用默认值", t);
    }

    public static Config load(XposedModule module) {
        boolean remote;
        try {
            remote = (module.getFrameworkProperties() & XposedInterface.PROP_CAP_REMOTE) != 0;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "framework properties unavailable: use built-in defaults", t);
            return new Config();
        }
        if (!remote) {
            module.log(Log.WARN, TAG, "no PROP_CAP_REMOTE: use built-in defaults");
            return new Config();
        }
        final SharedPreferences sp;
        try {
            sp = module.getRemotePreferences(GROUP);
            if (sp == null) {
                throw new IllegalStateException("remote prefs is null");
            }
        } catch (Throwable t) {
            // 连存储对象都拿不到，没有任何键可读：整体回退默认值。
            module.log(Log.WARN, TAG, "remote prefs unavailable: use built-in defaults", t);
            return new Config();
        }
        return loadTolerant(new PrefSource() {
            @Override
            public boolean getBoolean(String key, boolean def) {
                return sp.getBoolean(key, def);
            }

            @Override
            public int getInt(String key, int def) {
                return sp.getInt(key, def);
            }

            @Override
            public String getString(String key, String def) {
                return sp.getString(key, def);
            }
        }, (message, t) -> module.log(Log.WARN, TAG, message, t));
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

    /** 存储里读到的配置版本；缺键或读取失败时为 {@link #DEFAULT_CONFIG_VERSION}。 */
    public int configVersion() {
        return mConfigVersion;
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
