package com.quietbox.app.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ForceSaveHooks 纯函数的 JVM 单测。
 *
 * 9.52.0 dexsql 核实：FunctionSwitch 的 a()=enable、c()=type；type 取值来自
 * 服务端权限数组（image_download / video_download 等）。只应对下载类 type 强制
 * enable，其它 type（评论、AI 配音等）必须保持宿主原值。
 */
public class ForceSaveHooksTest {

    @Test
    public void imageDownloadIsForcedEnabled() {
        assertTrue(ForceSaveHooks.shouldForceEnable("image_download"));
    }

    @Test
    public void videoDownloadIsForcedEnabled() {
        assertTrue(ForceSaveHooks.shouldForceEnable("video_download"));
    }

    @Test
    public void otherTypesKeepHostValue() {
        assertFalse(ForceSaveHooks.shouldForceEnable("comment"));
        assertFalse(ForceSaveHooks.shouldForceEnable("ai_dubbing"));
        assertFalse(ForceSaveHooks.shouldForceEnable(""));
        assertFalse(ForceSaveHooks.shouldForceEnable(null));
    }

    @Test
    public void typeMatchIsExactNotPrefix() {
        // 防止将来改成 startsWith/contains 时把 video_download_x 之类误放行
        assertFalse(ForceSaveHooks.shouldForceEnable("image_download_extra"));
        assertFalse(ForceSaveHooks.shouldForceEnable(" video_download"));
    }
}
