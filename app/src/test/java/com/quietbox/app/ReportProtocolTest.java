package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * StatusProvider 的纯 Java 协议规则：键白名单、值长度、hit 最大值合并。
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
    public void validKeyAcceptsAll72GeneratedKeys() {
        int count = 0;
        for (Feature f : Feature.values()) {
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_REG + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_HIT + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_SKIP + f.key));
            count += 3;
        }
        assertEquals(72, count);
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

        // 本次会话成功注册：snapshot 发送 reg=label、skip=""，provider 用最新值覆盖。
        store.put(regKey, ReportProtocol.mergeText(store.get(regKey), "m0#e0"));
        store.put(skipKey, ReportProtocol.mergeText(store.get(skipKey), ""));
        // 本次没有新 hit 回传：空值不能覆盖历史 hit。
        long hitAfter = ReportProtocol.mergeHit(oldHit, 0L);

        assertEquals("", store.get(skipKey));
        assertEquals("旧 hit 必须保留", 7L, hitAfter);
        assertEquals("横幅计数应归零", 0,
                failedCount(store.get(regKey), store.get(skipKey), hitAfter));
    }

    @Test
    public void missingRegStillCountsAsFailureWhenReported() {
        assertEquals(1, failedCount("", "方法未找到 x", 0L));
        assertEquals(1, failedCount("", "", 3L)); // 有 hit 回传但无注册标签
        assertEquals(0, failedCount("m0#e0", "", 3L));
        assertEquals(0, failedCount(null, null, 0L)); // 无回传不计入
    }

    private static int failedCount(String reg, String skip, long hit) {
        return ReportProtocol.featureFailed(reg, skip, hit) ? 1 : 0;
    }
}
