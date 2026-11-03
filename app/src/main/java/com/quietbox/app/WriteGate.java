package com.quietbox.app;

import java.util.HashMap;
import java.util.Map;

/**
 * 单个 key 的写回状态机：序号标识最新一次写入，确认值只由 commit 成功更新。
 *
 * <p>纯逻辑、不引用 Android 类型，由 JVM 单测固定两条语义：
 * <ul>
 *   <li>成功写入一律记入确认值——即使它已被更新的写入取代，它仍是最近落盘的值，
 *       后续写入失败必须回滚到它，而不是回滚到更旧的乐观值；</li>
 *   <li>只有最新一次写入的结果才能更新界面，旧结果不能覆盖用户的新选择。</li>
 * </ul>
 * 与 CloudMusicTool 的 WriteGate.onResult 保持同一语义。
 */
final class WriteGate {

    /** key → 最近一次写入的序号。 */
    private final Map<String, Long> mLatest = new HashMap<>();
    /** key → 最近一次确认落盘成功的值。 */
    private final Map<String, Boolean> mConfirmed = new HashMap<>();
    private long mCounter;

    /** 记录一次新写入并返回其序号（单调递增，不依赖系统时钟）。 */
    long begin(String key) {
        long seq = ++mCounter;
        mLatest.put(key, seq);
        return seq;
    }

    /**
     * 记录一次写入结果。
     *
     * @return true 表示该结果仍是最新一次写入，调用方可以据此更新界面；
     *         false 表示已有更新的写入，旧结果不能覆盖界面。
     */
    boolean onResult(String key, long seq, boolean committed, boolean requested) {
        if (committed) {
            // 成功一律记入确认值：被更新写入取代也仍然落盘了。
            mConfirmed.put(key, requested);
        }
        Long latest = mLatest.get(key);
        return latest != null && latest.longValue() == seq;
    }

    /** 失败回滚值：最近一次确认落盘的值；从未确认过返回 null，由调用方兜底。 */
    Boolean rollback(String key) {
        return mConfirmed.get(key);
    }

    /** Fragment 销毁时丢弃在途结果与确认值。 */
    void clear() {
        mLatest.clear();
        mConfirmed.clear();
    }
}
