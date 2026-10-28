package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * CopyLinkHooks.sanitizeCopy / isXhsHost / isCopyLabel 的 JVM 单测。
 *
 * 误伤修复（Phase 4）：剪贴板写入点覆盖全 App，旧实现对所有单条文本 URL 都删
 * share_id/xhsshare/shareRedId/apptime/sid，导致在 XHS 内复制外部链接
 * （如 https://example.com/?sid=abc）也被改坏。现在只对小红书 host 或
 * label=ug-share-copy-link 的复制净化，未知 host 原样放行。
 */
public class CopyLinkHooksTest {

    private static final String LABEL = "ug-share-copy-link";

    @Test
    public void xhsNoteLink_removesTracking_keepsXsecTokenByDefault() {
        String in = "https://www.xiaohongshu.com/discovery/item/66f?"
                + "share_id=abc&xhsshare=Weixin&shareRedId=xyz&apptime=1700000000"
                + "&sid=deadbeef&xsec_token=SECRET&foo=bar";
        assertEquals("https://www.xiaohongshu.com/discovery/item/66f?xsec_token=SECRET&foo=bar",
                CopyLinkHooks.sanitizeCopy(in, null, false));
    }

    @Test
    public void xhsNoteLink_stripsXsecTokenWhenEnabled() {
        String in = "https://www.xiaohongshu.com/discovery/item/66f?xsec_token=SECRET&sid=1";
        assertEquals("https://www.xiaohongshu.com/discovery/item/66f",
                CopyLinkHooks.sanitizeCopy(in, null, true));
    }

    @Test
    public void xhsLinks_shortDomainsAndSubdomains_areSanitized() {
        assertEquals("https://xhslink.com/a/b",
                CopyLinkHooks.sanitizeCopy("https://xhslink.com/a/b?sid=1", null, false));
        assertEquals("https://xhslink.cn/o/abc",
                CopyLinkHooks.sanitizeCopy("https://xhslink.cn/o/abc?share_id=1", null, false));
        assertEquals("https://xhs.cn/x",
                CopyLinkHooks.sanitizeCopy("https://xhs.cn/x?apptime=1", null, false));
        assertEquals("http://m.xiaohongshu.com/x",
                CopyLinkHooks.sanitizeCopy("http://m.xiaohongshu.com/x?sid=9", null, false));
        // scheme / host 大小写、显式端口、userinfo 都不影响 host 判断。
        assertEquals("HTTPS://WWW.XIAOHONGSHU.COM/x",
                CopyLinkHooks.sanitizeCopy(
                        "HTTPS://WWW.XIAOHONGSHU.COM/x?sid=9", null, false));
        assertEquals("https://www.xiaohongshu.com:8443/x",
                CopyLinkHooks.sanitizeCopy(
                        "https://www.xiaohongshu.com:8443/x?sid=9", null, false));
    }

    @Test
    public void xhsLink_keepsFragmentAndNonTrackingParams() {
        String in = "https://www.xiaohongshu.com/x?foo=1&sid=2&bar=%E4%B8%AD#frag";
        assertEquals("https://www.xiaohongshu.com/x?foo=1&bar=%E4%B8%AD#frag",
                CopyLinkHooks.sanitizeCopy(in, null, false));
    }

    @Test
    public void nonXhsLink_isUntouched() {
        String[] urls = {
                "https://example.com/?sid=abc",
                "https://example.com/?sid=abc&apptime=1&xsec_token=t",
                // 相似域名不算 XHS：必须是精确域名或点分隔子域。
                "https://xiaohongshu.com.evil.com/?sid=1",
                "https://notxiaohongshu.com/?sid=1",
                "https://example.com/path#sid=1",
        };
        for (String url : urls) {
            assertSame(url, CopyLinkHooks.sanitizeCopy(url, null, false));
            assertSame(url, CopyLinkHooks.sanitizeCopy(url, "copy", false));
        }
    }

    @Test
    public void nonUrlText_isUntouched_evenWithCopyLabel() {
        String[] texts = {
                "hello world",
                "hello?sid=1",
                "note: https://example.com/?sid=1",
                "ftp://example.com/?sid=1",
                "www.xiaohongshu.com/x?sid=1",
        };
        for (String text : texts) {
            assertSame(text, CopyLinkHooks.sanitizeCopy(text, null, false));
            assertSame(text, CopyLinkHooks.sanitizeCopy(text, LABEL, false));
        }
        assertNull(CopyLinkHooks.sanitizeCopy(null, LABEL, false));
        assertEquals("", CopyLinkHooks.sanitizeCopy("", LABEL, false));
    }

    @Test
    public void copyLabel_fallback_sanitizesUnknownHost() {
        String in = "https://example.com/share?sid=abc&foo=1";
        assertEquals("https://example.com/share?foo=1",
                CopyLinkHooks.sanitizeCopy(in, LABEL, false));
        // redutils/q.a 的 label 可能带前缀/后缀，沿用 contains 语义。
        assertEquals("https://example.com/share?foo=1",
                CopyLinkHooks.sanitizeCopy(in, "xhs:" + LABEL + ":v2", false));
    }

    @Test
    public void copyLabel_nearMiss_isNotEnough() {
        String in = "https://example.com/?sid=1";
        assertSame(in, CopyLinkHooks.sanitizeCopy(in, "ug-share-copy", false));
        assertSame(in, CopyLinkHooks.sanitizeCopy(in, "", false));
    }

    @Test
    public void isXhsHost_matchesDomainAndDotSubdomainOnly() {
        assertTrue(CopyLinkHooks.isXhsHost("xiaohongshu.com"));
        assertTrue(CopyLinkHooks.isXhsHost("www.xiaohongshu.com"));
        assertTrue(CopyLinkHooks.isXhsHost("XHSLink.com"));
        assertTrue(CopyLinkHooks.isXhsHost("xhslink.cn"));
        assertTrue(CopyLinkHooks.isXhsHost("xhs.cn"));
        // 尾点（FQDN 写法）归一化后仍匹配。
        assertTrue(CopyLinkHooks.isXhsHost("www.xiaohongshu.com."));
        assertFalse(CopyLinkHooks.isXhsHost("xiaohongshu.com.evil.com"));
        assertFalse(CopyLinkHooks.isXhsHost("notxiaohongshu.com"));
        assertFalse(CopyLinkHooks.isXhsHost("xhslink.com.cn"));
        assertFalse(CopyLinkHooks.isXhsHost("example.com"));
        assertFalse(CopyLinkHooks.isXhsHost(null));
    }

    /** 防止重构成私有/实例方法后测试静默失效。 */
    @Test
    public void pureHelpers_arePackagePrivateStatic() throws Exception {
        Method copy = CopyLinkHooks.class.getDeclaredMethod("sanitizeCopy",
                String.class, CharSequence.class, boolean.class);
        Method host = CopyLinkHooks.class.getDeclaredMethod("isXhsHost", String.class);
        Method label = CopyLinkHooks.class.getDeclaredMethod("isCopyLabel", CharSequence.class);
        for (Method m : new Method[]{copy, host, label}) {
            int mods = m.getModifiers();
            assertTrue(m.getName() + " must be static", Modifier.isStatic(mods));
            assertTrue(m.getName() + " 应保持 package-private，供同包测试直接调用",
                    (mods & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) == 0);
        }
    }
}
