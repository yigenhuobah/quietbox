package com.quietbox.app;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void snapshotAggregatesFeatureLevelState() {
        Feature f = Feature.WATERMARK_POLICY;
        HookState.installed(f, "alpha");
        HookState.markNotFound(f, "missing");

        HookState.Snapshot snap = HookState.snapshot();
        assertTrue("已注册的功能应计入 ok", snap.ok >= 1);
        assertTrue("有 skip 的功能应计入 bad", snap.bad >= 1);
        assertTrue("miss 与 bad 都按功能去重", snap.miss == snap.bad);
        assertTrue("快照应包含 EN-01", snap.missed.contains("EN-01"));
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
