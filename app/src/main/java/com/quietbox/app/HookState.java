package com.quietbox.app;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 目标进程内的诊断日志与挂载统计。
 *
 * 正式 UI 不展示"已注册挂载点 / 命中次数"（按用户要求完全隐藏），因此大部分状态
 * 只写 logcat，排障时用。真正带细节的日志由 HookKit 负责，所以 hit / note 保持
 * 空实现或纯日志，避免重复刷屏。
 *
 * 唯一例外是「挂载统计」：小红书升级后挂载点可能大面积失效，而 UI 侧完全看不出来，
 * 用户只会觉得"开关没用"。installAll 结束时把 ok/miss 计数与 miss 明细推给模块 App，
 * 由它判断是否显示"当前小红书版本不兼容"横幅。计数健康时不显示，UI 仍是零诊断内容。
 */
public final class HookState {

    private static final String TAG = "QuietBox";

    /** Feature → {ok 数, miss 数}。 */
    private static final Map<Feature, int[]> MOUNTS = new HashMap<>();

    private HookState() {
    }

    public static void enable(Feature f) {
        Log.i(TAG, f.id + " enabled");
    }

    /** HookKit.install 已经打过 "registered <label>"。 */
    public static void installed(Feature f, String label) {
        count(f, true);
    }

    /** HookKit.findClass/findMethod 已经打过 "skip: ... not found"。 */
    public static void markNotFound(Feature f) {
        count(f, false);
    }

    /** HookKit.hit 已经打过命中日志。 */
    public static void hit(Feature f) {
    }

    public static void note(Feature f, String text) {
        if (text != null) {
            Log.i(TAG, f.id + " " + text);
        }
    }

    private static void count(Feature f, boolean ok) {
        synchronized (MOUNTS) {
            int[] c = MOUNTS.get(f);
            if (c == null) {
                c = new int[2];
                MOUNTS.put(f, c);
            }
            c[ok ? 0 : 1]++;
        }
    }

    /** 统计快照：ok/miss 计数 + 有 miss 的功能 id 列表。 */
    public static final class Snapshot {
        public final int ok;
        public final int miss;
        public final List<String> missed;

        Snapshot(int ok, int miss, List<String> missed) {
            this.ok = ok;
            this.miss = miss;
            this.missed = missed;
        }
    }

    /** 取一份统计快照并清零，供 installAll 结束时推送。 */
    public static Snapshot drain() {
        int ok = 0;
        int miss = 0;
        List<String> missed = new ArrayList<>();
        synchronized (MOUNTS) {
            for (Map.Entry<Feature, int[]> e : MOUNTS.entrySet()) {
                int[] c = e.getValue();
                ok += c[0];
                miss += c[1];
                if (c[1] > 0) {
                    missed.add(e.getKey().id);
                }
            }
            MOUNTS.clear();
        }
        return new Snapshot(ok, miss, missed);
    }
}
