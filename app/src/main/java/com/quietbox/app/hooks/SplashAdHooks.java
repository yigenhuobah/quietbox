package com.quietbox.app.hooks;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * AD-01 开屏广告跳过。
 *
 * 开屏广告有两条互不覆盖的展示路径（2026-10-16 DexSQL 复核，报告
 * E:\tmp-xhs-mod\agy-scratch\splash_ad_path.md）：
 *
 * Path A｜首页 Controller 内嵌 Fragment（已覆盖）
 *   AdsIndexSpiImp 的 open 系与 can 系方法全部只有一个调用方 Ljcd/t;->onAttach，
 *   是一个互斥 if/else 分发。展示入口按广告类型择一调用，必须全部拦。
 *   所有方法体首行是 Robust PatchProxy 插桩，hook 在方法入口、早于 PatchProxy。
 *
 * Path B｜独立全屏开屏 Activity（本次补上）
 *   com.xingin.advert.intersitial.ui.a（AdsActivityLifecycleCallback，由
 *   AdvertApplication.onCreate 注册到 registerActivityLifecycleCallbacks）在
 *   onActivityResumed 时把开屏广告拉起为独立全屏 Activity。四个 Activity
 *   （SplashAdActivity / SplashDynamicActivity / SplashCNYActivity /
 *   InterstitialAdsActivity）的唯一非 debug 启动方就是 ui.a#d，
 *   完全不经过 AdsIndexSpiImp，旧挂载点拦不到。
 *   - 首选挂 ui.a#d（Path B 唯一出口，void，跳过即全拦）。
 *   - 再挂四个 $a#a(Context,SplashAd) 作兜底（防 ui.a#d 被 Robust 热修绕过）。
 */
final class SplashAdHooks {

    private static final String CLAZZ = "com.xingin.advert.spi.AdsIndexSpiImp";

    private static final String[] VOID_ENTRIES = {
            "openLCBSplashAdsV2",          // index 页 V2 / RN 开屏
            "openLCBRedSplashAdsV2",       // 红 / CNY 开屏
            "openLCBBrandMaxSplashAds",    // 品牌 Max
            "openBrandMax",
            "openNeedLazyHFSplashAds",     // 懒加载 HF 开屏（Dynamic/CNY/Stellar 分支）
    };

    /** 类型 / UI 变体判定，不是展示总闸，置 false 只是辅助。 */
    private static final String[] DECISIONS = {
            "canLazyOpenSplash",
            "canNeedNewCloser",
    };

    /** Path B：独立全屏开屏 Activity 的生命周期回调（AdsActivityLifecycleCallback）。 */
    private static final String LIFECYCLE_CB = "com.xingin.advert.intersitial.ui.a";

    /** Path B：四个全屏开屏 Activity 的 launcher（$a 内部类）。 */
    private static final String[] FULLSCREEN_LAUNCHERS = {
            "com.xingin.advert.intersitial.ui2.view.SplashAdActivity$a",
            "com.xingin.advert.intersitial.ui2.view.dynamic.SplashDynamicActivity$a",
            "com.xingin.advert.intersitial.ui2.cny.SplashCNYActivity$a",
            "com.xingin.advert.intersitial.ui.InterstitialAdsActivity$a",
    };

    /**
     * Path C：冷启动占位 Fragment。SplashV2Controller.onAttach 在 coldStartMode==0 时
     * replace 这个 Fragment 防白屏，它不是广告内容，但观感上算开屏画面。
     */
    private static final String PLACEHOLDER_VIEW = "com.xingin.advert.intersitial.ui.SplashAdPlaceHolderView";

    private SplashAdHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SPLASH_AD_SKIP;
        try {
            installEmbedded(module, cl, f);
            installFullScreen(module, cl, f);
            installPlaceholder(module, cl, f);
            HookState.note(f, "内嵌 Fragment（AdsIndexSpiImp）+ 全屏 Activity（ui.a#d 及四个 $a#a）+ 冷启动占位（SplashAdPlaceHolderView）");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    /** Path A：首页 Controller 内嵌 Fragment 开屏。 */
    private static void installEmbedded(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
        if (cls == null) {
            return;
        }
        for (String name : VOID_ENTRIES) {
            Method m = HookKit.findMethod(module, f, cls, name);
            HookKit.install(module, f, name, m, HookKit.skipVoid(module, f, name));
        }
        for (String name : DECISIONS) {
            Method m = HookKit.findMethod(module, f, cls, name);
            HookKit.install(module, f, name, m, HookKit.constBool(module, f, name, false));
        }
    }

    /** Path B：独立全屏开屏 Activity。 */
    private static void installFullScreen(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> cb = HookKit.findClass(module, f, cl, LIFECYCLE_CB);
        if (cb != null) {
            // d(WeakReference, SplashAd)：Path B 唯一出口，后面的 SplashAd 是混淆包内类型，
            // 编译期拿不到 Class，用前导参数 WeakReference 定位。
            Method d = HookKit.findByShape(cb, "d", WeakReference.class);
            HookKit.install(module, f, "ui.a#d", d, HookKit.skipVoid(module, f, "ui.a#d"));
        }
        for (String name : FULLSCREEN_LAUNCHERS) {
            Class<?> launcher = HookKit.findClass(module, f, cl, name);
            if (launcher == null) {
                continue;
            }
            Method a = HookKit.findByShape(launcher, "a", Context.class);
            String label = shortName(name) + "#a";
            HookKit.install(module, f, label, a, HookKit.skipVoid(module, f, label));
        }
    }

    private static String shortName(String dotted) {
        int i = dotted.lastIndexOf('.');
        return i < 0 ? dotted : dotted.substring(i + 1);
    }

    /**
     * Path C：冷启动占位 Fragment。
     *
     * 不 hook 工厂（createSplashAdPlaceHolderView）的原因是它要返回
     * androidx.fragment.app.Fragment 实例，而实测在目标进程里
     * Class.forName("androidx.fragment.app.Fragment", false, cl) 拿不到（XHS 的类加载器
     * 对 androidx 走的是另一层），所以改成 hook 占位 Fragment 自身的 onCreateView，
     * 直接返回一个空 View。类名是非混淆的语义名。
     */
    private static void installPlaceholder(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> cls = HookKit.findClass(module, f, cl, PLACEHOLDER_VIEW);
        if (cls == null) {
            return;
        }
        Method m = HookKit.findMethodQuiet(cls, "onCreateView",
                LayoutInflater.class, ViewGroup.class, Bundle.class);
        if (m == null) {
            HookKit.warn(module, f.id + " skip: SplashAdPlaceHolderView#onCreateView not found");
            return;
        }
        HookKit.install(module, f, "SplashAdPlaceHolderView#onCreateView", m, chain -> {
            try {
                Object inflater = chain.getArg(0);
                if (inflater instanceof LayoutInflater) {
                    HookKit.hit(module, f, "placeholder suppressed");
                    return new View(((LayoutInflater) inflater).getContext());
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error placeholder", t);
            }
            return chain.proceed();
        });
    }
}
