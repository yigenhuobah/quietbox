package com.quietbox.app.hooks;

import androidx.annotation.Nullable;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * EN-03 实况图去水印（默认关闭：开启后会切换实况保存链路）。
 *
 * <p>9.52.0 真机验证通过（25102RKBEC）：同一条实况笔记开/关对比，EN-03 开 →
 * 3/3 段内嵌视频无水印，关 → 3/3 段有「小红书」logo + 小红书号水印；静态帧
 * 两组均无水印（EN-01 覆盖）；批量保存 11 张 JPG 全部含 MotionPhoto 元数据，
 * 无崩溃、无 -4 失败。日志证实 {@code p32.h#a(1)→false} 与 {@code dg7.h.e/h set=2}
 * 均命中，{@code zm7.j#c} 在 9.52.0 实况保存路径真实执行。
 *
 * <p>9.52.0 实况保存入口（dexsql 反汇编 Lzm7/j;->c(Lpr7/l;Ldg7/h;Z)V，classes8.dex）：
 * <pre>
 *   zm7.j#c(pr7.l listener, dg7.h feedback, boolean saveStaticImage)
 *     // 1) 静态帧：dg7.h.e（disableWaterMark）与 dg7.h.h（disableAiWaterMark）
 *     //    作为布尔位传入 jn6.i.h（12 参默认参桥接，内部委托 11 参 jn6.i.i，
 *     //    对应 EN-01 的 index 5 / index 10），Canvas 合成时跳过水印绘制。
 *     // 2) 实况视频：dg7.h.g（livePhotoVideoPath）非空时：
 *     //      if (p32.h#a(1))   // ConfigCenter android_feature_close_switch_by_code code=1
 *     //          新链路：zm7.j#f(dg7.h.f livePhotoFileId, ...) → POST live_photo/save，
 *     //                  视频由服务端渲染，可能带烧录水印；
 *     //      else
 *     //          旧链路：g42.e#d(dg7.h.g, Z) → 直接下载 livePhotoVideoPath，
 *     //                  与笔记详情预览播放同源，是唯一可能无客户端水印的来源。
 * </pre>
 *
 * <p>两个挂载点：
 * <ol>
 *   <li>before {@code zm7.j#c}：把 dg7.h 参数对象的 e/h 反射置 true，静态帧走 EN-01
 *       同一条 Canvas 跳绘路径（e=true 同时绕过 jn6.i 的
 *       {@code if (!m && watermark == null) fail(-4)} fail-open 守卫，保存不会因此失败）。</li>
 *   <li>{@code p32.h#a(I)Z}：仅当参数 == {@link #LIVE_PHOTO_SAVE_SWITCH_CODE}（1，dexsql
 *       确认 zm7.j#c 内 {@code const 1} 传入）时返回 false，强制走旧链路；其余 code
 *       一律 proceed 原值，不影响其他功能的关闭开关判断。</li>
 * </ol>
 *
 * <p>选择 p32.h#a(1) 而不是 hook 新链路响应类 zp8.a#d() 换 URL 的原因：zp8.a 与 dg7.h
 * 无任何引用关系，换 URL 需要跨 hook 传递状态（zm7.j#c 时暂存 g、zp8.a#d 时取用），
 * 时序脆弱；p32.h#a(1) 是链路分叉的唯一判定点，在判定处改写无状态且同时让
 * live_photo/save 请求不再发出。代价是 code=1 的其他读点（如有）也会读到 false——
 * 该 code 在 zm7.j#c 中的语义就是实况保存链路开关，真机验证未观察到其他影响；
 * 宿主后续若改保存链路（如下线 livePhotoVideoPath），本功能可能失效。
 *
 * <p>签名字段语义（9.52.0 dexsql 复核）：dg7.h 为 8 参构造
 * {@code <init>(String,String,String,String,Z,String,String,Z)}，字段 a~d=String、
 * e=Z（disableWaterMark）、f=String（livePhotoFileId）、g=String（livePhotoVideoPath）、
 * h=Z（disableAiWaterMark），全部为跨类直接 iget/iput 的公开字段，可反射改写。
 */
final class LivePhotoWatermarkHooks {

    /** 实况保存入口：zm7.j#c(pr7.l, dg7.h, Z)V。9.52.0 实测签名。 */
    private static final String ENTRY_CLAZZ = "zm7.j";

    /** 入口第 1 参：保存事件监听（未挂，仅用于签名匹配）。 */
    private static final String ENTRY_LISTENER = "pr7.l";

    /** 入口第 2 参：FeedbackDownloadImage，字段 e/h 为两个水印开关。 */
    private static final String ENTRY_FEEDBACK = "dg7.h";

    /** ConfigCenter 关闭开关读取点：p32.h#a(I)Z。 */
    private static final String SWITCH_CLAZZ = "p32.h";

    /**
     * 实况保存链路开关 code：zm7.j#c 内 {@code const v5, 0x1} 传入 {@code p32.h#a(I)Z}。
     * p32.h#a 的语义：android_feature_close_switch_by_code 列表含该 code → false，
     * 否则 true；默认（服务端未下发）为 true 即新链路。
     */
    static final int LIVE_PHOTO_SAVE_SWITCH_CODE = 1;

    private LivePhotoWatermarkHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.LIVE_PHOTO_WATERMARK;
        int installed = 0;

        // 1) 入口改参：dg7.h.e / dg7.h.h 置 true，静态帧跳过水印绘制。
        installed += hookSaveEntry(module, f, cl);

        // 2) 链路开关：code=1 时返回 false，实况视频走旧链路（预览同源 URL）。
        installed += hookSaveSwitch(module, f, cl);

        if (installed == 0) {
            HookState.markNotFound(f, "实况水印挂载点未找到");
        }
    }

    /**
     * zm7.j#c before：改写 dg7.h 参数的水印开关位后按原参 proceed。
     * 改写发生在原方法执行前（dg7.h 实例随后被传入 jn6.i 与链路判定，before 时改写生效）。
     */
    private static int hookSaveEntry(XposedModule module, Feature f, ClassLoader cl) {
        try {
            Class<?> entry = Class.forName(ENTRY_CLAZZ, false, cl);
            Class<?> listener = Class.forName(ENTRY_LISTENER, false, cl);
            Class<?> feedback = Class.forName(ENTRY_FEEDBACK, false, cl);
            Method m = HookKit.findMethodQuiet(module, f, entry, "c", listener, feedback, boolean.class);
            if (m == null) {
                return 0;
            }
            HookKit.install(module, f, ENTRY_CLAZZ + "#c(disableWaterMark=true)", m,
                    chain -> {
                        int set = 0;
                        try {
                            List<Object> args = chain.getArgs();
                            if (args.size() >= 2) {
                                set = applyFeedbackFlags(args.get(1));
                            }
                            HookKit.hit(module, f, ENTRY_FEEDBACK + ".e/h set=" + set);
                        } catch (Throwable t) {
                            HookKit.error(module, f.id + " intercept error " + ENTRY_CLAZZ + "#c", t);
                        }
                        // 改写的是参数对象内部字段，原参 proceed 即可。
                        return chain.proceed();
                    });
            return 1;
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: class " + ENTRY_CLAZZ + "/" + ENTRY_FEEDBACK + " " + t);
            return 0;
        }
    }

    /**
     * p32.h#a(I)Z：仅 code == {@link #LIVE_PHOTO_SAVE_SWITCH_CODE} 时返回 false（旧链路），
     * 其余 code 一律 proceed，避免全局返回 false 影响其他功能。
     */
    private static int hookSaveSwitch(XposedModule module, Feature f, ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(SWITCH_CLAZZ, false, cl);
            Method m = HookKit.findMethodQuiet(module, f, cls, "a", int.class);
            if (m == null) {
                return 0;
            }
            HookKit.install(module, f, SWITCH_CLAZZ + "#a(code=" + LIVE_PHOTO_SAVE_SWITCH_CODE + "→false)", m,
                    chain -> {
                        try {
                            List<Object> args = chain.getArgs();
                            if (args.size() == 1 && args.get(0) instanceof Integer
                                    && isLivePhotoSwitchCode((Integer) args.get(0))) {
                                HookKit.hit(module, f, SWITCH_CLAZZ + "#a(" + args.get(0) + ") → false");
                                return Boolean.FALSE;
                            }
                        } catch (Throwable t) {
                            HookKit.error(module, f.id + " intercept error " + SWITCH_CLAZZ + "#a", t);
                        }
                        return chain.proceed();
                    });
            return 1;
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: class " + SWITCH_CLAZZ + " " + t);
            return 0;
        }
    }

    /** 仅实况保存链路开关的 code 命中；其他 code 不干预。 */
    static boolean isLivePhotoSwitchCode(int code) {
        return code == LIVE_PHOTO_SAVE_SWITCH_CODE;
    }

    /**
     * 反射把 feedback 对象（dg7.h）的公开 boolean 字段 e（disableWaterMark）与
     * h（disableAiWaterMark）置 true。抽成纯静态方法以便 JVM 单测
     * （LivePhotoWatermarkHooksTest 用字段形状相同的替身类验证）。
     *
     * @param target dg7.h 实例；null 或字段缺失时不动任何东西
     * @return 实际置位的字段数（0~2）；target 为 null 返回 -1
     */
    static int applyFeedbackFlags(@Nullable Object target) {
        if (target == null) {
            return -1;
        }
        return setBooleanTrue(target, "e") + setBooleanTrue(target, "h");
    }

    /** 单字段置 true；字段不存在、类型不符或反射失败都返回 0，不抛出。 */
    private static int setBooleanTrue(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            if (field.getType() != boolean.class) {
                return 0;
            }
            field.setAccessible(true);
            field.setBoolean(target, true);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }
}
