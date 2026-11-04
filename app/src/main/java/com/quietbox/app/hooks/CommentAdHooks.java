package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * AD-03 评论广告过滤。
 * 公开方法是评论区模块的唯一开关入口，直接返回 false 可绕过 Lazy 缓存与内部实验判定。
 * getAdsCommentComponentBinder 无条件返回 binder，若调用方不经本开关，广告组件仍可能出现。
 */
final class CommentAdHooks {

    private CommentAdHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.COMMENT_AD_FILTER;
        try {
            Method m = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.COMMENT_AD_DSL_ENABLE, true);
            HookKit.install(module, f, "commentAdvertComponentDSLEnable", m,
                    HookKit.constBool(module, f, "commentAdvertComponentDSLEnable", false));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
