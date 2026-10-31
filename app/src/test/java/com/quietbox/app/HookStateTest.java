package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * HookState 的纯 JVM 行为：命中计数、标签排序、skip 原因保留、并发累加。
 * 诊断页与 Report 回传的快照都从这里取值，语义变了会直接反映到用户界面。
 *
 * 每个用例使用不同的 Feature：HookState 是进程级静态状态，测试方法之间不能互相污染。
 */
public class HookStateTest {

    @Test
    public void hitIncrementsCount() {
        Feature f = Feature.SPLASH_AD_SKIP;
        assertEquals(1L, HookState.hit(f));
        assertEquals(2L, HookState.hit(f));
        assertEquals(2L, HookState.hitCount(f));
    }

    @Test
    public void labelsAreSorted() {
        Feature f = Feature.SCORE_GUIDE_BLOCK;
        HookState.installed(f, "beta");
        HookState.installed(f, "alpha");
        assertEquals(Arrays.asList("alpha", "beta"), HookState.labels(f));
    }

    @Test
    public void markNotFoundKeepsLastReason() {
        Feature f = Feature.SCREENSHOT_DETECT_OFF;
        HookState.markNotFound(f, "first");
        HookState.markNotFound(f, "second");
        assertEquals("second", HookState.skipReason(f));
        assertFalse("方法级 skip 是可选锚点，不能标 critical", HookState.hasCriticalSkip(f));
    }

    @Test
    public void markNotFoundCriticalSetsSkipAndCriticalFlag() {
        Feature f = Feature.EXT_APP_BLOCK;
        HookState.markNotFoundCritical(f, "类未找到 x");
        assertEquals("类未找到 x", HookState.skipReason(f));
        assertTrue("类缺失必须标 critical", HookState.hasCriticalSkip(f));
    }

    @Test
    public void criticalFlagSurvivesLaterOptionalSkip() {
        Feature f = Feature.CONTENT_RATING_BLOCK;
        HookState.markNotFoundCritical(f, "类未找到 x");
        HookState.markNotFound(f, "方法未找到 y");
        assertEquals("诊断页展示最后一个原因", "方法未找到 y", HookState.skipReason(f));
        assertTrue("后续可选 skip 不能清掉 critical 标记", HookState.hasCriticalSkip(f));
    }

    @Test
    public void untouchedFeatureHasNoCriticalSkip() {
        Feature f = Feature.GOODS_COMMENT_BLOCK;
        assertFalse(HookState.hasCriticalSkip(f));
    }

    @Test
    public void untouchedFeatureHasEmptyState() {
        Feature f = Feature.NOTE_UNAVAILABLE_VIEW;
        assertEquals(0L, HookState.hitCount(f));
        assertTrue("未触碰的 Feature 不应有标签", HookState.labels(f).isEmpty());
        assertNull("未触碰的 Feature 不应有 skip 原因", HookState.skipReason(f));
    }

    @Test
    public void enableCreatesEmptyStateForUntouchedFeature() {
        Feature f = Feature.COMMENT_AD_FILTER;
        HookState.enable(f);
        assertTrue("enable 后 Feature 必须进入 touched，才能回传空 reg/skip",
                HookState.touched().contains(f));
        assertTrue(HookState.labels(f).isEmpty());
        assertNull(HookState.skipReason(f));
        assertEquals(0L, HookState.hitCount(f));
    }

    /**
     * PV-02 未被本文件其他用例、ReportTest、HookDiagnosisTest 触碰：HookState 是
     * 进程级静态状态，只有用未被污染的 Feature 做快照前后对比，统计断言才不会
     * 恰好被其他用例的残留状态兜底。断言用精确增量而非 >=1。
     */
    @Test
    public void snapshotAggregatesFeatureLevelState() {
        Feature f = Feature.CONTACT_SYNC_OFF;
        HookState.Snapshot before = HookState.snapshot();
        assertFalse("前置：PV-02 不应已被其他用例计入 missed", before.missed.contains(f.id));

        HookState.installed(f, "alpha");
        HookState.Snapshot afterInstall = HookState.snapshot();
        assertEquals("注册标签应把功能计入 ok（恰好 +1）", before.ok + 1, afterInstall.ok);
        assertEquals("仅注册不影响 skip 统计", before.bad, afterInstall.bad);
        assertEquals("首次 touch 应使 features 计数 +1", before.features + 1, afterInstall.features);
        assertFalse("尚无 skip 时不应进入 missed", afterInstall.missed.contains(f.id));

        HookState.markNotFound(f, "missing");
        HookState.Snapshot snap = HookState.snapshot();
        assertEquals("skip 不影响 ok 统计", before.ok + 1, snap.ok);
        assertEquals("有 skip 的功能应计入 bad（恰好 +1）", before.bad + 1, snap.bad);
        assertEquals("miss 应随新 skip 按功能 +1，与 bad 同步", before.miss + 1, snap.miss);
        assertTrue("快照应包含 PV-02", snap.missed.contains("PV-02"));
        for (int i = 1; i < snap.missed.size(); i++) {
            assertTrue("missed 列表应按编号排序",
                    snap.missed.get(i - 1).compareTo(snap.missed.get(i)) <= 0);
        }
    }

    @Test
    public void concurrentHitsAreNotLost() throws Exception {
        final Feature f = Feature.FEED_AD_FILTER;
        final int threads = 4;
        final int perThread = 1000;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perThread; j++) {
                        HookState.hit(f);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue("并发计数未在 10 秒内完成", done.await(10, TimeUnit.SECONDS));
        assertEquals(threads * perThread, HookState.hitCount(f));
    }
}
