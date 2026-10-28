package com.quietbox.app.hooks;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.ReportProtocol;
import com.quietbox.app.StatusProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 目标进程 → 模块 App 的诊断回传（registered / hit N / skip / critical）。
 *
 * 方向与 AbReportProvider 相同：hook 侧只能主动 call 进模块的 ContentProvider
 * （remote prefs 在目标进程只读）。Context 用 ActivityThread 反射拿——框架类
 * 黑名单禁止 hook android.app.Application，不能像别处那样挂 onCreate 兜底。
 *
 * 只由主进程启动：子进程的 hook 注册状态不完整，回传会让诊断页误报。
 * 节流：后台单线程每 3 秒把 HookState 快照打平上报一次，内容没变不发；
 * 每分钟至少发一次心跳刷新 meta.updated_at，避免长期无变化被判为过期。
 * provider 不可达时退避 60 秒，不刷日志。
 */
final class Report {

    private static final String TAG = "QuietBox";
    private static final int MAX_VALUE = 256;

    /** 本次目标进程会话标识：用启动时刻毫秒值，便于跨会话比较新旧。 */
    private static final String SESSION = String.valueOf(System.currentTimeMillis());

    /** 内容无变化时的心跳间隔；必须明显小于 StatusProvider 的 10 分钟 TTL。 */
    private static final long HEARTBEAT_MS = 60_000L;
    /** provider 失败后的退避时间。 */
    private static final long RETRY_MS = 60_000L;
    /** 单次 provider 调用最多等 2 秒，避免诊断线程被卡死的模块 App 永久阻塞。 */
    private static final long CALL_TIMEOUT_MS = 2_000L;

    /**
     * provider 调用池：单线程 + 容量 1 的有界队列。
     * provider 卡住时最多占住一个线程、排队一个任务，后续提交直接拒绝，
     * 不会像每次 new 线程池那样泄漏线程，也不会无限堆积任务。
     */
    private static final ExecutorService CALL_POOL = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, "QuietBox-Report-call");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private static volatile boolean sStarted;
    private static volatile Context sContext;
    private static volatile Map<String, String> sLast = new TreeMap<>();
    private static volatile long sLastOk;
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
        Map<String, String> content = contentOf(batch);
        if (content.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = !content.equals(sLast);
        // 内容没变也要按心跳重发，否则 meta.updated_at 会一直停在旧值，
        // 超过 TTL 后 StatusProvider 会把正常会话误判为过期。
        if (!changed && now - sLastOk < HEARTBEAT_MS) {
            return;
        }
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
        Bundle result = callProvider(context, extras);
        if (result != null) {
            sLast = content;
            sLastOk = now;
            return;
        }
        // call() 返回 null / 超时 / 抛异常都视为 provider 暂不可达：退避 60 秒，
        // 不更新 sLast，下次内容变化或退避到期后再试。
        sNextTry = System.currentTimeMillis() + RETRY_MS;
        if (sErrors++ < 3) {
            Log.w(TAG, "status provider unavailable");
        }
    }

    /**
     * provider 调用放到静态有界线程池，最多等 2 秒；超时取消并返回 null，
     * 由 flush 走原有退避路径，绝不向诊断线程外抛异常。
     */
    private static Bundle callProvider(Context context, Bundle extras) {
        final Uri uri = Uri.parse("content://" + StatusProvider.AUTHORITY);
        Future<Bundle> future;
        try {
            future = CALL_POOL.submit(() -> context.getContentResolver().call(uri, "report", null, extras));
        } catch (Throwable t) {
            // 队列已满（前一次调用卡死）或线程池已关闭：直接按失败处理。
            return null;
        }
        return ReportProtocol.awaitOrCancel(future, CALL_TIMEOUT_MS);
    }

    /** 去掉会话/时间戳后的业务内容，用于判断“内容是否真的变化”。 */
    private static Map<String, String> contentOf(Map<String, String> batch) {
        Map<String, String> out = new TreeMap<>(batch);
        out.remove(ReportProtocol.KEY_META_SESSION);
        out.remove(ReportProtocol.KEY_META_UPDATED_AT);
        return out;
    }

    /**
     * HookState 快照 → 平铺的 key/value（key 用 reg./hit./skip./crit. 前缀，
     * value 截断到 256），并附带 meta.session / meta.updated_at。
     *
     * reg 与 skip 对每个 touched Feature 都必须发送，缺失时发空串：StatusProvider 用
     * 最新值覆盖旧值，只有空串才能清掉上一次目标进程会话遗留的 skip。否则旧 skip
     * 会永久留在 status_report 里，让横幅按「已启用且 skip 非空」持续误报。
     * hit 仍只在 >0 时发送，由 StatusProvider 取历史最大值，不会被空值覆盖。
     * crit 只在主锚点（类）缺失时为 "1"，方法级 skip 保持空串。
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
            out.put(StatusProvider.PREFIX_CRIT + f.key,
                    HookState.hasCriticalSkip(f) ? "1" : "");
        }
        out.put(ReportProtocol.KEY_META_SESSION, SESSION);
        out.put(ReportProtocol.KEY_META_UPDATED_AT,
                String.valueOf(System.currentTimeMillis()));
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
