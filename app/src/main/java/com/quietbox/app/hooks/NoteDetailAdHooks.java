package com.quietbox.app.hooks;

import android.view.View;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * AD-06 详情页广告条屏蔽。
 *
 * 笔记详情页底部的「二跳条」由 AdvertV2View（继承抽象类 p.a，即 FrameLayout）承载。
 * 构造时它会 lazy 出一个真实广告 view 并 addView；广告数据通过
 * a(Lb20/b;) / b(Lp50/v;) 绑定进来。
 *
 * 为什么不 hook 工厂：create*NoteAdvertBannerView 的返回类型是抽象类 Lp/a;，
 * 没法 new 一个假实例；返回 null 又会被下游 addView/强转打崩。
 * 所以在**绑定数据时**把视图设为 GONE 并跳过绑定：视图还在布局树里但不占位、不渲染。
 *
 * 类是语义名（com.xingin.matrix.notedetail.asyncwidgets.advert.AdvertV2View），
 * 不是混淆类，跨版本相对稳定。
 */
final class NoteDetailAdHooks {

    private NoteDetailAdHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.NOTE_DETAIL_AD_BANNER;
        try {
            // a(Lb20/b;) 绑图片广告模型；b(Lp50/v;) 绑视频广告模型。
            Method image = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.NOTE_DETAIL_AD_IMAGE, true);
            Method video = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.NOTE_DETAIL_AD_VIDEO, true);
            HookKit.install(module, f, "AdvertV2View#a", image, hideHook(module, f, "image"));
            HookKit.install(module, f, "AdvertV2View#b", video, hideHook(module, f, "video"));
            HookState.note(f, "AdvertV2View 绑定广告数据时置 GONE 并跳过绑定");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    private static XposedInterface.Hooker hideHook(XposedModule module, Feature f, String kind) {
        return chain -> {
            try {
                Object self = chain.getThisObject();
                if (self instanceof View) {
                    ((View) self).setVisibility(View.GONE);
                }
                HookKit.hit(module, f, "AdvertV2View " + kind + " hidden");
                // void 方法：不 proceed 即不绑定广告数据。
                return null;
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error AdvertV2View " + kind, t);
                return chain.proceed();
            }
        };
    }
}
