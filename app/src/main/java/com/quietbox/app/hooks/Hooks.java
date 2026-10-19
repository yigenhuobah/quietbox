package com.quietbox.app.hooks;

import android.util.Log;

import com.quietbox.app.AbReportProvider;
import com.quietbox.app.Config;
import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import io.github.libxposed.api.XposedModule;

/**
 * 统一在 onPackageReady（已确认包名 com.xingin.xhs）之后调用。
 * 单个功能的失败不得影响其余功能，也不得让模块加载失败。
 */
public final class Hooks {

    private static final String TAG = "QuietBox";

    private Hooks() {
    }

    public static void installAll(XposedModule module, ClassLoader cl) {
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
     * 挂载统计推送：小红书升级后挂载点可能大面积失效，UI 侧看不出来。
     * installAll 结束时统计 ok/miss，挂 Application#onCreate 拿 Context 后
     * 一次性推给模块 App，由它判断是否显示不兼容横幅。
     *
     * 这里不再判断 anyEnabled：走到这里说明至少有一个功能开着，统计才有意义。
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
                            if (self instanceof android.content.Context && !sMountPushed) {
                                sMountPushed = true;
                                HookState.Snapshot snap = HookState.drain();
                                if (snap.ok + snap.miss > 0) {
                                    sendMount((android.content.Context) self, snap);
                                }
                            }
                        } catch (Throwable t) {
                            HookKit.warn(module, "mount report skipped: " + t);
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            HookKit.warn(module, "mount report install skipped: " + t);
        }
    }

    private static volatile boolean sMountPushed;

    private static void sendMount(android.content.Context ctx, HookState.Snapshot snap) {
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putInt(AbReportProvider.KEY_MOUNT_OK, snap.ok);
            extras.putInt(AbReportProvider.KEY_MOUNT_MISS, snap.miss);
            extras.putString(AbReportProvider.KEY_MOUNT_DETAIL,
                    android.text.TextUtils.join(",", snap.missed));
            ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + AbReportProvider.AUTHORITY),
                    "mount", null, extras);
        } catch (Throwable t) {
            Log.w(TAG, "mount report send failed: " + t);
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
            HookState.note(f, "安装异常");
        }
    }
}
