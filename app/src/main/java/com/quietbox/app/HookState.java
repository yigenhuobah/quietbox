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

    /**
     * 单个功能最多保留的 skip 原因数。超出后优先丢最旧的非 critical 原因；
     * 若剩下的原因全是 critical，则丢次旧项，始终保留首条与最新一条。
     */
    private static final int MAX_SKIP_REASONS = 32;

    private HookState() {
    }

    private static final class State {
        final Set<String> labels = ConcurrentHashMap.newKeySet();
        final Set<String> criticalSkips = ConcurrentHashMap.newKeySet();
        final AtomicLong hits = new AtomicLong();
        /**
         * 去重后的 skip 原因，按首次出现顺序排列；最后一条与 {@link #skip} 相同。
         * 锁 reasons 本身：hooker 线程追加、诊断线程遍历。
         */
        final List<String> reasons = new ArrayList<>();
        /** 兼容旧字段：最后一条原因。 */
        volatile String skip;

        void markSkip(String reason) {
            if (reason == null || reason.isEmpty()) {
                return;
            }
            synchronized (reasons) {
                if (reasons.contains(reason)) {
                    return;
                }
                if (reasons.size() >= MAX_SKIP_REASONS) {
                    evictOldestReason();
                }
                reasons.add(reason);
                skip = reason;
            }
        }

        /**
         * 容量已满时丢弃一条旧原因：优先丢首条之后最旧的非 critical 原因；
         * 若剩下的全是 critical（没有可丢的可选项），则丢次旧项（下标 1）。
         *
         * 首条原因始终保留，作为「首个 critical」的展示锚点，因此即使所有原因
         * 都是 critical，列表也不会超过 {@link #MAX_SKIP_REASONS}。
         * 被丢弃原因若曾标过 critical，其标记仍留在 criticalSkips 中，
         * hasCriticalSkip 的「本会话出现过主锚点缺失」语义不受影响。
         */
        private void evictOldestReason() {
            for (int i = 1; i < reasons.size(); i++) {
                if (!criticalSkips.contains(reasons.get(i))) {
                    reasons.remove(i);
                    return;
                }
            }
            reasons.remove(1);
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

    /**
     * 功能开始安装。创建空 State，确保「已启用但安装阶段完全没有 touch」的功能
     * 也会进入快照并发送空 reg/skip，从而清掉上一会话的旧 skip。
     */
    public static void enable(Feature f) {
        state(f);
        Log.i(TAG, f.id + " enabled");
    }

    public static void installed(Feature f, String label) {
        state(f).labels.add(label);
    }

    /**
     * 可选锚点缺失：只记录 skip 供诊断页展示，不计入失败横幅。
     * 方法改名/签名变化都属于这一类。原因按出现顺序去重保留，
     * {@link #skipReason} 仍返回最后一条以兼容旧展示。
     */
    public static void markNotFound(Feature f, String reason) {
        state(f).markSkip(reason);
    }

    /**
     * 主锚点缺失（例如整个类找不到）：除记录 skip 外，再打上 critical 标记，
     * 让 ReportProtocol.featureFailed 把该功能计入失败横幅。
     */
    public static void markNotFoundCritical(Feature f, String reason) {
        State s = state(f);
        s.markSkip(reason);
        if (reason != null) {
            s.criticalSkips.add(reason);
        }
    }

    /** 本会话是否出现过主锚点缺失。 */
    public static boolean hasCriticalSkip(Feature f) {
        State s = STATES.get(f);
        return s != null && !s.criticalSkips.isEmpty();
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
     * 本会话记录过的全部 skip 原因（按首次出现顺序去重）。
     *
     * 旧实现只有一个 volatile String，后一条直接覆盖前一条：主锚点缺失后又记一条
     * 可选锚点缺失，诊断页与上报就只剩后者。这里保留完整列表供诊断展示。
     */
    public static List<String> skipReasons(Feature f) {
        State s = STATES.get(f);
        if (s == null) {
            return Collections.emptyList();
        }
        synchronized (s.reasons) {
            return new ArrayList<>(s.reasons);
        }
    }

    /**
     * 上报用的 skip 摘要：首条 critical 原因 + 最后一条原因（相同时只留一条），
     * 以 " | " 连接。保证「主锚点缺失」不会因为后面又记了一条可选 skip 而从
     * 回传里消失，同时仍能带出最新的可选失败。
     */
    public static String skipSummary(Feature f) {
        State s = STATES.get(f);
        if (s == null) {
            return null;
        }
        List<String> reasons;
        synchronized (s.reasons) {
            reasons = new ArrayList<>(s.reasons);
        }
        if (reasons.isEmpty()) {
            return null;
        }
        String last = reasons.get(reasons.size() - 1);
        String firstCritical = null;
        for (String r : reasons) {
            if (s.criticalSkips.contains(r)) {
                firstCritical = r;
                break;
            }
        }
        if (firstCritical == null || firstCritical.equals(last)) {
            return last;
        }
        return firstCritical + " | " + last;
    }

    /**
     * 清除该功能的失败标记（skip 原因列表与 critical 集合）；labels/hits 保留。
     *
     * 用于 QuietBoxModule 回滚 sInstalled 后的重试：失败标记只增不清，上一轮安装
     * 记下的 skip/crit 若不在下一轮安装前清掉，重试成功的功能会继续上报 crit=1
     * 与旧 skip，诊断页和失败横幅会把已挂载的功能判成整体失效。
     */
    public static void clearFailures(Feature f) {
        State s = STATES.get(f);
        if (s == null) {
            return;
        }
        s.criticalSkips.clear();
        synchronized (s.reasons) {
            s.reasons.clear();
            s.skip = null;
        }
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
                boolean hasSkip = !skipReasons(f).isEmpty();
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
