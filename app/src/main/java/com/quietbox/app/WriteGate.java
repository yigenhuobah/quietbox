package com.quietbox.app;

/**
 * 单个 key 的写回状态机适配器：逻辑全部由 core 的泛型
 * {@link io.github.yigenhuobah.core.WriteGate} 实现，本类只固定 Boolean 值类型
 * 与模块内的调用形状。
 *
 * <p>保留原有模块 API（begin/onResult/rollback/clear），调用点无需改动；
 * 回滚值由 core 的 {@code rollback(key, fallback)} 给出：最近一次确认落盘的值
 * 优先，从未确认时由调用方传入的兜底值决定。
 */
final class WriteGate {

    private final io.github.yigenhuobah.core.WriteGate<Boolean> mDelegate =
            new io.github.yigenhuobah.core.WriteGate<>();

    /** 记录一次新写入并返回其序号（单调递增，不依赖系统时钟）。 */
    long begin(String key) {
        return mDelegate.begin(key);
    }

    /**
     * 记录一次写入结果。
     *
     * @return true 表示该结果仍是最新一次写入，调用方可以据此更新界面；
     *         false 表示已有更新的写入，旧结果不能覆盖界面。
     */
    boolean onResult(String key, long seq, boolean committed, boolean requested) {
        return mDelegate.onResult(key, seq, committed, requested);
    }

    /**
     * 失败回滚值：最近一次确认落盘的值；从未确认时返回调用方兜底值。
     *
     * @param key      配置键
     * @param fallback 没有确认值时使用的值（模块内为 {@code !requested}）
     */
    boolean rollback(String key, boolean fallback) {
        return mDelegate.rollback(key, fallback);
    }

    /** Fragment 销毁时丢弃在途结果与确认值。 */
    void clear() {
        mDelegate.clear();
    }
}
