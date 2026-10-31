package com.quietbox.app.hooks;

import com.quietbox.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * PV-02 通讯录同步上传关闭。
 *
 * 通讯录上传是可选功能，不上传就不会有通讯录推荐/好友发现。start /
 * 2 参 requestContactsSync / installLifecycleObserver 都是无回调契约的 void，直接 skip；
 * 带 z2.b 回调的 3 参 requestContactsSync 先调 onCompleted 再跳过业务体，
 * 同步仍然不执行，但回调链不断（见 completeThenSkip）。不碰登录与安全链路。
 */
final class ContactSyncHooks {

    private static final String CLAZZ =
            "com.xingin.login.manager.UploadContactServiceExtension";

    private ContactSyncHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.CONTACT_SYNC_OFF;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            HookKit.install(module, f, "start",
                    HookKit.findMethod(module, f, cls, "start", android.content.Context.class),
                    HookKit.skipVoid(module, f, "start"));
            HookKit.install(module, f, "requestContactsSync",
                    HookKit.findMethod(module, f, cls, "requestContactsSync",
                            android.content.Context.class, String.class),
                    HookKit.skipVoid(module, f, "requestContactsSync"));
            // DEX 已确认的同名 3 参重载：requestContactsSync(Context, String, z2.b)。
            // 带 onCompleted 回调契约，不能整体 skipVoid（回调会随方法体一起消失）。
            HookKit.install(module, f, "requestContactsSync#3",
                    HookKit.findByNameAndArity(module, f, cls, "requestContactsSync", 3),
                    completeThenSkip(module, f, "requestContactsSync#3"));
            HookKit.install(module, f, "installLifecycleObserver",
                    HookKit.findMethod(module, f, cls, "installLifecycleObserver",
                            android.app.Application.class),
                    HookKit.skipVoid(module, f, "installLifecycleObserver"));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /**
     * 3 参 requestContactsSync 的原方法体在触发未知与同步结束两处都会回调
     * z2.b.onCompleted()（9.50.0 UploadContactServiceExtension.java:103-116）。
     * 整体 skipVoid 会让回调链断掉，依赖 onCompleted 收尾的调用方收不到完成通知。
     * 这里改为先调用回调再跳过业务体：同步上传仍然不执行（PV-02 的核心），
     * 回调契约保留。回调是混淆接口（z2.b），编译期拿不到 Class 字面量，
     * 按名字 + 0 参反射调用；回调失败只记日志，业务体依旧跳过
     * （异常时放行等于恢复上传，不可接受）。
     */
    static XposedInterface.Hooker completeThenSkip(XposedModule module, Feature f, String detail) {
        return chain -> {
            try {
                Object callback = chain.getArg(2);
                if (callback != null) {
                    try {
                        invokeOnCompleted(callback);
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " onCompleted invoke failed " + detail, t);
                    }
                }
                HookKit.hit(module, f, detail);
                return null;
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error " + detail, t);
                return null;
            }
        };
    }

    /** 在回调对象上找 onCompleted()（类层级与接口都查），找不到抛 NoSuchMethodException。 */
    private static void invokeOnCompleted(Object callback) throws Exception {
        Method onCompleted = findZeroArgMethod(callback.getClass(), "onCompleted");
        if (onCompleted == null) {
            throw new NoSuchMethodException(
                    "onCompleted() not found on " + callback.getClass().getName());
        }
        onCompleted.setAccessible(true);
        onCompleted.invoke(callback);
    }

    private static Method findZeroArgMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name);
            } catch (Throwable ignored) {
                // 本层没有，继续查接口与父类。
            }
            for (Class<?> ifc : c.getInterfaces()) {
                Method m = findZeroArgMethod(ifc, name);
                if (m != null) {
                    return m;
                }
            }
        }
        return null;
    }
}
