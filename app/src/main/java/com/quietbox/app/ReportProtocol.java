package com.quietbox.app;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 诊断回传通道的纯 Java 协议逻辑：键白名单、取值长度、命中计数合并。
 *
 * 不依赖任何 android.*，可直接在 JVM 单测（见 ReportProtocolTest）；
 * StatusProvider 只做 Binder / SharedPreferences 胶水，校验规则集中在这里。
 */
public final class ReportProtocol {

    public static final String PREFIX_REG = "reg.";
    public static final String PREFIX_HIT = "hit.";
    public static final String PREFIX_SKIP = "skip.";

    public static final int MAX_KEY = 128;
    public static final int MAX_VALUE = 256;

    /** 24 个 Feature × 3 个前缀 = 72 个合法键，构建后不可变。 */
    private static final Set<String> ALLOWED_KEYS = buildAllowedKeys();

    private ReportProtocol() {
    }

    private static Set<String> buildAllowedKeys() {
        Set<String> keys = new HashSet<>();
        for (Feature f : Feature.values()) {
            keys.add(PREFIX_REG + f.key);
            keys.add(PREFIX_HIT + f.key);
            keys.add(PREFIX_SKIP + f.key);
        }
        return Collections.unmodifiableSet(keys);
    }

    /** 只接受白名单键；非空、长度上限一并校验，其他一律拒绝。 */
    public static boolean validKey(String key) {
        return key != null && !key.isEmpty() && key.length() <= MAX_KEY
                && ALLOWED_KEYS.contains(key);
    }

    /** 值只校验非 null 与长度上限；hit 的可解析性由调用方再校验。 */
    public static boolean isValidValue(String value) {
        return value != null && value.length() <= MAX_VALUE;
    }

    /**
     * 命中计数合并：多进程各自回传本进程计数，历史值取最大，避免重复累加，
     * 也避免较大计数被较小值覆盖。负数（异常输入）按 0 处理。
     */
    public static long mergeHit(long prev, long next) {
        long p = prev < 0 ? 0 : prev;
        long n = next < 0 ? 0 : next;
        return Math.max(p, n);
    }

    /**
     * reg/skip 文本合并：最新值无条件覆盖旧值，空串也覆盖。
     *
     * 目标进程每次启动都是新的 HookState：本次会话没有 skip 时必须发送空串，
     * 才能清掉上一次会话落盘的旧 skip；缺失不发送会让失败横幅永久误报。
     */
    public static String mergeText(String prev, String next) {
        return next;
    }

    /** 已启用功能是否有回传（reg/skip 非空或 hit > 0）。 */
    private static boolean reported(String reg, String skip, long hit) {
        return !isEmpty(reg) || !isEmpty(skip) || hit > 0;
    }

    /**
     * 已启用且有回传的功能是否明确失败：reg 为空或 skip 非空。
     * 没有回传的功能不计入，避免目标应用未启动时误报。
     */
    public static boolean featureFailed(String reg, String skip, long hit) {
        if (!reported(reg, skip, hit)) {
            return false;
        }
        return isEmpty(reg) || !isEmpty(skip);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
