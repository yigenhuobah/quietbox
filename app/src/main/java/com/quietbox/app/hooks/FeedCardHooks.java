package com.quietbox.app.hooks;

import com.quietbox.app.Config;
import com.quietbox.app.Feature;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * P1-21/22/23 信息流直播卡、带货卡、投票卡过滤。
 *
 * 复用 P0-2 已验证的列表过滤链路（r7d/m0#Q、#A0、m7d/s#Yd，见 FeedAdHooks），
 * 只动列表内容、不动游标，分页不受影响。三个功能各挂各的谓词，互不依赖。
 *
 * 判定优先用 NoteItemBean.modelType 字符串，字段（live / goodsNoteV2）作兜底：
 * modelType 到卡片类型的取值映射需真机日志确认，因此这里两条判据都实现，
 * 哪条先命中就用哪条——误判风险集中在 modelType，字段兜底只在字段非空时生效。
 *
 * 注意：只挂首页信息流的 Q/A0/Yd 路径（已验证调用面），不碰详情连播链路。
 */
final class FeedCardHooks {

    /** 首页列表实现（混淆类）：追加/刷新/插入。 */
    private static final String FEED_LIST = "r7d.m0";
    /** 分页上游（混淆类）。 */
    private static final String FEED_PAGER = "m7d.s";

    /** NoteItemBean.modelType 字段缓存；null 表示该类没有这个字段。 */
    private static final Map<Class<?>, Field> MODEL_TYPE = new HashMap<>();
    private static final Map<Class<?>, Field> LIVE = new HashMap<>();
    private static final Map<Class<?>, Field> GOODS_V2 = new HashMap<>();

    /** 诊断用：每个入口只记一次"被调用过"，确认挂载点真的在链路上。 */
    private static final Set<String> SEEN = new HashSet<>();

    private FeedCardHooks() {
    }

    /**
     * 三个卡过滤功能共用同一组挂载点。Hooks 只对本版本启用的功能调用进来，
     * 因此这里逐个判断 enabled，只装启用项的谓词；挂载本身一次性完成。
     */
    private static volatile boolean sInstalled;

    static void install(XposedModule module, ClassLoader cl, Config config) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        if (config.enabled(Feature.FEED_LIVE_CARD_BLOCK)) {
            installOne(module, cl, Feature.FEED_LIVE_CARD_BLOCK, "live");
        }
        if (config.enabled(Feature.FEED_GOODS_CARD_BLOCK)) {
            installOne(module, cl, Feature.FEED_GOODS_CARD_BLOCK, "goods");
        }
        if (config.enabled(Feature.FEED_VOTE_CARD_BLOCK)) {
            installOne(module, cl, Feature.FEED_VOTE_CARD_BLOCK, "vote");
        }
    }

    private static void installOne(XposedModule module, ClassLoader cl, Feature f, String kind) {
        try {
            Class<?> feed = HookKit.findClass(module, f, cl, FEED_LIST);
            if (feed != null) {
                HookKit.install(module, f, "m0.Q",
                        HookKit.findByNameAndArity(feed, "Q", 1),
                        dropHook(module, f, "m0.Q", 0, kind));
                HookKit.install(module, f, "m0.A0",
                        HookKit.findByNameAndArity(feed, "A0", 2),
                        dropHook(module, f, "m0.A0", 0, kind));
            }
            Class<?> pager = HookKit.findClass(module, f, cl, FEED_PAGER);
            if (pager != null) {
                HookKit.install(module, f, "m7d.s.Yd",
                        HookKit.findByNameAndArity(pager, "Yd", 3),
                        dropHook(module, f, "m7d.s.Yd", 0, kind));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    private static XposedInterface.Hooker dropHook(XposedModule module, Feature f,
                                                   String where, int argIndex, String kind) {
        return chain -> {
            try {
                Object arg = chain.getArg(argIndex);
                if (arg instanceof List) {
                    List<?> list = (List<?>) arg;
                    seenOnce(module, f, where, list.size());
                    int removed = drop(list, kind);
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
            if (!SEEN.add(f.id + " " + where)) {
                return;
            }
        }
        HookKit.info(module, f.id + " seen " + where + " size=" + size);
    }

    private static int drop(List<?> list, String kind) {
        int removed = 0;
        try {
            Iterator<?> it = list.iterator();
            while (it.hasNext()) {
                Object note = it.next();
                if (note != null && matches(note, kind)) {
                    it.remove();
                    removed++;
                }
            }
        } catch (Throwable ignored) {
            // 不可变列表等异常：原样放行，不影响原逻辑。
        }
        return removed;
    }

    /** 判定某一类卡片：modelType 命中即算；字段判据只在对应 kind 下生效。 */
    private static boolean matches(Object note, String kind) {
        String modelType = modelType(note);
        if (modelType != null) {
            switch (kind) {
                case "live":
                    if (modelType.contains("live")) {
                        return true;
                    }
                    break;
                case "goods":
                    if (modelType.contains("goods")) {
                        return true;
                    }
                    break;
                case "vote":
                    if (modelType.contains("vote") || modelType.contains("poll")) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        // 兜底：按字段非空判定。modelType 拿不到或取值映射没覆盖时才走到这里。
        switch (kind) {
            case "live":
                return nonNull(note, LIVE, "live");
            case "goods":
                return nonNull(note, GOODS_V2, "goodsNoteV2");
            default:
                return false;
        }
    }

    private static String modelType(Object note) {
        try {
            Field fld = cached(MODEL_TYPE, note, "modelType");
            if (fld == null) {
                return null;
            }
            Object v = fld.get(note);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean nonNull(Object note, Map<Class<?>, Field> cache, String name) {
        try {
            Field fld = cached(cache, note, name);
            return fld != null && fld.get(note) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Field cached(Map<Class<?>, Field> cache, Object note, String name) {
        Class<?> cls = note.getClass();
        synchronized (cache) {
            if (cache.containsKey(cls)) {
                return cache.get(cls);
            }
            Field found = resolve(cls, name);
            cache.put(cls, found);
            return found;
        }
    }

    private static Field resolve(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }
}
