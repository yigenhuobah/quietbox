package com.quietbox.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 挂载自检的 App 侧读取。
 *
 * 数据由目标进程在 {@link com.quietbox.app.hooks.Hooks} 安装完挂载点后，经
 * {@link AbReportProvider} 的 "mount" 方法一次性写入。模块 App 只读。
 *
 * 判据刻意保守：只有「确实收到过统计」且「miss 超过阈值」才认为不兼容，
 * 避免统计缺失（旧版本模块、provider 被拦）时误报。
 */
public final class MountReport {

    /** miss 数达到这个值就认为当前小红书版本大概率不兼容。 */
    public static final int MISS_THRESHOLD = 5;

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

    /** 是否应该显示「当前小红书版本不兼容」横幅。 */
    public boolean incompatible() {
        return received() && bad() >= 2;
    }
}
