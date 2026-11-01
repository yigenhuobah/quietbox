package com.quietbox.app.hooks;

import android.util.Log;

import com.quietbox.app.Config;
import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * 统一在 onPackageReady（已确认包名 com.xingin.xhs）之后调用。
 * 单个功能的失败不得影响其余功能，也不得让模块加载失败。
 */
public final class Hooks {

    private static final String TAG = "QuietBox";

    /** 单个 Feature 的安装动作；module/cl/config/feature 统一由 installOne 传入。 */
    @FunctionalInterface
    interface Installer {
        void install(XposedModule module, ClassLoader cl, Config config, Feature feature);
    }

    /**
     * Feature -> 安装器。只登记非 blocked Feature；blocked Feature 不在表内，
     * installOne 会按原 switch 的行为打印 blocked 日志且不安装。
     *
     * 安装顺序仍由 installAll 遍历 Feature.values() 决定，与本表迭代顺序无关。
     */
    static final EnumMap<Feature, Installer> INSTALLERS = createInstallers();

    private Hooks() {
    }

    private static EnumMap<Feature, Installer> createInstallers() {
        EnumMap<Feature, Installer> map = new EnumMap<>(Feature.class);

        map.put(Feature.SPLASH_AD_SKIP,
                (module, cl, config, f) -> SplashAdHooks.install(module, cl));
        map.put(Feature.FEED_AD_FILTER,
                (module, cl, config, f) -> FeedAdHooks.install(module, cl));
        map.put(Feature.COMMENT_AD_FILTER,
                (module, cl, config, f) -> CommentAdHooks.install(module, cl));
        map.put(Feature.UPDATE_DIALOG_BLOCK,
                (module, cl, config, f) -> UpdateDialogHooks.install(module, cl));
        map.put(Feature.SCREENSHOT_DETECT_OFF,
                (module, cl, config, f) -> ScreenShotHooks.install(module, cl));
        map.put(Feature.EXT_APP_BLOCK,
                (module, cl, config, f) -> ExtAppHooks.install(module, cl));
        map.put(Feature.COPY_LINK_SANITIZE,
                (module, cl, config, f) -> CopyLinkHooks.install(
                        module, cl, config.stripXsecToken()));
        map.put(Feature.WATERMARK_POLICY,
                (module, cl, config, f) -> WatermarkHooks.install(module, cl));
        map.put(Feature.LIVE_PHOTO_WATERMARK,
                (module, cl, config, f) -> LivePhotoWatermarkHooks.install(module, cl));
        map.put(Feature.AUTOPLAY_PREFERENCE,
                (module, cl, config, f) -> AutoPlayHooks.install(module, cl));
        map.put(Feature.SEARCH_AD_FILTER,
                (module, cl, config, f) -> SearchAdHooks.install(module, cl));
        map.put(Feature.NOTE_DETAIL_AD_BANNER,
                (module, cl, config, f) -> NoteDetailAdHooks.install(module, cl));
        map.put(Feature.NOTE_UNAVAILABLE_VIEW,
                (module, cl, config, f) -> NoteUnavailableHooks.install(module, cl));
        map.put(Feature.SCORE_GUIDE_BLOCK,
                (module, cl, config, f) -> ScoreGuideHooks.install(module, cl));
        map.put(Feature.PUSH_GUIDE_BLOCK,
                (module, cl, config, f) -> PushGuideHooks.install(module, cl));
        map.put(Feature.CONTENT_RATING_BLOCK,
                (module, cl, config, f) -> ContentRatingHooks.install(module, cl));
        map.put(Feature.CONTACT_SYNC_OFF,
                (module, cl, config, f) -> ContactSyncHooks.install(module, cl));
        map.put(Feature.GOODS_COMMENT_BLOCK,
                (module, cl, config, f) -> GoodsCommentHooks.install(module, cl));
        map.put(Feature.FEED_LIVE_CARD_BLOCK,
                (module, cl, config, f) -> FeedCardHooks.install(module, cl, config));
        map.put(Feature.FEED_GOODS_CARD_BLOCK,
                (module, cl, config, f) -> FeedCardHooks.install(module, cl, config));
        map.put(Feature.FEED_VOTE_CARD_BLOCK,
                (module, cl, config, f) -> FeedCardHooks.install(module, cl, config));
        map.put(Feature.AB_TEST_PANEL,
                (module, cl, config, f) -> AbTestHooks.install(module, cl));

        return map;
    }

    /** 注册表键快照，供单测断言「非 blocked 全覆盖、blocked 不安装」。 */
    static Set<Feature> installerKeys() {
        EnumSet<Feature> keys = EnumSet.noneOf(Feature.class);
        keys.addAll(INSTALLERS.keySet());
        return Collections.unmodifiableSet(keys);
    }

    /**
     * @param mainProcess 当前是否为主进程（进程名 == 包名）。诊断回传只在主进程启动，
     *                    避免子进程未装全 hook 时把功能误报为失败。
     */
    public static void installAll(XposedModule module, ClassLoader cl, boolean mainProcess) {
        Config config;
        try {
            config = Config.load(module);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, "config load failed", t);
            return;
        }
        if (!config.anyEnabled()) {
            module.log(Log.INFO, TAG, "all features disabled, nothing to hook");
            return;
        }
        if (mainProcess) {
            Report.start();
            Report.installCrashHandler();
        } else {
            module.log(Log.INFO, TAG, "non-main process, status report disabled");
        }
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                module.log(Log.INFO, TAG, f.id + " blocked: " + f.blockedReason);
                continue;
            }
            if (!config.enabled(f)) {
                module.log(Log.INFO, TAG, f.id + " disabled");
                continue;
            }
            HookState.enable(f);
            installOne(module, cl, f, config);
        }
    }

    private static void installOne(XposedModule module, ClassLoader cl, Feature f, Config config) {
        try {
            Installer installer = INSTALLERS.get(f);
            if (installer == null) {
                if (f.blocked()) {
                    module.log(Log.INFO, TAG, f.id + " blocked: " + f.blockedReason);
                } else {
                    module.log(Log.INFO, TAG, f.id + " pending implementation in later phase");
                }
                return;
            }
            installer.install(module, cl, config, f);
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, f.id + " install threw", t);
            // 安装阶段整段抛出属于主链路失效：记 critical，让失败横幅可见。
            HookState.markNotFoundCritical(f, "安装异常");
        }
    }
}
