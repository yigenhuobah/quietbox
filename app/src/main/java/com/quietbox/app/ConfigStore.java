package com.quietbox.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 模块 App 侧的开关读写。
 *
 * 必须写框架的 remote preferences：小红书进程读的是那份存储，
 * 写进模块自己的 MODE_PRIVATE 对目标进程完全不可见（历史上踩过这个坑）。
 *
 * 写入语义：
 * <ul>
 *   <li>remote 不可用时 {@link #put} 直接返回 false，不再回退本地冒充成功；</li>
 *   <li>commit() 是同步磁盘/binder I/O，统一排到 {@link ConfigWrites} 的后台单线程；</li>
 *   <li>put() 的 boolean 只表示「已排入后台提交」，真实结果由 callback 在后台线程
 *       返回；调用方必须在回调成功后才能把新值当成已保存，失败则 Toast + 回滚。</li>
 * </ul>
 */
final class ConfigStore {

    private static final String TAG = "QuietBox";

    /** 重新向框架要一份 remote prefs 句柄；生产环境固定走 ServiceBridge。 */
    @FunctionalInterface
    interface RemoteProvider {
        SharedPreferences get() throws Throwable;
    }

    private final SharedPreferences mRemote;
    private final SharedPreferences mLocal;
    /** 本地 AB 镜像（ab_report）；与 {@link AbConfigStore} 使用同一份文件。 */
    private final SharedPreferences mReport;
    /** reset 读回校验专用：commit 后重新取 handle，不复用 mRemote 的内存副本。 */
    private final RemoteProvider mRemoteProvider;

    ConfigStore(Context context) {
        this(ServiceBridge.remotePreferences(Config.GROUP),
                context.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE),
                context.getSharedPreferences(AbReportProvider.PREFS, Context.MODE_PRIVATE),
                () -> ServiceBridge.remotePreferences(Config.GROUP));
    }

    /** 测试入口：直接注入两份存储，不经过 ServiceBridge。 */
    ConfigStore(SharedPreferences remote, SharedPreferences local) {
        this(remote, local, null, null);
    }

    /** 测试入口：附带本地 ab_report 存储，用于校验恢复默认时清理镜像。 */
    ConfigStore(SharedPreferences remote, SharedPreferences local, SharedPreferences report) {
        this(remote, local, report, null);
    }

    /** 测试入口：remoteProvider 用于模拟「commit 后重新取一份 remote handle」。 */
    ConfigStore(SharedPreferences remote, SharedPreferences local, SharedPreferences report,
                RemoteProvider remoteProvider) {
        mRemote = remote;
        mLocal = local;
        mReport = report;
        mRemoteProvider = remoteProvider;
    }

    /** true = 写的是框架存储，目标进程能读到。 */
    boolean remote() {
        return mRemote != null;
    }

    /**
     * 读开关。remote 不可用时回退读本地：0.8.x 及更早版本在框架未绑定时会把开关
     * 写进本地（git show v0.8.1 的 put 是这么做的），这里只读不写，保留升级残留的
     * 旧值；写路径绝不回退（见 {@link #put}），否则目标进程读不到会误报「已保存」。
     */
    boolean get(String key, boolean def) {
        try {
            return (mRemote != null ? mRemote : mLocal).getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 异步写开关，不关心真实结果时使用。
     *
     * @return false = remote 不可用（调用方必须回滚 UI）；true = 已排入后台提交。
     */
    boolean put(String key, boolean value) {
        return put(key, value, null);
    }

    /**
     * 异步写开关。
     *
     * @return false = remote 不可用或任务没排进队列（调用方必须回滚 UI）；
     *         true = 已排入后台提交，最终是否落盘由 callback 通知。
     */
    boolean put(String key, boolean value, ConfigWrites.Callback callback) {
        if (!ConfigWritePolicy.accepted(ConfigWritePolicy.decide(mRemote != null))) {
            // 不能静默写本地：目标进程读不到，UI 会误报「已保存」。
            Log.w(TAG, "config write rejected (remote unavailable): " + key);
            return false;
        }
        final SharedPreferences remote = mRemote;
        boolean queued = ConfigWrites.submit(() -> {
            boolean committed;
            try {
                // config_version 与开关值必须同一次 commit：目标进程按版本判断兼容性，
                // 不能出现「新值已落盘、版本号还是旧」的窗口。
                committed = remote.edit()
                        .putBoolean(key, value)
                        .putInt(Config.KEY_CONFIG_VERSION, Config.CURRENT_CONFIG_VERSION)
                        .commit();
            } catch (Throwable t) {
                Log.w(TAG, "config write failed: " + key, t);
                committed = false;
            }
            if (!committed) {
                Log.w(TAG, "config commit failed: " + key);
            }
            ConfigWrites.notifyResult(callback, committed);
        });
        if (!queued) {
            Log.w(TAG, "config write queue rejected: " + key);
        }
        return queued;
    }

    /**
     * 恢复出厂配置：所有功能开关写成编译期默认值，strip 子开关与 AB 覆盖表
     * 直接删除（与全新安装的读取结果一致），config_version 写成当前版本。
     *
     * <p>整个计划在同一次 editor/commit 里完成，不用 Editor.clear()（那会把未来新增的
     * 无关键一起抹掉）。commit 后重新向框架取一份 remote handle 逐键回读，功能开关、
     * config_version、两个被移除的键全部符合预期才算成功；随后清掉本地 ab_report 里
     * 的 panel_enabled / ab_overrides 镜像，但保留 v:* / t 探测数据。
     *
     * <p>与 {@link #put} 一致：remote 不可用时直接失败，绝不写本地冒充成功。
     * callback 固定在主线程触发（单测无 Looper 时退化为当前线程），且总会触发一次。
     *
     * @return false = remote 不可用或任务没排进队列；true = 已排入后台执行，
     *         最终结果由 callback 通知。
     */
    boolean resetToDefaults(ConfigWrites.Callback callback) {
        if (!ConfigWritePolicy.accepted(ConfigWritePolicy.decide(mRemote != null))) {
            Log.w(TAG, "config reset rejected (remote unavailable)");
            notifyResetResult(callback, false);
            return false;
        }
        final SharedPreferences remote = mRemote;
        boolean queued = ConfigWrites.submit(() -> {
            boolean success = performReset(remote);
            notifyResetResult(callback, success);
        });
        if (!queued) {
            Log.w(TAG, "config reset queue rejected");
            notifyResetResult(callback, false);
        }
        return queued;
    }

    /** 在 ConfigWrites 后台线程执行；任何异常都算失败，不向外抛。 */
    private boolean performReset(SharedPreferences remote) {
        try {
            SharedPreferences.Editor editor = remote.edit();
            for (Feature f : Feature.values()) {
                editor.putBoolean(f.key, f.defaultEnabled);
            }
            // 全新安装时这两个键不存在：strip 读默认 false，AB 覆盖表为空。
            editor.remove(Config.KEY_STRIP_XSEC_TOKEN);
            editor.remove(Config.KEY_AB_OVERRIDES);
            editor.putInt(Config.KEY_CONFIG_VERSION, Config.CURRENT_CONFIG_VERSION);
            if (!editor.commit()) {
                Log.w(TAG, "config reset commit failed");
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "config reset failed", t);
            return false;
        }
        // 读回必须用 commit 之后重新取的 handle，不能拿 commit() 那个实例自证。
        SharedPreferences fresh = freshRemoteHandle();
        if (fresh == null || !matchesResetPlan(fresh)) {
            return false;
        }
        return clearLocalAbMirror();
    }

    /**
     * 重新向框架要一份 remote prefs。
     *
     * <p>RemotePreferences 的 commit() 会同步更新本实例的内存 map，拿同一个实例读回
     * 等于自己验证自己，磁盘/框架侧失败也会被判成功。这里再走一次
     * ServiceBridge.remotePreferences(group)：libxposed 若按 group 缓存实例，拿到的
     * 仍是同一代理（与目标进程读到的行为一致），否则就是框架重新打开的最新存储；
     * 两种情况都比复用调用方手里的旧引用更接近真实落盘状态。
     */
    private SharedPreferences freshRemoteHandle() {
        if (mRemoteProvider == null) {
            // 只有测试注入的 2/3 参构造器会走到这里；生产构造器始终提供 provider。
            return mRemote;
        }
        try {
            SharedPreferences fresh = mRemoteProvider.get();
            if (fresh == null) {
                Log.w(TAG, "config reset read-back unavailable: remote prefs is null");
            }
            return fresh;
        } catch (Throwable t) {
            Log.w(TAG, "config reset read-back unavailable", t);
            return null;
        }
    }

    /**
     * 读回校验：功能开关与版本号必须匹配，strip/AB 两个键必须真的不存在。
     * 布尔默认值取反，使「缺键」也判为不一致，避免把未落盘当成成功。
     */
    private static boolean matchesResetPlan(SharedPreferences sp) {
        try {
            for (Feature f : Feature.values()) {
                if (sp.getBoolean(f.key, !f.defaultEnabled) != f.defaultEnabled) {
                    Log.w(TAG, "config reset read-back mismatch: " + f.key);
                    return false;
                }
            }
            int version = sp.getInt(Config.KEY_CONFIG_VERSION, -1);
            if (version != Config.CURRENT_CONFIG_VERSION) {
                Log.w(TAG, "config reset read-back mismatch: " + Config.KEY_CONFIG_VERSION);
                return false;
            }
            if (sp.contains(Config.KEY_STRIP_XSEC_TOKEN)) {
                Log.w(TAG, "config reset read-back mismatch: "
                        + Config.KEY_STRIP_XSEC_TOKEN + " still present");
                return false;
            }
            if (sp.contains(Config.KEY_AB_OVERRIDES)) {
                Log.w(TAG, "config reset read-back mismatch: "
                        + Config.KEY_AB_OVERRIDES + " still present");
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "config reset read-back failed", t);
            return false;
        }
        return true;
    }

    /**
     * 清掉本地 AB 镜像里由「实验」页维护的两个键。探测数据（v:* / t）必须保留，
     * 所以只能用显式 remove，不能 clear()。失败计入整体 success：UI 若显示成功，
     * 宿主运行中的拉取通道必须也已经看不到旧面板状态。
     */
    private boolean clearLocalAbMirror() {
        if (mReport == null) {
            return true;
        }
        try {
            boolean committed = mReport.edit()
                    .remove(AbReportProvider.KEY_PANEL_ENABLED)
                    .remove(AbReportProvider.KEY_OVERRIDES)
                    .commit();
            if (!committed) {
                Log.w(TAG, "ab mirror reset commit failed");
                return false;
            }
            if (mReport.contains(AbReportProvider.KEY_PANEL_ENABLED)
                    || mReport.contains(AbReportProvider.KEY_OVERRIDES)) {
                Log.w(TAG, "ab mirror reset read-back mismatch");
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "ab mirror reset failed", t);
            return false;
        }
        return true;
    }

    /** 重置结果回主线程；单测环境没有主 Looper 时直接回调，保证结果不丢。 */
    private static void notifyResetResult(ConfigWrites.Callback callback, boolean success) {
        if (callback == null) {
            return;
        }
        try {
            Looper looper = Looper.getMainLooper();
            if (looper == null) {
                ConfigWrites.notifyResult(callback, success);
                return;
            }
            new Handler(looper).post(() -> ConfigWrites.notifyResult(callback, success));
        } catch (Throwable t) {
            ConfigWrites.notifyResult(callback, success);
        }
    }
}
