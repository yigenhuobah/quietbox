package com.quietbox.app;

import android.util.Log;

import androidx.annotation.NonNull;

import com.quietbox.app.hooks.Hooks;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Single Java entry class. API 102 only supports hot reload for modules that declare
 * exactly one entry, so this must stay the only class listed in java_init.list.
 *
 * <p>Nothing may be initialised before {@link #onModuleLoaded} runs: the framework attaches
 * itself to this instance after construction, and every API call before that throws.
 */
public class QuietBoxModule extends XposedModule {

    static final String TAG = "QuietBox";
    static final String TARGET_PACKAGE = "com.xingin.xhs";

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded process=" + param.getProcessName()
                + " api=" + getApiVersion()
                + " frameworkProps=0x" + Long.toHexString(getFrameworkProperties()));
    }

    /**
     * Fires once the app classloader exists, which is the earliest point a hook can resolve
     * XHS classes. Note the framework injects us into every process of the scoped package and
     * fires this callback for <em>every</em> package loaded in that process, so filtering here
     * is mandatory, not defensive.
     */
    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        log(Log.INFO, TAG, "package ready: " + param.getPackageName()
                + " first=" + param.isFirstPackage());
        // 框架只兜 RuntimeException；HookFailedError 继承 Error，必须自己兜住整段安装。
        try {
            Hooks.installAll(this, param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install aborted", t);
        }
    }
}
