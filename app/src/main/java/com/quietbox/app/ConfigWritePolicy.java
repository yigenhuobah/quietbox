package com.quietbox.app;

/**
 * 配置写入与 store 复用的纯决策逻辑。
 *
 * 不引用任何 Android 类型：这些判断是「写失败不能让 UI 显示成功」的核心，
 * 用 JVM 单测固定住，避免以后又退回 apply() + 静默本地回退。
 */
final class ConfigWritePolicy {

    /** put() 的同步结果：只表示是否已排入后台提交，不代表已经落盘。 */
    enum Decision {
        /** remote 可用，写入已排入后台单线程。 */
        QUEUED,
        /** remote 不可用，直接拒绝；调用方必须回滚 UI。 */
        REJECTED_NO_REMOTE
    }

    private ConfigWritePolicy() {
    }

    /** remote 不可用一律拒绝，绝不拿本地写入冒充成功。 */
    static Decision decide(boolean remoteAvailable) {
        return remoteAvailable ? Decision.QUEUED : Decision.REJECTED_NO_REMOTE;
    }

    static boolean accepted(Decision decision) {
        return decision == Decision.QUEUED;
    }

    /** 只有 commit 真正成功，UI 才能保留用户刚选的新值。 */
    static boolean keepUiState(boolean accepted, boolean committed) {
        return accepted && committed;
    }

    /** 失败（remote 不可用 / commit 返回 false / commit 抛异常）必须回滚并提示。 */
    static boolean mustRollback(boolean accepted, boolean committed) {
        return !keepUiState(accepted, committed);
    }

    /**
     * 同一 service 实例内 remote prefs 不变，可复用 store；
     * service 换绑后必须重建，否则会继续用已失效的 binder。
     */
    static boolean reuseStore(boolean hasStore, Object cachedService, Object currentService) {
        return hasStore && cachedService == currentService;
    }
}
