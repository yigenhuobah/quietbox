package com.quietbox.app;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块进程侧的 XposedService 绑定。静盒的开关页必须通过 {@link XposedService#getRemotePreferences}
 * 写入，因为小红书进程读的是那份存储——写进模块自己的 MODE_PRIVATE 对目标进程不可见。
 *
 * <p>binder 的中转由 manifest 里声明的 {@code io.github.libxposed.service.XposedProvider} 负责
 * （authority 必须是 {@code <applicationId>.XposedService}）：官方 provider 调的是同包的
 * package-private 入口，所以这里不能再包一层，只能直接用它。
 */
public final class ServiceBridge {

    private static final String TAG = "QuietBox";
    private static final String AUTHORITY_SUFFIX = ".XposedService";

    private static final AtomicReference<XposedService> sService = new AtomicReference<>();

    private static boolean sListenerRegistered;

    /** service 真正绑定后要跑的一次性重绘；由 UI 层设置。 */
    private static Runnable pendingRender = () -> { };

    private ServiceBridge() {
    }

    /** UI 层在等 service 绑定时挂一个重绘回调。 */
    public static void setOnBound(Runnable r) {
        pendingRender = (r == null) ? () -> { } : r;
    }

    /**
     * 框架通过 {@code <packageName>.XposedService} 这个 authority 把 binder 送进我们的 provider，
     * 所以 manifest 必须声明 {@link Provider}，且 android:authorities 与 applicationId 一致。
     * 框架可能有多个，保留最后一个可用实例。
     */
    public static synchronized void bind(Context context) {
        if (sListenerRegistered) {
            return;
        }
        sListenerRegistered = true;
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                // 框架会先投一个 api=0 且无 PROP_CAP_REMOTE 的占位 binder，随后才投真正的
                // （实测日志：先 api=0，几百毫秒后 api=102）。占位的必须丢弃，
                // 否则 getFrameworkProperties() 缺失会被误判成框架不支持远程配置。
                if (safeApi(service) < 101) {
                    Log.i(TAG, "ignoring placeholder binder, api=" + safeApi(service));
                    return;
                }
                XposedService previous = sService.getAndSet(service);
                Log.i(TAG, "service bound, api=" + safeApi(service) + " replaced=" + (previous != null));
                // 绑定真正到位后再 poke 一次，让 framework 侧知道我们要用它。
                pendingRender.run();
            }

            @Override
            public void onServiceDied(XposedService service) {
                if (sService.compareAndSet(service, null)) {
                    Log.w(TAG, "service died");
                }
            }
        });
        // 框架可能在 registerListener 之前就已完成投递，此时不会再有回调，补一次主动取。
        if (sService.get() == null) {
            poke(context);
        }
    }

    public static synchronized XposedService get() {
        return sService.get();
    }

    /**
     * 拿一份可读写的 remote preferences；service 未绑定或框架不支持时返回 null。
     * 调用方据此决定回退到本地暂存。
     */
    public static android.content.SharedPreferences remotePreferences(String group) {
        XposedService service = sService.get();
        if (service == null) {
            // 这条日志是用来抓"静默回退本地存储"的：没有它，开关改不动会毫无线索。
            Log.w(TAG, "remote prefs requested before service bound");
            return null;
        }
        try {
            if ((service.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) == 0) {
                Log.w(TAG, "framework lacks PROP_CAP_REMOTE");
                return null;
            }
            return service.getRemotePreferences(group);
        } catch (Throwable t) {
            Log.w(TAG, "remote prefs unavailable", t);
            return null;
        }
    }

    /** 向自己的 provider 发一次 call，让框架重新投递 binder。 */
    public static void poke(Context context) {
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putBinder("binder", new android.os.Binder());
            context.getContentResolver().call(
                    android.net.Uri.parse("content://" + context.getPackageName() + AUTHORITY_SUFFIX),
                    "SendBinder", null, extras);
        } catch (Throwable t) {
            Log.w(TAG, "poke failed", t);
        }
    }

    /** 取不到 API 版本时返回 0，调用方按 0 处理为不可用。 */
    private static int safeApi(XposedService service) {
        try {
            return service.getApiVersion();
        } catch (Throwable t) {
            return 0;
        }
    }
}
