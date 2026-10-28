package com.quietbox.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

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
 * skip.p0_1_splash_ad_skip / crit.p0_1_splash_ad_skip，另加
 * meta.session 与 meta.updated_at 两个会话元数据键。
 *
 * 会话语义：新会话首次写入前清掉上一会话的 reg/skip/hit/crit；迟到的旧会话
 * 整批丢弃，不能覆盖新会话。超过 {@link ReportProtocol#DEFAULT_TTL_MS} 没有
 * 新回传的数据视为过期，失败计数按 0（未知）处理，避免旧会话长期误报横幅。
 */
public final class StatusProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.yigenhuobah.quietbox.status";
    public static final String PREFS = "status_report";

    public static final String PREFIX_REG = ReportProtocol.PREFIX_REG;
    public static final String PREFIX_HIT = ReportProtocol.PREFIX_HIT;
    public static final String PREFIX_SKIP = ReportProtocol.PREFIX_SKIP;
    public static final String PREFIX_CRIT = ReportProtocol.PREFIX_CRIT;

    /** 回传数据的新鲜度上限，与 ReportProtocol 的默认 TTL 保持一致。 */
    public static final long REPORT_TTL_MS = ReportProtocol.DEFAULT_TTL_MS;

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

        // 先看本批携带的会话标识：迟到的旧会话整批丢弃；新会话先清掉旧数据。
        String incomingSession = null;
        for (int i = 0; i < keys.length; i++) {
            if (ReportProtocol.KEY_META_SESSION.equals(keys[i])
                    && ReportProtocol.isValidValue(values[i])) {
                incomingSession = values[i];
            }
        }
        String storedSession = sp.getString(
                VALUE_PREFIX + ReportProtocol.KEY_META_SESSION, null);
        if (ReportProtocol.isStaleSession(storedSession, incomingSession)) {
            return new Bundle();
        }
        SharedPreferences.Editor editor = sp.edit();
        boolean newSession = ReportProtocol.isNewSession(storedSession, incomingSession);
        if (newSession) {
            clearFeatureData(sp, editor);
        }

        int accepted = 0;
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            String value = values[i];
            if (!ReportProtocol.validKey(key) || !ReportProtocol.isValidValue(value)) {
                continue;
            }
            if (ReportProtocol.KEY_META_SESSION.equals(key)) {
                editor.putString(VALUE_PREFIX + key, value);
            } else if (ReportProtocol.KEY_META_UPDATED_AT.equals(key)) {
                long updatedAt;
                try {
                    updatedAt = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (updatedAt <= 0L) {
                    continue;
                }
                editor.putLong(VALUE_PREFIX + key, updatedAt);
            } else if (key.startsWith(PREFIX_HIT)) {
                // 命中计数取历史最大值：多进程各自回传，取 max 不会重复累加，
                // 也不会被另一进程的低计数覆盖回去。新会话从 0 重新开始。
                long prev = newSession ? 0L : sp.getLong(VALUE_PREFIX + key, 0L);
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
                // reg/skip/crit 以最新值覆盖（空串也覆盖）：目标进程重开后，本次没有
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

    /** 新会话首次写入前清掉上一会话的逐功能数据；meta 键随后由本批覆盖。 */
    private static void clearFeatureData(SharedPreferences sp, SharedPreferences.Editor editor) {
        List<String> stale = new ArrayList<>();
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith(VALUE_PREFIX + PREFIX_REG)
                    || key.startsWith(VALUE_PREFIX + PREFIX_HIT)
                    || key.startsWith(VALUE_PREFIX + PREFIX_SKIP)
                    || key.startsWith(VALUE_PREFIX + PREFIX_CRIT)) {
                stale.add(key);
            }
        }
        for (String key : stale) {
            editor.remove(key);
        }
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

    /** 最近一次成功回传的落盘时间；从未回传返回 0。 */
    public static long reportUpdatedAt(Context context) {
        if (context == null) {
            return 0L;
        }
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getLong(VALUE_PREFIX + ReportProtocol.KEY_META_UPDATED_AT, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 距最近一次回传的毫秒数；没有数据返回 Long.MAX_VALUE。 */
    public static long reportAgeMs(Context context) {
        long updatedAt = reportUpdatedAt(context);
        if (updatedAt <= 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, System.currentTimeMillis() - updatedAt);
    }

    /** 回传数据是否在 TTL 内；过期或从未回传都返回 false。 */
    public static boolean hasFreshReport(Context context) {
        return ReportProtocol.isFresh(reportUpdatedAt(context),
                System.currentTimeMillis(), REPORT_TTL_MS);
    }

    /**
     * 统计“已启用且明确失败”的功能数，供异常横幅使用。
     *
     * 失败判定同时满足：用户配置为开启；目标进程有回传；reg 为空或存在
     * critical skip（类级主锚点缺失）。方法级 skip 只作诊断，不计入失败。
     * 没有回传的功能不计入，避免刚装上或目标应用未启动时误报。
     * 数据超过 TTL 视为未知，直接返回 0，避免旧会话长期误报横幅。
     * 只在主进程回传，子进程未装 hook 的 skip 不会被误算。
     */
    public static int failedFeatureCount(Context context) {
        return failedFeatureCount(context, null);
    }

    /**
     * 复用调用方已构造的 store，避免每次统计都重新触发 remote prefs binder。
     * 必须从后台线程调用（会读 SharedPreferences / remote prefs）。
     *
     * @param store 已复用的 store；null 时现场构造一份
     */
    static int failedFeatureCount(Context context, ConfigStore store) {
        if (context == null) {
            return 0;
        }
        if (!hasFreshReport(context)) {
            return 0;
        }
        ConfigStore configStore = store;
        if (configStore == null) {
            try {
                configStore = new ConfigStore(context);
            } catch (Throwable t) {
                configStore = null;
            }
        }
        int failed = 0;
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            boolean enabled = configStore != null
                    ? configStore.get(f.key, f.defaultEnabled) : f.defaultEnabled;
            if (!enabled) {
                continue;
            }
            String reg = reportString(context, PREFIX_REG, f.key);
            String skip = reportString(context, PREFIX_SKIP, f.key);
            String crit = reportString(context, PREFIX_CRIT, f.key);
            long hit = reportHit(context, f.key);
            if (ReportProtocol.featureFailed(reg, skip, ReportProtocol.isCritical(crit), hit)) {
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
