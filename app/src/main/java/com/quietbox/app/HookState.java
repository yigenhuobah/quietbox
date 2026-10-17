package com.quietbox.app;

import android.util.Log;

/**
 * 目标进程内的诊断日志。
 *
 * 正式 UI 不展示"已注册挂载点 / 命中次数"（按用户要求完全隐藏），因此不再需要
 * 跨进程回传状态——模块 App 需要的"是否激活 / 作用域 / API 版本"都能直接从
 * 自己绑定的 XposedService 拿到。
 *
 * 这里只把状态写进 logcat，排障时用。真正带细节的日志由 HookKit 负责，
 * 所以 installed / hit / markNotFound 保持空实现，避免重复刷屏。
 */
public final class HookState {

    private static final String TAG = "QuietBox";

    private HookState() {
    }

    public static void enable(Feature f) {
        Log.i(TAG, f.id + " enabled");
    }

    /** HookKit.install 已经打过 "registered <label>"。 */
    public static void installed(Feature f, String label) {
    }

    /** HookKit.findClass/findMethod 已经打过 "skip: ... not found"。 */
    public static void markNotFound(Feature f) {
    }

    /** HookKit.hit 已经打过命中日志。 */
    public static void hit(Feature f) {
    }

    public static void note(Feature f, String text) {
        if (text != null) {
            Log.i(TAG, f.id + " " + text);
        }
    }
}
