package com.quietbox.app;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.yigenhuobah.core.HookStateCore;

/**
 * 目标进程内的诊断状态适配器：账本、skip 去重/容量、critical 标记、命中计数
 * 全部由 core 的 {@link HookStateCore} 实现；本类保留原有静态 API，并负责
 * 模块专属的 {@link Feature} ↔ 稳定 key 映射与日志。
 *
 * 真正带细节的日志由 HookKit 打进 logcat（tag QuietBox）；这里只存计数快照，
 * 由 hooks 包的 Report 定期回传给模块 App 的 StatusProvider，供诊断页展示。
 */
public final class HookState {

    private static final String TAG = "QuietBox";

    private static final HookStateCore CORE = new HookStateCore();

    /** Feature.key → Feature；Report/HookState 的账本以稳定字符串 key 为索引。 */
    private static final Map<String, Feature> BY_KEY = new HashMap<>();

    static {
        for (Feature f : Feature.values()) {
            BY_KEY.put(f.key, f);
        }
    }

    private HookState() {
    }

    /**
     * 功能开始安装。创建空 State，确保「已启用但安装阶段完全没有 touch」的功能
     * 也会进入快照并发送空 reg/skip，从而清掉上一会话的旧 skip。
     */
    public static void enable(Feature f) {
        CORE.enable(f.key);
        Log.i(TAG, f.id + " enabled");
    }

    public static void installed(Feature f, String label) {
        CORE.installed(f.key, label);
    }

    /**
     * 可选锚点缺失：只记录 skip 供诊断页展示，不计入失败横幅。
     * 方法改名/签名变化都属于这一类。原因按出现顺序去重保留，
     * {@link #skipReason} 仍返回最后一条以兼容旧展示。
     */
    public static void markNotFound(Feature f, String reason) {
        CORE.markNotFound(f.key, reason);
    }

    /**
     * 主锚点缺失（例如整个类找不到）：除记录 skip 外，再打上 critical 标记，
     * 让 ReportProtocol.featureFailed 把该功能计入失败横幅。
     */
    public static void markNotFoundCritical(Feature f, String reason) {
        CORE.markNotFoundCritical(f.key, reason);
    }

    /** 本会话是否出现过主锚点缺失。 */
    public static boolean hasCriticalSkip(Feature f) {
        return CORE.hasCriticalSkip(f.key);
    }

    /** 命中计数 +1，返回累加后的次数。 */
    public static long hit(Feature f) {
        return CORE.hit(f.key);
    }

    /** 安装阶段的补充说明：只写日志，不进入诊断页。 */
    public static void note(Feature f, String text) {
        CORE.note(f.key, text);
        if (text != null) {
            Log.i(TAG, f.id + " " + text);
        }
    }

    // ---- 快照（供 Report / 挂载统计回传） ----

    public static List<Feature> touched() {
        List<Feature> out = new ArrayList<>();
        for (String key : CORE.touched()) {
            Feature f = BY_KEY.get(key);
            if (f != null) {
                out.add(f);
            }
        }
        return out;
    }

    public static List<String> labels(Feature f) {
        return CORE.labels(f.key);
    }

    public static long hitCount(Feature f) {
        return CORE.hits(f.key);
    }

    public static String skipReason(Feature f) {
        return CORE.skipReason(f.key);
    }

    /**
     * 本会话记录过的全部 skip 原因（按首次出现顺序去重）。
     *
     * 旧实现只有一个 volatile String，后一条直接覆盖前一条：主锚点缺失后又记一条
     * 可选锚点缺失，诊断页与上报就只剩后者。这里保留完整列表供诊断展示。
     */
    public static List<String> skipReasons(Feature f) {
        return CORE.skipReasons(f.key);
    }

    /**
     * 上报用的 skip 摘要：首条 critical 原因 + 最后一条原因（相同时只留一条），
     * 以 " | " 连接。保证「主锚点缺失」不会因为后面又记了一条可选 skip 而从
     * 回传里消失，同时仍能带出最新的可选失败。
     */
    public static String skipSummary(Feature f) {
        return CORE.skipSummary(f.key);
    }

    /**
     * 清除该功能的失败标记（skip 原因列表与 critical 集合）；labels/hits 保留。
     *
     * 用于 QuietBoxModule 回滚 sInstalled 后的重试：失败标记只增不清，上一轮安装
     * 记下的 skip/crit 若不在下一轮安装前清掉，重试成功的功能会继续上报 crit=1
     * 与旧 skip，诊断页和失败横幅会把已挂载的功能判成整体失效。
     */
    public static void clearFailures(Feature f) {
        CORE.clearFailures(f.key);
    }

    /**
     * 挂载统计快照。计数按「功能」去重：
     * ok = 至少注册成功一个挂载点的功能数，miss/bad = 有失败记录的功能数。
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
        HookStateCore.Snapshot core = CORE.snapshot();
        List<String> missed = new ArrayList<>(core.missed.size());
        for (String key : core.missed) {
            Feature f = BY_KEY.get(key);
            missed.add(f != null ? f.id : key);
        }
        Collections.sort(missed);
        return new Snapshot(core.ok, core.miss, missed, core.features, core.bad);
    }
}
