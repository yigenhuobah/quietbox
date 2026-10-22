package com.quietbox.app;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 目标进程内的诊断状态：每个功能的注册标签、命中次数、跳过原因。
 *
 * 真正带细节的日志由 HookKit 打进 logcat（tag QuietBox）；这里只存计数快照，
 * 由 hooks 包的 Report 定期回传给模块 App 的 StatusProvider，供诊断页展示。
 *
 * 线程安全：命中可能来自任意线程（含 binder 线程），全部用并发容器。
 */
public final class HookState {

    private static final String TAG = "QuietBox";

    private static final ConcurrentMap<Feature, State> STATES = new ConcurrentHashMap<>();

    private HookState() {
    }

    private static final class State {
        final Set<String> labels = ConcurrentHashMap.newKeySet();
        final AtomicLong hits = new AtomicLong();
        volatile String skip;

        void markSkip(String reason) {
            if (reason != null) {
                skip = reason;
            }
        }
    }

    private static State state(Feature f) {
        State s = STATES.get(f);
        if (s == null) {
            s = new State();
            State prev = STATES.putIfAbsent(f, s);
            if (prev != null) {
                s = prev;
            }
        }
        return s;
    }

    /** 功能开始安装；仅用于日志，不代表任何 hook 已注册。 */
    public static void enable(Feature f) {
        Log.i(TAG, f.id + " enabled");
    }

    public static void installed(Feature f, String label) {
        state(f).labels.add(label);
    }

    public static void markNotFound(Feature f, String reason) {
        state(f).markSkip(reason);
    }

    /** 命中计数 +1，返回累加后的次数。 */
    public static long hit(Feature f) {
        return state(f).hits.incrementAndGet();
    }

    /** 安装阶段的补充说明：只写日志，不进入诊断页。 */
    public static void note(Feature f, String text) {
        if (text != null) {
            Log.i(TAG, f.id + " " + text);
        }
    }

    // ---- 快照（供 Report / 挂载统计回传） ----

    public static List<Feature> touched() {
        return new ArrayList<>(STATES.keySet());
    }

    public static List<String> labels(Feature f) {
        State s = STATES.get(f);
        if (s == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(s.labels);
        Collections.sort(out);
        return out;
    }

    public static long hitCount(Feature f) {
        State s = STATES.get(f);
        return s == null ? 0L : s.hits.get();
    }

    public static String skipReason(Feature f) {
        State s = STATES.get(f);
        return s == null ? null : s.skip;
    }

    /**
     * 挂载统计快照。计数按「功能」去重：
     * ok = 至少注册成功一个挂载点的功能数，miss/bad = 有 skip 的功能数。
     */
    public static final class Snapshot {
        public final int ok;
        public final int miss;
        public final List<String> missed;
        public final int features;
        public final int bad;

        Snapshot(int ok, int miss, List<String> missed, int features, int bad) {
            this.ok = ok;
            this.miss = miss;
            this.missed = missed;
            this.features = features;
            this.bad = bad;
        }
    }

    public static Snapshot snapshot() {
        int ok = 0;
        int bad = 0;
        List<String> missed = new ArrayList<>();
        synchronized (STATES) {
            for (Feature f : STATES.keySet()) {
                boolean hasLabel = !labels(f).isEmpty();
                boolean hasSkip = skipReason(f) != null;
                if (hasLabel) {
                    ok++;
                }
                if (hasSkip) {
                    bad++;
                    missed.add(f.id);
                }
            }
            Collections.sort(missed);
            return new Snapshot(ok, bad, missed, STATES.size(), bad);
        }
    }

}
