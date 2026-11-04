package com.quietbox.app;

import io.github.yigenhuobah.core.ConfigPolicy;

/**
 * 配置写入与 store 复用的模块适配器：判定逻辑全部在 core 的
 * {@link ConfigPolicy} 中，本类只保留模块原有的静态方法名，避免改动调用点。
 *
 * <p>不引用任何 Android 类型；remote 不可用必须拒绝、commit 失败必须回滚、
 * store 只在同一 service 实例内复用等规则由 core 单测固定。
 */
final class ConfigWritePolicy {

    private ConfigWritePolicy() {
    }

    /** remote 不可用一律拒绝，绝不拿本地写入冒充成功。 */
    static ConfigPolicy.Decision decide(boolean remoteAvailable) {
        return ConfigPolicy.decide(remoteAvailable);
    }

    static boolean accepted(ConfigPolicy.Decision decision) {
        return ConfigPolicy.accepted(decision);
    }

    /** 只有 commit 真正成功，UI 才能保留用户刚选的新值。 */
    static boolean keepUiState(boolean accepted, boolean committed) {
        return ConfigPolicy.keepUiState(accepted, committed);
    }

    /** 失败（remote 不可用 / commit 返回 false / commit 抛异常）必须回滚并提示。 */
    static boolean mustRollback(boolean accepted, boolean committed) {
        return ConfigPolicy.mustRollback(accepted, committed);
    }

    /**
     * 同一 service 实例内 remote prefs 不变，可复用 store；
     * service 换绑后必须重建，否则会继续用已失效的 binder。
     */
    static boolean reuseStore(boolean hasStore, Object cachedService, Object currentService) {
        return ConfigPolicy.reuseStore(hasStore, cachedService, currentService);
    }
}
