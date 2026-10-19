package com.quietbox.app.hooks;

import com.quietbox.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * P1-17 通知引导弹窗屏蔽。
 *
 * 挂 SPI 实现 com.xingin.spi.IGrowthCupidProxyImpl 的四个展示入口，全部是 void，直接 skip。
 * 不碰 systemNotificationAuthorizationClick 等授权回执方法：用户主动开通知不受影响。
 */
final class PushGuideHooks {

    private static final String CLAZZ = "com.xingin.spi.IGrowthCupidProxyImpl";

    /** 只拦展示入口，不碰授权回执。 */
    private static final String[] SHOW_METHODS = {
            "pushGuideDialogDialogShow",
            "showGuidePopupWindow",
            "showPushGuidShowForInside",
            "openPushGuideActivity",
    };

    private PushGuideHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.PUSH_GUIDE_BLOCK;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            for (String name : SHOW_METHODS) {
                Method m = HookKit.findMethod(module, f, cls, name);
                if (m != null) {
                    HookKit.install(module, f, name, m,
                            HookKit.skipVoid(module, f, name));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
