package com.quietbox.app.hooks;

import com.quietbox.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * P1-20 评论区带货插入屏蔽。
 *
 * 评论区顶部插商品卡/带货弹层的 SPI 实现，类名非混淆。startAddIn 是 void，直接 skip。
 * 副作用：评论里「提及商品」插入也会一起失效，可接受。
 */
final class GoodsCommentHooks {

    private static final String CLAZZ =
            "com.xingin.commercial.vision.post.spi.GoodsCommentAddInProcessor";

    private GoodsCommentHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.GOODS_COMMENT_BLOCK;
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, CLAZZ);
            HookKit.install(module, f, "startAddIn",
                    HookKit.findByNameAndArity(cls, "startAddIn", 5),
                    HookKit.skipVoid(module, f, "startAddIn"));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
