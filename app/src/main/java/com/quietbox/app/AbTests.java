package com.quietbox.app;

import java.util.HashSet;
import java.util.Set;

/**
 * 精选 AB 实验清单。
 *
 * 收录标准：
 * 1) 代码里确实有客户端读取点（tq.h / eb6.o），不是纯服务端下发；
 * 2) 默认值是 false/0，强开才有意义（默认 true 的项是 no-op，不收录）；
 * 3) 和现有功能不重复，且对日常浏览有正面收益。
 *
 * 广告、埋点、直播、服务端调参一律不收录。
 */
public final class AbTests {

    private static final Set<String> CURATED = new HashSet<>();

    public static final AbTest[] ALL = {
            // 详情页与信息流
            new AbTest("andr_note_detail_text_layout_optimized4_new",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_text_layout,
                    R.string.ab_detail_text_layout_desc, "true"),
            new AbTest("andr_note_detail_scroll_opt_horizon",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_scroll,
                    R.string.ab_detail_scroll_desc, "true"),
            new AbTest("andr_note_image_detail_cache_layout_opt",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_image_cache,
                    R.string.ab_detail_image_cache_desc, "true"),
            new AbTest("andr_note_detail_text_pre_parse_optimized_new",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_text_pre_parse,
                    R.string.ab_detail_text_pre_parse_desc, "true"),
            new AbTest("andr_note_detail_engage_bar_first_frame_opt",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_engage_bar,
                    R.string.ab_detail_engage_bar_desc, "true"),
            new AbTest("andr_note_detail_async_precomputed_text",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_async_text,
                    R.string.ab_detail_async_text_desc, "true"),
            new AbTest("andr_detail_feed_layout_preload_opt_new",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_layout_preload,
                    R.string.ab_detail_layout_preload_desc, "1"),
            new AbTest("andr_profile_layout_request_preload",
                    AbTest.GROUP_DETAIL, R.string.ab_profile_preload,
                    R.string.ab_profile_preload_desc, "1"),

            // 播放体验
            new AbTest("andr_video_progress_bar_hide_opt_enable",
                    AbTest.GROUP_PLAYBACK, R.string.ab_video_progress_hide,
                    R.string.ab_video_progress_hide_desc, "1"),
            new AbTest("andr_video_persistent_play_position",
                    AbTest.GROUP_PLAYBACK, R.string.ab_video_resume_position,
                    R.string.ab_video_resume_position_desc, "1"),
            new AbTest("andr_video_play_continuity_out_app",
                    AbTest.GROUP_PLAYBACK, R.string.ab_video_background_play,
                    R.string.ab_video_background_play_desc, "1"),
            new AbTest("andr_video_edge_gesture_exclusion",
                    AbTest.GROUP_PLAYBACK, R.string.ab_video_edge_gesture,
                    R.string.ab_video_edge_gesture_desc, "1"),
            new AbTest("andr_video_player_vibrate_feed_back",
                    AbTest.GROUP_PLAYBACK, R.string.ab_video_vibrate,
                    R.string.ab_video_vibrate_desc, "1"),
            new AbTest("andr_video_light_ui_opt",
                    AbTest.GROUP_PLAYBACK, R.string.ab_video_light_ui,
                    R.string.ab_video_light_ui_desc, "1"),
            new AbTest("andr_caption_new_style_v3",
                    AbTest.GROUP_PLAYBACK, R.string.ab_caption_new_style,
                    R.string.ab_caption_new_style_desc, "1"),

            // 其他推荐
            new AbTest("andr_cmt_optimization_keyboard",
                    AbTest.GROUP_MISC, R.string.ab_cmt_keyboard,
                    R.string.ab_cmt_keyboard_desc, "1"),
            new AbTest("andr_homefeed_preload_video",
                    AbTest.GROUP_MISC, R.string.ab_home_preload_video,
                    R.string.ab_home_preload_video_desc, "true"),
            new AbTest("andr_search_disable_force_dark",
                    AbTest.GROUP_MISC, R.string.ab_search_dark,
                    R.string.ab_search_dark_desc, "true"),
            new AbTest("andr_home_feed_red_dot_opt",
                    AbTest.GROUP_MISC, R.string.ab_home_red_dot,
                    R.string.ab_home_red_dot_desc, "1"),
    };

    static {
        for (AbTest t : ALL) {
            CURATED.add(t.key);
        }
    }

    private AbTests() {
    }

    public static boolean isCurated(String key) {
        return key != null && CURATED.contains(key);
    }

    public static int groupTitle(int group) {
        switch (group) {
            case AbTest.GROUP_DETAIL:
                return R.string.ab_group_detail;
            case AbTest.GROUP_PLAYBACK:
                return R.string.ab_group_playback;
            default:
                return R.string.ab_group_misc;
        }
    }
}
