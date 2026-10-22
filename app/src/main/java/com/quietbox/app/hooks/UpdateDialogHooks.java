package com.quietbox.app.hooks;

import android.app.Activity;
import android.os.Bundle;

import com.quietbox.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * UI-04 更新弹窗屏蔽。
 * onCreate 不能跳过：super.onCreate 只在静态 de() 内被调用一次，
 * 跳过会让 Activity.mCalled 校验抛 SuperNotCalledException。
 * 因此 onCreate 只 finish() 后照常 proceed；he() 是普通 void，可安全跳过。
 */
final class UpdateDialogHooks {

    private static final String CLAZZ = "com.xingin.update.UpdateDialogActivity";

    private UpdateDialogHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.UPDATE_DIALOG_BLOCK;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            Method onCreate = HookKit.findMethod(module, f, cls, "onCreate", Bundle.class);
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
            Method he = HookKit.findMethod(module, f, cls, "he");
            HookKit.install(module, f, "he", he, HookKit.skipVoid(module, f, "he"));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
