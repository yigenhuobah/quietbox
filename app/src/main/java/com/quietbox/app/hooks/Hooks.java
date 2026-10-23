package com.quietbox.app.hooks;

import android.util.Log;

import com.quietbox.app.AbReportProvider;
import com.quietbox.app.Config;
import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * 统一在 onPackageReady（已确认包名 com.xingin.xhs）之后调用。
 * 单个功能的失败不得影响其余功能，也不得让模块加载失败。
 */
public final class Hooks {

    private static final String TAG = "QuietBox";

    private static volatile boolean sMountPushed;
    private static final AtomicBoolean sMountSending = new AtomicBoolean();

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
            // 全关时也要推一次 0/0，清掉上一次运行留下的不兼容告警。
            pushMountReport(module, cl);
            return;
        }
        if (mainProcess) {
            Report.start();
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
        pushMountReport(module, cl);
    }

    /**
     * 挂载统计推送（模块专属扩展，保留给 MountReport 排障用）。
     * installAll 结束时按「功能去重」统计 ok/miss，挂 Application#onCreate 拿 Context 后
     * 一次性推给模块 App；统一异常横幅改用 StatusProvider 的逐功能回传数据。
     *
     * 全关时也会走到这里（推 0/0 清旧告警），因此不能假设至少有一个功能开着。
     */
    private static void pushMountReport(XposedModule module, ClassLoader cl) {
        try {
            Class<?> app = Class.forName("android.app.Application", false, cl);
            java.lang.reflect.Method onCreate =
                    HookKit.findMethodQuiet(app, "onCreate");
            if (onCreate == null) {
                return;
            }
            module.hook(onCreate)
                    .setId("QuietBox#mount-report")
                    .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            Object self = chain.getThisObject();
                            if (self instanceof android.content.Context
                                    && !sMountPushed
                                    && sMountSending.compareAndSet(false, true)) {
                                final android.content.Context ctx = (android.content.Context) self;
                                final HookState.Snapshot snap = HookState.snapshot();
                                new Thread(() -> {
                                    boolean sent = sendMount(ctx, snap);
                                    android.util.Log.i(TAG, "mount report sent=" + sent
                                            + " ok=" + snap.ok + " miss=" + snap.miss
                                            + " bad=" + snap.bad + " features=" + snap.features);
                                    // 不清空 HookState：同一份状态还要供 Report 周期回传，
                                    // 清掉会让诊断页丢失 registered/skip。
                                    // 每进程只尝试一次：Application#onCreate 可能被多个
                                    // Application 实例重复触发，失败重试只会刷日志，
                                    // 进程内也没有更好的重试时机。
                                    sMountPushed = true;
                                    sMountSending.set(false);
                                }, "QuietBox-mount").start();
                            }
                        } catch (Throwable t) {
                            sMountSending.set(false);
                            HookKit.warn(module, "mount report skipped: " + t);
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            HookKit.warn(module, "mount report install skipped: " + t);
        }
    }

    private static boolean sendMount(android.content.Context ctx, HookState.Snapshot snap) {
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putInt(AbReportProvider.KEY_MOUNT_OK, snap.ok);
            extras.putInt(AbReportProvider.KEY_MOUNT_MISS, snap.miss);
            extras.putInt(AbReportProvider.KEY_MOUNT_FEATURES, snap.features);
            extras.putInt(AbReportProvider.KEY_MOUNT_BAD, snap.bad);
            extras.putString(AbReportProvider.KEY_MOUNT_DETAIL,
                    android.text.TextUtils.join(",", snap.missed));
            Object result = ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + AbReportProvider.AUTHORITY),
                    "mount", null, extras);
            return result != null;
        } catch (Throwable t) {
            Log.w(TAG, "mount report send failed: " + t);
            return false;
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
            HookState.markNotFound(f, "安装异常");
        }
    }
}
