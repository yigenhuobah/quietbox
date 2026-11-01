package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * StatusProvider 的纯 Java 协议规则：键白名单、值长度、hit 最大值合并、
 * critical skip 判定、会话新旧比较与 TTL 新鲜度。
 * 这些规则决定跨进程写入 prefs 的边界，改动必须同步更新本测试。
 */
public class ReportProtocolTest {

    @Test
    public void validKeyAcceptsRepresentativePrefixes() {
        assertTrue(ReportProtocol.validKey("reg.p0_1_splash_ad_skip"));
        assertTrue(ReportProtocol.validKey("hit.p1_13_search_ad_filter"));
        assertTrue(ReportProtocol.validKey("skip.p1_9_copy_link_sanitize"));
    }

    @Test
    public void validKeyAcceptsAll78GeneratedKeys() {
        int count = 0;
        for (Feature f : Feature.values()) {
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_REG + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_HIT + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_SKIP + f.key));
            count += 3;
        }
        assertEquals(78, count);
        // crash0/1/2 是白名单里的固定键（非 Feature 派生），不参与上面的 78 计数。
        assertTrue(ReportProtocol.validKey(ReportProtocol.KEY_CRASH_0));
        assertTrue(ReportProtocol.validKey(ReportProtocol.KEY_CRASH_1));
        assertTrue(ReportProtocol.validKey(ReportProtocol.KEY_CRASH_2));
    }

    @Test
    public void validKeyAcceptsCriticalAndMetaKeys() {
        for (Feature f : Feature.values()) {
            assertTrue("crit 键必须放行", ReportProtocol.validKey(ReportProtocol.PREFIX_CRIT + f.key));
        }
        assertTrue(ReportProtocol.validKey(ReportProtocol.KEY_META_SESSION));
        assertTrue(ReportProtocol.validKey(ReportProtocol.KEY_META_UPDATED_AT));
        assertFalse(ReportProtocol.validKey("meta.unknown"));
        assertFalse(ReportProtocol.validKey("crit."));
        assertFalse(ReportProtocol.validKey("crash3"));
        assertFalse(ReportProtocol.validKey("crash"));
    }

    /** crash 回传键：固定键名白名单放行，单条值仍受 MAX_VALUE=256 上限（生产端截 240）。 */
    @Test
    public void crashKeysAreWhitelistedAndValueCapped() {
        assertEquals("crash0", ReportProtocol.KEY_CRASH_0);
        assertEquals("crash1", ReportProtocol.KEY_CRASH_1);
        assertEquals("crash2", ReportProtocol.KEY_CRASH_2);
        assertTrue(ReportProtocol.isValidValue("c".repeat(240)));
        assertTrue(ReportProtocol.isValidValue("c".repeat(256)));
        assertFalse("crash 键不豁免 256 上限", ReportProtocol.isValidValue("c".repeat(257)));
    }

    @Test
    public void validKeyRejectsUnknownKeys() {
        assertFalse(ReportProtocol.validKey("foo"));
        assertFalse(ReportProtocol.validKey("hit.not_a_feature"));
        assertFalse(ReportProtocol.validKey("hit." + "x".repeat(125)));
        assertFalse(ReportProtocol.validKey(null));
        assertFalse(ReportProtocol.validKey(""));
        assertFalse(ReportProtocol.validKey("REG.p0_1_splash_ad_skip"));
        assertFalse(ReportProtocol.validKey("reg.p0_1_splash_ad_skip "));
    }

    @Test
    public void mergeHitKeepsLargerValue() {
        assertEquals(10L, ReportProtocol.mergeHit(0L, 10L));
        assertEquals(10L, ReportProtocol.mergeHit(10L, 8L));
    }

    @Test
    public void mergeHitTreatsNegativeAsZero() {
        assertEquals(3L, ReportProtocol.mergeHit(-5L, 3L));
        assertEquals(0L, ReportProtocol.mergeHit(-5L, -3L));
        assertEquals(7L, ReportProtocol.mergeHit(7L, -1L));
    }

    @Test
    public void isValidValueRejectsNullAndOversizedValues() {
        assertTrue(ReportProtocol.isValidValue(""));
        assertTrue(ReportProtocol.isValidValue("a".repeat(256)));
        assertFalse(ReportProtocol.isValidValue(null));
        assertFalse(ReportProtocol.isValidValue("a".repeat(257)));
    }

    @Test
    public void validKeyRejectsOversizedKnownFeatureKey() {
        String valid = ReportProtocol.PREFIX_HIT + Feature.SPLASH_AD_SKIP.key;
        assertTrue(ReportProtocol.validKey(valid));
        String oversized = valid + "x".repeat(ReportProtocol.MAX_KEY - valid.length() + 1);
        assertTrue(oversized.length() > ReportProtocol.MAX_KEY);
        assertFalse("超过 MAX_KEY 的键必须拒绝", ReportProtocol.validKey(oversized));
    }

    @Test
    public void validKeyRejectsMalformedPrefixShapes() {
        assertFalse(ReportProtocol.validKey("reg."));
        assertFalse(ReportProtocol.validKey("hit"));
        assertFalse(ReportProtocol.validKey("reg.."));
        assertFalse(ReportProtocol.validKey("reg. "));
        assertFalse(ReportProtocol.validKey(" hit.p0_1_splash_ad_skip"));
    }

    /**
     * QuietBox 的回传通道是 Bundle + String，不是 JSON：skip 原因是自由文本，
     * 只受长度约束。这里锁住「畸形 JSON 不会被当成结构解析」这一事实；
     * 若将来引入 JSON 载荷，应改由专门的解析函数拒绝，而不是放宽本测试。
     */
    @Test
    public void malformedJsonIsOpaqueValueNotParsed() {
        assertTrue(ReportProtocol.isValidValue("{\"broken\":"));
        assertTrue(ReportProtocol.isValidValue("null"));
        assertTrue(ReportProtocol.isValidValue("[]"));
        assertFalse(ReportProtocol.validKey("{\"broken\":"));
        assertFalse(ReportProtocol.isValidValue("{\"a\":\"" + "x".repeat(300)));
    }

    @Test
    public void multiProcessHitSequenceKeepsHistoricalMaximum() {
        long merged = 0L;
        merged = ReportProtocol.mergeHit(merged, 5L);  // 主进程首次回传
        merged = ReportProtocol.mergeHit(merged, 9L);  // :play 进程回传
        merged = ReportProtocol.mergeHit(merged, 7L);  // 主进程再次回传
        merged = ReportProtocol.mergeHit(merged, -1L); // 异常负值不覆盖
        assertEquals("多进程 hit 只能取历史最大值", 9L, merged);
    }

    /**
     * 旧会话落盘的 skip 必须被本次会话的空串覆盖，否则 failedFeatureCount
     * 会一直把已恢复正常的功能算作失败，横幅无法归零。
     *
     * 本文件只锁协议函数（mergeText/mergeHit）本身的返回值；provider 写入路径
     * （会话判定、新会话清库、空串落地）已下沉为 StatusProvider.applyReport，
     * 由 StatusProviderTest 直接执行生产代码，不在此复刻。
     */
    @Test
    public void emptyTextOverwritesStaleSkipAndResetsFailure() {
        String regKey = ReportProtocol.PREFIX_REG + Feature.FEED_GOODS_CARD_BLOCK.key;
        String skipKey = ReportProtocol.PREFIX_SKIP + Feature.FEED_GOODS_CARD_BLOCK.key;
        Map<String, String> store = new HashMap<>();
        store.put(regKey, "");
        store.put(skipKey, "类未找到 m7d.s"); // 上一次目标进程会话的残留
        long oldHit = ReportProtocol.mergeHit(0L, 7L);

        assertEquals("旧状态应计入失败", 1, failedCount(store.get(regKey), store.get(skipKey), oldHit));

        // 本次会话成功注册：snapshot 发送 reg=label、skip=""，provider 按合并函数落地。
        String regAfter = ReportProtocol.mergeText(store.get(regKey), "ExploreView#g0");
        String skipAfter = ReportProtocol.mergeText(store.get(skipKey), "");
        assertEquals("最新 reg 必须覆盖旧值", "ExploreView#g0", regAfter);
        assertEquals("空串必须覆盖旧会话残留的 skip，否则横幅永久误报", "", skipAfter);
        // 本次没有新 hit 回传：空值不能覆盖历史 hit。
        long hitAfter = ReportProtocol.mergeHit(oldHit, 0L);

        store.put(regKey, regAfter);
        store.put(skipKey, skipAfter);
        assertEquals("", store.get(skipKey));
        assertEquals("旧 hit 必须保留", 7L, hitAfter);
        assertEquals("横幅计数应归零", 0,
                failedCount(store.get(regKey), store.get(skipKey), hitAfter));
    }

    @Test
    public void missingRegStillCountsAsFailureWhenReported() {
        assertEquals(1, failedCount("", "方法未找到 x", 0L));
        assertEquals(1, failedCount("", "", 3L)); // 有 hit 回传但无注册标签
        assertEquals(0, failedCount("ExploreView#g0", "", 3L));
        assertEquals(0, failedCount(null, null, 0L)); // 无回传不计入
    }

    /** 可选锚点（方法）缺失：已有主锚点注册时不算失败，skip 仍保留给诊断页。 */
    @Test
    public void optionalSkipWithRegistrationIsNotFailure() {
        assertFalse(ReportProtocol.featureFailed("ExploreView#g0", "方法未找到 x", false, 0L));
        assertFalse(ReportProtocol.featureFailed("ExploreView#g0|vha.s#Pd", "方法未找到 x", false, 3L));
    }

    /** 主锚点（类）缺失：即使已有其它注册标签，也必须计入失败。 */
    @Test
    public void criticalSkipIsFailure() {
        assertTrue(ReportProtocol.featureFailed("ExploreView#g0", "类未找到 x", true, 0L));
        assertTrue(ReportProtocol.featureFailed("ExploreView#g0", "类未找到 x", true, 5L));
        assertTrue("critical 标记本身就算有回传",
                ReportProtocol.featureFailed("", "", true, 0L));
    }

    /** 没有注册标签仍然是明确失败，与 critical 标记无关。 */
    @Test
    public void missingRegIsFailureEvenWithoutCriticalSkip() {
        assertTrue(ReportProtocol.featureFailed("", "方法未找到 x", false, 0L));
        assertTrue(ReportProtocol.featureFailed("", "", false, 3L));
        assertFalse(ReportProtocol.featureFailed("", "", false, 0L));
    }

    @Test
    public void isCriticalOnlyAcceptsMarkerValue() {
        assertTrue(ReportProtocol.isCritical("1"));
        assertFalse(ReportProtocol.isCritical(""));
        assertFalse(ReportProtocol.isCritical(null));
        assertFalse(ReportProtocol.isCritical("0"));
        assertFalse(ReportProtocol.isCritical("true"));
    }

    @Test
    public void isFreshHonorsTtlAndMissingTimestamp() {
        long now = 10_000_000L;
        long ttl = ReportProtocol.DEFAULT_TTL_MS;
        assertTrue(ReportProtocol.isFresh(now - 60_000L, now, ttl));
        assertTrue("恰好等于 TTL 仍算新鲜", ReportProtocol.isFresh(now - ttl, now, ttl));
        assertFalse(ReportProtocol.isFresh(now - ttl - 1L, now, ttl));
        assertFalse("从未回传视为不新鲜", ReportProtocol.isFresh(0L, now, ttl));
        assertFalse(ReportProtocol.isFresh(-1L, now, ttl));
        assertTrue("小幅时钟回拨（5 分钟内）仍按新鲜处理",
                ReportProtocol.isFresh(now + 60_000L, now, ttl));
    }

    /** 时钟回拨/未来时间戳：updatedAt 领先超过 5 分钟视为过期，不再当新鲜。 */
    @Test
    public void isFreshRejectsFutureTimestampsBeyondGrace() {
        long now = 10_000_000L;
        long ttl = ReportProtocol.DEFAULT_TTL_MS;
        assertTrue("恰好领先 5 分钟仍在容忍范围内",
                ReportProtocol.isFresh(now + ReportProtocol.MAX_FUTURE_SKEW_MS, now, ttl));
        assertFalse("未来时间戳超过 5 分钟必须视为过期",
                ReportProtocol.isFresh(now + ReportProtocol.MAX_FUTURE_SKEW_MS + 1L, now, ttl));
        assertFalse("时钟大幅回拨后旧 updatedAt 不能一直新鲜",
                ReportProtocol.isFresh(now + 60 * 60 * 1000L, now, ttl));
    }

    /**
     * 会话按身份比较：字符串不同即新会话（清库后接受），相同即同进程；
     * 不再按毫秒比大小丢弃整批——时钟回拨后新会话的回传不能被静默丢掉。
     * 缺失 session（旧版回传）按同会话兼容合并：不清库、不丢弃（与 CMT/QM 一致）。
     */
    @Test
    public void sessionComparisonUsesIdentityNotOrdering() {
        // 无已存会话：带 session 的首次回传算新会话；旧版无 session 不算新。
        assertTrue(ReportProtocol.isNewSession(null, "200"));
        assertTrue(ReportProtocol.isNewSession("", "200"));
        assertFalse(ReportProtocol.isNewSession(null, null));
        assertFalse(ReportProtocol.isNewSession("100", "100"));

        // 身份比较：不同即新会话，与数值大小无关（时钟回拨场景）。
        assertTrue("回拨后较小的毫秒也必须算新会话", ReportProtocol.isNewSession("200", "100"));

        // 缺失 session 的旧版回传：同会话兼容合并，绝不能被判成“丢弃/清库”。
        assertFalse("无 session 的回传不能触发新会话清库",
                ReportProtocol.isNewSession("200", null));
        assertFalse(ReportProtocol.isNewSession("200", ""));
        assertFalse(ReportProtocol.isNewSession(null, null));

        assertTrue(ReportProtocol.isNewSession("100", "200"));
        assertTrue(ReportProtocol.isNewSession("200", "100"));
    }

    /** 旧格式（纯数字毫秒）会话同样按“不同即新会话”处理，模块 App 与目标进程可各自升级。 */
    @Test
    public void legacyNumericSessionsFollowIdentityComparison() {
        assertTrue("旧格式不同毫秒 = 不同进程 = 新会话",
                ReportProtocol.isNewSession("1700000000000", "1699999999999"));
        assertFalse("旧格式相同毫秒 = 同进程，重复上报不清库",
                ReportProtocol.isNewSession("1700000000000", "1700000000000"));
        assertTrue("旧格式 → 新格式：字符串不同即新会话",
                ReportProtocol.isNewSession("1700000000000", "s1700000000000-0a1b2c3d"));
        assertTrue("新格式 → 旧格式：字符串不同即新会话",
                ReportProtocol.isNewSession("s1700000000000-0a1b2c3d", "1700000000001"));
    }

    /** 时钟回拨：新会话（随机后缀）与已存 session 不同 → 按新会话接受，不再整批丢弃。 */
    @Test
    public void clockRollbackSessionIsAcceptedAsNewSession() {
        String stored = "s2000000000000-0a1b2c3d";
        String incoming = "s1000000000000-9f8e7d6c";
        assertTrue("回拨后新会话必须被接受", ReportProtocol.isNewSession(stored, incoming));
        assertTrue("回拨后首带 session 的回传会重置旧会话数据",
                ReportProtocol.isNewSession(stored, "s3000000000000-12345678"));
    }

    /** 同会话重复上报：session 相同 = 同进程，不触发清库，按正常合并处理。 */
    @Test
    public void duplicateReportsInSameSessionAreNotNewSession() {
        String session = "s1700000000000-0a1b2c3d";
        assertFalse(ReportProtocol.isNewSession(session, session));
    }

    /**
     * 导出诊断包文本的纯拼装（{@link DiagnosticsFragment#joinExportText}）：
     * 锁定 模块版本/系统/框架 API/目标版本/功能状态/最近命中/最近崩溃 关键段
     * 齐全且顺序稳定。Fragment 的 Android 依赖无法在 JVM 构造，与协议同层的
     * 静态拼装逻辑放在这里直接测。
     */
    @Test
    public void exportTextContainsAllKeySections() {
        String text = DiagnosticsFragment.joinExportText(
                "静盒 0.9.2\n系统: Android 15 (API 35)\n框架 API: 102\n"
                        + "目标版本: 9.52.0 (9520807)\n数据来自目标进程回传 · 刷新时间 12:00",
                Collections.singletonList("AD-01 启动页开屏广告跳过: registered · hit 3 · skip —"),
                Collections.singletonList("AD-01 启动页开屏广告跳过: hit 3"),
                "crash0 1000|main|java.lang.NullPointerException|at a.B.d(B.java:1)|at e.F.h(F.java:2)\n",
                "—");

        assertTrue(text.contains("静盒 0.9.2"));
        assertTrue("必须含系统版本段", text.contains("系统: Android 15 (API 35)"));
        assertTrue("必须含框架 API 段", text.contains("框架 API: 102"));
        assertTrue("必须含目标版本段", text.contains("目标版本: 9.52.0 (9520807)"));
        assertTrue("必须含功能状态段", text.contains("[功能状态]\n"
                + "AD-01 启动页开屏广告跳过: registered · hit 3 · skip —\n"));
        assertTrue("必须含最近命中段", text.contains("[最近命中]\n"
                + "AD-01 启动页开屏广告跳过: hit 3\n"));
        assertTrue("必须含最近崩溃段", text.contains("[最近崩溃]\n"
                + "crash0 1000|main|java.lang.NullPointerException|at a.B.d(B.java:1)|at e.F.h(F.java:2)\n"));
        assertTrue("关键段顺序必须稳定：功能状态 < 最近命中 < 最近崩溃",
                text.indexOf("[功能状态]") < text.indexOf("[最近命中]")
                        && text.indexOf("[最近命中]") < text.indexOf("[最近崩溃]"));
    }

    /** 导出文本空数据段：最近命中/最近崩溃无数据时以 noData 占位。 */
    @Test
    public void exportTextUsesNoDataMarkerForEmptySections() {
        String text = DiagnosticsFragment.joinExportText("静盒 0.9.2",
                Arrays.asList("AD-01 启动页开屏广告跳过: —"),
                Collections.emptyList(), "", "—");

        assertTrue(text.contains("[功能状态]\nAD-01 启动页开屏广告跳过: —\n"));
        assertTrue("最近命中无数据必须占位", text.contains("[最近命中]\n—\n"));
        assertTrue("最近崩溃无数据必须占位", text.contains("[最近崩溃]\n—\n"));
        assertFalse("无崩溃时不得出现 crash 行", text.contains("crash0"));
    }

    @Test
    public void awaitOrCancelReturnsCompletedValue() {
        FutureTask<String> task = new FutureTask<>(() -> "ok");
        task.run();
        assertEquals("ok", ReportProtocol.awaitOrCancel(task, 1_000L));
    }

    @Test
    public void awaitOrCancelTimesOutAndCancels() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        FutureTask<String> task = new FutureTask<>(() -> {
            latch.await();
            return "late";
        });
        Thread worker = new Thread(task, "report-protocol-test");
        worker.setDaemon(true);
        worker.start();
        try {
            assertNull("超时必须返回 null，不能抛出", ReportProtocol.awaitOrCancel(task, 50L));
            assertTrue("超时后必须取消 future", task.isCancelled());
        } finally {
            latch.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    private static int failedCount(String reg, String skip, long hit) {
        return ReportProtocol.featureFailed(reg, skip, false, hit) ? 1 : 0;
    }
}
