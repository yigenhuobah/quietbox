package com.quietbox.app.hooks;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import com.quietbox.app.AbReportProvider;
import com.quietbox.app.Config;
import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * AB 测试面板的运行时。
 *
 * 读取点只有三处，覆盖小红书 9.50.0 的全部 AB 读取：
 * 1) tq.h#f / tq.h#b —— ABTest SDK 的 getValueJustOnce / getValue；
 * 2) eb6.o#l —— ConfigCenter 的字符串取值总入口。
 *
 * 强制值来自模块 App：remote prefs 作为启动兜底，运行中每 3 秒通过
 * AbReportProvider 拉取本地副本（remote prefs 在宿主里是启动快照，不会热更新）。
 * 面板总开关关闭时覆盖表清空。
 * 探测到的 key/value 通过模块 App 的 ContentProvider 回传，供「其他」区展示。
 */
public final class AbTestHooks {

    private static final String TAG = "QuietBox";
    private static final String AB_STORE = "tq.h";
    private static final String CONFIG_CENTER = "eb6.o";

    private AbTestHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.AB_TEST_PANEL;
        try {
            Class<?> store = HookKit.findClass(module, f, cl, AB_STORE);
            if (store != null) {
                for (String name : new String[]{"f", "b"}) {
                    Method m = HookKit.findMethod(module, f, store, name,
                            String.class, Type.class, Object.class);
                    HookKit.install(module, f, "ABStore#" + name, m, valueHook(module, f));
                }
            }

            Class<?> center = HookKit.findClass(module, f, cl, CONFIG_CENTER);
            if (center != null) {
                Method m = HookKit.findMethod(module, f, center, "l", String.class);
                HookKit.install(module, f, "ConfigCenter#l", m, stringHook(module, f));
            }

            installContextCapture(module, cl, f);
            AbRuntime.start(module);
            HookState.note(f, "AB 面板已挂载");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    /** tq.h 的返回值是已经按 Type 解析好的对象；按原对象类型转换强制值。 */
    private static XposedInterface.Hooker valueHook(XposedModule module, Feature f) {
        return chain -> {
            Object result = chain.proceed();
            try {
                Object arg = chain.getArg(0);
                if (arg instanceof String) {
                    String key = (String) arg;
                    AbRuntime.observe(key, result);
                    String forced = AbRuntime.override(key);
                    if (forced != null) {
                        return coerce(forced, result);
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " ab intercept error", t);
            }
            return result;
        };
    }

    /** eb6.o#l 直接返回字符串，调用方自己用 Gson 解析。 */
    private static XposedInterface.Hooker stringHook(XposedModule module, Feature f) {
        return chain -> {
            Object result = chain.proceed();
            try {
                Object arg = chain.getArg(0);
                if (arg instanceof String) {
                    String key = (String) arg;
                    AbRuntime.observe(key, result);
                    String forced = AbRuntime.override(key);
                    if (forced != null) {
                        return forced;
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " config intercept error", t);
            }
            return result;
        };
    }

    private static Object coerce(String value, Object original) {
        try {
            if (original instanceof Boolean) {
                return Boolean.valueOf(value);
            }
            if (original instanceof Integer) {
                return Integer.valueOf(value);
            }
            if (original instanceof Long) {
                return Long.valueOf(value);
            }
            if (original instanceof Float) {
                return Float.valueOf(value);
            }
            if (original instanceof Double) {
                return Double.valueOf(value);
            }
            if (original instanceof String) {
                return value;
            }
        } catch (Throwable ignored) {
        }
        return original;
    }

    /**
     * 目标进程里没有现成的 Application Context 入口，用 ActivityThread 反射；
     * 同时挂 Application#onCreate 兜底（onPackageReady 早于它执行）。
     */
    private static void installContextCapture(XposedModule module, ClassLoader cl, Feature f) {
        try {
            Class<?> app = Class.forName("android.app.Application", false, cl);
            Method onCreate = HookKit.findMethod(module, f, app, "onCreate");
            HookKit.install(module, f, "Application#onCreate", onCreate, chain -> {
                Object self = chain.getThisObject();
                if (self instanceof Context) {
                    AbRuntime.setContext((Context) self);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " context capture skipped: " + t);
        }
    }

    /** 运行时状态与后台刷新/回传。 */
    static final class AbRuntime {

        private static volatile Map<String, String> sOverrides = Collections.emptyMap();
        private static final Map<String, String> sObserved = new ConcurrentHashMap<>();
        private static final Set<String> sSent = ConcurrentHashMap.newKeySet();
        private static final Set<String> sLogged = ConcurrentHashMap.newKeySet();
        private static final int MAX_KEYS = 800;
        private static final int MAX_BATCH = 200;

        private static volatile boolean sEnabled;
        private static volatile boolean sStarted;
        private static volatile long sNextReportTry;
        private static volatile Context sContext;
        private static volatile SharedPreferences sPrefs;
        private static volatile String sLastRaw = "";
        private static volatile int sPullErrors;

        private AbRuntime() {
        }

        static void start(XposedModule module) {
            if (sStarted) {
                return;
            }
            sStarted = true;
            // 只在这里碰一次框架 API；后台线程只读 SharedPreferences（线程安全）。
            try {
                sPrefs = module.getRemotePreferences(Config.GROUP);
            } catch (Throwable ignored) {
            }
            refresh();
            ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "QuietBox-AB");
                t.setDaemon(true);
                return t;
            });
            ex.scheduleWithFixedDelay(() -> {
                try {
                    refresh();
                    flush();
                } catch (Throwable ignored) {
                }
            }, 2, 3, TimeUnit.SECONDS);
        }

        static void setContext(Context context) {
            sContext = context;
        }

        static String override(String key) {
            if (!sEnabled || key == null) {
                return null;
            }
            String value = sOverrides.get(key);
            if (value != null && sLogged.add(key)) {
                Log.i(TAG, "P2-1 override " + key + "=" + value);
            }
            return value;
        }
        static void observe(String key, Object value) {
            if (!sEnabled || key == null || value == null || key.length() > 128) {
                return;
            }
            if (!looksLikeAb(key) || sObserved.size() >= MAX_KEYS) {
                return;
            }
            String text = String.valueOf(value);
            if (text.length() > 200) {
                text = text.substring(0, 200);
            }
            sObserved.putIfAbsent(key, text);
        }

        private static boolean looksLikeAb(String key) {
            return key.startsWith("andr_") || key.startsWith("ANDR_")
                    || key.startsWith("Andr_") || key.startsWith("caption_")
                    || key.startsWith("local_exp_");
        }

        private static void refresh() {
            // 运行中优先向模块 App 拉取：remote prefs 在宿主里是启动快照，
            // 改开关后不会变，必须靠 provider 这条活通道。
            Context context = context();
            if (context != null) {
                try {
                    Bundle b = context.getContentResolver().call(
                            android.net.Uri.parse("content://" + AbReportProvider.AUTHORITY),
                            "overrides", null, null);
                    if (b != null) {
                        boolean enabled = b.getBoolean(AbReportProvider.KEY_PANEL_ENABLED, false);
                        String raw = b.getString(AbReportProvider.KEY_OVERRIDES, "");
                        if (!raw.equals(sLastRaw)) {
                            sLastRaw = raw;
                            Log.i(TAG, "P2-1 provider pull enabled=" + enabled + " raw=" + raw);
                        }
                        Map<String, String> next = enabled
                                ? parse(raw)
                                : Collections.emptyMap();
                        apply(enabled, next);
                        return;
                    }
                } catch (Throwable t) {
                    if (sPullErrors++ < 3) {
                        Log.w(TAG, "P2-1 provider pull failed", t);
                    }
                    // 落到下面的启动快照。
                }
            }
            SharedPreferences sp = sPrefs;
            if (sp == null) {
                return;
            }
            try {
                boolean enabled = sp.getBoolean(Feature.AB_TEST_PANEL.key, false);
                apply(enabled, enabled
                        ? parse(sp.getString(Config.KEY_AB_OVERRIDES, ""))
                        : Collections.emptyMap());
            } catch (Throwable ignored) {
                // 读取失败时保持上一次状态，不打断宿主。
            }
        }

        private static void apply(boolean enabled, Map<String, String> next) {
            sEnabled = enabled;
            if (!next.equals(sOverrides)) {
                Log.i(TAG, "P2-1 overrides loaded: " + next);
            }
            sOverrides = next;
        }

        private static void flush() {
            if (!sEnabled || sObserved.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now < sNextReportTry) {
                return;
            }
            Context context = context();
            if (context == null) {
                return;
            }
            List<String> keys = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (Map.Entry<String, String> e : sObserved.entrySet()) {
                if (sSent.contains(e.getKey())) {
                    continue;
                }
                keys.add(e.getKey());
                values.add(e.getValue());
                if (keys.size() >= MAX_BATCH) {
                    break;
                }
            }
            if (keys.isEmpty()) {
                return;
            }
            Bundle extras = new Bundle();
            extras.putStringArray("keys", keys.toArray(new String[0]));
            extras.putStringArray("values", values.toArray(new String[0]));
            try {
                context.getContentResolver().call(
                        android.net.Uri.parse("content://" + AbReportProvider.AUTHORITY),
                        "report", null, extras);
                sSent.addAll(keys);
            } catch (Throwable t) {
                // 模块 App 未启动、被 MIUI 拦截等情况：一分钟后再试，别每 3 秒刷日志。
                sNextReportTry = System.currentTimeMillis() + 60_000L;
                Log.w(TAG, "ab report provider unavailable", t);
            }
        }

        private static Context context() {
            Context c = sContext;
            if (c != null) {
                return c;
            }
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object app = at.getMethod("currentApplication").invoke(null);
                if (app instanceof Context) {
                    sContext = (Context) app;
                    return sContext;
                }
            } catch (Throwable ignored) {
            }
            return null;
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
    }
}
