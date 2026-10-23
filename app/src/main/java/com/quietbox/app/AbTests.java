package com.quietbox.app;

import java.util.HashSet;
import java.util.Set;

/**
 * 精选 AB 实验清单。
 *
 * 收录标准：
 * 1) 代码里确实有客户端读取点（tq.h / mb6.o，9.50.0 为 eb6.o），不是纯服务端下发；
 * 2) 默认值是 false/0，强开才有意义；默认 true 但可被服务端改写的项也收录，
 *    作用是钉死防护，防服务端下发把已生效的行为关回去；
 * 3) 和现有功能不重复，且对日常浏览有正面收益。
 *
 * 广告、埋点、直播、服务端调参一律不收录。
 *
 * valueType 是 9.50.0 反汇编三重证据（TypeToken 泛型注解 + 默认值字面量 +
 * 返回值 check-cast）逐条判定的真实返回类型，勿凭 key 名猜。
 */
public final class AbTests {

    private static final Set<String> CURATED = new HashSet<>();

    private static final AbTest.ValueType BOOL = AbTest.ValueType.BOOL;
    private static final AbTest.ValueType INT = AbTest.ValueType.INT;

    public static final AbTest[] ALL = {
            // 详情页与信息流
            new AbTest("andr_note_detail_text_layout_optimized4_new",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_text_layout,
                    R.string.ab_detail_text_layout_desc, "true", BOOL),
            new AbTest("andr_note_detail_scroll_opt_horizon",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_scroll,
                    R.string.ab_detail_scroll_desc, "true", BOOL),
            new AbTest("andr_note_image_detail_cache_layout_opt",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_image_cache,
                    R.string.ab_detail_image_cache_desc, "true", BOOL),
            new AbTest("andr_note_detail_text_pre_parse_optimized_new",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_text_pre_parse,
                    R.string.ab_detail_text_pre_parse_desc, "true", BOOL),
            new AbTest("andr_note_detail_engage_bar_first_frame_opt",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_engage_bar,
                    R.string.ab_detail_engage_bar_desc, "true", BOOL),
            new AbTest("andr_note_detail_async_precomputed_text",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_async_text,
                    R.string.ab_detail_async_text_desc, "true", BOOL),
            // 两个读取点阈值不同：al2.u#k 判定 >0、al2.u#l 判定 ==1，喂 1 同时满足。
            new AbTest("andr_detail_feed_layout_preload_opt_new",
                    AbTest.GROUP_DETAIL, R.string.ab_detail_layout_preload,
                    R.string.ab_detail_layout_preload_desc, "1", INT),
            // 两个读取点阈值不同：al2.u#U 判定 >0、ipc.g#invoke 判定 >1，喂 2 才都点亮。
            new AbTest("andr_profile_layout_request_preload",
                    AbTest.GROUP_DETAIL, R.string.ab_profile_preload,
                    R.string.ab_profile_preload_desc, "2", INT),
            new AbTest("andr_note_detail_preload_p3_image_request",
                    AbTest.GROUP_DETAIL, R.string.ab_p3_preload,
                    R.string.ab_p3_preload_desc, "true", BOOL),

            // 播放体验
            new AbTest("andr_video_progress_bar_hide_opt_enable",
                    AbTest.GROUP_PLAYBACK, R.string.ab_video_progress_hide,
                    R.string.ab_video_progress_hide_desc, "1", INT),
            new AbTest("andr_video_persistent_play_position",
                    AbTest.GROUP_PLAYBACK, R.string.ab_video_resume_position,
                    R.string.ab_video_resume_position_desc, "1", INT),
            new AbTest("andr_video_play_continuity_out_app",
                    AbTest.GROUP_PLAYBACK, R.string.ab_video_background_play,
                    R.string.ab_video_background_play_desc, "1", INT),
            new AbTest("andr_video_edge_gesture_exclusion",
                    AbTest.GROUP_PLAYBACK, R.string.ab_video_edge_gesture,
                    R.string.ab_video_edge_gesture_desc, "1", INT),
            new AbTest("andr_video_player_vibrate_feed_back",
                    AbTest.GROUP_PLAYBACK, R.string.ab_video_vibrate,
                    R.string.ab_video_vibrate_desc, "1", INT),
            new AbTest("andr_video_light_ui_opt",
                    AbTest.GROUP_PLAYBACK, R.string.ab_video_light_ui,
                    R.string.ab_video_light_ui_desc, "1", INT),
            new AbTest("caption_new_style_v3",
                    AbTest.GROUP_PLAYBACK, R.string.ab_caption_new_style,
                    R.string.ab_caption_new_style_desc, "1", INT),
            new AbTest("andr_player_intelligent_rep_select_disable_filter_2k_4k",
                    AbTest.GROUP_PLAYBACK, R.string.ab_2k4k_filter,
                    R.string.ab_2k4k_filter_desc, "1", INT),

            // 其他推荐
            new AbTest("andr_cmt_optimization_keyboard",
                    AbTest.GROUP_MISC, R.string.ab_cmt_keyboard,
                    R.string.ab_cmt_keyboard_desc, "1", INT),
            // v0.5.0 曾误传 "true"：该读点是 Integer 型，Integer.valueOf("true")
            // 抛异常被吞，开关静默失效；v0.6.0 起改 "1"。
            new AbTest("andr_homefeed_preload_video",
                    AbTest.GROUP_MISC, R.string.ab_home_preload_video,
                    R.string.ab_home_preload_video_desc, "1", INT),
            new AbTest("andr_search_disable_force_dark",
                    AbTest.GROUP_MISC, R.string.ab_search_dark,
                    R.string.ab_search_dark_desc, "true", BOOL),
            new AbTest("andr_home_feed_red_dot_opt",
                    AbTest.GROUP_MISC, R.string.ab_home_red_dot,
                    R.string.ab_home_red_dot_desc, "1", INT),
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

    public static AbTest byKey(String key) {
        if (key == null) {
            return null;
        }
        for (AbTest t : ALL) {
            if (t.key.equals(key)) {
                return t;
            }
        }
        return null;
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
