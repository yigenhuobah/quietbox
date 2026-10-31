package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安装诊断记账回归：
 * <ul>
 *   <li>HookKit.findClass 默认按可选记账，兜底类缺失不得把仍工作的功能判进失败横幅；
 *       critical=true 时才标 critical。</li>
 *   <li>WatermarkHooks 主锚点（jn6.j / jn6.i）缺失必须留下 critical skip，
 *       纯语义 getter 不能把主链路失效掩盖成"已安装"。</li>
 *   <li>ScreenShotHooks 只有安装成功才登记检测器类；失败后同一类必须还能重试。</li>
 * </ul>
 *
 * libxposed 的 XposedModule 是抽象壳：用 Proxy 实现 XposedInterface 后 attachFramework，
 * 即可在 JVM 单测里跑真实 HookKit 路径；hook() 返回 null 会落在 HookKit.install 的
 * catch 分支（等价安装失败），返回 HookHandle 代理则等价安装成功。
 *
 * HookState 是进程级静态状态：与既有测试一样按 Feature 隔离，本文件只用
 * AUTOPLAY_PREFERENCE / UPDATE_DIALOG_BLOCK / COPY_LINK_SANITIZE（既有测试未触碰），
 * WATERMARK_POLICY / SCREENSHOT_DETECT_OFF 与 HookStateTest 互不写 critical，无顺序依赖。
 */
public class HookDiagnosisTest {

    // ---------------------------------------------------------------- fake module

    private static XposedModule newModule(InvocationHandler handler) throws Exception {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                handler);
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(base, () -> {
        });
        return module;
    }

    /** 记录 hook() 调用次数；hookSucceeds=false 时返回 null，模拟 HookBuilder 创建即失败。 */
    private static InvocationHandler hookCountingHandler(final AtomicInteger hookCalls,
                                                         final boolean hookSucceeds) {
        return (proxy, method, args) -> {
            if ("hook".equals(method.getName())) {
                hookCalls.incrementAndGet();
                if (!hookSucceeds) {
                    return null;
                }
                // hook() 返回 HookBuilder，链式 setId/setExceptionMode 后 intercept 得到
                // HookHandle；一个同时实现两个接口的代理即可走完整条成功链。
                ClassLoader cl = XposedInterface.class.getClassLoader();
                return Proxy.newProxyInstance(cl,
                        new Class<?>[]{XposedInterface.HookBuilder.class,
                                XposedInterface.HookHandle.class},
                        (p, m, a) -> {
                            if ("setId".equals(m.getName())
                                    || "setExceptionMode".equals(m.getName())
                                    || "intercept".equals(m.getName())) {
                                return p;
                            }
                            return "toString".equals(m.getName()) ? "fake-handle" : null;
                        });
            }
            if ("toString".equals(method.getName())) {
                return "fake-module";
            }
            return null;
        };
    }

    private static final class MissingEverythingLoader extends ClassLoader {
    }

    // ---------------------------------------------------------------- HookKit.findClass

    @Test
    public void findClassDefaultTreatsMissingClassAsOptional() throws Exception {
        Feature f = Feature.AUTOPLAY_PREFERENCE;
        XposedModule module = newModule((p, m, a) -> null);

        assertNull(HookKit.findClass(module, f, new MissingEverythingLoader(),
                "com.xingin.missing.OptionalFallback"));

        assertFalse("可选类缺失不得标 critical（否则兜底类改名会把功能算进失败横幅）",
                HookState.hasCriticalSkip(f));
        assertEquals("类未找到 com.xingin.missing.OptionalFallback", HookState.skipReason(f));
    }

    @Test
    public void findClassWithCriticalFlagMarksCritical() throws Exception {
        Feature f = Feature.UPDATE_DIALOG_BLOCK;
        XposedModule module = newModule((p, m, a) -> null);

        assertNull(HookKit.findClass(module, f, new MissingEverythingLoader(),
                "com.xingin.missing.MainAnchor", true));

        assertTrue("主锚点类缺失必须标 critical", HookState.hasCriticalSkip(f));
        assertEquals("类未找到 com.xingin.missing.MainAnchor", HookState.skipReason(f));
    }

    @Test
    public void findClassSuccessRecordsNothing() throws Exception {
        Feature f = Feature.COPY_LINK_SANITIZE;
        XposedModule module = newModule((p, m, a) -> null);

        assertNotNull(HookKit.findClass(module, f,
                getClass().getClassLoader(), "java.lang.String"));

        assertNull(HookState.skipReason(f));
        assertFalse(HookState.hasCriticalSkip(f));
    }

    // ---------------------------------------------------------------- WatermarkHooks

    @Test
    public void watermarkInstallRecordsCriticalWhenMainAnchorsMissing() throws Exception {
        Feature f = Feature.WATERMARK_POLICY;
        XposedModule module = newModule((p, m, a) -> null);

        WatermarkHooks.install(module, new MissingEverythingLoader());

        assertTrue("jn6.j / jn6.i 关键锚点缺失必须标 critical，让失败横幅可见",
                HookState.hasCriticalSkip(f));
        assertNotNull("锚点缺失必须留下 skip 原因供诊断页展示", HookState.skipReason(f));
    }

    // ---------------------------------------------------------------- Hooks.installOne

    @Test
    public void installOneMarksCriticalWhenInstallerThrows() throws Exception {
        Feature f = Feature.UPDATE_DIALOG_BLOCK;
        XposedModule module = newModule((p, m, a) -> null);
        // 保存原 installer，结束后精确还原，避免影响 HookRegistryTest 的精确键集断言。
        Hooks.Installer prev = Hooks.INSTALLERS.put(f, (m, cl, config, feature) -> {
            throw new IllegalStateException("installer boom");
        });
        try {
            Method installOne = Hooks.class.getDeclaredMethod("installOne",
                    XposedModule.class, ClassLoader.class, Feature.class, com.quietbox.app.Config.class);
            installOne.setAccessible(true);
            installOne.invoke(null, module, getClass().getClassLoader(), f, null);
        } finally {
            if (prev == null) {
                Hooks.INSTALLERS.remove(f);
            } else {
                Hooks.INSTALLERS.put(f, prev);
            }
        }

        assertTrue("installer 整段抛出必须标 critical，失败横幅才可见", HookState.hasCriticalSkip(f));
        assertEquals("安装异常", HookState.skipReason(f));
    }

    // ---------------------------------------------------------------- ScreenShotHooks

    /** 结构上满足检测器特征的外层类：有 Context 字段 + 唯一 (Uri, boolean) -> void 方法。 */
    private static final class FakeDetectorOuter {
        private android.content.Context context;

        void onChange(android.net.Uri uri, boolean auto) {
        }
    }

    private static void bindOuter(XposedModule module, Feature f, Class<?> outer) throws Exception {
        Method m = ScreenShotHooks.class.getDeclaredMethod("bindOuter",
                XposedModule.class, Feature.class, Class.class);
        m.setAccessible(true);
        m.invoke(null, module, f, outer);
    }

    @SuppressWarnings("unchecked")
    private static void clearBound() throws Exception {
        Field field = ScreenShotHooks.class.getDeclaredField("BOUND");
        field.setAccessible(true);
        ((Set<String>) field.get(null)).clear();
    }

    @Test
    public void screenshotBindRetriesSameClassAfterFailedInstall() throws Exception {
        clearBound();
        Feature f = Feature.SCREENSHOT_DETECT_OFF;
        AtomicInteger hookCalls = new AtomicInteger();
        XposedModule module = newModule(hookCountingHandler(hookCalls, false));

        bindOuter(module, f, FakeDetectorOuter.class);
        bindOuter(module, f, FakeDetectorOuter.class);

        assertEquals("安装失败不得登记 BOUND：第二次注册观察者必须重试",
                2, hookCalls.get());
    }

    @Test
    public void screenshotBindDoesNotRehookSuccessfulInstall() throws Exception {
        clearBound();
        Feature f = Feature.SCREENSHOT_DETECT_OFF;
        AtomicInteger hookCalls = new AtomicInteger();
        XposedModule module = newModule(hookCountingHandler(hookCalls, true));

        bindOuter(module, f, FakeDetectorOuter.class);
        bindOuter(module, f, FakeDetectorOuter.class);

        assertEquals("成功挂载后同一检测器类不得重复 hook",
                1, hookCalls.get());
    }
}
