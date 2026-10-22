package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import io.github.libxposed.api.XposedModule;

/**
 * UI-05 自动播放与画质偏好。
 * 只实现自动播放：isAutoPlayOpen 是 SPI 契约方法（详情页），
 * isAutoPlayOpenServer 是 tab 场景实际被调用的那个（VideoTabProxyImpl.java:70
 * 调 AutoScrollStateTabHelper.INSTANCE.isAutoPlayOpenServer()），故两个类各挂两个。
 * 画质偏好不实现：getDefinitionPreference 的返回值在可读源码里没有任何一处
 * 决定播放清晰度（真实档位来自事件对象 wlc.o.f110816b），且「最高档」码是服务端下发，
 * 模块内无法硬编码。
 * isAutoPlayOpen 方法体所在 classes4.dex 未反编译，「返回 false 即关闭自动播放」
 * 是按方法名与接口契约推断。
 */
final class AutoPlayHooks {

    private static final String DETAIL = "com.xingin.detailfeed.services.autoscroll.AutoScrollStateHelper";
    private static final String TAB = "com.xingin.detailfeed.services.autoscroll.AutoScrollStateTabHelper";
    private static final String[] METHODS = {"isAutoPlayOpen", "isAutoPlayOpenServer"};

    private AutoPlayHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.AUTOPLAY_PREFERENCE;
        try {
            for (String clazz : new String[]{DETAIL, TAB}) {
                Class<?> cls = HookKit.findClass(module, f, cl, clazz);
                for (String name : METHODS) {
                    HookKit.install(module, f, shortName(clazz) + "#" + name,
                            HookKit.findMethod(module, f, cls, name),
                            HookKit.constBool(module, f, shortName(clazz) + "#" + name, false));
                }
            }
            HookState.note(f, "仅自动播放；画质偏好在可读源码中无可确认的消费方，未实现");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    private static String shortName(String clazz) {
        int i = clazz.lastIndexOf('.');
        return i < 0 ? clazz : clazz.substring(i + 1);
    }
}
