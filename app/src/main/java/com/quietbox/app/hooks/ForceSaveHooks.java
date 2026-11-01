package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * EN-04 强制允许保存。
 *
 * <p>「作者关闭下载」有两道门（9.52.0 dexsql 核实）：
 * <ol>
 *   <li>旧门：{@code com.xingin.entities.MediaSaveConfig.b()} = disable_save。
 *       长按/反馈面板的保存项、分享面板、下载入口都读它（调用方包括
 *       {@code DetailFeedItemFeedbackController#t2}、{@code VideoItemFeedBackController#ub}、
 *       {@code AsyncImageGalleryController#S3} 等）。强制返回 false 即解除。</li>
 *   <li>新门（服务端灰度）：{@code com.xingin.entities.notedetail.FunctionSwitch.a()} = enable，
 *       配合 {@code c()} = type。分享面板列表构造 {@code sq9/b#a} 与下载入口
 *       {@code matrix/notedetail/feedback/download/d#invoke} 按 type 过滤。只对
 *       {@code image_download}/{@code video_download} 强制 true，避免放开无关的 function switch。</li>
 * </ol>
 *
 * <p>两道门都只改「是否允许保存」的判定，不改保存执行链本身；水印仍由 EN-01/EN-03 负责。
 */
final class ForceSaveHooks {

    /** 旧门：disable_save getter。9.50/9.52 方法表均为 a,b,c（b=disable_save）。 */
    private static final String SAVE_CONFIG_CLAZZ = "com.xingin.entities.MediaSaveConfig";

    /** 新门：权限数组元素。a()=enable、b()=reason、c()=type。 */
    private static final String FUNCTION_SWITCH_CLAZZ =
            "com.xingin.entities.notedetail.FunctionSwitch";

    static final String TYPE_IMAGE_DOWNLOAD = "image_download";
    static final String TYPE_VIDEO_DOWNLOAD = "video_download";

    private ForceSaveHooks() {
    }

    /**
     * 纯函数：只有下载类权限才强制 enable（供 JVM 单测）。
     * 其它 type（如评论、AI 配音）保持宿主原值，避免副作用。
     */
    static boolean shouldForceEnable(String type) {
        return TYPE_IMAGE_DOWNLOAD.equals(type) || TYPE_VIDEO_DOWNLOAD.equals(type);
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.FORCE_SAVE;
        int installed = 0;

        // 门 1：MediaSaveConfig.b() -> false（主门，缺失即功能不可用）。
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, SAVE_CONFIG_CLAZZ, true);
            if (cls != null) {
                Method m = HookKit.findMethodQuiet(module, f, cls, "b");
                if (m != null && HookKit.install(module, f, "MediaSaveConfig#b(allow-save)", m,
                        HookKit.constBool(module, f, "MediaSaveConfig#b", false))) {
                    installed++;
                }
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: MediaSaveConfig " + t);
        }

        // 门 2：FunctionSwitch.a() -> true，仅 image_download / video_download。
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, FUNCTION_SWITCH_CLAZZ, false);
            if (cls != null) {
                Method enable = HookKit.findMethodQuiet(module, f, cls, "a");
                Method type = HookKit.findMethodQuiet(module, f, cls, "c");
                if (enable != null && type != null) {
                    final Method typeGetter = type;
                    boolean ok = HookKit.install(module, f, "FunctionSwitch#a(enable-download)",
                            enable, chain -> {
                                Object original = chain.proceed();
                                try {
                                    Object self = chain.getThisObject();
                                    if (self != null) {
                                        Object typeValue = typeGetter.invoke(self);
                                        if (typeValue instanceof String
                                                && shouldForceEnable((String) typeValue)) {
                                            HookKit.hit(module, f,
                                                    "FunctionSwitch#a:" + typeValue);
                                            return Boolean.TRUE;
                                        }
                                    }
                                } catch (Throwable t) {
                                    HookKit.error(module, f.id + " FunctionSwitch#a error", t);
                                }
                                return original;
                            });
                    if (ok) {
                        installed++;
                    }
                }
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: FunctionSwitch " + t);
        }

        if (installed == 0) {
            HookState.markNotFound(f, "强制保存挂载点未找到");
        }
    }
}
