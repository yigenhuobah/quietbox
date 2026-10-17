package com.quietbox.app;

/**
 * 功能清单。key 同时是模块 App 内 remote prefs 的键，也是目标进程
 * getRemotePreferences("config") 的键，两处必须一致。
 *
 * blockedReason 非空表示本版本无可实现的挂载点：只登记、不注册 hook、也不在 UI 里
 * 渲染成开关（页脚统一说明）。
 */
public enum Feature {

    SPLASH_AD_SKIP("P0-1", R.string.f_p0_1_title, R.string.f_p0_1_summary,
            "p0_1_splash_ad_skip", false, null),
    FEED_AD_FILTER("P0-2", R.string.f_p0_2_title, R.string.f_p0_2_summary,
            "p0_2_feed_ad_filter", false, null),
    COMMENT_AD_FILTER("P0-3", R.string.f_p0_3_title, R.string.f_p0_3_summary,
            "p0_3_comment_ad_filter", false, null),
    UPDATE_DIALOG_BLOCK("P0-4", R.string.f_p0_4_title, R.string.f_p0_4_summary,
            "p0_4_update_dialog_block", false, null),
    SCREENSHOT_DETECT_OFF("P0-5", R.string.f_p0_5_title, R.string.f_p0_5_summary,
            "p0_5_screenshot_detect_off", false, null),
    VIDEO_DEFAULT_MUTE("P0-6", R.string.f_p0_6_title, R.string.f_p0_6_summary,
            "p0_6_video_default_mute", false,
            "小红书自带自动静音，按用户要求关闭"),

    EXT_APP_BLOCK("P1-7", R.string.f_p1_7_title, R.string.f_p1_7_summary,
            "p1_7_ext_app_block", false, null),
    SHAKE_JUMP_DISABLE("P1-8", R.string.f_p1_8_title, R.string.f_p1_8_summary,
            "p1_8_shake_jump_disable", false,
            "广告摇一摇的检测在原生/Predy 引擎侧，Java 层无精确挂载点"),
    COPY_LINK_SANITIZE("P1-9", R.string.f_p1_9_title, R.string.f_p1_9_summary,
            "p1_9_copy_link_sanitize", false, null),
    TRACK_SUPPRESS("P1-10", R.string.f_p1_10_title, R.string.f_p1_10_summary,
            "p1_10_track_suppress", false,
            "本版本无可实现挂载点：Lancet 插桩方法体内唯一动作是调用原方法体，跳过即断生命周期"),
    WATERMARK_POLICY("P1-11", R.string.f_p1_11_title, R.string.f_p1_11_summary,
            "p1_11_watermark_policy", false, null),
    AUTOPLAY_PREFERENCE("P1-12", R.string.f_p1_12_title, R.string.f_p1_12_summary,
            "p1_12_autoplay_preference", false, null),
    SEARCH_AD_FILTER("P1-13", R.string.f_p1_13_title, R.string.f_p1_13_summary,
            "p1_13_search_ad_filter", false, null),
    NOTE_DETAIL_AD_BANNER("P1-14", R.string.f_p1_14_title, R.string.f_p1_14_summary,
            "p1_14_note_detail_ad_banner", false, null),
    NOTE_UNAVAILABLE_VIEW("P1-15", R.string.f_p1_15_title, R.string.f_p1_15_summary,
            "p1_15_note_unavailable_view", false, null);

    public final String id;
    public final int titleRes;
    public final int summaryRes;
    public final String key;
    /** 编译期内置默认值：无 PROP_CAP_REMOTE 时用，全部关闭。 */
    public final boolean defaultEnabled;
    /** 非空 = 本版本不注册任何 hook。 */
    public final String blockedReason;

    Feature(String id, int titleRes, int summaryRes, String key,
            boolean defaultEnabled, String blockedReason) {
        this.id = id;
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
        this.key = key;
        this.defaultEnabled = defaultEnabled;
        this.blockedReason = blockedReason;
    }

    public boolean blocked() {
        return blockedReason != null;
    }
}
