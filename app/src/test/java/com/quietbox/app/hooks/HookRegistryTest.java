package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.quietbox.app.Feature;

import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

/**
 * Hook 注册表完整性：所有非 blocked Feature 必须有 installer，blocked Feature 不得注册。
 *
 * Hooks 与 installerKeys() 均为 package-private，本测试放在同一包内直接读取，
 * 不为了测试放宽生产代码可见性。
 */
public class HookRegistryTest {

    @Test
    public void everyNonBlockedFeatureHasInstaller() {
        Set<Feature> registered = Hooks.installerKeys();
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            assertTrue("缺少 installer: " + f.id + " (" + f.key + ")",
                    registered.contains(f));
        }
    }

    @Test
    public void blockedFeaturesAreNotInstalled() {
        Set<Feature> registered = Hooks.installerKeys();
        int blocked = 0;
        for (Feature f : Feature.values()) {
            if (!f.blocked()) {
                continue;
            }
            blocked++;
            assertFalse("blocked Feature 不应注册 installer: " + f.id,
                    registered.contains(f));
        }
        assertTrue("应至少有一个 blocked Feature 用于校验该规则", blocked > 0);
    }

    @Test
    public void registryContainsExactlyTheNonBlockedFeatures() {
        Set<Feature> expected = EnumSet.noneOf(Feature.class);
        for (Feature f : Feature.values()) {
            if (!f.blocked()) {
                expected.add(f);
            }
        }
        Set<Feature> registered = Hooks.installerKeys();
        assertEquals("注册表必须恰好覆盖全部非 blocked Feature", expected, registered);
        assertFalse("installer 注册表为空", registered.isEmpty());
        assertFalse(registered.contains(null));
    }
}
