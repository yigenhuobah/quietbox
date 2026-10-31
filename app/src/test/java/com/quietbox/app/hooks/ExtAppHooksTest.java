package com.quietbox.app.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;

/**
 * ExtAppHooks.shouldBlock / hasAdRedirectInfoParam 的 JVM 单测（发现 #61 回归）：
 *
 * 旧汇聚点判定只看「Context 是 ExternalWebViewActivityV2 + scheme 不在白名单」，
 * 完全不判断是否广告页：普通外链 H5（AdRedirectInfo 为 null 的宿主页面）里的
 * tel:/mailto:/weixin:///alipays:// 等正常外跳被一并掐断，超出 AD-04
 * 「广告落地页不拉起外部 App」的声明范围。新判定必须：
 * <ul>
 *   <li>AdRedirectInfo（第 3 参）非空才拦——只有广告落地页才带；
 *       普通外链页宿主把它置 null（ExternalWebViewActivityV2.Qe() 读 intent 的
 *       AdRedirectData，无该 extra 即 null），宿主 zyd.i0.b 对 null 走确认弹窗/正常外跳；</li>
 *   <li>白名单 scheme（http(s)/站内路由）即便在广告页也放行；</li>
 *   <li>宿主不是 ExternalWebViewActivityV2 时一律放行；</li>
 *   <li>汇聚点形状校验：第 3 参非 .AdRedirectInfo 结尾时不得按广告页判定。</li>
 * </ul>
 *
 * shouldBlock 的宿主类名判定用测试源集里的同名替身
 * （com.xingin.xywebview.activity.ExternalWebViewActivityV2，仅单测可见）。
 */
public class ExtAppHooksTest {

    /** 同名替身的子类：isExternalWebView 沿父类链匹配名字，子类必须命中。 */
    static class FakeExtWebView extends com.xingin.xywebview.activity.ExternalWebViewActivityV2 {
    }

    /** 形状正确的替身：b(Uri, Context, AdRedirectInfo, boolean, boolean)。 */
    static class ShapeWithAdInfo {
        static boolean b(android.net.Uri uri, android.content.Context ctx,
                         AdRedirectInfo adInfo, boolean z, boolean z2) {
            return false;
        }
    }

    /** 形状不符的替身：第 3 参不是广告信息类型。 */
    static class ShapeWithOtherParam {
        static boolean b(android.net.Uri uri, android.content.Context ctx,
                         String other, boolean z, boolean z2) {
            return false;
        }
    }

    private static Method bOf(Class<?> cls, Class<?> third) throws Exception {
        return cls.getDeclaredMethod("b",
                android.net.Uri.class, android.content.Context.class, third,
                boolean.class, boolean.class);
    }

    @Test
    public void blocksAdLandingPageAppScheme() {
        // 广告落地页（AdRedirectInfo 非空）+ 三方 scheme：AD-04 声明范围内的拦截。
        assertTrue(ExtAppHooks.shouldBlock(FakeExtWebView.class, "taobao", new AdRedirectInfo()));
        assertTrue(ExtAppHooks.shouldBlock(FakeExtWebView.class, "openapp.jdmobile", new AdRedirectInfo()));
    }

    @Test
    public void normalExternalLinksOnPlainH5Pass() {
        // 发现 #61 的回归核心：普通外链 H5（AdRedirectInfo == null）的
        // tel:/mailto:/weixin:///alipays:// 等正常外跳不再被掐断。
        Object[] noAdInfo = {null};
        for (String scheme : new String[]{"tel", "mailto", "weixin", "alipays", "market", "taobao"}) {
            assertFalse("scheme=" + scheme + " 在非广告页必须放行",
                    ExtAppHooks.shouldBlock(FakeExtWebView.class, scheme, noAdInfo[0]));
        }
    }

    @Test
    public void whitelistedSchemesPassEvenOnAdPage() {
        AdRedirectInfo adInfo = new AdRedirectInfo();
        for (String scheme : new String[]{"http", "https", "file", "about", "javascript",
                "data", "blob", "xhsdiscover", "xhsapp", "xhs", "content"}) {
            assertFalse("scheme=" + scheme + " 在白名单内必须放行",
                    ExtAppHooks.shouldBlock(FakeExtWebView.class, scheme, adInfo));
        }
    }

    @Test
    public void nonExternalWebViewHostNeverBlocks() {
        // 宿主不是 ExternalWebViewActivityV2（例如普通页面 WebView）：一律放行。
        assertFalse(ExtAppHooks.shouldBlock(Object.class, "taobao", new AdRedirectInfo()));
    }

    @Test
    public void nullOrEmptySchemeNeverBlocks() {
        AdRedirectInfo adInfo = new AdRedirectInfo();
        assertFalse(ExtAppHooks.shouldBlock(FakeExtWebView.class, null, adInfo));
        assertFalse(ExtAppHooks.shouldBlock(FakeExtWebView.class, "", adInfo));
    }

    @Test
    public void adInfoParamShapeIsVerifiedAtInstall() throws Exception {
        Method ok = bOf(ShapeWithAdInfo.class, AdRedirectInfo.class);
        assertTrue("第 3 参为 AdRedirectInfo 的形状必须通过校验",
                ExtAppHooks.hasAdRedirectInfoParam(ok));

        Method wrong = bOf(ShapeWithOtherParam.class, String.class);
        assertFalse("第 3 参非 AdRedirectInfo 的形状必须被拒（否则按广告页判定会误拦）",
                ExtAppHooks.hasAdRedirectInfoParam(wrong));
    }
}
