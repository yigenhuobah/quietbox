package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * LivePhotoWatermarkHooks 纯函数的 JVM 单测。
 *
 * dexsql 对 9.52.0 的结论（见 LivePhotoWatermarkHooks 类注释）：
 * zm7.j#c(pr7.l, dg7.h, Z)V 是实况保存入口；dg7.h 的字段 e=disableWaterMark、
 * h=disableAiWaterMark（公开 boolean 字段）；p32.h#a(I)Z 的 code=1 是实况保存
 * 链路开关（zm7.j#c 内 const 1 传入），true=新链路（服务端渲染，可能带烧录水印）、
 * false=旧链路（直接下载 dg7.h.g 预览同源 URL）。
 */
public class LivePhotoWatermarkHooksTest {

    /** 字段形状与 9.52.0 dg7.h 相同的替身：a~d=String、e=Z、f=String、g=String、h=Z。 */
    static class Feedback {
        public String a = "a";
        public String b = "b";
        public String c = "c";
        public String d = "d";
        public boolean e = false;
        public String f = "fileId";
        public String g = "videoPath";
        public boolean h = false;
    }

    @Test
    public void feedback_setsDisableWaterMarkAndAiWaterMark() {
        Feedback fb = new Feedback();
        assertEquals(2, LivePhotoWatermarkHooks.applyFeedbackFlags(fb));
        assertTrue("e (disableWaterMark) 必须置 true", fb.e);
        assertTrue("h (disableAiWaterMark) 必须置 true", fb.h);
    }

    @Test
    public void feedback_overwritesTrueInputs() {
        Feedback fb = new Feedback();
        fb.e = true;
        fb.h = true;
        assertEquals(2, LivePhotoWatermarkHooks.applyFeedbackFlags(fb));
        assertTrue(fb.e);
        assertTrue(fb.h);
    }

    @Test
    public void feedback_keepsStringFieldsUntouched() {
        Feedback fb = new Feedback();
        LivePhotoWatermarkHooks.applyFeedbackFlags(fb);
        // f=livePhotoFileId、g=livePhotoVideoPath 与 a~d 原样保留。
        assertEquals("fileId", fb.f);
        assertEquals("videoPath", fb.g);
        assertEquals("a", fb.a);
        assertEquals("d", fb.d);
    }

    @Test
    public void nullTarget_returnsMinusOneAndDoesNotThrow() {
        assertEquals(-1, LivePhotoWatermarkHooks.applyFeedbackFlags(null));
    }

    @Test
    public void targetWithoutFields_returnsZeroAndDoesNotThrow() {
        // 没有 e/h 字段的对象（例如混淆换名后的宿主类）不置位、不抛出。
        assertEquals(0, LivePhotoWatermarkHooks.applyFeedbackFlags(new Object()));
        assertEquals(0, LivePhotoWatermarkHooks.applyFeedbackFlags("string"));
    }

    @Test
    public void switchCode_onlyCodeOneMatches() {
        // 只对目标 code 生效：1 命中，其余（含 0、2、负数）不干预。
        assertTrue(LivePhotoWatermarkHooks.isLivePhotoSwitchCode(1));
        assertFalse(LivePhotoWatermarkHooks.isLivePhotoSwitchCode(0));
        assertFalse(LivePhotoWatermarkHooks.isLivePhotoSwitchCode(2));
        assertFalse(LivePhotoWatermarkHooks.isLivePhotoSwitchCode(-1));
        assertFalse(LivePhotoWatermarkHooks.isLivePhotoSwitchCode(Integer.MIN_VALUE));
    }

    @Test
    public void switchConstantMatchesDexsqlFinding() {
        // dexsql 反汇编 zm7.j#c：const v5, 0x1 传入 p32.h#a(I)Z。code 改动必须显式过测试。
        assertEquals(1, LivePhotoWatermarkHooks.LIVE_PHOTO_SAVE_SWITCH_CODE);
    }

    /** 防止重构成实例方法/改可见性时测试静默失效。 */
    @Test
    public void helpersArePackagePrivateStatic() throws Exception {
        Method flags = LivePhotoWatermarkHooks.class
                .getDeclaredMethod("applyFeedbackFlags", Object.class);
        int mods = flags.getModifiers();
        assertTrue(Modifier.isStatic(mods));
        assertTrue("应保持 package-private，供同包测试直接调用",
                (mods & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) == 0);

        Method code = LivePhotoWatermarkHooks.class
                .getDeclaredMethod("isLivePhotoSwitchCode", int.class);
        int codeMods = code.getModifiers();
        assertTrue(Modifier.isStatic(codeMods));
        assertTrue((codeMods & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) == 0);
    }
}
