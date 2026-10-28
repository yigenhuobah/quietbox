package com.quietbox.app.hooks;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * EN-01 水印策略。
 *
 * <p>真链路（9.50.0 实测，LSPosed 侧确认命中；9.51.0 起 nd8.i/nd8.j 改名 ce8.i/ce8.j，
 * 入口方法 g 改名 i，签名形状不变）：笔记图片库长按「保存图片」走
 * {@code ce8.i/ce8.j}（内部 tag "SaveImagesHelper"，classes19.dex），
 * 用 Canvas 手动合成水印后 {@code Bitmap.compress(JPEG)} 写盘：
 * <pre>
 *   AsyncImageGalleryController.B3   // 长按菜单入口，读 MediaSaveConfig.c()
 *   -> pc9.y (onDownloadClick)
 *   -> ce8.i.i(... disableWaterMark, ..., disableAiWaterMark ...)   [9.51.0；9.50.0 为 nd8.i.g]
 *   -> ce8.j.a(src, watermark, sticker, aiWatermark, disableWaterMark, disableAiWaterMark)
 *        if (!disableWaterMark && watermark != null) canvas.drawBitmap(watermark, ...)
 * </pre>
 *
 * <p>9.51.0 另有 12 参重载 ce8.i.g（多一个布尔），它内部仍委托到 11 参的
 * ce8.i.i，所以只挂 i 即可覆盖两条入口。11 参形状与各布尔位的语义证据见
 * {@link #applyWatermarkFlags}。
 *
 * <p>此前 5 次尝试全部失败的原因：a27/s、cp6/r、DotsImageSaveHelper 属于
 * {@code com.xingin.dots.*}（点点/AI 助手），与笔记图片库保存是两套代码；
 * co3/c 与 ImageAlbumSaveService 属于发布/相册链路。都不是这条路。
 *
 * <p>拦 {@code ce8.j.a} 的第 5 个参数（disableWaterMark）为 true 即可跳过绘制：
 * smali 守卫是 {@code if-nez v4 -> 跳过 drawBitmap}。
 */
final class WatermarkHooks {

    /**
     * Canvas 合成点：a(Bitmap src, Bitmap watermark, Bitmap sticker, Bitmap ai,
     * boolean disableWaterMark, boolean disableAiWaterMark) -> Bitmap。
     * 9.50.0 为 nd8.j。
     */
    private static final String COMPOSE_CLAZZ = "ce8.j";

    /** 保存入口：i(Activity, String×4, Z, F1, F1, Z, Z, Z) 共 11 参。9.50.0 为 nd8.i#g。 */
    private static final String SAVE_CLAZZ = "ce8.i";

    /** 语义 getter：MediaSaveConfig.c() -> disableWaterMark。 */
    private static final String MODEL_CLAZZ = "com.xingin.entities.MediaSaveConfig";

    private WatermarkHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.WATERMARK_POLICY;
        int installed = 0;

        // 1) 主路径：Canvas 合成时把 disableWaterMark / disableAiWaterMark 置 true。
        try {
            Class<?> cls = Class.forName(COMPOSE_CLAZZ, false, cl);
            Method m = HookKit.findMethodQuiet(cls, "a",
                    Bitmap.class, Bitmap.class, Bitmap.class, Bitmap.class,
                    boolean.class, boolean.class);
            if (m == null) {
                HookKit.warn(module, f.id + " skip: ce8.j#a not found");
            } else {
                HookKit.install(module, f, "ce8.j#a(disableWaterMark=true)", m,
                        chain -> {
                            Object[] replaced = null;
                            try {
                                HookKit.hit(module, f, "ce8.j#a");
                                List<Object> args = chain.getArgs();
                                if (args.size() >= 6) {
                                    replaced = args.toArray();
                                    // 跳过主水印
                                    replaced[4] = Boolean.TRUE;
                                    // 跳过 AI 水印
                                    replaced[5] = Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                HookKit.error(module, f.id + " intercept error ce8.j#a", t);
                            }
                            // proceed 放在 try 外：原方法自己抛异常时不能再调一次。
                            return replaced == null ? chain.proceed() : chain.proceed(replaced);
                        });
                installed++;
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: class ce8.j " + t);
        }

        // 2) 入口侧：ce8.i.i（9.50.0 为 nd8.i.g）的 disableWaterMark / disableAiWaterMark 置 true。
        installed += hookSaveEntry(module, f, cl);

        // 3) 语义 getter：让上层查询也认为"无水印"。
        try {
            Class<?> cls = Class.forName(MODEL_CLAZZ, false, cl);
            Method m = HookKit.findMethodQuiet(cls, "c");
            if (m == null) {
                HookKit.warn(module, f.id + " skip: MediaSaveConfig#c not found");
            } else {
                HookKit.install(module, f, "MediaSaveConfig#c(no-watermark)", m,
                        HookKit.constBool(module, f, "MediaSaveConfig#c", true));
                installed++;
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: class MediaSaveConfig " + t);
        }

        if (installed == 0) {
            HookState.markNotFound(f, "水印挂载点未找到");
        }
    }

    /**
     * 入口侧改参逻辑，抽成纯静态方法以便 JVM 单测（WatermarkHooksTest）。
     *
     * <p>ce8.i#i 是 11 参：{@code (Activity, String, String, String, String, Z,
     * Function1, Function1, Z, Z, Z)}，布尔位在 index 5、8、9、10。
     * dexsql 对 9.51.0（classes6.dex）反汇编的结论：
     * <ul>
     *   <li>index 5 → disableWaterMark：两条路径都传入 ce8.d 的字段 m，
     *       subscribe 里既做 fail-open 守卫
     *       {@code if (!m && watermark == null) fail(-4 "save image failed without watermark")}，
     *       又作为 ce8.j#a 第 5 参（true 时跳过主水印 drawBitmap）。</li>
     *   <li>index 10 → disableAiWaterMark：传入 ce8.d 的字段 o，
     *       subscribe 里以 {@code o && ce8.i#b()} 作为 ce8.j#a 第 6 参
     *       （方法体首行日志 "disableAiWaterMark is ..."，true 时跳过 AI 水印 drawBitmap）。</li>
     *   <li>index 8 → 仅在位图为空的兜底路径传入 ce8.h.p → ce8.e，是"存入相册
     *       (MediaStore DCIM)"的副作用开关，与水印绘制无关，不设。</li>
     *   <li>index 9 → 传入 ce8.d 的字段 i，作为 ce8.j#c(Context,String,Z) /
     *       ce8.j#b(Context,Z) 取图的布尔参数（缓存/强制类开关），不设。</li>
     * </ul>
     *
     * <p>只设置有证据的两个位；其余长度（如 9.50.0 的 12 参 nd8.i#g 形状或未来
     * 重载）返回 null，由调用方走原参 proceed，不做猜测。
     *
     * @return 置位后的新数组；长度非 11 返回 null
     */
    @Nullable
    static Object[] applyWatermarkFlags(Object[] args) {
        if (args == null || args.length != 11) {
            return null;
        }
        Object[] replaced = args.clone();
        replaced[5] = Boolean.TRUE;    // disableWaterMark
        replaced[10] = Boolean.TRUE;   // disableAiWaterMark
        return replaced;
    }

    /**
     * i(Activity, String, String, String, String, boolean, Function1, Function1,
     * boolean, boolean, boolean)：11 参，后半是混淆/Kotlin 函数类型，
     * 编译期写不全参数类型，所以只校验前导参数（Activity + 4 个 String）定位。
     * 不能用无参 findMethodQuiet：那等价于 getDeclaredMethod("i")，永远匹配不到这个重载。
     * 9.50.0 该方法是 nd8.i.g，9.51.0 改名为 ce8.i.i。
     */
    private static int hookSaveEntry(XposedModule module, Feature f, ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(SAVE_CLAZZ, false, cl);
            Method m = HookKit.findByShape(cls, "i", mainActivityClass(cl), String.class,
                    String.class, String.class, String.class);
            if (m == null) {
                HookKit.warn(module, f.id + " skip: ce8.i#i not found");
                return 0;
            }
            HookKit.install(module, f, "ce8.i#i(disableWaterMark=true)", m,
                    chain -> {
                        Object[] replaced = null;
                        try {
                            HookKit.hit(module, f, "ce8.i#i");
                            List<Object> args = chain.getArgs();
                            replaced = applyWatermarkFlags(args.toArray());
                        } catch (Throwable t) {
                            HookKit.error(module, f.id + " intercept error ce8.i#i", t);
                        }
                        return replaced == null ? chain.proceed() : chain.proceed(replaced);
                    });
            return 1;
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: class ce8.i " + t);
            return 0;
        }
    }

    /** ce8.i.i 的第一参在 9.51.0 经 DEX 复核就是 android.app.Activity（不是 XhsActivity），用 Activity 做形状匹配。 */
    @Nullable
    private static Class<?> mainActivityClass(ClassLoader cl) {
        try {
            return Class.forName("android.app.Activity", false, cl);
        } catch (Throwable t) {
            return null;
        }
    }
}
