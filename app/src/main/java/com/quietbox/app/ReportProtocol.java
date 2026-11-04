package com.quietbox.app;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.yigenhuobah.core.ReportProtocolCore;

/**
 * 诊断回传协议的模块适配器：键白名单、长度、计数/文本合并、失败判定与新鲜度
 * 全部由 core 的 {@link ReportProtocolCore} 实现；本类只负责把模块的
 * {@link Feature} 清单配置进 core，并保留既有公开常量与方法签名。
 *
 * <p>白名单用 {@link ReportProtocolCore#configure(Set)} 精确配置：每个 Feature 的
 * {@code reg./hit./skip./crit.} + {@code meta.session/meta.updated_at} +
 * {@code crash0/1/2}。刻意不包含 {@code err.*}——QuietBox 从未上报 hook 体错误
 * 计数，放行这些键只会扩大跨进程写入面。
 *
 * <p>会话身份比较与 provider 调用等待是 core 未覆盖的两处模块 helper，保留在这里。
 */
public final class ReportProtocol {

    public static final String PREFIX_REG = ReportProtocolCore.PREFIX_REG;
    public static final String PREFIX_HIT = ReportProtocolCore.PREFIX_HIT;
    public static final String PREFIX_SKIP = ReportProtocolCore.PREFIX_SKIP;
    /** 主锚点（类）缺失标记：只有它会把功能计入失败横幅。 */
    public static final String PREFIX_CRIT = ReportProtocolCore.PREFIX_CRIT;

    /** 回传快照的元数据：目标进程会话标识与落盘时间。 */
    public static final String KEY_META_SESSION = ReportProtocolCore.KEY_META_SESSION;
    public static final String KEY_META_UPDATED_AT = ReportProtocolCore.KEY_META_UPDATED_AT;

    /** 最近崩溃回传键（crash0 最新，crash2 最旧）：键值通道的固定键名。 */
    public static final String KEY_CRASH_0 = ReportProtocolCore.KEY_CRASH_0;
    public static final String KEY_CRASH_1 = ReportProtocolCore.KEY_CRASH_1;
    public static final String KEY_CRASH_2 = ReportProtocolCore.KEY_CRASH_2;

    public static final int MAX_KEY = ReportProtocolCore.MAX_KEY;
    public static final int MAX_VALUE = ReportProtocolCore.MAX_VALUE;

    /** 超过 10 分钟没有新回传就视为过期，横幅按“未知”处理（0）。 */
    public static final long DEFAULT_TTL_MS = ReportProtocolCore.DEFAULT_TTL_MS;

    /** 未来时间戳容忍上限：updatedAt 领先 now 超过该值视为时钟回拨/异常，直接判过期。 */
    public static final long MAX_FUTURE_SKEW_MS = ReportProtocolCore.MAX_FUTURE_SKEW_MS;

    static {
        // 精确白名单：不用 core 的“feature key × 5 前缀”生成式，旧实现就不含
        // err.*，这里保持逐键恢复（reg/hit/skip/crit × Feature + meta + crash0/1/2）。
        Set<String> allowedKeys = new LinkedHashSet<>();
        for (Feature f : Feature.values()) {
            allowedKeys.add(PREFIX_REG + f.key);
            allowedKeys.add(PREFIX_HIT + f.key);
            allowedKeys.add(PREFIX_SKIP + f.key);
            allowedKeys.add(PREFIX_CRIT + f.key);
        }
        allowedKeys.add(KEY_META_SESSION);
        allowedKeys.add(KEY_META_UPDATED_AT);
        allowedKeys.add(KEY_CRASH_0);
        allowedKeys.add(KEY_CRASH_1);
        allowedKeys.add(KEY_CRASH_2);
        ReportProtocolCore.configure(allowedKeys);
    }

    private ReportProtocol() {
    }

    /** 只接受白名单键；非空、长度上限一并校验，其他一律拒绝。 */
    public static boolean validKey(String key) {
        return ReportProtocolCore.validKey(key);
    }

    /** 值只校验非 null 与长度上限；hit 的可解析性由调用方再校验。 */
    public static boolean isValidValue(String value) {
        return ReportProtocolCore.isValidValue(value);
    }

    /**
     * 命中计数合并：多进程各自回传本进程计数，历史值取最大，避免重复累加，
     * 也避免较大计数被较小值覆盖。负数（异常输入）按 0 处理。
     */
    public static long mergeHit(long prev, long next) {
        return ReportProtocolCore.mergeHit(prev, next);
    }

    /**
     * reg/skip 文本合并：最新值无条件覆盖旧值，空串也覆盖。
     *
     * 目标进程每次启动都是新的 HookState：本次会话没有 skip 时必须发送空串，
     * 才能清掉上一次会话落盘的旧 skip；缺失不发送会让失败横幅永久误报。
     */
    public static String mergeText(String prev, String next) {
        return ReportProtocolCore.mergeText(prev, next);
    }

    /**
     * 已启用且有回传的功能是否明确失败：reg 为空或存在 critical skip（主锚点/类缺失）。
     *
     * 方法级 skip 属于可选锚点缺失，只用于诊断展示，不再计入失败，
     * 避免任一可选方法改名就把整个功能判死、触发红色横幅。
     */
    public static boolean featureFailed(String reg, String skip, boolean criticalSkip, long hit) {
        return ReportProtocolCore.featureFailed(reg, skip, criticalSkip, hit);
    }

    /** crit 值固定为 "1"；其余（空串/缺失/旧数据）都按可选处理。 */
    public static boolean isCritical(String value) {
        return ReportProtocolCore.isCritical(value);
    }

    /**
     * 回传数据是否仍新鲜。updatedAt <= 0 表示从未回传或旧版本没有时间戳；
     * updatedAt 领先 now 超过 {@link #MAX_FUTURE_SKEW_MS}（时钟回拨/未来时间戳）
     * 视为过期，不再当新鲜；小幅偏差仍按新鲜处理，正常 TTL 逻辑保留。
     */
    public static boolean isFresh(long updatedAt, long now, long ttlMs) {
        return ReportProtocolCore.isFresh(updatedAt, now, ttlMs);
    }

    /**
     * 会话身份比较（core 未提供，保留在模块内）：session 字符串不同即新会话
     * （上一会话数据必须重置），相同即同进程。旧格式（纯数字启动毫秒）同样按
     * 字符串比较，不做大小比较——设备时钟回拨会让新进程的毫秒值更小，按大小
     * 比较会把整批回写误判为旧会话丢弃。
     *
     * 旧版目标进程没有 session 字段（incoming 为空）：无从比较，按同会话兼容，
     * 保证模块 App 与目标进程可各自升级；首个带 session 的回传仍会重置旧数据。
     */
    public static boolean isNewSession(String storedSession, String incomingSession) {
        if (incomingSession == null || incomingSession.isEmpty()) {
            return false;
        }
        return !incomingSession.equals(storedSession);
    }

    /**
     * 等待异步 provider 调用结果（core 未提供，保留在模块内）：最多等 timeoutMs，
     * 超时取消并返回 null。任何异常都吞掉返回 null，保证调用方可以走原有退避路径。
     */
    public static <T> T awaitOrCancel(Future<T> future, long timeoutMs) {
        if (future == null) {
            return null;
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
