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
 * P1-7 广告外跳拦截。
 *
 * 链路（2026-10-16 DexSQL 复核，报告 E:\tmp-xhs-mod\agy-scratch\verify_p1_7.md）：
 *   广告落地页 WebView（ExternalWebViewActivityV2）触发三方 scheme
 *     → Llzd/l0.L → Lp0e/i0.b（唯一汇聚点）
 *         ├─ Path A：AdRedirectInfo.c()(showXhsOpenAppDialog)==true
 *         │    → IAdvertProxy.gotoExtApp → AdvertSpiImpl.gotoExtApp → startActivity
 *         └─ Path B：showXhsOpenAppDialog==false 或 AdRedirectInfo==null
 *              → XYUriUtils → Context.startActivity（不经 gotoExtApp）
 *
 * 之前注释"gotoExtApp 全源码无调用点"是错的：搜的是实现类的直接调用（SPI 无直接 invoke），
 * 真实调用方 invoke 的是接口 IAdvertProxy。已核实调用方存在（Lp0e/i0.b 等）。
 * gotoExtApp 仍是广告专用 SPI 的动作方法，跳过原方法体即阻断 Path A。
 * Path B 走 XYUriUtils，gotoExtApp 拦不到，故补挂汇聚点 Lp0e/i0.b：
 *   仅在 ctx 是 ExternalWebViewActivityV2 且 scheme 非 http(s) 时返回 false（未处理），
 *   让落地页留在 WebView 内，不拉起外部 App；普通 http(s) 导航不受影响。
 */
final class ExtAppHooks {

    private static final String CLAZZ = "com.xingin.advert.spi.AdvertSpiImpl";
    private static final String METHOD = "gotoExtApp";

    /** 广告落地页 WebView 三方 URL 的唯一汇聚点。 */
    private static final String CONVERGE = "p0e.i0";

    private static final String EXT_WEBVIEW = "com.xingin.xywebview.activity.ExternalWebViewActivityV2";

    private ExtAppHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.EXT_APP_BLOCK;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            Method m = HookKit.findMethod(module, f, cls, METHOD);
            HookKit.install(module, f, METHOD, m, HookKit.skipVoid(module, f, METHOD));

            Class<?> i0 = HookKit.findClass(module, f, cl, CONVERGE);
            if (i0 != null) {
                // b(Uri, Context, AdRedirectInfo, boolean, boolean)：后三参是混淆/实体类型，
                // 用前导参数 (Uri, Context) 定位。
                Method b = HookKit.findByShape(i0, "b", Uri.class, Context.class);
                HookKit.install(module, f, "p0e.i0#b", b, convergeHook(module, f));
            }

            HookState.note(f, "gotoExtApp（Path A）+ 落地页外跳汇聚点 p0e.i0#b（Path B，限 ExternalWebViewActivityV2 非 http(s)）");
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
                        && isExternalWebView(ctx.getClass())
                        && isAppScheme(((Uri) uri).getScheme())) {
                    HookKit.hit(module, f, "p0e.i0#b blocked scheme=" + ((Uri) uri).getScheme());
                    return Boolean.FALSE;
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error p0e.i0#b", t);
            }
            return chain.proceed();
        };
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
