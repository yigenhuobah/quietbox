package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * EX-01 已删除/不可见笔记仍按本地缓存展示。
 *
 * 现象：笔记在信息流里还能看到（封面、标题、图片都在本地），点进去却立刻变成
 * 「当前内容无法展示」。断网时反而能正常浏览 —— 说明详情页在请求失败时会保留
 * 首屏已有的数据，只有服务端明确返回「错误页」模型时才把内容换掉。
 *
 * 服务端的错误页是 NoteFeed.modelType == "error"（资源字符串「当前内容无法展示」
 * 就是它的错误页）。NoteDetailRepository 的 map 阶段（混淆 lambda sr9.o，
 * 9.50.0 为 dr9.o，由 NoteDetailRepository.k 直接构造）会把这个
 * 错误页包装成 DetailNoteFeedHolder 交给 UI。
 *
 * 处理方式：
 * 1. 仓库里还留着上一份正常 holder（同一 noteId）→ 直接返回它，页面内容不变；
 * 2. 没有可用的旧 holder → 抛 IOException，让 RxJava 走 onError 分支，
 *    也就是断网时那条已验证可用的路径。
 *
 * 注意：不修改 NoteFeed 字段、不伪造内容。没有本地数据时页面会是空的/保持首屏，
 * 但不会伪造出一条笔记。
 */
final class NoteUnavailableHooks {

    /** 混淆 lambda：NoteDetailRepository 的 map 阶段。9.50.0 为 dr9.o。 */
    private static final String LAMBDA = "sr9.o";
    private static final String REPO = "com.xingin.matrix.notedetail.v3.repo.NoteDetailRepository";
    private static final String HOLDER = "com.xingin.notebase.entities.notedetail.DetailNoteFeedHolder";
    private static final String FEED = "com.xingin.entities.notedetail.NoteFeed";
    private static final String ERROR_MODEL = "error";

    /** 每个进程只打一次，确认 map 挂载点确实在链路上。 */
    private static volatile boolean sDiagnosed;

    private NoteUnavailableHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.NOTE_UNAVAILABLE_VIEW;
        try {
            Class<?> lambda = HookKit.findClass(module, f, cl, LAMBDA);
            if (lambda == null) {
                return;
            }
            Method invoke = HookKit.findByNameAndArity(lambda, "invoke", 1);
            HookKit.install(module, f, "NoteDetailRepository#map", invoke, hook(module, f),
                    XposedInterface.ExceptionMode.PASSTHROUGH);
            HookState.note(f, "服务端返回 error 页时改用本地数据/离线路径");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            HookState.note(f, "安装异常");
        }
    }

    private static XposedInterface.Hooker hook(XposedModule module, Feature f) {
        return chain -> {
            Object result;
            try {
                result = chain.proceed();
            } catch (Throwable t) {
                // 原方法自己失败，交给下游原有的错误处理。
                throw t;
            }
            try {
                if (!sDiagnosed) {
                    sDiagnosed = true;
                    HookKit.hit(module, f, "map observed result=" + className(result)
                            + " modelType=" + modelType(feedOf(result)));
                }
                if (!isErrorHolder(result)) {
                    return result;
                }
                Object repo = valueOfType(chain.getThisObject(), REPO);
                Object previous = repo == null ? null : valueOfType(repo, HOLDER);
                if (isUsableHolder(previous) && sameNote(previous, result)) {
                    HookKit.hit(module, f, "restore cached holder");
                    return previous;
                }
                HookKit.hit(module, f, "error feed -> offline path");
                // RxJava 会把它当成请求失败，走与断网相同的分支。
                throw new IOException("quietbox: note unavailable");
            } catch (IOException io) {
                throw io;
            } catch (Throwable t) {
                HookKit.error(module, f.id + " intercept error", t);
                return result;
            }
        };
    }

    /** 结果是 DetailNoteFeedHolder 且主 NoteFeed 的 modelType 为 "error"。 */
    private static boolean isErrorHolder(Object holder) {
        Object feed = feedOf(holder);
        return feed != null && ERROR_MODEL.equals(modelType(feed));
    }

    /** 旧 holder 可用：非空、有 NoteFeed、且本身不是错误页。 */
    private static boolean isUsableHolder(Object holder) {
        Object feed = feedOf(holder);
        return feed != null && !ERROR_MODEL.equals(modelType(feed));
    }

    /** 同一篇笔记才允许回填，避免把上一篇的内容显示到当前页。 */
    private static boolean sameNote(Object a, Object b) {
        Object fa = feedOf(a);
        Object fb = feedOf(b);
        if (fa == null || fb == null) {
            return false;
        }
        String ida = noteId(fa);
        String idb = noteId(fb);
        return ida != null && ida.length() > 0 && ida.equals(idb);
    }

    private static Object feedOf(Object holder) {        if (holder == null || !HOLDER.equals(holder.getClass().getName())) {
            return null;
        }
        return callNoArg(holder, "b");
    }

    private static String modelType(Object feed) {
        Object v = callNoArg(feed, "c1");
        return v instanceof String ? (String) v : null;
    }

    private static String noteId(Object feed) {
        Object v = callNoArg(feed, "u0");
        return v instanceof String ? (String) v : null;
    }

    private static String className(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static Object callNoArg(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在对象（含父类）的字段里按运行时类型找一个值。混淆 lambda 的字段都声明成 Object。 */
    private static Object valueOfType(Object target, String typeName) {
        if (target == null) {
            return null;
        }
        try {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(target);
                        if (value != null && typeName.equals(value.getClass().getName())) {
                            return value;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
