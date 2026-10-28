package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * AD-05 搜索广告过滤。
 *
 * 9.52.0 的搜索结果广告插入点在数据层实现 Lpc0/c0.o(...)Ljava/util/List;
 * （9.51.0 为 Lhl0/f0.o，9.50.0 为 Lfl0/f0.o；
 * 方法签名形状与方法体一致，仅混淆类型/包名变化）。遍历 response.items，
 * modelType=="ads" 时取 alioth.entities.c.getAds() 塞进返回列表。广告实体仍是
 * Lcom/xingin/alioth/entities/i;（继承 alioth.entities.c，未改名）。
 *
 * 做法：调用原方法后过滤返回值。在数据层删掉，UI 不会留下空白卡片；
 * 不要改卡片 binder —— 那会让广告卡片变成空白占位。
 */
final class SearchAdHooks {

    private static final String CLAZZ = "pc0.c0";
    private static final String METHOD = "o";
    private static final String AD_ENTITY = "com.xingin.alioth.entities.i";

    private SearchAdHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SEARCH_AD_FILTER;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            if (cls == null) {
                return;
            }
            Class<?> ad = HookKit.findClass(module, f, cl, AD_ENTITY);
            if (ad == null) {
                return;
            }
            Method m = HookKit.findByNameAndArity(cls, METHOD, 4);
            HookKit.install(module, f, "pc0.c0#o", m, filterHook(module, f, ad));
            HookState.note(f, "搜索仓库返回列表过滤 alioth.entities.i");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    private static XposedInterface.Hooker filterHook(XposedModule module, Feature f, Class<?> adClass) {
        return chain -> {
            // 先执行原方法：它自己抛的异常原样向上抛，不要在这里 proceed 第二次。
            Object result = chain.proceed();
            try {
                if (result instanceof List) {
                    List<?> src = (List<?>) result;
                    List<Object> out = new ArrayList<>(src.size());
                    int removed = 0;
                    for (Object item : src) {
                        if (item != null && adClass.isInstance(item)) {
                            removed++;
                        } else {
                            out.add(item);
                        }
                    }
                    if (removed > 0) {
                        HookKit.hit(module, f, "search ads removed=" + removed);
                        return out;
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error pc0.c0#o", t);
            }
            return result;
        };
    }
}
