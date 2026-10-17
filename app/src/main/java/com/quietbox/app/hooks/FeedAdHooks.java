package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * P0-2 信息流广告过滤。
 *
 * 9.50.0 的信息流广告有两种来源（2026-10-17 DexSQL 复核）：
 *
 * 1) 开屏关联的首页第 2 位广告（2-pos）：客户端自己插，链路是
 *    AdsHomeFeedManagerServiceImpl.handleFeedDataIfNeed → recordAdsNoteData
 *    → xy.c → Lr7d/m0.e0 插入。这一种旧 hook（只跳 handleFeedDataIfNeed）能覆盖。
 *
 * 2) **普通信息流广告**：服务端直接下发 NoteItemBean.isAd=true，客户端没有"插入广告"
 *    的方法可拦，只能在列表层过滤。旧 hook 对这类完全无效。
 *
 * 本类两层都做：
 * - 2-pos：跳 handleFeedDataIfNeed + recordAdsNoteData，并守卫 m0.e0。
 * - 普通广告：在 m0.Q / m0.A0 / m7d.s.Yd 调用原方法前，从入参 ArrayList 移除
 *   isAd==true 的项。只动列表内容，不动游标，分页不受影响。
 *
 * isAd 是 NoteItemBean 的**字段**（不是 getter）：Lcom/xingin/entities/NoteItemBean;->isAd:Z。
 */
final class FeedAdHooks {

    private static final String SERVICE_CLAZZ = "com.xingin.advert.spi.AdsHomeFeedManagerServiceImpl";
    private static final String RECORD_METHOD = "recordAdsNoteData";
    private static final String HANDLE_METHOD = "handleFeedDataIfNeed";

    /** 首页列表实现（混淆类）：追加/刷新/插入。 */
    private static final String FEED_LIST = "r7d.m0";
    /** 分页上游（混淆类）。 */
    private static final String FEED_PAGER = "m7d.s";

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
            HookState.note(f, "2-pos（handleFeedDataIfNeed/recordAdsNoteData/m0.e0）+ 普通广告列表过滤（m0.Q/m0.A0/m7d.s.Yd）");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    /** 开屏关联的第 2 位广告：客户端自己插的那一种。 */
    private static void installTwoPosition(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> service = HookKit.findClass(module, f, cl, SERVICE_CLAZZ);
        if (service == null) {
            return;
        }
        Method handle = HookKit.findMethodQuiet(service, HANDLE_METHOD, java.util.ArrayList.class, String.class);
        HookKit.install(module, f, HANDLE_METHOD, handle, HookKit.skipVoid(module, f, HANDLE_METHOD));

        Method record = HookKit.findByNameAndArity(service, RECORD_METHOD, 2);
        HookKit.install(module, f, RECORD_METHOD, record, HookKit.skipVoid(module, f, RECORD_METHOD));

        Class<?> feed = HookKit.findClass(module, f, cl, FEED_LIST);
        Method insert = HookKit.findByNameAndArity(feed, "e0", 2);
        HookKit.install(module, f, "m0.e0", insert, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object note = chain.getArg(1);
                    if (note != null && isAd(note)) {
                        HookKit.hit(module, f, "m0.e0 blocked ad insert");
                        return null;
                    }
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error m0.e0", t);
                }
                return chain.proceed();
            }
        });
    }

    /** 普通信息流广告：服务端下发 isAd，只能在列表层过滤。 */
    private static void installListFilter(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> feed = HookKit.findClass(module, f, cl, FEED_LIST);
        if (feed != null) {
            HookKit.install(module, f, "m0.Q", HookKit.findByNameAndArity(feed, "Q", 1),
                    dropAdsHook(module, f, "m0.Q", 0));
            HookKit.install(module, f, "m0.A0", HookKit.findByNameAndArity(feed, "A0", 2),
                    dropAdsHook(module, f, "m0.A0", 0));
        }
        Class<?> pager = HookKit.findClass(module, f, cl, FEED_PAGER);
        if (pager != null) {
            HookKit.install(module, f, "m7d.s.Yd", HookKit.findByNameAndArity(pager, "Yd", 3),
                    dropAdsHook(module, f, "m7d.s.Yd", 0));
        }
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
                    int removed = dropAds(list);
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

    private static int dropAds(List<?> list) {
        int removed = 0;
        try {
            Iterator<?> it = list.iterator();
            while (it.hasNext()) {
                Object note = it.next();
                if (note != null && isAd(note)) {
                    it.remove();
                    removed++;
                }
            }
        } catch (Throwable ignored) {
            // 不可变列表等异常：原样放行，不影响原逻辑。
        }
        return removed;
    }

    private static boolean isAd(Object note) {
        try {
            Field fld;
            Class<?> cls = note.getClass();
            synchronized (IS_AD) {
                if (IS_AD.containsKey(cls)) {
                    fld = IS_AD.get(cls);
                } else {
                    fld = resolveIsAd(cls);
                    IS_AD.put(cls, fld);
                }
            }
            return fld != null && fld.getBoolean(note);
        } catch (Throwable t) {
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
