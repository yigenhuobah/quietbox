package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.quietbox.app.Feature;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * ContactSyncHooks.completeThenSkip 的 JVM 单测（发现 #60 回归）：
 *
 * 3 参 requestContactsSync(Context, String, z2.b) 带完成回调契约（原方法体在触发未知
 * 与同步结束两处都会回调 onCompleted，见 9.50.0 UploadContactServiceExtension.java:103-116）。
 * 旧实现整体 skipVoid，回调随方法体一起消失。新实现必须：
 * <ul>
 *   <li>先调用回调（onCompleted 恰好一次），再跳过业务体——返回 null 且从不 proceed；</li>
 *   <li>回调缺失（null）与回调抛错都不放行业务体（放行等于恢复通讯录上传）；</li>
 *   <li>反射在实现类 / 接口上都能找到 onCompleted()。</li>
 * </ul>
 *
 * libxposed 的 XposedModule 是抽象壳：用 Proxy 实现 XposedInterface 后 attachFramework，
 * 即可在 JVM 单测里跑真实 Hooker（与 HookDiagnosisTest 同构）。
 *
 * HookState 是进程级静态状态：PV-02 被 HookStateTest.snapshotAggregatesFeatureLevelState
 * 声明为「未被其他用例触碰」（精确增量断言依赖），因此本文件用 blocked Feature
 * VIDEO_DEFAULT_MUTE 做替身（FeatureTest 只断言其 blocked 位，不写 HookState）。
 */
public class ContactSyncHooksTest {

    /** z2.b 的替身：公开 onCompleted()，实现可直接落在实现类上。 */
    static class CompletedOnSelf {
        int completed;

        public void onCompleted() {
            completed++;
        }
    }

    /** onCompleted 只声明在接口上的替身（对应宿主里回调可能由接口提供）。 */
    interface OnCompletedInterface {
        void onCompleted();
    }

    static class CompletedOnInterface implements OnCompletedInterface {
        int completed;

        @Override
        public void onCompleted() {
            completed++;
        }
    }

    /** onCompleted 抛错的替身：回调失败也不得放行业务体。 */
    static class ThrowingCallback {
        public void onCompleted() {
            throw new IllegalStateException("callback boom");
        }
    }

    /** Chain 替身：记录 proceed 调用次数（业务体是否被执行）。 */
    private static final class ChainFake {
        final List<Object> args;
        final AtomicInteger proceedCalls = new AtomicInteger();

        ChainFake(Object... args) {
            this.args = Arrays.asList(args);
        }

        XposedInterface.Chain proxy() {
            return (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class},
                    (proxy, method, methodArgs) -> {
                        switch (method.getName()) {
                            case "getArg":
                                return args.get((Integer) methodArgs[0]);
                            case "getArgs":
                                return args;
                            case "proceed":
                            case "proceedWith":
                                proceedCalls.incrementAndGet();
                                return "proceeded";
                            default:
                                return null;
                        }
                    });
        }
    }

    private static XposedModule newModule() {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> null);
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(base, () -> {
        });
        return module;
    }

    @Test
    public void invokesCallbackOnceThenSkipsBody() throws Throwable {
        XposedModule module = newModule();
        CompletedOnSelf callback = new CompletedOnSelf();
        ChainFake chain = new ChainFake(new Object(), "trigger", callback);

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenSkip(module, Feature.VIDEO_DEFAULT_MUTE, "requestContactsSync#3");

        Object result = hooker.intercept(chain.proxy());

        assertNull("跳过业务体必须返回 null（void 方法）", result);
        assertEquals("onCompleted 必须恰好回调一次", 1, callback.completed);
        assertEquals("业务体不得执行（proceed 即恢复上传）", 0, chain.proceedCalls.get());
    }

    @Test
    public void findsOnCompletedDeclaredOnInterface() throws Throwable {
        XposedModule module = newModule();
        CompletedOnInterface callback = new CompletedOnInterface();
        ChainFake chain = new ChainFake(new Object(), "trigger", callback);

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenSkip(module, Feature.VIDEO_DEFAULT_MUTE, "requestContactsSync#3");

        hooker.intercept(chain.proxy());

        assertEquals("接口上的 onCompleted 也必须被调到", 1, callback.completed);
        assertEquals(0, chain.proceedCalls.get());
    }

    @Test
    public void nullCallbackStillSkipsBody() throws Throwable {
        XposedModule module = newModule();
        ChainFake chain = new ChainFake(new Object(), "trigger", null);

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenSkip(module, Feature.VIDEO_DEFAULT_MUTE, "requestContactsSync#3");

        Object result = hooker.intercept(chain.proxy());

        assertNull(result);
        assertEquals("无回调时同样跳过业务体", 0, chain.proceedCalls.get());
    }

    @Test
    public void throwingCallbackIsLoggedButBodyStillSkipped() throws Throwable {
        XposedModule module = newModule();
        ChainFake chain = new ChainFake(new Object(), "trigger", new ThrowingCallback());

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenSkip(module, Feature.VIDEO_DEFAULT_MUTE, "requestContactsSync#3");

        Object result = hooker.intercept(chain.proxy());

        assertNull("回调抛错不得向外传播", result);
        assertEquals("回调抛错也不得放行业务体", 0, chain.proceedCalls.get());
    }

    @Test
    public void missingThirdArgStillSkipsBody() throws Throwable {
        XposedModule module = newModule();
        // 宿主若以少于 3 参调用（防御场景），getArg(2) 抛界外也必须吞掉并跳过业务体。
        ChainFake chain = new ChainFake(new Object(), "trigger");

        XposedInterface.Hooker hooker =
                ContactSyncHooks.completeThenSkip(module, Feature.VIDEO_DEFAULT_MUTE, "requestContactsSync#3");

        Object result = hooker.intercept(chain.proxy());

        assertNull(result);
        assertEquals(0, chain.proceedCalls.get());
    }
}
