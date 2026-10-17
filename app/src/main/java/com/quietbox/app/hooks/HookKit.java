package com.quietbox.app.hooks;

import android.util.Log;

import androidx.annotation.Nullable;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 约束：
 * 1) 定位/hook 失败只记日志并返回 null/false，绝不向外抛（HookFailedError 继承 Error，框架不兜底）。
 * 2) 每个 intercept 体整段包在 try/catch(Throwable) 内，catch 后记日志并 proceed 原逻辑。
 * 3) 命中日志固定 "<功能id> hit ..."，tag 固定 QuietBox。
 */
final class HookKit {

    static final String TAG = "QuietBox";

    private HookKit() {
    }

    static void info(XposedModule module, String msg) {
        module.log(Log.INFO, TAG, msg);
    }

    static void warn(XposedModule module, String msg) {
        module.log(Log.WARN, TAG, msg);
    }

    static void error(XposedModule module, String msg, Throwable t) {
        module.log(Log.ERROR, TAG, msg, t);
    }

    @Nullable
    static Class<?> findClass(XposedModule module, Feature f, ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            warn(module, f.id + " skip: class not found " + name);
            HookState.markNotFound(f);
            HookState.note(f, "类未找到 " + name);
            return null;
        }
    }

    @Nullable
    static Method findMethod(XposedModule module, Feature f, @Nullable Class<?> cls, String name,
                             Class<?>... params) {
        Method m = findMethodQuiet(cls, name, params);
        if (m == null) {
            warn(module, f.id + " skip: method not found "
                    + (cls == null ? "?" : cls.getName()) + "#" + name);
            HookState.markNotFound(f);
            HookState.note(f, "方法未找到 " + name);
        }
        return m;
    }

    /**
     * 按名字找方法，不看参数。getDeclaredMethod 不给参数类型时只匹配无参方法，
     * 有参入口必须走这里；同名重载不唯一时返回 null，不做猜测。
     */
    @Nullable
    static Method findMethod(XposedModule module, Feature f, @Nullable Class<?> cls, String name) {
        Method m = findByNameQuiet(cls, name);
        if (m == null) {
            warn(module, f.id + " skip: method not found "
                    + (cls == null ? "?" : cls.getName()) + "#" + name);
            HookState.markNotFound(f);
            HookState.note(f, "方法未找到 " + name);
        }
        return m;
    }

    @Nullable
    static Method findByNameQuiet(@Nullable Class<?> cls, String name) {
        if (cls == null) {
            return null;
        }
        try {
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!name.equals(m.getName())) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = m;
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按「名字 + 前导参数类型」定位方法：只校验最前面的若干个参数类型，后面的不看。
     * 用于参数后半段是混淆类型（无法在编译期写出 Class 字面量）的场景，
     * 例如 ui.a#d(WeakReference, SplashAd)、SplashAdActivity$a#a(Context, SplashAd)。
     * 同名同形状不唯一时返回 null，不做猜测。
     */
    @Nullable
    static Method findByShape(@Nullable Class<?> cls, String name, Class<?>... leading) {
        if (cls == null) {
            return null;
        }
        try {
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!name.equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length < leading.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < leading.length; i++) {
                    if (p[i] != leading[i]) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = m;
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按「名字 + 参数个数」定位方法。参数类型全是混淆类型时用这个，
     * 比 findByShape 更宽松。同名同参数个数不唯一时返回 null，不做猜测。
     */
    @Nullable
    static Method findByNameAndArity(@Nullable Class<?> cls, String name, int arity) {
        if (cls == null) {
            return null;
        }
        try {
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!name.equals(m.getName()) || m.getParameterTypes().length != arity) {
                    continue;
                }
                if (found != null) {
                    return null;
                }
                found = m;
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    static Method findMethodQuiet(@Nullable Class<?> cls, String name, Class<?>... params) {
        if (cls == null) {
            return null;
        }
        try {
            return cls.getDeclaredMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean install(XposedModule module, Feature f, String label, @Nullable Method m,
                           XposedInterface.Hooker hooker) {
        if (m == null) {
            return false;
        }
        return install(module, f, label, (java.lang.reflect.Executable) m, hooker);
    }

    /** 构造函数同样可以被 hook，libxposed 的 hook() 接受整个 Executable。 */
    static boolean install(XposedModule module, Feature f, String label,
                           @Nullable java.lang.reflect.Executable m,
                           XposedInterface.Hooker hooker) {
        if (m == null) {
            return false;
        }
        try {
            module.hook(m)
                    .setId(f.id + "#" + label)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            HookState.installed(f, label);
            info(module, f.id + " registered " + label);
            return true;
        } catch (Throwable t) {
            error(module, f.id + " hook failed " + label, t);
            HookState.markNotFound(f);
            HookState.note(f, "hook 失败 " + label);
            return false;
        }
    }

    static void hit(XposedModule module, Feature f, String detail) {
        module.log(Log.INFO, TAG, f.id + " hit " + detail);
    }

    /** void 方法：不 proceed 即原方法体不执行。 */
    static XposedInterface.Hooker skipVoid(XposedModule module, Feature f, String detail) {
        return chain -> {
            try {
                hit(module, f, detail);
                return null;
            } catch (Throwable t) {
                error(module, f.id + " intercept error " + detail, t);
                return chain.proceed();
            }
        };
    }

    /** 布尔方法：直接给常量，不 proceed。 */
    static XposedInterface.Hooker constBool(XposedModule module, Feature f, String detail, boolean value) {
        return chain -> {
            try {
                hit(module, f, detail);
                return value ? Boolean.TRUE : Boolean.FALSE;
            } catch (Throwable t) {
                error(module, f.id + " intercept error " + detail, t);
                return chain.proceed();
            }
        };
    }
}
