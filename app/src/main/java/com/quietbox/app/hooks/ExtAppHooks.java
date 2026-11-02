package com.quietbox.app.hooks;

import android.content.Context;
import android.net.Uri;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * AD-04 广告外跳拦截。
 *
 * 链路（2026-10-16 DexSQL 复核，内部记录；
 * 9.52.0 汇聚点恢复为真实类名 XYWebViewUtil，方法签名不变；9.51.0 为 k1e/i0，
 * 9.50.0 为 p0e/i0）：
 *   广告落地页 WebView（ExternalWebViewActivityV2）触发三方 scheme
 *     → Llzd/l0.L → LXYWebViewUtil.b（唯一汇聚点）
 *         ├─ Path A：AdRedirectInfo.c()(showXhsOpenAppDialog)==true
 *         │    → IAdvertProxy.gotoExtApp → AdvertSpiImpl.gotoExtApp → startActivity
 *         └─ Path B：showXhsOpenAppDialog==false 或 AdRedirectInfo==null
 *              → XYUriUtils → Context.startActivity（不经 gotoExtApp）
 *
 * 之前注释"gotoExtApp 全源码无调用点"是错的：搜的是实现类的直接调用（SPI 无直接 invoke），
 * 真实调用方 invoke 的是接口 IAdvertProxy。已核实调用方存在（9.52.0 为
 * Lcom/xingin/xywebview/util/XYWebViewUtil;.b，9.50.0 为 Lp0e/i0.b，9.51.0 为 Lk1e/i0.b）。
 * gotoExtApp 仍是广告专用 SPI 的动作方法，跳过原方法体即阻断 Path A。
 * Path B 走 XYUriUtils，gotoExtApp 拦不到，故补挂汇聚点 XYWebViewUtil.b：
 *   仅在「ctx 是 ExternalWebViewActivityV2 且第 3 参 AdRedirectInfo 非空（广告落地页，
 *   普通外链 H5 宿主把它置 null，见 ExternalWebViewActivityV2.Qe()）且 scheme 非 http(s)」
 *   时返回 false（未处理），让广告落地页留在 WebView 内，不拉起外部 App；
 *   tel:/mailto:/weixin:// 等普通外链 H5 外跳与 http(s) 导航原样放行。
 */
final class ExtAppHooks {

    private static final String CLAZZ = "com.xingin.advert.spi.AdvertSpiImpl";
    private static final String METHOD = "gotoExtApp";

    /** 广告落地页 WebView 三方 URL 的唯一汇聚点。9.52.0 为真实类名；9.51.0 为 k1e.i0，9.50.0 为 p0e.i0。 */
    private static final String CONVERGE = "com.xingin.xywebview.util.XYWebViewUtil";

    private static final String EXT_WEBVIEW = "com.xingin.xywebview.activity.ExternalWebViewActivityV2";

    private ExtAppHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.EXT_APP_BLOCK;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ, true);
            Method m = HookKit.findMethod(module, f, cls, METHOD);
            HookKit.install(module, f, METHOD, m, HookKit.skipVoid(module, f, METHOD));

            Class<?> util = HookKit.findClass(module, f, cl, CONVERGE, true);
            if (util != null) {
                // b(Uri, Context, AdRedirectInfo, boolean, boolean)：后三参是混淆/实体类型，
                // 用前导参数 (Uri, Context) 定位。9.52.0 该方法为 static。
                // AD-04 只拦广告落地页：第 3 参必须是 AdRedirectInfo。形状不符宁可放弃
                // 这个汇聚点（Path A 的 gotoExtApp 仍生效），也不退回「无广告信息也全拦」。
                Method b = HookKit.findByShape(module, f, util, "b", Uri.class, Context.class);
                if (b != null && hasAdRedirectInfoParam(b)) {
                    HookKit.install(module, f, "XYWebViewUtil#b", b, convergeHook(module, f));
                } else if (b != null) {
                    HookKit.warn(module, f.id
                            + " skip: XYWebViewUtil#b 第 3 参非 AdRedirectInfo，汇聚点不安装");
                    HookState.note(f, "XYWebViewUtil#b 形状不符，未安装");
                }
            }

            HookState.note(f, "gotoExtApp（Path A）+ 落地页外跳汇聚点 XYWebViewUtil#b（Path B，限广告落地页 ExternalWebViewActivityV2 非 http(s)）");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    private static XposedInterface.Hooker convergeHook(XposedModule module, Feature f) {
        return chain -> {
            try {
                Object uri = chain.getArg(0);
                Object ctx = chain.getArg(1);
                if (uri instanceof Uri && ctx != null
                        && shouldBlock(ctx.getClass(),
                                ((Uri) uri).getScheme(), chain.getArg(2))) {
                    HookKit.hit(module, f, "XYWebViewUtil#b blocked scheme=" + ((Uri) uri).getScheme());
                    return Boolean.FALSE;
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error XYWebViewUtil#b", t);
            }
            return chain.proceed();
        };
    }

    /**
     * AD-04 拦截判定：必须同时满足
     * 1) 第 3 参 AdRedirectInfo 非空——只有广告落地页才带。宿主 zyd.i0.b 对 null 走
     *    确认弹窗 / XYUriUtils 正常外跳（普通外链 H5 的 tel:/mailto:/weixin:// 等），
     *    只有 adRedirectInfo != null 才走 gotoExtApp 广告外跳，故以此区分广告页与普通外链页；
     * 2) 宿主是 ExternalWebViewActivityV2；
     * 3) scheme 不在白名单（要拉起外部 App 的三方 scheme）。
     */
    static boolean shouldBlock(Class<?> ctxClass, String scheme, Object adRedirectInfo) {
        return adRedirectInfo != null
                && isExternalWebView(ctxClass)
                && isAppScheme(scheme);
    }

    /** 汇聚点第 3 参必须是 AdRedirectInfo，否则该版本的参数形状已变，不能按广告页判定。 */
    static boolean hasAdRedirectInfoParam(Method b) {
        Class<?>[] params = b.getParameterTypes();
        return params.length > 2
                && params[2].getName().endsWith(".AdRedirectInfo");
    }

    private static boolean isExternalWebView(Class<?> cls) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            if (EXT_WEBVIEW.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 放行的站内 / 系统 scheme 白名单：http(s) 等常规 WebView 导航，file/about/javascript/data/blob
     * 等内嵌内容，以及站内路由（xhsdiscover/xhsapp/xhs）和 content。只有白名单外的 scheme
     * （taobao:// openapp.jdmobile:// snssdk1128:// 等）才视为"拉起外部 App"。
     * 用白名单而不是黑名单，避免漏掉未列举的站内 scheme 被误拦。
     */
    private static final Set<String> ALLOWED_SCHEMES = new HashSet<>(Arrays.asList(
            "http", "https", "file", "about", "javascript", "data", "blob",
            "xhsdiscover", "xhsapp", "xhs", "content"));

    private static boolean isAppScheme(String scheme) {
        if (scheme == null || scheme.isEmpty()) {
            return false;
        }
        return !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
    }
}
