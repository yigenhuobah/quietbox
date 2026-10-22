package com.quietbox.app.hooks;

import com.quietbox.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * P1-19 通讯录同步上传关闭。
 *
 * 通讯录上传是可选功能，不上传就不会有通讯录推荐/好友发现。三个入口都是 void，直接 skip。
 * 不碰登录与安全链路。
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
            HookKit.install(module, f, "requestContactsSync#3",
                    HookKit.findByNameAndArity(module, f, cls, "requestContactsSync", 3),
                    HookKit.skipVoid(module, f, "requestContactsSync#3"));
            HookKit.install(module, f, "installLifecycleObserver",
                    HookKit.findMethod(module, f, cls, "installLifecycleObserver",
                            android.app.Application.class),
                    HookKit.skipVoid(module, f, "installLifecycleObserver"));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
