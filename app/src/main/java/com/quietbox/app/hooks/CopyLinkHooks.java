package com.quietbox.app.hooks;

import android.content.ClipData;
import android.content.ClipDescription;

import androidx.annotation.Nullable;

import com.quietbox.app.Feature;
import com.quietbox.app.anchor.AnchorKeys;
import com.quietbox.app.HookState;

import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * PV-03 复制链接净化。
 *
 * 2026-10-16 DexSQL 复核（内部记录）：
 * 旧实现挂在混淆类 e2c.i 上（跨版本会被 R8 重命名，脆弱），且只覆盖 note 详情页。
 * 真实链路：所有"复制链接"表面 → Lk92/r.a(ctx,"ug-share-copy-link",text)
 *   → Lp52/a → Lq7b/e.b（全 App 剪贴板写入收口）
 *   → SPI IClipboardProxy.setPrimaryClip → ClipboardProxyImpl.setPrimaryClip（非混淆实现）。
 * 9.52.1：String 收口为 k92/r，写入点为 q7b/e（9.52.0 为 com.xingin.android.redutils.q
 * 与 jv8/e；9.51.0 为 o92/q、u0b/e，9.50.0 为 j92/q、e0b/e）。两处方法名/签名均不变；
 * 注意 q7b.e 在 9.52.0 是另一个只有 <init>/finalize 的类，必须按方法形状而不是类名判定。
 *
 * 新挂载点（按稳定性排序）：
 * 1) 主挂 ClipboardProxyImpl#setPrimaryClip —— 非混淆、剪贴板写入唯一收口，
 *    覆盖所有复制链接表面及未来新增；按 ClipData 文本净化，非 URL / 无追踪参数时原样返回。
 * 2) 副挂 k92/r#a —— 所有"复制链接"的 String 收口（label 过滤 "ug-share-copy-link"），
 *    String 级精确净化，与旧 urlArgHook 同构。
 * 旧 e2c.i 硬编码已移除（不再依赖混淆类名）。
 *
 * 净化规则：只对小红书域名（xiaohongshu.com / xhslink.com / xhslink.cn / xhs.cn
 * 及其子域）或 label 为 "ug-share-copy-link" 的复制生效，未知 host 原样放行
 * （剪贴板写入点是全 App 收口，用户复制外部链接时不能被误删 sid 等通用参数）。
 * 默认只删纯追踪参数（share_id/xhsshare/shareRedId/apptime/sid，
 * 与源码 addShareParams:526-530 写入的键一致）；xsec_token 是访问令牌，默认保留，
 * 由 p1_9_strip_xsec_token 子选项控制。
 */
final class CopyLinkHooks {

    private static final String COPY_LABEL = "ug-share-copy-link";

    private static final String[] TRACKING = {"share_id", "xhsshare", "shareRedId", "apptime", "sid"};
    private static final String XSEC_TOKEN = "xsec_token";

    /** 小红书链接域名（含子域）；只有这些 host 的复制默认参与净化。 */
    private static final String[] XHS_HOSTS = {"xiaohongshu.com", "xhslink.com", "xhslink.cn", "xhs.cn"};

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
            // 主挂：真正的剪贴板写入点 q7b/e.b。ClipboardProxyImpl.setPrimaryClip 只是 SPI
            // 回调（只打日志、不写剪贴板），在那里改 ClipData 对剪贴板零效果，已弃用。
            Method write = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.COPY_LINK_CLIPBOARD_WRITE, true);
            HookKit.install(module, f, "q7b.e#b(setPrimaryClip)",
                    write, clipDataHook(module, f, stripXsecToken));

            // 副挂/兜底：所有"复制链接"的 String 收口。主挂是混淆类，被 R8 重命名时靠它兜底。
            Method a = HookKit.anchorMethod(cl, AnchorKeys.COPY_LINK_FUNNEL);
            HookKit.install(module, f, "k92/r#a", a, funnelHook(module, f, stripXsecToken));

            HookState.note(f, "剪贴板写入点 q7b.e#b（主挂）+ 复制链接 String 收口 k92/r#a（兜底，label=" + COPY_LABEL + "）");
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
                HookKit.error(module, f.id + " intercept error q7b.e#b", t);
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
        String clean = sanitizeCopy(raw, label, stripToken);
        if (clean.equals(raw)) {
            return null;
        }
        HookKit.hit(module, f, "ClipboardProxyImpl removed=" + removedKeys(raw, stripToken));
        ClipDescription src = clip.getDescription();
        // 保留原 MIME 列表与 htmlText：newPlainText 会把 MIME 固定为 text/plain 且不设
        // html，原条目的 text/html（富文本）粘贴到富文本目标会退化成纯文本。
        // Item(text, htmlText) 自 API 16（minSdk 29，无需版本守卫）；htmlText 为 null
        // 时等价于不设置。原 item 的 Intent/Uri 维持旧行为不带走。
        ClipData out = new ClipData(new ClipDescription(label,
                        preservedMimes(readMimeTypes(src))),
                new ClipData.Item(clean, preserveHtml(
                        clip.getItemAt(0).getHtmlText(), raw, clean)));
        if (src != null && src.getExtras() != null) {
            out.getDescription().setExtras(
                    new android.os.PersistableBundle(src.getExtras()));
        }
        return out;
    }

    /** ClipDescription.getMimeTypes() 是 @hide，只能按 getMimeTypeCount/getMimeType 逐个读出。 */
    @Nullable
    private static String[] readMimeTypes(@Nullable ClipDescription src) {
        if (src == null) {
            return null;
        }
        try {
            int n = src.getMimeTypeCount();
            String[] out = new String[n];
            for (int i = 0; i < n; i++) {
                out[i] = src.getMimeType(i);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 净化后重建 ClipData 的 MIME 列表：原样保留原列表（text/html 等不丢），缺
     * text/plain 时补上——净化后的文本是纯文本，只认 text/plain 的粘贴目标仍要能看到。
     * 原列表为空时退回纯 text/plain（与 newPlainText 等价）。纯函数，供 JVM 单测直接调用。
     */
    static String[] preservedMimes(@Nullable String[] src) {
        if (src == null || src.length == 0) {
            return new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN};
        }
        for (String m : src) {
            if (ClipDescription.MIMETYPE_TEXT_PLAIN.equals(m)) {
                return src;
            }
        }
        String[] out = new String[src.length + 1];
        System.arraycopy(src, 0, out, 0, src.length);
        out[src.length] = ClipDescription.MIMETYPE_TEXT_PLAIN;
        return out;
    }

    /**
     * 原 item 带 htmlText 时的处理：格式原样保留，但其中的原始 URL 全部替换成净化后的
     * URL，避免富文本粘贴把追踪参数带回去（净化只落在纯文本上就等于没净化富文本面）。
     * 原 item 无 htmlText 时返回 null（Item 构造传 null 等价于不设置）。
     * 纯函数，供 JVM 单测直接调用。
     */
    @Nullable
    static String preserveHtml(@Nullable CharSequence html, String raw, String clean) {
        if (html == null || html.length() == 0) {
            return null;
        }
        return html.toString().replace(raw, clean);
    }

    /** 副挂：k92/r.a(Context, label, text)，只对 label == "ug-share-copy-link" 的复制链接生效。 */
    private static XposedInterface.Hooker funnelHook(XposedModule module, Feature f, boolean stripToken) {
        return chain -> {
            Object[] replaced = null;
            try {
                List<Object> args = chain.getArgs();
                if (args.size() >= 3) {
                    Object label = args.get(1);
                    Object text = args.get(2);
                    seen(module, f, "k92/r.a label=" + label);
                    CharSequence csLabel = label instanceof CharSequence ? (CharSequence) label : null;
                    if (text instanceof String && isCopyLabel(csLabel)) {
                        String raw = (String) text;
                        String clean = sanitizeCopy(raw, csLabel, stripToken);
                        if (!clean.equals(raw)) {
                            replaced = args.toArray();
                            replaced[2] = clean;
                            HookKit.hit(module, f, "k92/r.a removed=" + removedKeys(raw, stripToken));
                        }
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error k92/r.a", t);
            }
            return replaced == null ? chain.proceed() : chain.proceed(replaced);
        };
    }

    /**
     * 复制内容净化入口：只有小红书 host 或 copy-link label 才净化；未知 host、
     * 非 http(s) URL 文本一律原样返回。纯函数，供 JVM 单测直接调用。
     */
    @Nullable
    static String sanitizeCopy(@Nullable String text, @Nullable CharSequence label,
                               boolean stripXsecToken) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String host = hostOf(text);
        if (host == null) {
            // 非 http(s) URL 文本（普通文本、file: 等）：不动。
            return text;
        }
        if (!isXhsHost(host) && !isCopyLabel(label)) {
            return text;
        }
        return sanitize(text, stripXsecToken);
    }

    /** label 是否为小红书"复制链接"动作的标记（ClipData label 兜底用）。 */
    static boolean isCopyLabel(@Nullable CharSequence label) {
        return label != null && label.toString().contains(COPY_LABEL);
    }

    /** host（大小写不敏感、允许 FQDN 尾点）是否属于小红书域名；未知 host 返回 false。 */
    static boolean isXhsHost(@Nullable String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        for (String domain : XHS_HOSTS) {
            if (h.equals(domain) || h.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取 http(s) URL 的 host（去 userinfo / 端口，IPv6 去方括号）；非 URL、
     * 非 http(s) 或 authority 为空时返回 null。手工解析不经 android.net.Uri，
     * 保证纯 JVM 单测可运行。
     */
    @Nullable
    private static String hostOf(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        int sep = s.indexOf("://");
        if (sep <= 0) {
            return null;
        }
        String scheme = s.substring(0, sep);
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return null;
        }
        int start = sep + 3;
        int end = start;
        while (end < s.length()) {
            char c = s.charAt(end);
            if (c == '/' || c == '?' || c == '#') {
                break;
            }
            end++;
        }
        String authority = s.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) {
                return null;
            }
            authority = authority.substring(1, close);
        } else {
            int colon = authority.indexOf(':');
            if (colon >= 0) {
                authority = authority.substring(0, colon);
            }
        }
        return authority.isEmpty() ? null : authority;
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

    /**
     * 参数名解码（仅用于和 TRACKING 常量比较）。用 JDK URLDecoder 而不是
     * android.net.Uri#decode：后者在 JVM 单测里是返回默认值的 stub，且不经
     * Android 运行时也无从验证。解码失败时退回原串，避免把合法参数误判。
     */
    private static String dec(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        try {
            return URLDecoder.decode(s, "UTF-8");
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
