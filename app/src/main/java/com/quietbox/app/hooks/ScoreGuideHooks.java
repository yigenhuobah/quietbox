package com.quietbox.app.hooks;

import android.app.Activity;
import android.os.Bundle;

import com.quietbox.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * UI-01 评分引导双弹窗屏蔽。
 *
 * 评分引导有两个独立入口，任一个漏掉都会弹：
 * 1) 应用内「满意吗 / 去评分」弹窗：AppScoreDialogActivity，走 onCreate，
 *    不能跳过（super.onCreate 只在静态 de() 内被调用一次，跳过会抛
 *    SuperNotCalledException）。但 finish() 拦不到弹窗本身：评分弹窗是原方法体
 *    de() 里 new 出来再经 ui.a.a→show() 展示的独立 Dialog（9.50.0
 *    AppScoreDialogActivity.java:140-148、ui/a.java:12），而 de() 只在
 *    param_dialog_type == "dialog_app_score" 分支构造弹窗，未知类型分支由宿主
 *    自己 finish()（AppScoreDialogActivity.java:152）。因此 hook 里在 finish()
 *    之后抹掉该 extra，让 de() 走宿主自身的未知类型→finish() 分支：
 *    super.onCreate 照常执行，弹窗不再构造、不再 show，窗口泄漏随之消失。
 * 2) Google Play 系统评分：ShareApplication#tryLaunchGpReview，普通 void，直接 skip。
 */
final class ScoreGuideHooks {

    private static final String DIALOG_CLAZZ =
            "com.xingin.lurker.appscore.ui.AppScoreDialogActivity";
    private static final String GP_REVIEW_METHOD = "tryLaunchGpReview";

    /** 宿主 de() 只在该 dialog_type 取值分支构造并 show() 评分弹窗。 */
    static final String SCORE_DIALOG_TYPE = "dialog_app_score";

    private ScoreGuideHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SCORE_GUIDE_BLOCK;
        try {
            Class<?> dialog = HookKit.findClass(module, f, cl, DIALOG_CLAZZ);
            Method onCreate = HookKit.findMethod(module, f, dialog, "onCreate", Bundle.class);
            HookKit.install(module, f, "onCreate", onCreate, onCreateHooker(module, f));

            Class<?> app = HookKit.findClass(module, f, cl, "com.xingin.xhs.app.ShareApplication");
            HookKit.install(module, f, GP_REVIEW_METHOD,
                    HookKit.findMethod(module, f, app, GP_REVIEW_METHOD),
                    HookKit.skipVoid(module, f, GP_REVIEW_METHOD));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /**
     * finish() + 抹掉 param_dialog_type 后照常 proceed：proceed 恰好一次且在 try 外，
     * super.onCreate 仍由宿主 de() 执行（跳过整个 onCreate 会抛
     * SuperNotCalledException），弹窗构造分支则因 extra 缺失不再进入。
     */
    static XposedInterface.Hooker onCreateHooker(XposedModule module, Feature f) {
        return chain -> {
            try {
                Object self = chain.getThisObject();
                if (self instanceof Activity) {
                    Activity act = (Activity) self;
                    act.finish();
                    neutralizeScoreDialogType(act);
                }
                HookKit.hit(module, f, "onCreate(finish)");
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error onCreate", t);
            }
            return chain.proceed();
        };
    }

    /** 抹掉 param_dialog_type，让宿主 de() 走未知类型→finish() 分支，不再构造弹窗。 */
    static void neutralizeScoreDialogType(Activity act) {
        android.content.Intent intent = act.getIntent();
        if (intent != null) {
            intent.removeExtra(SCORE_DIALOG_TYPE);
        }
    }
}
