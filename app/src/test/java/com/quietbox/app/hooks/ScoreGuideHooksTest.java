package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.content.Intent;

import com.quietbox.app.Feature;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * ScoreGuideHooks.onCreateHooker 的 JVM 单测（发现 #66 回归）：
 *
 * 评分弹窗是宿主 AppScoreDialogActivity.onCreate→de() 原方法体里 new 出来再 show()
 * 的独立 Dialog，finish() 拦不到。de() 只在 param_dialog_type == "dialog_app_score"
 * 分支构造弹窗（9.50.0 AppScoreDialogActivity.java:59-148、ui/a.java:12 show），
 * 未知类型分支由宿主自己 finish()（:152）。新 hook 必须：
 * <ul>
 *   <li>finish() 后抹掉 param_dialog_type extra，让 de() 走未知类型→finish() 分支，
 *       弹窗不再构造、不再 show；</li>
 *   <li>照常 proceed 恰好一次（super.onCreate 只在 de() 内被调用，跳过整个 onCreate
 *       会抛 SuperNotCalledException）；</li>
 *   <li>extra 字面量与宿主分支一致（"dialog_app_score"）。</li>
 * </ul>
 *
 * libxposed 的 XposedModule 是抽象壳：用 Proxy 实现 XposedInterface 后 attachFramework，
 * 即可在 JVM 单测里跑真实 Hooker（与 HookDiagnosisTest 同构）。Activity/Intent 构造
 * 在单测 mockable android.jar 下可用，getIntent/removeExtra 用测试子类记录。
 *
 * HookState 是进程级静态状态：UI-01 被 HookStateTest.labelsAreSorted 触碰，
 * 本文件用 blocked Feature TRACK_SUPPRESS 做替身（FeatureTest 只断言其 blocked 位）。
 */
public class ScoreGuideHooksTest {

    /** 记录 removeExtra 调用的 Intent 替身。 */
    static class RecordingIntent extends Intent {
        String removedExtra;

        @Override
        public void removeExtra(String name) {
            removedExtra = name;
        }
    }

    /** getIntent 返回记录用 Intent 的 Activity 替身。 */
    static class FakeScoreActivity extends Activity {
        final RecordingIntent intent = new RecordingIntent();

        @Override
        public Intent getIntent() {
            return intent;
        }
    }

    /** getIntent 返回 null 的替身（防御：attach 异常时 hook 不得崩）。 */
    static class NullIntentActivity extends Activity {
    }

    /** Chain 替身：记录 proceed 调用次数。 */
    private static final class ChainFake {
        final Object self;
        final AtomicInteger proceedCalls = new AtomicInteger();

        ChainFake(Object self) {
            this.self = self;
        }

        XposedInterface.Chain proxy() {
            return (XposedInterface.Chain) Proxy.newProxyInstance(
                    XposedInterface.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class},
                    (p, method, args) -> {
                        if ("getThisObject".equals(method.getName())) {
                            return self;
                        }
                        if ("proceed".equals(method.getName())) {
                            proceedCalls.incrementAndGet();
                            return "proceeded";
                        }
                        return null;
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
    public void dialogTypeLiteralMatchesHostBranch() {
        // 宿主 de() 以 Intrinsics.areEqual(stringExtra, "dialog_app_score") 进入弹窗分支，
        // 字面量漂移时这里先失败，避免静默退回「弹窗照弹」的旧行为。
        assertEquals("dialog_app_score", ScoreGuideHooks.SCORE_DIALOG_TYPE);
    }

    @Test
    public void neutralizesDialogTypeThenProceedsOnce() throws Throwable {
        XposedModule module = newModule();
        FakeScoreActivity activity = new FakeScoreActivity();
        ChainFake chain = new ChainFake(activity);

        XposedInterface.Hooker hooker =
                ScoreGuideHooks.onCreateHooker(module, Feature.TRACK_SUPPRESS);
        Object result = hooker.intercept(chain.proxy());

        assertEquals("必须抹掉宿主的 param_dialog_type extra",
                "dialog_app_score", activity.intent.removedExtra);
        assertEquals("proceed 必须恰好一次（super.onCreate 仍由宿主执行）",
                1, chain.proceedCalls.get());
        assertEquals("proceed 结果必须原样返回", "proceeded", result);
    }

    @Test
    public void nullIntentStillProceedsOnce() throws Throwable {
        XposedModule module = newModule();
        NullIntentActivity activity = new NullIntentActivity();
        ChainFake chain = new ChainFake(activity);

        XposedInterface.Hooker hooker =
                ScoreGuideHooks.onCreateHooker(module, Feature.TRACK_SUPPRESS);
        Object result = hooker.intercept(chain.proxy());

        assertEquals("proceed 结果必须原样返回", "proceeded", result);
        assertEquals(1, chain.proceedCalls.get());
    }

    @Test
    public void nonActivitySelfStillProceedsOnce() throws Throwable {
        XposedModule module = newModule();
        ChainFake chain = new ChainFake(new Object());

        XposedInterface.Hooker hooker =
                ScoreGuideHooks.onCreateHooker(module, Feature.TRACK_SUPPRESS);
        Object result = hooker.intercept(chain.proxy());

        assertEquals("proceed 结果必须原样返回", "proceeded", result);
        assertEquals("thisObject 非 Activity 时不得破坏原调用", 1, chain.proceedCalls.get());
    }
}
