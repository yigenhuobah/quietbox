package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.ReportProtocol;

import org.junit.Test;

import java.util.Map;

/**
 * Report.snapshot 的键完整性：reg/skip 对每个 touched Feature 都必须存在（可为空串），
 * hit 只在 >0 时出现。回归「旧会话 skip 残留导致横幅永久误报」的问题。
 *
 * HookState 是进程级静态状态：每个用例使用不同 Feature，避免测试之间互相污染。
 */
public class ReportTest {

    @Test
    public void snapshotSendsEmptySkipForRegisteredFeatureWithoutSkip() {
        Feature f = Feature.FEED_LIVE_CARD_BLOCK;
        HookState.installed(f, "alpha");

        Map<String, String> snap = Report.snapshot();

        assertEquals("alpha", snap.get(ReportProtocol.PREFIX_REG + f.key));
        assertTrue("无 skip 也必须发送 skip 键", snap.containsKey(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("空串才能覆盖旧 skip", "", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
        assertFalse("hit 为 0 时不发送，避免多余写入", snap.containsKey(ReportProtocol.PREFIX_HIT + f.key));
    }

    @Test
    public void snapshotSendsEmptyRegForSkipOnlyFeature() {
        Feature f = Feature.FEED_VOTE_CARD_BLOCK;
        HookState.markNotFound(f, "类未找到 x");

        Map<String, String> snap = Report.snapshot();

        assertTrue("未注册也必须发送 reg 键", snap.containsKey(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("空串才能覆盖旧注册标签", "", snap.get(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("类未找到 x", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
    }

    @Test
    public void snapshotIncludesEnabledButNotInstalled() {
        Feature f = Feature.SEARCH_AD_FILTER;
        HookState.enable(f);

        Map<String, String> snap = Report.snapshot();

        assertEquals("enable 后必须发送空 reg，清掉旧会话标签", "",
                snap.get(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("enable 后必须发送空 skip，清掉旧会话 skip", "",
                snap.get(ReportProtocol.PREFIX_SKIP + f.key));
    }

    @Test
    public void snapshotKeepsPositiveHit() {
        Feature f = Feature.FEED_GOODS_CARD_BLOCK;
        HookState.installed(f, "beta");
        HookState.hit(f);
        HookState.hit(f);

        Map<String, String> snap = Report.snapshot();

        assertEquals("2", snap.get(ReportProtocol.PREFIX_HIT + f.key));
        assertTrue(snap.containsKey(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
    }
}
