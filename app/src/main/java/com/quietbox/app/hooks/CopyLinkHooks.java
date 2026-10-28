package com.quietbox.app.hooks;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * PV-03 复制链接净化。
 *
 * 2026-10-16 DexSQL 复核（报告 E:\tmp-xhs-mod\agy-scratch\verify_p1_9.md）：
 * 旧实现挂在混淆类 e2c.i 上（跨版本会被 R8 重命名，脆弱），且只覆盖 note 详情页。
 * 真实链路：所有"复制链接"表面 → Lredutils/q.a(ctx,"ug-share-copy-link",text)
 *   → Lp52/a → Ljv8/e.b（全 App 剪贴板写入收口）
 *   → SPI IClipboardProxy.setPrimaryClip → ClipboardProxyImpl.setPrimaryClip（非混淆实现）。
 * 9.52.0：String 收口恢复为真实类名 com.xingin.android.redutils.q（9.51.0 为 o92/q，
 * 9.50.0 为 j92/q）；写入点改名为 jv8/e（9.51.0 为 u0b/e，9.50.0 为 e0b/e）。
 * 两处方法名/签名均不变。
 *
 * 新挂载点（按稳定性排序）：
 * 1) 主挂 ClipboardProxyImpl#setPrimaryClip —— 非混淆、剪贴板写入唯一收口，
 *    覆盖所有复制链接表面及未来新增；按 ClipData 文本净化，非 URL / 无追踪参数时原样返回。
 * 2) 副挂 redutils/q#a —— 所有"复制链接"的 String 收口（label 过滤 "ug-share-copy-link"），
 *    String 级精确净化，与旧 urlArgHook 同构。
 * 旧 e2c.i 硬编码已移除（不再依赖混淆类名）。
 *
 * 净化规则：默认只删纯追踪参数（share_id/xhsshare/shareRedId/apptime/sid，
 * 与源码 addShareParams:526-530 写入的键一致）；xsec_token 是访问令牌，默认保留，
 * 由 p1_9_strip_xsec_token 子选项控制。
 */
final class CopyLinkHooks {

    /** 剪贴板真正写入点：jv8/e.b(ClipboardManager, ClipData) —— 全 APK 唯一调 ClipboardManager.setPrimaryClip 的地方。9.51.0 为 u0b.e。 */
    private static final String CLIP_WRITER = "jv8.e";

    /** 所有"复制链接"的 String 收口（9.52.0 恢复真实类名，作主挂失效时的兜底）。9.51.0 为 o92.q。 */
    private static final String COPY_FUNNEL = "com.xingin.android.redutils.q";
    private static final String COPY_LABEL = "ug-share-copy-link";

    private static final String[] TRACKING = {"share_id", "xhsshare", "shareRedId", "apptime", "sid"};
    private static final String XSEC_TOKEN = "xsec_token";

    /** 诊断用：每个 label 只记一次"见到剪贴板写入"，避免刷屏，也便于确认真实链路。 */
    private static final Set<String> SEEN = new HashSet<>();

    private CopyLinkHooks() {
    }

    private static void seen(XposedModule module, Feature f, String detail) {
        synchronized (SEEN) {
            if (SEEN.size() > 256) {
                SEEN.clear();
            }
            if (!SEEN.add(detail)) {
                return;
            }
        }
        HookKit.info(module, f.id + " seen " + detail);
    }

    static void install(XposedModule module, ClassLoader cl, boolean stripXsecToken) {
        Feature f = Feature.COPY_LINK_SANITIZE;
        try {
            // 主挂：真正的剪贴板写入点 jv8/e.b。ClipboardProxyImpl.setPrimaryClip 只是 SPI
            // 回调（只打日志、不写剪贴板），在那里改 ClipData 对剪贴板零效果，已弃用。
            Class<?> writer = HookKit.findClass(module, f, cl, CLIP_WRITER);
            Method write = HookKit.findMethod(module, f, writer, "b",
                    ClipboardManager.class, ClipData.class);
            HookKit.install(module, f, "jv8.e#b(setPrimaryClip)",
                    write, clipDataHook(module, f, stripXsecToken));

            // 副挂/兜底：所有"复制链接"的 String 收口。主挂是混淆类，被 R8 重命名时靠它兜底。
            Class<?> funnel = HookKit.findClass(module, f, cl, COPY_FUNNEL);
            Method a = HookKit.findMethodQuiet(module, f, funnel, "a",
                    Context.class, CharSequence.class, CharSequence.class);
            HookKit.install(module, f, "redutils/q#a", a, funnelHook(module, f, stripXsecToken));

            HookState.note(f, "剪贴板写入点 jv8.e#b（主挂）+ 复制链接 String 收口 redutils/q#a（兜底，label=" + COPY_LABEL + "）");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    /** 主挂：改写即将写入剪贴板的 ClipData 文本。仅处理单条纯文本，其余原样放行。 */
    private static XposedInterface.Hooker clipDataHook(XposedModule module, Feature f, boolean stripToken) {
        return chain -> {
            Object[] replaced = null;
            try {
                List<Object> args = chain.getArgs();
                Object data = args.size() > 1 ? args.get(1) : null;
                if (data instanceof ClipData) {
                    ClipData clean = sanitizeClip(module, f, (ClipData) data, stripToken);
                    if (clean != null) {
                        replaced = args.toArray();
                        replaced[1] = clean;
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error jv8.e#b", t);
            }
            return replaced == null ? chain.proceed() : chain.proceed(replaced);
        };
    }

    private static ClipData sanitizeClip(XposedModule module, Feature f, ClipData clip, boolean stripToken) {
        if (clip == null || clip.getItemCount() != 1) {
            return null;
        }
        CharSequence label = clip.getDescription() == null ? null : clip.getDescription().getLabel();
        CharSequence text = clip.getItemAt(0).getText();
        seen(module, f, "clip label=" + label);
        if (!(text instanceof String)) {
            return null;
        }
        String raw = (String) text;
        String clean = sanitize(raw, stripToken);
        if (clean.equals(raw)) {
            return null;
        }
        HookKit.hit(module, f, "ClipboardProxyImpl removed=" + removedKeys(raw, stripToken));
            ClipData out = ClipData.newPlainText(label, clean);
            ClipDescription src = clip.getDescription();
            if (src != null && src.getExtras() != null) {
                out.getDescription().setExtras(
                        new android.os.PersistableBundle(src.getExtras()));
            }
            return out;
    }

    /** 副挂：redutils/q.a(Context, label, text)，只对 label == "ug-share-copy-link" 的复制链接生效。 */
    private static XposedInterface.Hooker funnelHook(XposedModule module, Feature f, boolean stripToken) {
        return chain -> {
            Object[] replaced = null;
            try {
                List<Object> args = chain.getArgs();
                if (args.size() >= 3) {
                    Object label = args.get(1);
                    Object text = args.get(2);
                    seen(module, f, "redutils/q.a label=" + label);
                    if (label instanceof CharSequence && text instanceof String
                            && label.toString().contains(COPY_LABEL)) {
                        String raw = (String) text;
                        String clean = sanitize(raw, stripToken);
                        if (!clean.equals(raw)) {
                            replaced = args.toArray();
                            replaced[2] = clean;
                            HookKit.hit(module, f, "redutils/q.a removed=" + removedKeys(raw, stripToken));
                        }
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error redutils/q.a", t);
            }
            return replaced == null ? chain.proceed() : chain.proceed(replaced);
        };
    }

    static String sanitize(String url, boolean stripXsecToken) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        try {
            // 直接在原始串上切分，不经 Uri.getQuery()：后者返回解码后的串，
            // 再拿它去 url.indexOf 会在保留参数含 %XX / + 时定位错位。
            int hash = url.indexOf('#');
            String head = hash < 0 ? url : url.substring(0, hash);
            String frag = hash < 0 ? "" : url.substring(hash);
            int q = head.indexOf('?');
            if (q < 0) {
                return url;
            }
            String rawQuery = head.substring(q + 1);
            if (rawQuery.isEmpty()) {
                return url;
            }
            String kept = dropTracking(rawQuery, stripXsecToken);
            if (kept == null) {
                return url;
            }
            String base = head.substring(0, q);
            return (kept.isEmpty() ? base : base + "?" + kept) + frag;
        } catch (Throwable t) {
            return url;
        }
    }

    /** 保留非追踪参数的 query 片段（原编码不动）；没有命中任何追踪参数时返回 null。 */
    @Nullable
    private static String dropTracking(String rawQuery, boolean stripXsecToken) {
        StringBuilder sb = new StringBuilder();
        boolean removed = false;
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (isTracking(dec(name), stripXsecToken)) {
                removed = true;
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(pair);
        }
        return removed ? sb.toString() : null;
    }

    private static String dec(String s) {
        try {
            return Uri.decode(s);
        } catch (Throwable t) {
            return s;
        }
    }

    private static String removedKeys(String url, boolean stripXsecToken) {
        if (url == null) {
            return "";
        }
        try {
            int hash = url.indexOf('#');
            String head = hash < 0 ? url : url.substring(0, hash);
            int q = head.indexOf('?');
            if (q < 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (String pair : head.substring(q + 1).split("&", -1)) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                if (isTracking(dec(name), stripXsecToken)) {
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(name);
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean isTracking(String name, boolean stripXsecToken) {
        if (name == null) {
            return false;
        }
        if (XSEC_TOKEN.equalsIgnoreCase(name)) {
            return stripXsecToken;
        }
        for (String key : TRACKING) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
