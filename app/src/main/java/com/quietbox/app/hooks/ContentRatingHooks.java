package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-03 内容评价 Snackbar 屏蔽。
 *
 * 详情页底部「对当前的内容满意吗？」调研条。主挂 shouldShowInternal（return false），
 * 一处管全部；showForVideo / showForImageText 两个分场景入口作为兜底 skip，
 * 防将来调用方绕过判定直接展示。
 */
final class ContentRatingHooks {

    private ContentRatingHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.CONTENT_RATING_BLOCK;
        try {
            Method should = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.CONTENT_RATING_SHOULD_SHOW, true);
            HookKit.install(module, f, "shouldShowInternal", should,
                    HookKit.constBool(module, f, "shouldShowInternal", false));
            // 公开入口：showContentRatingSnackBarIfNeeded(Activity, IContentRatingProxy$b, int, int)V。
            HookKit.install(module, f, "showContentRatingSnackBarIfNeeded",
                    HookKit.findAnchorMethod(module, f, cl,
                            AnchorKeys.CONTENT_RATING_SHOW_IF_NEEDED, false),
                    HookKit.skipVoid(module, f, "showContentRatingSnackBarIfNeeded"));
            for (String key : new String[]{
                    AnchorKeys.CONTENT_RATING_SHOW_VIDEO,
                    AnchorKeys.CONTENT_RATING_SHOW_IMAGE_TEXT}) {
                Method m = HookKit.findAnchorMethod(module, f, cl, key, false);
                if (m != null) {
                    HookKit.install(module, f, key, m, HookKit.skipVoid(module, f, key));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
