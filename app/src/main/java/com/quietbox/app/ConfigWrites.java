package com.quietbox.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 配置写入的后台执行器。
 *
 * SharedPreferences.commit() 是同步落盘（remote prefs 还要过一次 binder），
 * 不能在主线程调用。所有配置写入排到这一个线程，既避免主线程 I/O，
 * 也保证同一进程内多次 read-modify-write 的顺序。
 */
final class ConfigWrites {

    /** 写入结果回调；在后台线程触发，调用方需自行切回主线程再改 UI。 */
    interface Callback {
        void onResult(boolean committed);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "qb-config-write");
        t.setDaemon(true);
        return t;
    });

    private ConfigWrites() {
    }

    /** 返回 false = 没排进队列（executor 已关闭等），调用方按写失败处理。 */
    static boolean submit(Runnable task) {
        try {
            EXECUTOR.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    static void notifyResult(Callback callback, boolean committed) {
        if (callback == null) {
            return;
        }
        try {
            callback.onResult(committed);
        } catch (Throwable ignored) {
            // 回调方的异常不能影响后续写入。
        }
    }
}
