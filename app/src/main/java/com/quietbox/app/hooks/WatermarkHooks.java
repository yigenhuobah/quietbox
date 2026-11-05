package com.quietbox.app.hooks;

import androidx.annotation.Nullable;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * EN-01 水印策略。
 *
 * <p>真链路（9.50.0 实测，LSPosed 侧确认命中；9.52.0 为 jn6.i/jn6.j，9.52.1 起
 * jn6 → bm8，即 {@code bm8.i/bm8.j}，入口方法 g → i，签名形状不变）：笔记图片库
 * 长按「保存图片」走 {@code bm8.i/bm8.j}（内部 tag "SaveImagesHelper"），
 * 用 Canvas 手动合成水印后 {@code Bitmap.compress(JPEG)} 写盘：
 * <pre>
 *   AsyncImageGalleryController.B3   // 长按菜单入口，读 MediaSaveConfig.c()
 *   -> pc9.y (onDownloadClick)
 *   -> bm8.i.i(... disableWaterMark, ..., disableAiWaterMark ...)   [9.52.1；9.52.0 为 jn6.i.i]
 *   -> bm8.j.a(src, watermark, sticker, aiWatermark, disableWaterMark, disableAiWaterMark)
 *        if (!disableWaterMark && watermark != null) canvas.drawBitmap(watermark, ...)
 * </pre>
 *
 * <p>9.51.0 起有 12 参重载（9.52.1 为 bm8.i.g，9.52.0 为 jn6.i.g，9.51.0 为 ce8.i.g；
 * 多一个布尔），它内部仍委托到 11 参的 bm8.i.i，所以只挂 i 即可覆盖两条入口。
 * 11 参形状与各布尔位的语义证据见 {@link #applyWatermarkFlags}。
 *
 * <p>9.52.0 的教训：R8 曾把 ce8.i/ce8.j 这两个名字复用给无关的 lambda 类
 * （ce8.i 只剩 &lt;init&gt;/run，ce8.j 只剩 &lt;init&gt;/invoke）。"类还在"不能证明
 * 挂载点还有效，必须按方法签名形状核对；9.52.1 真实锚点是 bm8.i/bm8.j
 * （9.52.1 静态体检命中 bm8.i#i 11 参描述符与 bm8.j#a 6 参描述符）。
 *
 * <p>此前 5 次尝试全部失败的原因：a27/s、cp6/r、DotsImageSaveHelper 属于
 * {@code com.xingin.dots.*}（点点/AI 助手），与笔记图片库保存是两套代码；
 * co3/c 与 ImageAlbumSaveService 属于发布/相册链路。都不是这条路。
 *
 * <p>拦 {@code bm8.j.a} 的第 5 个参数（disableWaterMark）为 true 即可跳过绘制：
 * smali 守卫是 {@code if-nez v4 -> 跳过 drawBitmap}。
 */
final class WatermarkHooks {

    private WatermarkHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.WATERMARK_POLICY;
        int installed = 0;
        // 主水印链路（bm8.j#a / bm8.i#i）的挂载数；纯语义 getter 不计入，
        // 避免 getter 挂上后把主链路全失效掩盖成"已安装"。
        int mainInstalled = 0;

        // 1) 主路径：Canvas 合成时把 disableWaterMark / disableAiWaterMark 置 true。
        try {
            // 关键锚点：bm8.j#a 缺失即主水印链路失效，记 critical 进失败横幅。
            Method m = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.WATERMARK_COMPOSE, true);
            if (m != null) {
                if (HookKit.install(module, f, "bm8.j#a(disableWaterMark=true)", m,
                        chain -> {
                            Object[] replaced = null;
                            try {
                                HookKit.hit(module, f, "bm8.j#a");
                                List<Object> args = chain.getArgs();
                                if (args.size() >= 6) {
                                    replaced = args.toArray();
                                    // 跳过主水印
                                    replaced[4] = Boolean.TRUE;
                                    // 跳过 AI 水印
                                    replaced[5] = Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                HookKit.error(module, f.id + " intercept error bm8.j#a", t);
                            }
                            // proceed 放在 try 外：原方法自己抛异常时不能再调一次。
                            return replaced == null ? chain.proceed() : chain.proceed(replaced);
                        })) {
                    installed++;
                    mainInstalled++;
                }
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: class bm8.j " + t);
        }

        // 2) 入口侧：bm8.i.i（9.51.0 为 ce8.i.i）的 disableWaterMark / disableAiWaterMark 置 true。
        int saveEntry = hookSaveEntry(module, f, cl);
        installed += saveEntry;
        mainInstalled += saveEntry;

        // 3) 语义 getter：让上层查询也认为"无水印"。可选锚点：缺失只记 skip，不标 critical。
        try {
            Method m = HookKit.anchorMethod(cl, AnchorKeys.WATERMARK_MODEL_GETTER);
            if (m != null && HookKit.install(module, f, "MediaSaveConfig#c(no-watermark)", m,
                    HookKit.constBool(module, f, "MediaSaveConfig#c", true))) {
                installed++;
            }
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: class MediaSaveConfig " + t);
        }

        if (installed == 0) {
            HookState.markNotFound(f, "水印挂载点未找到");
        } else if (mainInstalled == 0) {
            HookState.markNotFound(f, "水印主链挂载点未找到（仅语义 getter 生效）");
        }
    }

    /**
     * 入口侧改参逻辑，抽成纯静态方法以便 JVM 单测（WatermarkHooksTest）。
     *
     * <p>bm8.i#i 是 11 参：{@code (Activity, String, String, String, String, Z,
     * Function1, Function1, Z, Z, Z)}，布尔位在 index 5、8、9、10。
     * dexsql 对 9.51.0/9.52.0 反汇编的结论（9.52.0 为 classes21/classes22.dex；
     * 下列 jn6.* 为 9.52.0 类名，9.52.1 对应包为 bm8.*）：
     * <ul>
     *   <li>index 5 → disableWaterMark：两条路径都传入 jn6.d 的字段 m，
     *       subscribe 里既做 fail-open 守卫
     *       {@code if (!m && watermark == null) fail(-4 "save image failed without watermark")}，
     *       又作为 bm8.j#a 第 5 参（true 时跳过主水印 drawBitmap）。</li>
     *   <li>index 10 → disableAiWaterMark：传入 jn6.d 的字段 o，
     *       subscribe 里以 {@code o && bm8.i#b()} 作为 bm8.j#a 第 6 参
     *       （方法体首行日志 "disableAiWaterMark is ..."，true 时跳过 AI 水印 drawBitmap）。</li>
     *   <li>index 8 → 仅在位图为空的兜底路径传入 jn6.h.p → jn6.e，是"存入相册
     *       (MediaStore DCIM)"的副作用开关，与水印绘制无关，不设。</li>
     *   <li>index 9 → 传入 jn6.d 的字段 i，作为 bm8.j#c(Context,String,Z) /
     *       bm8.j#b(Context,Z) 取图的布尔参数（缓存/强制类开关），不设。</li>
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
     * 9.50.0 该方法是 nd8.i.g，9.51.0 为 ce8.i.i，9.52.0 为 jn6.i.i，9.52.1 为 bm8.i.i。
     */
    private static int hookSaveEntry(XposedModule module, Feature f, ClassLoader cl) {
        try {
            // 关键锚点：bm8.i#i 是保存入口，缺失即入口侧失效，记 critical 进失败横幅。
            Method m = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.WATERMARK_SAVE_ENTRY, true);
            if (m == null) {
                return 0;
            }
            return HookKit.install(module, f, "bm8.i#i(disableWaterMark=true)", m,
                    chain -> {
                        Object[] replaced = null;
                        try {
                            HookKit.hit(module, f, "bm8.i#i");
                            List<Object> args = chain.getArgs();
                            replaced = applyWatermarkFlags(args.toArray());
                        } catch (Throwable t) {
                            HookKit.error(module, f.id + " intercept error bm8.i#i", t);
                        }
                        return replaced == null ? chain.proceed() : chain.proceed(replaced);
                    }) ? 1 : 0;
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: anchor bm8.i#i " + t);
            return 0;
        }
    }

}
