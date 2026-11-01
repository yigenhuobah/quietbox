package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import org.junit.Test;

import java.util.Arrays;

/**
 * StatusProvider 落盘核心的直接覆盖：call() 里的会话判定、新会话清库与合并写入
 * 原样下沉在 {@link StatusProvider#applyReport}（包级可见的纯 JVM 函数），
 * 这里用 {@link FakeSharedPreferences} 执行生产代码路径，不再复刻写入逻辑。
 *
 * 覆盖口径（改动必须同步更新本测试）：
 * - 新会话必须清掉上一会话的 reg/skip/hit/crit，否则横幅按旧残留永久误报；
 * - 同会话回传的空串必须覆盖旧 skip（provider 停写空串时本测试必须变红）；
 * - hit 取历史最大值，负数/更小值不覆盖；
 * - 无 session 的旧版回传按「同会话兼容」合并进当前数据：不清库、不丢弃
 *   （与 CMT/QM 语义一致），首个带 session 的回传仍会重置旧数据。
 *
 * call() 剩余的 Binder / PackageManager 胶水（UID 校验、Context 判空、method
 * 分发）依赖 Android 框架，仍需 Robolectric/androidTest，不在本文件范围。
 */
public class StatusProviderTest {

    private static final String SESSION = ReportProtocol.KEY_META_SESSION;
    private static final String UPDATED_AT = ReportProtocol.KEY_META_UPDATED_AT;

    private static final String REG =
            ReportProtocol.PREFIX_REG + Feature.FEED_GOODS_CARD_BLOCK.key;
    private static final String SKIP =
            ReportProtocol.PREFIX_SKIP + Feature.FEED_GOODS_CARD_BLOCK.key;
    private static final String HIT =
            ReportProtocol.PREFIX_HIT + Feature.FEED_GOODS_CARD_BLOCK.key;
    private static final String CRIT =
            ReportProtocol.PREFIX_CRIT + Feature.FEED_GOODS_CARD_BLOCK.key;

    /** 与 StatusProvider 写盘一致：所有值都带 "v:" 前缀。 */
    private static String v(String key) {
        return "v:" + key;
    }

    /** 新会话首传：清掉上一会话全部逐功能数据，再按本批落地（含空串覆盖）。 */
    @Test
    public void newSessionClearsStaleSessionDataAndAppliesIncomingBatch() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        sp.edit().putString(v(SESSION), "s1000-aaaaaaaa").apply();
        sp.edit()
                .putString(v(REG), "")
                .putString(v(SKIP), "类未找到 m7d.s")
                .putLong(v(HIT), 7L)
                .putString(v(CRIT), "1")
                .apply();

        Bundle result = StatusProvider.applyReport(sp,
                new String[]{SESSION, UPDATED_AT, REG, SKIP, CRIT},
                new String[]{"s2000-bbbbbbbb", "5000", "ExploreView#g0", "", ""});

        assertNotNull(result);
        assertEquals("会话标识按本批覆盖", "s2000-bbbbbbbb", sp.getString(v(SESSION), null));
        assertEquals("updated_at 落盘为 long", 5000L, sp.getLong(v(UPDATED_AT), 0L));
        assertEquals("新 reg 必须覆盖旧值", "ExploreView#g0", sp.getString(v(REG), null));
        assertEquals("旧会话残留的 skip 必须被清掉", "", sp.getString(v(SKIP), null));
        assertEquals("旧 critical 标记必须被空串覆盖", "", sp.getString(v(CRIT), null));
        assertEquals("新会话必须清掉旧 hit，从 0 重新开始", 0L, sp.getLong(v(HIT), 0L));

        // 清库 + 空串覆盖后，横幅计数必须归零（这就是「漏清旧会话」要防的误报）。
        assertFalse("残留清理后功能不再计入失败横幅",
                ReportProtocol.featureFailed(sp.getString(v(REG), null),
                        sp.getString(v(SKIP), null),
                        ReportProtocol.isCritical(sp.getString(v(CRIT), null)),
                        sp.getLong(v(HIT), 0L)));
    }

    /** 同会话重复回传：空串覆盖旧 skip，hit 只取历史最大值（生产 mergeText/mergeHit 路径）。 */
    @Test
    public void sameSessionBatchOverwritesTextAndKeepsMaxHit() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        sp.edit().putString(v(SESSION), "s2000-bbbbbbbb").apply();
        sp.edit()
                .putString(v(REG), "ExploreView#g0")
                .putString(v(SKIP), "方法未找到 x")
                .putLong(v(HIT), 9L)
                .apply();

        Bundle result = StatusProvider.applyReport(sp,
                new String[]{SESSION, UPDATED_AT, REG, SKIP, HIT},
                new String[]{"s2000-bbbbbbbb", "6000", "ExploreView#g0", "", "5"});

        assertNotNull(result);
        assertEquals("同会话空串必须覆盖旧 skip，否则横幅永久误报",
                "", sp.getString(v(SKIP), null));
        assertEquals("同会话 hit 不能被更小值覆盖", 9L, sp.getLong(v(HIT), 0L));
        assertEquals("同会话 reg 按最新值覆盖", "ExploreView#g0", sp.getString(v(REG), null));
        assertEquals(6000L, sp.getLong(v(UPDATED_AT), 0L));
    }

    /** 负数 hit 按生产 mergeHit 折算为 0，不覆盖已存计数。 */
    @Test
    public void nonPositiveHitDoesNotOverwriteExistingCount() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        sp.edit().putString(v(SESSION), "s3000-cccccccc").putLong(v(HIT), 3L).apply();

        assertNotNull(StatusProvider.applyReport(sp,
                new String[]{SESSION, HIT},
                new String[]{"s3000-cccccccc", "-5"}));

        assertEquals(3L, sp.getLong(v(HIT), 0L));
    }

    /** 旧版回传（无 meta.session）按同会话兼容合并进当前数据，不再整批丢弃。 */
    @Test
    public void legacyBatchWithoutSessionIsMergedAsSameSession() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        sp.edit().putString(v(SESSION), "s2000-bbbbbbbb").apply();
        sp.edit().putString(v(REG), "keep").putLong(v(UPDATED_AT), 1111L).apply();

        Bundle result = StatusProvider.applyReport(sp,
                new String[]{REG, SKIP, UPDATED_AT},
                new String[]{"intruder", "旧版无 session 的覆盖", "2222"});

        assertNotNull(result);
        assertEquals("无 session 的回传按同会话兼容合并，不能整批丢弃",
                "intruder", sp.getString(v(REG), null));
        assertEquals("skip 同样按最新值落地", "旧版无 session 的覆盖", sp.getString(v(SKIP), null));
        assertEquals("updated_at 同样按最新值刷新", 2222L, sp.getLong(v(UPDATED_AT), 0L));
        assertEquals("已存会话标识不受无 session 批次影响",
                "s2000-bbbbbbbb", sp.getString(v(SESSION), null));
    }

    /** 空库上的旧版回传没有可保护的会话，正常接受。 */
    @Test
    public void legacyBatchWithoutSessionIsAcceptedOnFreshStore() {
        FakeSharedPreferences sp = new FakeSharedPreferences();

        assertNotNull(StatusProvider.applyReport(sp,
                new String[]{REG, HIT},
                new String[]{"ExploreView#g0", "4"}));

        assertEquals("ExploreView#g0", sp.getString(v(REG), null));
        assertEquals(4L, sp.getLong(v(HIT), 0L));
        assertNull("旧版回传不落 session 标识", sp.getString(v(SESSION), null));
    }

    /** 未知键 / 不可解析的 hit 与 updated_at 跳过；全无效批次不触发任何写盘。 */
    @Test
    public void invalidEntriesAreSkippedAndAllInvalidBatchWritesNothing() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        sp.edit().putLong(v(UPDATED_AT), 1111L).apply();

        assertNotNull(StatusProvider.applyReport(sp,
                new String[]{"meta.unknown", HIT, UPDATED_AT},
                new String[]{"x", "not-a-number", "not-a-number"}));
        assertFalse("未知键必须拒绝", sp.contains(v("meta.unknown")));
        assertFalse("hit 不可解析必须跳过", sp.contains(v(HIT)));
        assertEquals("updated_at 非数字不能覆盖旧值", 1111L, sp.getLong(v(UPDATED_AT), 0L));

        assertNotNull(StatusProvider.applyReport(sp,
                new String[]{UPDATED_AT}, new String[]{"0"}));
        assertEquals("updated_at <= 0 不落盘", 1111L, sp.getLong(v(UPDATED_AT), 0L));
    }

    /** 批次形状：null / 长度不匹配 / 超过 MAX_ITEMS(800) 拒绝；恰好 800 个接受。 */
    @Test
    public void malformedAndOversizedBatchesAreRejected() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        assertNull(StatusProvider.applyReport(sp, null, null));
        assertNull(StatusProvider.applyReport(sp, new String[]{REG}, new String[]{}));

        String[] oversizedKeys = new String[801];
        String[] oversizedValues = new String[801];
        Arrays.fill(oversizedKeys, REG);
        Arrays.fill(oversizedValues, "x");
        assertNull("超过 MAX_ITEMS 上限整批拒绝",
                StatusProvider.applyReport(sp, oversizedKeys, oversizedValues));

        String[] atLimitKeys = new String[800];
        String[] atLimitValues = new String[800];
        Arrays.fill(atLimitKeys, REG);
        Arrays.fill(atLimitValues, "ExploreView#g0");
        assertNotNull("恰好 800 个仍接受", StatusProvider.applyReport(sp, atLimitKeys, atLimitValues));
        assertEquals("ExploreView#g0", sp.getString(v(REG), null));
    }

    /** session 值非法（null）的本批视为无 session：同会话兼容合并，且不能覆盖已存会话标识。 */
    @Test
    public void nullSessionValueIsRecognizedAsSessionLessAndMergedCompatibly() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        sp.edit().putString(v(SESSION), "s2000-bbbbbbbb").apply();

        // session 值为 null：isValidValue 拒绝 → 本批视为无 session 回传（同会话兼容）。
        assertNotNull(StatusProvider.applyReport(sp,
                new String[]{SESSION, REG},
                new String[]{null, "intruder"}));
        assertEquals("null session 不能让批次获得身份，已存会话保持不变",
                "s2000-bbbbbbbb", sp.getString(v(SESSION), null));
        assertEquals("无 session 批次按同会话兼容合并，不再整批丢弃",
                "intruder", sp.getString(v(REG), null));
    }

    /** crash 回传键走 reg/skip 的文本覆盖路径：最新值落地，空串可清掉旧崩溃。 */
    @Test
    public void crashKeysFollowTextMergePath() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        assertNotNull(StatusProvider.applyReport(sp,
                new String[]{SESSION, ReportProtocol.KEY_CRASH_0, ReportProtocol.KEY_CRASH_1},
                new String[]{"s4000-dddddddd",
                        "1000|main|java.lang.NullPointerException|at a.b.C.d(C.java:1)|at e.f.G.h(G.java:2)",
                        ""}));

        assertEquals("1000|main|java.lang.NullPointerException|at a.b.C.d(C.java:1)|at e.f.G.h(G.java:2)",
                sp.getString("v:" + ReportProtocol.KEY_CRASH_0, null));
        assertEquals("无崩溃槽位以空串落地", "", sp.getString("v:" + ReportProtocol.KEY_CRASH_1, null));

        // 下一次目标进程会话无崩溃：空串覆盖必须清掉上一会话的崩溃记录。
        assertNotNull(StatusProvider.applyReport(sp,
                new String[]{ReportProtocol.KEY_CRASH_0},
                new String[]{""}));
        assertEquals("空串必须清掉旧会话崩溃残留", "",
                sp.getString("v:" + ReportProtocol.KEY_CRASH_0, null));
    }

    /** 写侧与读侧（reportString/reportHit）使用同一 "v:" 前缀契约。 */
    @Test
    public void writePathUsesSameValuePrefixAsReadHelpers() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        assertNotNull(StatusProvider.applyReport(sp,
                new String[]{REG, HIT}, new String[]{"ExploreView#g0", "2"}));
        // reportString(context, PREFIX_REG, key) 读 "v:reg.<key>"，reportHit 读 "v:hit.<key>"。
        assertEquals("ExploreView#g0", sp.getString(
                "v:" + StatusProvider.PREFIX_REG + Feature.FEED_GOODS_CARD_BLOCK.key, null));
        assertEquals(2L, sp.getLong(
                "v:" + StatusProvider.PREFIX_HIT + Feature.FEED_GOODS_CARD_BLOCK.key, 0L));
    }
}
