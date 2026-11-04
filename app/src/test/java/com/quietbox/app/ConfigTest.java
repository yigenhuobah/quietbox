package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // ------------------------------------------------------------ 逐键容错

    /**
     * 单个功能键读取抛异常（真实 SharedPreferences 遇到类型错误会抛
     * ClassCastException）时，只回退该键，其它成功读到的值必须保留；
     * strip 子开关与 AB 覆盖表也要照常加载。
     */
    @Test
    public void malformedFeatureKeyKeepsOtherValues() {
        FakeSource source = new FakeSource()
                .putBoolean(Feature.FEED_AD_FILTER.key, false)
                .putBoolean(Feature.SCREENSHOT_DETECT_OFF.key, true)
                .putBoolean(Config.KEY_STRIP_XSEC_TOKEN, true)
                .putString(Config.KEY_AB_OVERRIDES, "ab_key=1\nother=2")
                .breakKey(Feature.SPLASH_AD_SKIP.key);
        List<String> warnings = new ArrayList<>();

        Config c = Config.loadTolerant(source, (message, t) -> warnings.add(message));

        assertFalse("其它键成功读到的值不能被丢弃", c.enabled(Feature.FEED_AD_FILTER));
        assertTrue("其它键成功读到的值不能被丢弃", c.enabled(Feature.SCREENSHOT_DETECT_OFF));
        assertEquals("损坏键回退编译期默认值",
                Feature.SPLASH_AD_SKIP.defaultEnabled, c.enabled(Feature.SPLASH_AD_SKIP));
        assertTrue("strip 子开关仍要加载", c.stripXsecToken());
        assertEquals("AB 覆盖表仍要加载", "1", c.abOverride("ab_key"));
        assertEquals("只告警损坏的那个键", 1, warnings.size());
        assertTrue("告警必须带 key 名", warnings.get(0).contains(Feature.SPLASH_AD_SKIP.key));
        assertTrue("告警必须带异常类型", warnings.get(0).contains("ClassCastException"));
    }

    /** strip 键损坏同样不能影响功能开关与 AB 覆盖表。 */
    @Test
    public void malformedStripKeyKeepsFeaturesAndOverrides() {
        FakeSource source = new FakeSource()
                .putBoolean(Feature.FEED_AD_FILTER.key, false)
                .putString(Config.KEY_AB_OVERRIDES, "ab_key=1")
                .breakKey(Config.KEY_STRIP_XSEC_TOKEN);
        List<String> warnings = new ArrayList<>();

        Config c = Config.loadTolerant(source, (message, t) -> warnings.add(message));

        assertFalse(c.enabled(Feature.FEED_AD_FILTER));
        assertEquals(Config.DEFAULT_STRIP_XSEC_TOKEN, c.stripXsecToken());
        assertEquals("1", c.abOverride("ab_key"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(Config.KEY_STRIP_XSEC_TOKEN));
    }

    /** AB 覆盖表读取失败时保留其它键，覆盖表为空。 */
    @Test
    public void malformedOverridesKeyKeepsOtherValues() {
        FakeSource source = new FakeSource()
                .putBoolean(Feature.FEED_AD_FILTER.key, false)
                .putBoolean(Config.KEY_STRIP_XSEC_TOKEN, true)
                .breakKey(Config.KEY_AB_OVERRIDES);
        List<String> warnings = new ArrayList<>();

        Config c = Config.loadTolerant(source, (message, t) -> warnings.add(message));

        assertFalse(c.enabled(Feature.FEED_AD_FILTER));
        assertTrue(c.stripXsecToken());
        assertTrue(c.abOverrides().isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(Config.KEY_AB_OVERRIDES));
    }

    // ------------------------------------------------------------ config_version

    /** 老配置没有 config_version：按 1 读取。 */
    @Test
    public void absentConfigVersionDefaultsToOne() {
        Config c = Config.loadTolerant(new FakeSource(), (message, t) -> {
        });
        assertEquals(1, c.configVersion());
        assertEquals(Config.CURRENT_CONFIG_VERSION, c.configVersion());
    }

    /** 存储版本高于模块支持：告警但不抛异常、不丢弃其它值，按兼容模式继续。 */
    @Test
    public void newerConfigVersionLoadsWithoutThrowing() {
        FakeSource source = new FakeSource()
                .putInt(Config.KEY_CONFIG_VERSION, 2)
                .putBoolean(Feature.FEED_AD_FILTER.key, false);
        List<String> warnings = new ArrayList<>();

        Config c = Config.loadTolerant(source, (message, t) -> warnings.add(message));

        assertEquals("高于支持的版本号要原样保留", 2, c.configVersion());
        assertFalse("仍然要加载其它配置", c.enabled(Feature.FEED_AD_FILTER));
        assertEquals(1, warnings.size());
        assertTrue("告警要包含存储版本", warnings.get(0).contains("v2"));
        assertTrue("告警要包含支持版本", warnings.get(0).contains("v1"));
        assertTrue("告警要说明按兼容模式继续", warnings.get(0).contains("兼容模式"));
    }

    /** config_version 键本身损坏：回退 1，其它值不受影响。 */
    @Test
    public void malformedConfigVersionKeepsOtherValues() {
        FakeSource source = new FakeSource()
                .putBoolean(Feature.FEED_AD_FILTER.key, false)
                .breakKey(Config.KEY_CONFIG_VERSION);
        List<String> warnings = new ArrayList<>();

        Config c = Config.loadTolerant(source, (message, t) -> warnings.add(message));

        assertEquals(Config.DEFAULT_CONFIG_VERSION, c.configVersion());
        assertFalse(c.enabled(Feature.FEED_AD_FILTER));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(Config.KEY_CONFIG_VERSION));
    }

    /** 纯 JVM 的 PrefSource 替身：按 key 返回值，可指定某个 key 读取抛异常。 */
    private static final class FakeSource implements Config.PrefSource {

        private final Map<String, Boolean> mBooleans = new HashMap<>();
        private final Map<String, Integer> mInts = new HashMap<>();
        private final Map<String, String> mStrings = new HashMap<>();
        private final Set<String> mBroken = new HashSet<>();

        FakeSource putBoolean(String key, boolean value) {
            mBooleans.put(key, value);
            return this;
        }

        FakeSource putInt(String key, int value) {
            mInts.put(key, value);
            return this;
        }

        FakeSource putString(String key, String value) {
            mStrings.put(key, value);
            return this;
        }

        FakeSource breakKey(String key) {
            mBroken.add(key);
            return this;
        }

        private void check(String key) throws Throwable {
            if (mBroken.contains(key)) {
                throw new ClassCastException("wrong type for " + key);
            }
        }

        @Override
        public boolean getBoolean(String key, boolean def) throws Throwable {
            check(key);
            Boolean value = mBooleans.get(key);
            return value != null ? value : def;
        }

        @Override
        public int getInt(String key, int def) throws Throwable {
            check(key);
            Integer value = mInts.get(key);
            return value != null ? value : def;
        }

        @Override
        public String getString(String key, String def) throws Throwable {
            check(key);
            String value = mStrings.get(key);
            return value != null ? value : def;
        }
    }
}
