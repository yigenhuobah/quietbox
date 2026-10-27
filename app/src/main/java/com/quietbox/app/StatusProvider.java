package com.quietbox.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/**
 * 目标进程（小红书）→ 模块 App 的诊断状态通道。
 *
 * 方向不能反过来：remote prefs 在目标进程只读，openRemoteFile 也是只读，
 * 所以「哪些钩子注册了 / 命中了多少次」只能由目标进程主动 call 进来。
 *
 * 安全边界：provider 必须 exported 才能被目标进程跨应用调用，因此每次 call 都校验
 * 调用方 UID 必须属于 com.xingin.xhs；key 只接受 ReportProtocol 白名单，
 * value 有长度上限。不存任何敏感信息。
 *
 * key 形如 reg.p0_1_splash_ad_skip / hit.p0_1_splash_ad_skip /
 * skip.p0_1_splash_ad_skip，value 对应注册标签、命中计数、跳过原因。
 */
public final class StatusProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.yigenhuobah.quietbox.status";
    public static final String PREFS = "status_report";

    public static final String PREFIX_REG = ReportProtocol.PREFIX_REG;
    public static final String PREFIX_HIT = ReportProtocol.PREFIX_HIT;
    public static final String PREFIX_SKIP = ReportProtocol.PREFIX_SKIP;

    /** 落盘时统一加 "v:" 前缀，避免与其他本地键冲突。 */
    private static final String VALUE_PREFIX = "v:";

    /** 唯一允许写入诊断数据的宿主包名。 */
    private static final String TARGET_PACKAGE = "com.xingin.xhs";

    private static final int MAX_ITEMS = 800;

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * synchronized：目标应用多个进程的 binder 线程可能并发进来，
     * hit 的 getLong→compare→putLong 必须串行，否则较大计数会被较小值覆盖。
     */
    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        if (!"report".equals(method) || extras == null) {
            return null;
        }
        if (!isAllowedCaller(context, Binder.getCallingUid())) {
            return null;
        }
        String[] keys = extras.getStringArray("keys");
        String[] values = extras.getStringArray("values");
        if (keys == null || values == null || keys.length != values.length) {
            return null;
        }
        if (keys.length > MAX_ITEMS) {
            return null;
        }
        SharedPreferences sp = context.getSharedPreferences(PREFS, 0);
        SharedPreferences.Editor editor = sp.edit();
        int accepted = 0;
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            String value = values[i];
            if (!ReportProtocol.validKey(key) || !ReportProtocol.isValidValue(value)) {
                continue;
            }
            if (key.startsWith(PREFIX_HIT)) {
                // 命中计数取历史最大值：多进程各自回传，取 max 不会重复累加，
                // 也不会被另一进程的低计数覆盖回去。
                long prev = sp.getLong(VALUE_PREFIX + key, 0L);
                long next;
                try {
                    next = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    continue;
                }
                long merged = ReportProtocol.mergeHit(prev, next);
                if (merged > prev) {
                    editor.putLong(VALUE_PREFIX + key, merged);
                }
            } else {
                // reg/skip 以最新值覆盖（空串也覆盖）：目标进程重开后，本次没有
                // skip 的功能会回传空串，必须清掉上一次会话遗留的旧 skip。
                editor.putString(VALUE_PREFIX + key,
                        ReportProtocol.mergeText(sp.getString(VALUE_PREFIX + key, null), value));
            }
            accepted++;
        }
        if (accepted > 0) {
            editor.apply();
        }
        return new Bundle();
    }

    /** 读取一个回传字符串（reg/skip）；没有数据或读取失败返回 null。 */
    public static String reportString(Context context, String prefix, String key) {
        if (context == null) {
            return null;
        }
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getString(VALUE_PREFIX + prefix + key, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取命中计数；没有数据或读取失败返回 0。 */
    public static long reportHit(Context context, String key) {
        if (context == null) {
            return 0L;
        }
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getLong(VALUE_PREFIX + PREFIX_HIT + key, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 统计“已启用且明确失败”的功能数，供异常横幅使用。
     *
     * 失败判定同时满足：用户配置为开启；目标进程有回传；reg 为空或 skip 非空。
     * 没有回传的功能不计入，避免刚装上或目标应用未启动时误报。
     * 只在主进程回传，子进程未装 hook 的 skip 不会被误算。
     */
    public static int failedFeatureCount(Context context) {
        if (context == null) {
            return 0;
        }
        ConfigStore store;
        try {
            store = new ConfigStore(context);
        } catch (Throwable t) {
            store = null;
        }
        int failed = 0;
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            boolean enabled = store != null
                    ? store.get(f.key, f.defaultEnabled) : f.defaultEnabled;
            if (!enabled) {
                continue;
            }
            String reg = reportString(context, PREFIX_REG, f.key);
            String skip = reportString(context, PREFIX_SKIP, f.key);
            long hit = reportHit(context, f.key);
            if (ReportProtocol.featureFailed(reg, skip, hit)) {
                failed++;
            }
        }
        return failed;
    }

    /** 只放行小红书宿主 UID；模块自身与其他任意应用一律拒绝，不写盘也不抛异常。 */
    private static boolean isAllowedCaller(Context context, int uid) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (TARGET_PACKAGE.equals(pkg)) {
                return true;
            }
        }
        return false;
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
