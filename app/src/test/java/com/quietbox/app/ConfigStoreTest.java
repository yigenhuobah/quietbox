package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ConfigStore 的写入契约（纯 JVM，注入 SharedPreferences 替身）：
 * - remote 不可用时 put 返回 false，且绝不写本地冒充成功；
 * - commit 的真实结果通过回调返回，false / 抛异常都算失败；
 * - 所有写入走同一个后台线程，按提交顺序落盘。
 */
public class ConfigStoreTest {

    @Test
    public void remoteUnavailableReturnsFalseAndDoesNotTouchLocal() {
        FakeSharedPreferences local = new FakeSharedPreferences();
        ConfigStore store = new ConfigStore(null, local);
        assertFalse("remote 不可用时 put 必须返回 false", store.put("k", true));
        assertFalse("remote 不可用时不能写本地冒充成功", local.contains("k"));
    }

    @Test
    public void remoteCommitSuccessReportsTrueAndWritesValue() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences());
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        assertTrue("remote 可用时应排入后台提交",
                store.put("k", true, committed -> {
                    result.set(committed);
                    done.countDown();
                }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue("commit 成功必须回调 true", result.get());
        assertTrue(remote.getBoolean("k", false));
    }

    @Test
    public void remoteCommitFalseReportsFailure() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.setCommitResult(false);
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences());
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.put("k", true, committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse("commit 返回 false 必须回调失败", result.get());
        assertFalse(remote.contains("k"));
    }

    @Test
    public void remoteCommitThrowReportsFailure() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.setThrowOnCommit(true);
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences());
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.put("k", true, committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse("commit 抛异常必须回调失败", result.get());
    }

    @Test
    public void writesAreSerializedInOrder() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences());
        CountDownLatch done = new CountDownLatch(2);

        assertTrue(store.put("k", true, committed -> done.countDown()));
        assertTrue(store.put("k", false, committed -> done.countDown()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse("后提交的写入必须最后落盘", remote.getBoolean("k", true));
    }

    /** 普通开关写入必须顺带写 config_version，且同一次 commit。 */
    @Test
    public void putWritesConfigVersion() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences());
        Feature feature = Feature.FEED_AD_FILTER;
        boolean next = !feature.defaultEnabled;
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.put(feature.key, next, committed -> done.countDown()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(next, remote.getBoolean(feature.key, feature.defaultEnabled));
        assertTrue(remote.contains(Config.KEY_CONFIG_VERSION));
        assertEquals(Config.CURRENT_CONFIG_VERSION,
                remote.getInt(Config.KEY_CONFIG_VERSION, -1));
        assertEquals("开关与版本必须共用一次 commit", 1, remote.commitCount());
    }

    /**
     * 恢复默认 = 一次 remote 事务：所有功能开关写默认值、strip/AB 删除、
     * config_version 写 1；本地 ab_report 只清 panel_enabled/ab_overrides 镜像，
     * 探测数据 v:* / t 与无关 remote key 都保留。
     */
    @Test
    public void resetRestoresDefaultsInOneTransaction() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        for (Feature f : Feature.values()) {
            remote.values().put(f.key, !f.defaultEnabled);
        }
        remote.values().put(Config.KEY_STRIP_XSEC_TOKEN, true);
        remote.values().put(Config.KEY_AB_OVERRIDES, "ab_key=1");
        remote.values().put(Config.KEY_CONFIG_VERSION, 9);
        remote.values().put("ab_report", "keep-me");
        remote.values().put("launcher_icon", "keep-me-too");
        FakeSharedPreferences report = new FakeSharedPreferences();
        report.values().put(AbReportProvider.KEY_PANEL_ENABLED, true);
        report.values().put(AbReportProvider.KEY_OVERRIDES, "old=1");
        report.values().put("v:old_key", "old_value");
        report.values().put("t", 123L);
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        assertTrue("remote 可用时应排入后台执行",
                store.resetToDefaults(committed -> {
                    result.set(committed);
                    done.countDown();
                }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue("全部回读匹配才算成功", result.get());
        assertEquals("恢复默认必须只 commit 一次", 1, remote.commitCount());
        assertEquals("镜像清理必须只 commit 一次", 1, report.commitCount());

        for (Feature f : Feature.values()) {
            assertEquals("恢复默认后 " + f.key + " 应为编译期默认值",
                    f.defaultEnabled, remote.getBoolean(f.key, !f.defaultEnabled));
        }
        assertFalse("strip 子开关应回到「不存在」的全新安装状态",
                remote.contains(Config.KEY_STRIP_XSEC_TOKEN));
        assertFalse("AB 覆盖表应删除", remote.contains(Config.KEY_AB_OVERRIDES));
        assertEquals(Config.CURRENT_CONFIG_VERSION,
                remote.getInt(Config.KEY_CONFIG_VERSION, -1));
        assertEquals("无关键不能被清掉", "keep-me", remote.getString("ab_report", null));
        assertEquals("无关键不能被清掉", "keep-me-too",
                remote.getString("launcher_icon", null));

        assertFalse("本地镜像 panel_enabled 必须清掉",
                report.contains(AbReportProvider.KEY_PANEL_ENABLED));
        assertFalse("本地镜像 ab_overrides 必须清掉",
                report.contains(AbReportProvider.KEY_OVERRIDES));
        assertEquals("探测数据 v:* 不能删", "old_value",
                report.getString("v:old_key", null));
        assertEquals("探测时间 t 不能删", 123L, report.getLong("t", 0L));
    }

    /** 读回必须走 commit 后重新取的 handle，而不是 commit() 那个实例的内存副本。 */
    @Test
    public void resetVerifiesFreshRemoteHandle() throws Exception {
        Map<String, Object> backing = new HashMap<>();
        FakeSharedPreferences writeHandle = new FakeSharedPreferences(backing);
        FakeSharedPreferences freshHandle = new FakeSharedPreferences(backing);
        for (Feature f : Feature.values()) {
            backing.put(f.key, !f.defaultEnabled);
        }
        backing.put(Config.KEY_STRIP_XSEC_TOKEN, true);
        backing.put(Config.KEY_AB_OVERRIDES, "ab_key=1");
        backing.put(Config.KEY_CONFIG_VERSION, 9);
        ConfigStore store = new ConfigStore(writeHandle, new FakeSharedPreferences(),
                null, () -> freshHandle);
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.resetToDefaults(committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertTrue("重新取的 handle 看到正确值时应成功", result.get());
        assertEquals("写入只提交一次", 1, writeHandle.commitCount());
        assertEquals("读回 handle 不能发生写入", 0, freshHandle.commitCount());
        assertFalse(freshHandle.contains(Config.KEY_STRIP_XSEC_TOKEN));
        assertFalse(freshHandle.contains(Config.KEY_AB_OVERRIDES));
        assertEquals(Config.CURRENT_CONFIG_VERSION,
                freshHandle.getInt(Config.KEY_CONFIG_VERSION, -1));
    }

    /** 新 handle 看不到 commit 结果（模拟落盘/框架侧失败）：必须报告失败。 */
    @Test
    public void resetFailsWhenFreshHandleDoesNotSeeCommit() throws Exception {
        FakeSharedPreferences writeHandle = new FakeSharedPreferences();
        FakeSharedPreferences staleHandle = new FakeSharedPreferences();
        ConfigStore store = new ConfigStore(writeHandle, new FakeSharedPreferences(),
                null, () -> staleHandle);
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.resetToDefaults(committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertFalse("读回不匹配时不能因为 commit 返回 true 就报告成功", result.get());
        assertEquals("写入确实提交过一次", 1, writeHandle.commitCount());
    }

    /** 重新取 handle 失败（框架断开）：不能把 commit 成功当成整体成功。 */
    @Test
    public void resetFailsWhenFreshHandleUnavailable() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences(),
                null, () -> null);
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.resetToDefaults(committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(result.get());
    }

    /** commit 成功但 remove 没落盘：strip/ab_overrides 仍存在时必须报告失败。 */
    @Test
    public void resetFailsWhenRemovedKeysSurvive() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.values().put(Config.KEY_STRIP_XSEC_TOKEN, true);
        remote.values().put(Config.KEY_AB_OVERRIDES, "ab_key=1");
        remote.setDropRemovals(true);
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences());
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.resetToDefaults(committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertFalse("两个键删除后必须确认 contains=false", result.get());
        assertTrue(remote.contains(Config.KEY_STRIP_XSEC_TOKEN));
        assertTrue(remote.contains(Config.KEY_AB_OVERRIDES));
    }

    /** 本地镜像清理失败要计入整体结果，否则宿主活通道仍会读到旧面板状态。 */
    @Test
    public void resetFailsWhenAbMirrorCannotBeCleared() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        FakeSharedPreferences report = new FakeSharedPreferences();
        report.values().put(AbReportProvider.KEY_PANEL_ENABLED, true);
        report.setCommitResult(false);
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.resetToDefaults(committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertFalse("镜像没清掉时不能报告成功", result.get());
        assertEquals("remote 本身已按计划写入",
                Feature.FEED_AD_FILTER.defaultEnabled,
                remote.getBoolean(Feature.FEED_AD_FILTER.key,
                        !Feature.FEED_AD_FILTER.defaultEnabled));
    }

    /** remote 不可用：返回 false、回调 false，且绝不写本地冒充成功。 */
    @Test
    public void resetWhenRemoteUnavailableReportsFalseAndDoesNotTouchLocal() throws Exception {
        FakeSharedPreferences local = new FakeSharedPreferences();
        local.values().put(Feature.FEED_AD_FILTER.key, false);
        local.values().put(Config.KEY_STRIP_XSEC_TOKEN, true);
        ConfigStore store = new ConfigStore(null, local);
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertFalse("remote 不可用时 reset 必须返回 false",
                store.resetToDefaults(committed -> {
                    result.set(committed);
                    done.countDown();
                }));
        assertTrue("失败结果也必须回调", done.await(5, TimeUnit.SECONDS));
        assertFalse(result.get());
        assertEquals("本地存储不能被改写", Boolean.FALSE,
                local.values().get(Feature.FEED_AD_FILTER.key));
        assertTrue("本地 strip 值不能被改写",
                local.getBoolean(Config.KEY_STRIP_XSEC_TOKEN, false));
    }

    /** commit 失败：回调 false，不能把部分写入当成功。 */
    @Test
    public void resetCommitFailureReportsFalse() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.setCommitResult(false);
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences());
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.resetToDefaults(committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse("commit 返回 false 必须回调失败", result.get());
        assertFalse(remote.contains(Config.KEY_CONFIG_VERSION));
    }
}
