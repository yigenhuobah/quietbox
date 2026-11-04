package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * AD-02 信息流广告过滤。
 *
 * 9.52.0 的信息流广告有两种来源（2026-10-29 DexSQL 复核；9.51.0 为 l8d.m0/g8d.s，
 * 9.50.0 为 r7d.m0/m7d.s）：
 *
 * 1) 开屏关联的首页第 2 位广告（2-pos）：客户端自己插，链路是
 *    AdsHomeFeedManagerServiceImpl.handleFeedDataIfNeed → recordAdsNoteData
 *    → … → ExploreView.g0 插入。这一种旧 hook（只跳 handleFeedDataIfNeed）能覆盖。
 *
 * 2) **普通信息流广告**：服务端直接下发 NoteItemBean.isAd=true，客户端没有"插入广告"
 *    的方法可拦，只能在列表层过滤。旧 hook 对这类完全无效。
 *
 * 本类两层都做：
 * - 2-pos：跳 handleFeedDataIfNeed + recordAdsNoteData，并守卫 ExploreView.g0。
 * - 普通广告：在 ExploreView.S / ExploreView.C0 / vha.s.Pd 调用原方法前，从入参
 *   ArrayList 移除 isAd==true 的项。只动列表内容，不动游标，分页不受影响。
 *
 * 9.52.0 方法名映射（同一方法体、同一签名形状，DexSQL 反汇编比对）：
 *   l8d/m0#Q  → ExploreView#S   （日志 "appendResponseNoteWithCache"）
 *   l8d/m0#A0 → ExploreView#C0  （日志 "showRefreshNoteList"）
 *   l8d/m0#e0 → ExploreView#g0  （插入动画，调用 getRecyclerView）
 *   g8d/s#Yd  → vha/s#Pd        （Z, ArrayList, 回调 三参不变）
 *
 * isAd 是 NoteItemBean 的**字段**（不是 getter）：Lcom/xingin/entities/NoteItemBean;->isAd:Z。
 */
final class FeedAdHooks {

    /** 按 NoteItemBean 子类缓存 isAd 字段；找不到时缓存 null 避免反复反射。 */
    private static final Map<Class<?>, Field> IS_AD = new HashMap<>();

    /** 诊断用：每个入口只记一次"被调用过"，确认挂载点真的在链路上。 */
    private static final java.util.Set<String> SEEN = new java.util.HashSet<>();

    private FeedAdHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.FEED_AD_FILTER;
        try {
            installTwoPosition(module, cl, f);
            installListFilter(module, cl, f);
            HookState.note(f, "2-pos（handleFeedDataIfNeed/recordAdsNoteData/ExploreView.g0）+ 普通广告列表过滤（ExploreView.S/C0、vha.s.Pd）");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    /** 开屏关联的第 2 位广告：客户端自己插的那一种。 */
    private static void installTwoPosition(XposedModule module, ClassLoader cl, Feature f) {
        Method handle = HookKit.findAnchorMethod(module, f, cl,
                AnchorKeys.FEED_AD_HANDLE_IF_NEED, true);
        HookKit.install(module, f, "handleFeedDataIfNeed", handle,
                HookKit.skipVoid(module, f, "handleFeedDataIfNeed"));

        Method record = HookKit.findAnchorMethod(module, f, cl,
                AnchorKeys.FEED_AD_RECORD_NOTE, true);
        HookKit.install(module, f, "recordAdsNoteData", record,
                HookKit.skipVoid(module, f, "recordAdsNoteData"));

        Method insert = HookKit.findAnchorMethod(module, f, cl,
                AnchorKeys.FEED_AD_INSERT_GUARD, true);
        HookKit.install(module, f, "ExploreView.g0", insert, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object note = chain.getArg(1);
                    if (note != null && isAd(f, note)) {
                        HookKit.hit(module, f, "ExploreView.g0 blocked ad insert");
                        return null;
                    }
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error ExploreView.g0", t);
                }
                return chain.proceed();
            }
        });
    }

    /** 普通信息流广告：服务端下发 isAd，只能在列表层过滤。 */
    private static void installListFilter(XposedModule module, ClassLoader cl, Feature f) {
        HookKit.install(module, f, "ExploreView.S",
                HookKit.findAnchorMethod(module, f, cl, AnchorKeys.FEED_AD_APPEND_FILTER, true),
                dropAdsHook(module, f, "ExploreView.S", 0));
        HookKit.install(module, f, "ExploreView.C0",
                HookKit.findAnchorMethod(module, f, cl, AnchorKeys.FEED_AD_REFRESH_FILTER, true),
                dropAdsHook(module, f, "ExploreView.C0", 0));
        // Pd(boolean isRefresh, ArrayList pageData, 回调)：9.51.0 起列表从参数 0 移到参数 1
        // （参数 0 变成 isRefresh 布尔），列表经回调 onSuccess 交给 ExploreView.C0。
        HookKit.install(module, f, "vha.s.Pd",
                HookKit.findAnchorMethod(module, f, cl, AnchorKeys.FEED_AD_PAGER_FILTER, true),
                dropAdsHook(module, f, "vha.s.Pd", 1));
    }

    /** 在调用原方法前，把入参 List 里的广告项删掉。 */
    private static XposedInterface.Hooker dropAdsHook(XposedModule module, Feature f,
                                                      String where, int argIndex) {
        return chain -> {
            try {
                Object arg = chain.getArg(argIndex);
                if (arg instanceof List) {
                    List<?> list = (List<?>) arg;
                    seenOnce(module, f, where, list.size());
                    int removed = dropAds(module, f, where, list);
                    if (removed > 0) {
                        HookKit.hit(module, f, where + " removed=" + removed);
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error " + where, t);
            }
            return chain.proceed();
        };
    }

    private static void seenOnce(XposedModule module, Feature f, String where, int size) {
        synchronized (SEEN) {
            if (!SEEN.add(where)) {
                return;
            }
        }
        HookKit.info(module, f.id + " seen " + where + " size=" + size);
    }

    private static int dropAds(XposedModule module, Feature f, String where, List<?> list) {
        int removed = 0;
        try {
            Iterator<?> it = list.iterator();
            while (it.hasNext()) {
                Object note = it.next();
                if (note != null && isAd(f, note)) {
                    it.remove();
                    removed++;
                }
            }
        } catch (Throwable t) {
            // 不可变列表等异常：原样放行，不影响原逻辑；但必须记日志，否则该入口的
            // AD-02/AD-08/09/10 永久静默失效且无任何痕迹（HookKit 约定 2：catch 后记日志）。
            HookKit.error(module, f.id + " dropAds failed " + where, t);
        }
        return removed;
    }

    private static boolean isAd(Feature f, Object note) {
        Class<?> cls = note.getClass();
        try {
            Field fld;
            synchronized (IS_AD) {
                if (IS_AD.containsKey(cls)) {
                    fld = IS_AD.get(cls);
                } else {
                    fld = resolveIsAd(cls);
                    IS_AD.put(cls, fld);
                    if (fld == null) {
                        // 每个类只记一次（null 也进缓存）：字段改名后该入口会永久
                        // 静默失效，必须留下可诊断的原因。
                        HookState.markNotFound(f, "field:isAd 未找到 " + cls.getName());
                    }
                }
            }
            return fld != null && fld.getBoolean(note);
        } catch (Throwable t) {
            HookState.markNotFound(f, "field:isAd 读取失败 " + cls.getName());
            return false;
        }
    }

    private static Field resolveIsAd(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("isAd");
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }
}
