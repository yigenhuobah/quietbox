package com.quietbox.app;

import android.content.Context;
import android.content.SharedPreferences;
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

    private final SharedPreferences mRemote;
    private final SharedPreferences mLocal;

    ConfigStore(Context context) {
        this(ServiceBridge.remotePreferences(Config.GROUP),
                context.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE));
    }

    /** 测试入口：直接注入两份存储，不经过 ServiceBridge。 */
    ConfigStore(SharedPreferences remote, SharedPreferences local) {
        mRemote = remote;
        mLocal = local;
    }

    /** true = 写的是框架存储，目标进程能读到。 */
    boolean remote() {
        return mRemote != null;
    }

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
                committed = remote.edit().putBoolean(key, value).commit();
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
}
