package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Feature 编号、key 与编译期默认值映射。
 *
 * id 是统一功能编号，可随重编号调整；key 是 remote prefs 两读一写共用的键，
 * 改了会连带断掉已保存的开关状态，必须锁死。默认值遵循统一策略：
 * AD/UI 默认开，PV/EN/EX 默认关，blocked 一律默认关。
 */
public class FeatureTest {

    /** 统一编号格式：AD/UI/PV/EN/EX + 两位序号。 */
    private static final Pattern ID_PATTERN = Pattern.compile("^(AD|UI|PV|EN|EX)-\\d{2}$");

    /** FEATURE_ID_MAP.md 2.2 节 QuietBox 表：key → id，顺序即设置页顺序。 */
    private static final Map<String, String> EXPECTED_IDS = new LinkedHashMap<>();

    static {
        EXPECTED_IDS.put("p0_1_splash_ad_skip", "AD-01");
        EXPECTED_IDS.put("p0_2_feed_ad_filter", "AD-02");
        EXPECTED_IDS.put("p0_3_comment_ad_filter", "AD-03");
        EXPECTED_IDS.put("p1_7_ext_app_block", "AD-04");
        EXPECTED_IDS.put("p1_13_search_ad_filter", "AD-05");
        EXPECTED_IDS.put("p1_14_note_detail_ad_banner", "AD-06");
        EXPECTED_IDS.put("p1_20_goods_comment_block", "AD-07");
        EXPECTED_IDS.put("p1_21_feed_live_card_block", "AD-08");
        EXPECTED_IDS.put("p1_22_feed_goods_card_block", "AD-09");
        EXPECTED_IDS.put("p1_23_feed_vote_card_block", "AD-10");
        EXPECTED_IDS.put("p1_8_shake_jump_disable", "AD-11");
        EXPECTED_IDS.put("p1_16_score_guide_block", "UI-01");
        EXPECTED_IDS.put("p1_17_push_guide_block", "UI-02");
        EXPECTED_IDS.put("p1_18_content_rating_block", "UI-03");
        EXPECTED_IDS.put("p0_4_update_dialog_block", "UI-04");
        EXPECTED_IDS.put("p1_12_autoplay_preference", "UI-05");
        EXPECTED_IDS.put("p0_5_screenshot_detect_off", "PV-01");
        EXPECTED_IDS.put("p1_19_contact_sync_off", "PV-02");
        EXPECTED_IDS.put("p1_9_copy_link_sanitize", "PV-03");
        EXPECTED_IDS.put("p1_10_track_suppress", "PV-04");
        EXPECTED_IDS.put("p1_11_watermark_policy", "EN-01");
        EXPECTED_IDS.put("p0_6_video_default_mute", "EN-02");
        EXPECTED_IDS.put("p1_15_note_unavailable_view", "EX-01");
        EXPECTED_IDS.put("p2_1_ab_test_panel", "EX-02");
    }

    @Test
    public void twentyFourFeaturesPresent() {
        assertEquals("Feature 数量必须是 24", 24, Feature.values().length);
    }

    @Test
    public void keysMatchSpec() {
        Set<String> actual = new HashSet<>();
        for (Feature f : Feature.values()) {
            actual.add(f.key);
        }
        assertEquals(new HashSet<>(EXPECTED_IDS.keySet()), actual);
    }

    @Test
    public void idsMatchSpecAndEnumOrder() {
        assertEquals("枚举顺序必须与设置页顺序一致",
                new ArrayList<>(EXPECTED_IDS.keySet()), keysInEnumOrder());
        for (Feature f : Feature.values()) {
            assertEquals("编号与 FEATURE_ID_MAP 不一致: " + f.key,
                    EXPECTED_IDS.get(f.key), f.id);
        }
    }

    @Test
    public void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Feature f : Feature.values()) {
            assertTrue("id 重复: " + f.id, seen.add(f.id));
        }
        assertEquals(Feature.values().length, seen.size());
    }

    @Test
    public void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Feature f : Feature.values()) {
            assertTrue("key 重复: " + f.key, seen.add(f.key));
        }
        assertEquals(EXPECTED_IDS.size(), seen.size());
    }

    @Test
    public void idsUseUnifiedFormat() {
        for (Feature f : Feature.values()) {
            assertTrue("id 格式不合法: " + f.id, ID_PATTERN.matcher(f.id).matches());
        }
    }

    @Test
    public void idPrefixMatchesGroup() {
        for (Feature f : Feature.values()) {
            assertTrue("id 前缀与分组不一致: " + f.id + " / " + f.group,
                    f.id.startsWith(f.group.name() + "-"));
        }
    }

    @Test
    public void adAndUiDefaultEnabled() {
        for (Feature f : Feature.values()) {
            if (f.group == Feature.Group.AD || f.group == Feature.Group.UI) {
                if (f.blocked()) {
                    assertFalse(f.id + " " + f.key + " blocked 应默认关闭", f.defaultEnabled);
                } else {
                    assertTrue(f.id + " " + f.key + " 应默认开启", f.defaultEnabled);
                }
            }
        }
    }

    @Test
    public void pvEnExDefaultDisabled() {
        for (Feature f : Feature.values()) {
            if (f.group == Feature.Group.PV || f.group == Feature.Group.EN
                    || f.group == Feature.Group.EX) {
                assertFalse(f.id + " " + f.key + " 应默认关闭", f.defaultEnabled);
            }
        }
    }

    @Test
    public void blockedDefaultsDisabled() {
        int blocked = 0;
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                blocked++;
                assertFalse(f.id + " " + f.key + " blocked 必须默认关闭", f.defaultEnabled);
            }
        }
        assertEquals("blocked 数量必须是 3", 3, blocked);
        assertTrue(Feature.SHAKE_JUMP_DISABLE.blocked());
        assertTrue(Feature.TRACK_SUPPRESS.blocked());
        assertTrue(Feature.VIDEO_DEFAULT_MUTE.blocked());
    }

    private static List<String> keysInEnumOrder() {
        List<String> keys = new ArrayList<>();
        for (Feature f : Feature.values()) {
            keys.add(f.key);
        }
        return keys;
    }
}
