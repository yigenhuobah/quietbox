package com.quietbox.app;

/**
 * 功能清单。key 同时是模块 App 内 remote prefs 的键，也是目标进程
 * getRemotePreferences("config") 的键，两处必须一致。
 *
 * id 是统一功能编号（AD/UI/PV/EN/EX + 两位序号），只用于文档、日志前缀、
 * 诊断页与 CHANGELOG；remote key、AB key、类名不随编号变化。
 *
 * blockedReason 非空表示本版本无可实现的挂载点：保留编号与条目、默认关闭、
 * 不注册 hook、不在设置页渲染。
 */
public enum Feature {

    SPLASH_AD_SKIP("AD-01", "p0_1_splash_ad_skip",
            R.string.f_p0_1_splash_ad_skip_title, R.string.f_p0_1_splash_ad_skip_summary,
            true, Group.AD, null),
    FEED_AD_FILTER("AD-02", "p0_2_feed_ad_filter",
            R.string.f_p0_2_feed_ad_filter_title, R.string.f_p0_2_feed_ad_filter_summary,
            true, Group.AD, null),
    COMMENT_AD_FILTER("AD-03", "p0_3_comment_ad_filter",
            R.string.f_p0_3_comment_ad_filter_title, R.string.f_p0_3_comment_ad_filter_summary,
            true, Group.AD, null),
    EXT_APP_BLOCK("AD-04", "p1_7_ext_app_block",
            R.string.f_p1_7_ext_app_block_title, R.string.f_p1_7_ext_app_block_summary,
            true, Group.AD, null),
    SEARCH_AD_FILTER("AD-05", "p1_13_search_ad_filter",
            R.string.f_p1_13_search_ad_filter_title, R.string.f_p1_13_search_ad_filter_summary,
            true, Group.AD, null),
    NOTE_DETAIL_AD_BANNER("AD-06", "p1_14_note_detail_ad_banner",
            R.string.f_p1_14_note_detail_ad_banner_title,
            R.string.f_p1_14_note_detail_ad_banner_summary,
            true, Group.AD, null),
    GOODS_COMMENT_BLOCK("AD-07", "p1_20_goods_comment_block",
            R.string.f_p1_20_goods_comment_block_title,
            R.string.f_p1_20_goods_comment_block_summary,
            true, Group.AD, null),
    FEED_LIVE_CARD_BLOCK("AD-08", "p1_21_feed_live_card_block",
            R.string.f_p1_21_feed_live_card_block_title,
            R.string.f_p1_21_feed_live_card_block_summary,
            true, Group.AD, null),
    FEED_GOODS_CARD_BLOCK("AD-09", "p1_22_feed_goods_card_block",
            R.string.f_p1_22_feed_goods_card_block_title,
            R.string.f_p1_22_feed_goods_card_block_summary,
            true, Group.AD, null),
    FEED_VOTE_CARD_BLOCK("AD-10", "p1_23_feed_vote_card_block",
            R.string.f_p1_23_feed_vote_card_block_title,
            R.string.f_p1_23_feed_vote_card_block_summary,
            true, Group.AD, null),
    SHAKE_JUMP_DISABLE("AD-11", "p1_8_shake_jump_disable",
            R.string.f_p1_8_shake_jump_disable_title,
            R.string.f_p1_8_shake_jump_disable_summary,
            false, Group.AD,
            "广告摇一摇检测在原生/Predy 引擎侧，Java 层无精确挂载点"),

    SCORE_GUIDE_BLOCK("UI-01", "p1_16_score_guide_block",
            R.string.f_p1_16_score_guide_block_title,
            R.string.f_p1_16_score_guide_block_summary,
            true, Group.UI, null),
    PUSH_GUIDE_BLOCK("UI-02", "p1_17_push_guide_block",
            R.string.f_p1_17_push_guide_block_title,
            R.string.f_p1_17_push_guide_block_summary,
            true, Group.UI, null),
    CONTENT_RATING_BLOCK("UI-03", "p1_18_content_rating_block",
            R.string.f_p1_18_content_rating_block_title,
            R.string.f_p1_18_content_rating_block_summary,
            true, Group.UI, null),
    UPDATE_DIALOG_BLOCK("UI-04", "p0_4_update_dialog_block",
            R.string.f_p0_4_update_dialog_block_title,
            R.string.f_p0_4_update_dialog_block_summary,
            true, Group.UI, null),
    AUTOPLAY_PREFERENCE("UI-05", "p1_12_autoplay_preference",
            R.string.f_p1_12_autoplay_preference_title,
            R.string.f_p1_12_autoplay_preference_summary,
            true, Group.UI, null),

    SCREENSHOT_DETECT_OFF("PV-01", "p0_5_screenshot_detect_off",
            R.string.f_p0_5_screenshot_detect_off_title,
            R.string.f_p0_5_screenshot_detect_off_summary,
            false, Group.PV, null),
    CONTACT_SYNC_OFF("PV-02", "p1_19_contact_sync_off",
            R.string.f_p1_19_contact_sync_off_title,
            R.string.f_p1_19_contact_sync_off_summary,
            false, Group.PV, null),
    COPY_LINK_SANITIZE("PV-03", "p1_9_copy_link_sanitize",
            R.string.f_p1_9_copy_link_sanitize_title,
            R.string.f_p1_9_copy_link_sanitize_summary,
            false, Group.PV, null),
    TRACK_SUPPRESS("PV-04", "p1_10_track_suppress",
            R.string.f_p1_10_track_suppress_title,
            R.string.f_p1_10_track_suppress_summary,
            false, Group.PV,
            "本版本无可实现挂载点：Lancet 插桩方法体跳过即断生命周期"),

    WATERMARK_POLICY("EN-01", "p1_11_watermark_policy",
            R.string.f_p1_11_watermark_policy_title,
            R.string.f_p1_11_watermark_policy_summary,
            false, Group.EN, null),
    VIDEO_DEFAULT_MUTE("EN-02", "p0_6_video_default_mute",
            R.string.f_p0_6_video_default_mute_title,
            R.string.f_p0_6_video_default_mute_summary,
            false, Group.EN,
            "需求取消：小红书自带自动静音"),

    NOTE_UNAVAILABLE_VIEW("EX-01", "p1_15_note_unavailable_view",
            R.string.f_p1_15_note_unavailable_view_title,
            R.string.f_p1_15_note_unavailable_view_summary,
            false, Group.EX, null),
    AB_TEST_PANEL("EX-02", "p2_1_ab_test_panel",
            R.string.f_p2_1_ab_test_panel_title,
            R.string.f_p2_1_ab_test_panel_summary,
            false, Group.EX, null);

    public final String id;
    public final String key;
    public final int titleRes;
    public final int summaryRes;
    /** 编译期内置默认值：无 PROP_CAP_REMOTE 时用。AD/UI 开，PV/EN/EX 关。 */
    public final boolean defaultEnabled;
    public final Group group;
    /** 非空 = 本版本不注册任何 hook。 */
    public final String blockedReason;

    Feature(String id, String key, int titleRes, int summaryRes,
            boolean defaultEnabled, Group group, String blockedReason) {
        this.id = id;
        this.key = key;
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
        this.defaultEnabled = defaultEnabled;
        this.group = group;
        this.blockedReason = blockedReason;
    }

    public boolean blocked() {
        return blockedReason != null;
    }

    public enum Group {
        AD, UI, PV, EN, EX
    }
}
