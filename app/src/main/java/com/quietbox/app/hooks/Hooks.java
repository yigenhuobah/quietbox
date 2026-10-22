package com.quietbox.app.hooks;

import android.util.Log;

import com.quietbox.app.AbReportProvider;
import com.quietbox.app.Config;
import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

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

    private Hooks() {
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
            switch (f) {
                case SPLASH_AD_SKIP:
                    SplashAdHooks.install(module, cl);
                    break;
                case FEED_AD_FILTER:
                    FeedAdHooks.install(module, cl);
                    break;
                case COMMENT_AD_FILTER:
                    CommentAdHooks.install(module, cl);
                    break;
                case UPDATE_DIALOG_BLOCK:
                    UpdateDialogHooks.install(module, cl);
                    break;
                case SCREENSHOT_DETECT_OFF:
                    ScreenShotHooks.install(module, cl);
                    break;
                case EXT_APP_BLOCK:
                    ExtAppHooks.install(module, cl);
                    break;
                case COPY_LINK_SANITIZE:
                    CopyLinkHooks.install(module, cl, config.stripXsecToken());
                    break;
                case WATERMARK_POLICY:
                    WatermarkHooks.install(module, cl);
                    break;
                case AUTOPLAY_PREFERENCE:
                    AutoPlayHooks.install(module, cl);
                    break;
                case SEARCH_AD_FILTER:
                    SearchAdHooks.install(module, cl);
                    break;
                case NOTE_DETAIL_AD_BANNER:
                    NoteDetailAdHooks.install(module, cl);
                    break;
                case NOTE_UNAVAILABLE_VIEW:
                    NoteUnavailableHooks.install(module, cl);
                    break;
                case SCORE_GUIDE_BLOCK:
                    ScoreGuideHooks.install(module, cl);
                    break;
                case PUSH_GUIDE_BLOCK:
                    PushGuideHooks.install(module, cl);
                    break;
                case CONTENT_RATING_BLOCK:
                    ContentRatingHooks.install(module, cl);
                    break;
                case CONTACT_SYNC_OFF:
                    ContactSyncHooks.install(module, cl);
                    break;
                case GOODS_COMMENT_BLOCK:
                    GoodsCommentHooks.install(module, cl);
                    break;
                case FEED_LIVE_CARD_BLOCK:
                case FEED_GOODS_CARD_BLOCK:
                case FEED_VOTE_CARD_BLOCK:
                    FeedCardHooks.install(module, cl, config);
                    break;
                case AB_TEST_PANEL:
                    AbTestHooks.install(module, cl);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            module.log(Log.ERROR, TAG, f.id + " install threw", t);
            HookState.markNotFound(f, "安装异常");
        }
    }
}
