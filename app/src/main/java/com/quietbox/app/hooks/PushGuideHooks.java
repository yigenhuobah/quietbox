package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-02 通知引导弹窗屏蔽。
 *
 * 挂 SPI 实现 com.xingin.spi.IGrowthCupidProxyImpl 的四个展示入口，全部是 void，直接 skip。
 * 不碰 systemNotificationAuthorizationClick 等授权回执方法：用户主动开通知不受影响。
 */
final class PushGuideHooks {

    /** 只拦展示入口，不碰授权回执。 */
    private static final String[] SHOW_KEYS = {
            AnchorKeys.PUSH_GUIDE_DIALOG,
            AnchorKeys.PUSH_GUIDE_POPUP,
            AnchorKeys.PUSH_GUIDE_INSIDE,
            AnchorKeys.PUSH_GUIDE_ACTIVITY,
    };

    private PushGuideHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.PUSH_GUIDE_BLOCK;
        try {
            for (String key : SHOW_KEYS) {
                Method m = HookKit.findAnchorMethod(module, f, cl, key, true);
                if (m != null) {
                    HookKit.install(module, f, key, m, HookKit.skipVoid(module, f, key));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
