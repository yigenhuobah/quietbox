package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * FeedAdHooks.dropAds 的 JVM 单测（报告 #75 回归）：
 * <ul>
 *   <li>正常路径：从入参列表移除 isAd==true 的项（isAd 是 NoteItemBean 的字段不是
 *       getter），计数正确且不产生错误日志。</li>
 *   <li>异常路径（不可变列表等）：原样放行（不向外抛、不动原列表），但必须经
 *       HookKit.error 记日志——旧实现 catch(Throwable ignored) 静默吞掉，该入口的
 *       AD-02/AD-08/09/10 失效时无 hit、无 skip、无日志，诊断页与 logcat 都无痕迹。</li>
 * </ul>
 *
 * libxposed 的 XposedModule 是抽象壳：用 Proxy 实现 XposedInterface 后 attachFramework
 * 即可在 JVM 单测里跑真实 HookKit 路径（与 HookDiagnosisTest 同构）。
 * dropAds 是私有静态方法，按签名反射调用，签名漂移时测试会直接失败。
 */
public class FeedAdHooksTest {

    /** isAd 经反射按字段名解析，测试用带 isAd 字段的假 note。 */
    static class Note {
        boolean isAd;
        String tag;

        Note(boolean isAd, String tag) {
            this.isAd = isAd;
            this.tag = tag;
        }
    }

    /** 没有 isAd 字段的假 note：模拟宿主字段改名后判定永久失效。 */
    static class NoteWithoutIsAd {
        String tag = "no-field";
    }

    private static int dropAds(XposedModule module, Feature f, String where, List<?> list)
            throws Exception {
        Method m = FeedAdHooks.class.getDeclaredMethod("dropAds",
                XposedModule.class, Feature.class, String.class, List.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, module, f, where, list);
    }

    // ---------------------------------------------------------------- fake module

    static final class LogRecord {
        final int priority;
        final String msg;
        final Throwable t;

        LogRecord(int priority, String msg, Throwable t) {
            this.priority = priority;
            this.msg = msg;
            this.t = t;
        }
    }

    private static XposedModule newModule(final List<LogRecord> logs) throws Exception {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("log".equals(method.getName()) && args != null && args.length >= 3) {
                logs.add(new LogRecord((Integer) args[0], (String) args[2],
                        args.length > 3 && args[3] instanceof Throwable
                                ? (Throwable) args[3] : null));
            }
            return null;
        };
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

    @Test
    public void dropAds_removesAdNotesFromMutableList() throws Exception {
        List<LogRecord> logs = new ArrayList<>();
        XposedModule module = newModule(logs);

        Note ad1 = new Note(true, "ad1");
        Note normal = new Note(false, "normal");
        Note ad2 = new Note(true, "ad2");
        List<Note> list = new ArrayList<>(Arrays.asList(ad1, normal, ad2));

        int removed = dropAds(module, Feature.FEED_AD_FILTER, "ExploreView.S", list);

        assertEquals(2, removed);
        assertEquals(1, list.size());
        assertSame(normal, list.get(0));
        // 正常路径不产生错误日志。
        assertTrue(logs.isEmpty());
    }

    @Test
    public void dropAds_immutableList_logsError_butLeavesListUntouched() throws Exception {
        List<LogRecord> logs = new ArrayList<>();
        XposedModule module = newModule(logs);

        Note ad = new Note(true, "ad");
        // Arrays.asList 的 iterator().remove() 会抛 UnsupportedOperationException，
        // 等价宿主传入不可变列表的场景。
        List<Note> list = Arrays.asList(ad);

        int removed = dropAds(module, Feature.FEED_AD_FILTER, "ExploreView.S", list);

        // 原样放行：不计数、不动列表、不向外抛。
        assertEquals(0, removed);
        assertEquals(1, list.size());
        assertSame(ad, list.get(0));
        // 但必须留下错误日志，msg 带功能 id 与入口，异常原样附上。
        assertEquals(1, logs.size());
        assertEquals(Log.ERROR, logs.get(0).priority);
        assertTrue(logs.get(0).msg,
                logs.get(0).msg.contains("dropAds failed ExploreView.S"));
        assertTrue(logs.get(0).msg, logs.get(0).msg.contains(Feature.FEED_AD_FILTER.id));
        assertTrue(logs.get(0).t instanceof UnsupportedOperationException);
    }

    /**
     * isAd 字段缺失（宿主改名）时判定只能返回 false，但必须留下 field: 诊断原因，
     * 否则 AD-02 会永久静默失效、诊断页与 logcat 都无痕迹。
     */
    @Test
    public void dropAds_missingIsAdField_recordsSkipReason() throws Exception {
        List<LogRecord> logs = new ArrayList<>();
        XposedModule module = newModule(logs);

        List<NoteWithoutIsAd> list = new ArrayList<>();
        list.add(new NoteWithoutIsAd());

        int removed = dropAds(module, Feature.FEED_AD_FILTER, "ExploreView.S", list);

        assertEquals(0, removed);
        assertEquals(1, list.size());
        boolean found = false;
        for (String reason : HookState.skipReasons(Feature.FEED_AD_FILTER)) {
            if (reason.startsWith("field:isAd 未找到")) {
                found = true;
                break;
            }
        }
        assertTrue("字段缺失必须记录 field:isAd 诊断原因: "
                + HookState.skipReasons(Feature.FEED_AD_FILTER), found);
    }
}
