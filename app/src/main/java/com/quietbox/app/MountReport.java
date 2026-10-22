package com.quietbox.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 挂载自检的 App 侧读取。
 *
 * 数据由目标进程在 {@link com.quietbox.app.hooks.Hooks} 安装完挂载点后，经
 * {@link AbReportProvider} 的 "mount" 方法一次性写入。模块 App 只读。
 *
 * 这是模块专属扩展，保留给排障与后续展示；统一异常横幅改用
 * {@link StatusProvider#failedFeatureCount(Context)} 按「功能去重、≥2 功能失效」判断。
 */
public final class MountReport {

    private final SharedPreferences mSp;

    public MountReport(Context context) {
        mSp = context.getSharedPreferences(AbReportProvider.PREFS, Context.MODE_PRIVATE);
    }

    public boolean received() {
        return mSp.getLong(AbReportProvider.KEY_MOUNT_TIME, 0L) > 0L;
    }

    public int ok() {
        return mSp.getInt(AbReportProvider.KEY_MOUNT_OK, 0);
    }

    public int miss() {
        return mSp.getInt(AbReportProvider.KEY_MOUNT_MISS, 0);
    }

    /** 本次启用的功能数。 */
    public int features() {
        return mSp.getInt(AbReportProvider.KEY_MOUNT_FEATURES, 0);
    }

    /** miss > 0 的功能数。 */
    public int bad() {
        return mSp.getInt(AbReportProvider.KEY_MOUNT_BAD, 0);
    }

    /** 有 miss 的功能 id，逗号分隔。 */
    public String detail() {
        return mSp.getString(AbReportProvider.KEY_MOUNT_DETAIL, "");
    }
}
