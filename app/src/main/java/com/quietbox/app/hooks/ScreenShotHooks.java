package com.quietbox.app.hooks;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * PV-01 截图检测关闭。
 * 检测器类名每次发版都变（9.49.1 是 idc.d1/j1，9.50.0 是 xec.d1/j1），禁止硬编码。
 * 定位方案：按结构特征在 ContentObserver 注册时定位外层管理类 ——
 *   观察者类 extends ContentObserver、声明 onChange(boolean, Uri)、是嵌套类；
 *   外层类声明 android.content.Context 字段，且只有唯一一个 (Uri, boolean) -> void 方法。
 * 该组合在全 App 唯一（实测 d1/j1 均满足，其余注册点均不满足）。
 * 只在自动检测路径（z6 == false）拦截；z6 == true 是用户手动分享自己的截图，保留。
 */
final class ScreenShotHooks {

    private static final Set<String> BOUND = new HashSet<>();

    private ScreenShotHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SCREENSHOT_DETECT_OFF;
        try {
            Class<?> resolver = Class.forName("android.content.ContentResolver", false, cl);
            Method three = HookKit.findMethod(module, f, resolver, "registerContentObserver",
                    Uri.class, boolean.class, ContentObserver.class);
            HookKit.install(module, f, "registerContentObserver", three, observerHook(module, f));

            // 部分 ROM 上框架/App 走 4 参数重载；不存在就跳过，不记失败。
            Method four = HookKit.findMethodQuiet(resolver, "registerContentObserver",
                    Uri.class, boolean.class, ContentObserver.class, int.class);
            if (four != null) {
                HookKit.install(module, f, "registerContentObserver/4", four, observerHook(module, f));
            }
            HookState.note(f, "按运行时特征定位，等待检测器注册观察者");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    private static XposedInterface.Hooker observerHook(XposedModule module, Feature f) {
        return chain -> {
            try {
                Object arg = chain.getArg(2);
                if (arg instanceof ContentObserver) {
                    Class<?> outer = locateOuter(arg.getClass());
                    if (outer != null) {
                        bindOuter(module, f, outer);
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error registerContentObserver", t);
            }
            return chain.proceed();
        };
    }

    private static Class<?> locateOuter(Class<?> observerClass) {
        try {
            if (!ContentObserver.class.isAssignableFrom(observerClass)) {
                return null;
            }
            Class<?> outer = observerClass.getEnclosingClass();
            if (outer == null) {
                return null;
            }
            if (HookKit.findMethodQuiet(observerClass, "onChange", boolean.class, Uri.class) == null) {
                return null;
            }
            if (!hasContextField(outer)) {
                return null;
            }
            return mediaMethod(outer) != null ? outer : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasContextField(Class<?> cls) {
        try {
            for (Field field : cls.getDeclaredFields()) {
                if (Context.class.isAssignableFrom(field.getType())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Method mediaMethod(Class<?> cls) {
        Method found = null;
        try {
            for (Method m : cls.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2 || params[0] != Uri.class || params[1] != boolean.class) {
                    continue;
                }
                if (m.getReturnType() != void.class) {
                    continue;
                }
                if (found != null) {
                    return null; // 不唯一就不动
                }
                found = m;
            }
        } catch (Throwable t) {
            return null;
        }
        return found;
    }

    private static void bindOuter(XposedModule module, Feature f, Class<?> outer) {
        String key = outer.getName();
        synchronized (BOUND) {
            if (!BOUND.add(key)) {
                return;
            }
        }
        Method m = mediaMethod(outer);
        if (m == null) {
            return;
        }
        HookKit.install(module, f, key, m, mediaChangeHook(module, f, key));
        HookState.note(f, "已定位检测器类 " + key);
    }

    private static XposedInterface.Hooker mediaChangeHook(XposedModule module, Feature f, String key) {
        return chain -> {
            try {
                if (Boolean.FALSE.equals(chain.getArg(1))) {
                    HookKit.hit(module, f, key + " auto");
                    return null;
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error " + key, t);
            }
            return chain.proceed();
        };
    }
}
