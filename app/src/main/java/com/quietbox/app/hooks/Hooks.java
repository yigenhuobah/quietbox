package com.quietbox.app.hooks;

import android.util.Log;

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
