package com.quietbox.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * AB 面板总开关写路径的纯决策逻辑（不构造 Fragment，不依赖 Android 运行时）。
 *
 * 本地副本是宿主运行中 provider 的读源；只有已确认落盘的总开关值才能写进去。
 * 旧实现把「用户刚请求、后台 commit 还没返回」的值先写进去，commit 失败又恰好
 * 用户已离开页面（onWriteResult 因 getView()==null 直接返回）时，活通道会一直
 * 按未落盘的值生效。
 */
public class AbTestFragmentTest {

    @Test
    public void confirmedValueIsMirroredToLiveChannel() {
        assertTrue(AbTestFragment.mayMirrorPanelEnabled(false, true));
    }

    @Test
    public void requestedValueIsNotMirroredBeforeCommit() {
        assertFalse("masterOverride 非空 = 还没落盘，不能写给活通道",
                AbTestFragment.mayMirrorPanelEnabled(true, true));
    }

    @Test
    public void noRemoteNeverMirrors() {
        assertFalse(AbTestFragment.mayMirrorPanelEnabled(false, false));
        assertFalse(AbTestFragment.mayMirrorPanelEnabled(true, false));
    }
}
