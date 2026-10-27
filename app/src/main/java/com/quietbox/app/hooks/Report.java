package com.quietbox.app.hooks;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.StatusProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 目标进程 → 模块 App 的诊断回传（registered / hit N / skip）。
 *
 * 方向与 AbReportProvider 相同：hook 侧只能主动 call 进模块的 ContentProvider
 * （remote prefs 在目标进程只读）。Context 用 ActivityThread 反射拿——框架类
 * 黑名单禁止 hook android.app.Application，不能像别处那样挂 onCreate 兜底。
 *
 * 只由主进程启动：子进程的 hook 注册状态不完整，回传会让诊断页误报。
 * 节流：后台单线程每 3 秒把 HookState 快照打平上报一次，内容没变不发；
 * provider 不可达时退避 60 秒，不刷日志。
 */
final class Report {

    private static final String TAG = "QuietBox";
    private static final int MAX_VALUE = 256;

    private static volatile boolean sStarted;
    private static volatile Context sContext;
    private static volatile Map<String, String> sLast = new TreeMap<>();
    private static volatile long sNextTry;
    private static volatile int sErrors;

    private Report() {
    }

    static synchronized void start() {
        if (sStarted) {
            return;
        }
        sStarted = true;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QuietBox-Report");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            try {
                flush();
            } catch (Throwable ignored) {
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    private static void flush() {
        Map<String, String> batch = snapshot();
        if (batch.isEmpty() || batch.equals(sLast)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < sNextTry) {
            return;
        }
        Context context = context();
        if (context == null) {
            return;
        }
        List<String> keys = new ArrayList<>(batch.keySet());
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            values.add(batch.get(key));
        }
        Bundle extras = new Bundle();
        extras.putStringArray("keys", keys.toArray(new String[0]));
        extras.putStringArray("values", values.toArray(new String[0]));
        try {
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://" + StatusProvider.AUTHORITY), "report", null, extras);
            if (result != null) {
                sLast = batch;
                return;
            }
            // call() 返回 null 视为 provider 暂不可达：与抛异常一样退避 60 秒，
            // 不更新 sLast，下次内容变化或退避到期后再试。
            sNextTry = System.currentTimeMillis() + 60_000L;
            if (sErrors++ < 3) {
                Log.w(TAG, "status provider returned null");
            }
        } catch (Throwable t) {
            // 模块 App 未启动、被系统拦截等情况：一分钟后再试，别每 3 秒刷日志。
            sNextTry = System.currentTimeMillis() + 60_000L;
            if (sErrors++ < 3) {
                Log.w(TAG, "status provider unavailable", t);
            }
        }
    }

    /**
     * HookState 快照 → 平铺的 key/value（key 用 reg./hit./skip. 前缀，value 截断到 256）。
     *
     * reg 与 skip 对每个 touched Feature 都必须发送，缺失时发空串：StatusProvider 用
     * 最新值覆盖旧值，只有空串才能清掉上一次目标进程会话遗留的 skip。否则旧 skip
     * 会永久留在 status_report 里，让横幅按「已启用且 skip 非空」持续误报。
     * hit 仍只在 >0 时发送，由 StatusProvider 取历史最大值，不会被空值覆盖。
     */
    static Map<String, String> snapshot() {
        Map<String, String> out = new TreeMap<>();
        for (Feature f : HookState.touched()) {
            out.put(StatusProvider.PREFIX_REG + f.key,
                    truncate(join(HookState.labels(f))));
            long hits = HookState.hitCount(f);
            if (hits > 0) {
                out.put(StatusProvider.PREFIX_HIT + f.key, String.valueOf(hits));
            }
            out.put(StatusProvider.PREFIX_SKIP + f.key,
                    truncate(HookState.skipReason(f)));
        }
        return out;
    }

    private static String join(List<String> labels) {
        StringBuilder sb = new StringBuilder();
        for (String s : labels) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_VALUE ? s : s.substring(0, MAX_VALUE);
    }

    private static Context context() {
        Context c = sContext;
        if (c != null) {
            return c;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sContext = (Context) app;
                return sContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
