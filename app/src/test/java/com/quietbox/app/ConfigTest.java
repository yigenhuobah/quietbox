package com.quietbox.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Config.load(boolean, RemoteReader) 纯函数：remote 关闭、空 Map、部分键、
 * reader 抛异常、reader 返回 null 五条路径都必须回退到 Feature 编译期默认值，
 * 且异常绝不外抛（否则 Hooks.installAll 会整体放弃安装）。
 */
public class ConfigTest {

    /** remote=false：即使 reader 能读到全关，也必须走编译期默认值。 */
    @Test
    public void remoteDisabledUsesDefaults() {
        Config c = Config.load(false, () -> {
            Map<String, Boolean> allOff = new HashMap<>();
            for (Feature f : Feature.values()) {
                allOff.put(f.key, false);
            }
            return allOff;
        });
        for (Feature f : Feature.values()) {
            assertEquals("remote=false 时 " + f.key + " 应走编译期默认值",
                    f.defaultEnabled, c.enabled(f));
        }
    }

    /** reader 返回空 Map：缺键全部用编译期默认值。 */
    @Test
    public void emptyReaderMapUsesDefaults() {
        Config c = Config.load(true, () -> Collections.emptyMap());
        for (Feature f : Feature.values()) {
            assertEquals("空 Map 时 " + f.key + " 应走编译期默认值",
                    f.defaultEnabled, c.enabled(f));
        }
    }

    /** reader 返回部分键：只影响被覆盖的键，其余仍用默认值。 */
    @Test
    public void partialOverridesOnlyAffectThatKey() {
        Map<String, Boolean> overrides = new HashMap<>();
        overrides.put(Feature.SPLASH_AD_SKIP.key, false);
        overrides.put(Feature.SCREENSHOT_DETECT_OFF.key, true);
        Config c = Config.load(true, () -> overrides);
        for (Feature f : Feature.values()) {
            boolean expected;
            if (f == Feature.SPLASH_AD_SKIP) {
                expected = false;
            } else if (f == Feature.SCREENSHOT_DETECT_OFF) {
                expected = true;
            } else {
                expected = f.defaultEnabled;
            }
            assertEquals("部分键覆盖后 " + f.key + " 状态不对", expected, c.enabled(f));
        }
    }

    /** reader 抛异常：全默认且不向外抛。 */
    @Test
    public void readerThrowsFallsBackToDefaults() {
        Config c = Config.load(true, () -> {
            throw new IllegalStateException("remote prefs boom");
        });
        for (Feature f : Feature.values()) {
            assertEquals("reader 抛异常时 " + f.key + " 应走编译期默认值",
                    f.defaultEnabled, c.enabled(f));
        }
    }

    /** reader 返回 null（对应 getRemotePreferences 返回 null）：全默认。 */
    @Test
    public void nullReaderMapUsesDefaults() {
        Config c = Config.load(true, () -> null);
        for (Feature f : Feature.values()) {
            assertEquals("reader 返回 null 时 " + f.key + " 应走编译期默认值",
                    f.defaultEnabled, c.enabled(f));
        }
    }

    /** 未知 key 忽略，不产生额外 Feature。 */
    @Test
    public void unknownKeysIgnored() {
        Map<String, Boolean> overrides = new HashMap<>();
        overrides.put("not_a_feature", true);
        Config c = Config.load(true, () -> overrides);
        for (Feature f : Feature.values()) {
            assertEquals("未知 key 不应影响 " + f.key, f.defaultEnabled, c.enabled(f));
        }
    }
}
