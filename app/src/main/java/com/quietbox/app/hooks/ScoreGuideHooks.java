package com.quietbox.app.hooks;

import android.app.Activity;
import android.os.Bundle;

import com.quietbox.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * P1-16 评分引导双弹窗屏蔽。
 *
 * 评分引导有两个独立入口，任一个漏掉都会弹：
 * 1) 应用内「满意吗 / 去评分」弹窗：AppScoreDialogActivity，走 onCreate，
 *    不能跳过（super.onCreate 只在静态 de() 内被调用一次，跳过会抛
 *    SuperNotCalledException），与 P0-4 同构：finish() 后照常 proceed。
 * 2) Google Play 系统评分：ShareApplication#tryLaunchGpReview，普通 void，直接 skip。
 */
final class ScoreGuideHooks {

    private static final String DIALOG_CLAZZ =
            "com.xingin.lurker.appscore.ui.AppScoreDialogActivity";
    private static final String GP_REVIEW_METHOD = "tryLaunchGpReview";

    private ScoreGuideHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SCORE_GUIDE_BLOCK;
        try {
            Class<?> dialog = HookKit.findClass(module, f, cl, DIALOG_CLAZZ);
            Method onCreate = HookKit.findMethod(module, f, dialog, "onCreate", Bundle.class);
            HookKit.install(module, f, "onCreate", onCreate, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            ((Activity) self).finish();
                        }
                        HookKit.hit(module, f, "onCreate(finish)");
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " intercept error onCreate", t);
                    }
                    return chain.proceed();
                }
            });

            Class<?> app = HookKit.findClass(module, f, cl, "com.xingin.xhs.app.ShareApplication");
            HookKit.install(module, f, GP_REVIEW_METHOD,
                    HookKit.findMethod(module, f, app, GP_REVIEW_METHOD),
                    HookKit.skipVoid(module, f, GP_REVIEW_METHOD));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
