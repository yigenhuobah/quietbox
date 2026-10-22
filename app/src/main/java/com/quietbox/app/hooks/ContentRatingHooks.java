package com.quietbox.app.hooks;

import com.quietbox.app.Feature;

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

    private static final String CLAZZ = "com.xingin.growth.snackbar.ContentRatingProxyImpl";
    private static final String SHOULD_METHOD = "shouldShowInternal";

    private ContentRatingHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.CONTENT_RATING_BLOCK;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            Method should = HookKit.findMethod(module, f, cls, SHOULD_METHOD, String.class);
            HookKit.install(module, f, SHOULD_METHOD, should,
                    HookKit.constBool(module, f, SHOULD_METHOD, false));
            // 公开入口：showContentRatingSnackBarIfNeeded(Activity, IContentRatingProxy$b, int, int)V。
            String entry = "showContentRatingSnackBarIfNeeded";
            HookKit.install(module, f, entry,
                    HookKit.findByNameAndArity(module, f, cls, entry, 4),
                    HookKit.skipVoid(module, f, entry));
            for (String name : new String[]{"showForVideo", "showForImageText"}) {
                Method m = HookKit.findMethod(module, f, cls, name);
                if (m != null) {
                    HookKit.install(module, f, name, m, HookKit.skipVoid(module, f, name));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
