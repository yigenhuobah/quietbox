package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * WatermarkHooks.applyWatermarkFlags 的 JVM 单测。
 *
 * dexsql 对 9.51.0 的结论（见 WatermarkHooks.applyWatermarkFlags 注释）：
 * ce8.i#i 共 11 参，index 5 = disableWaterMark，index 10 = disableAiWaterMark
 * （AND 上 ce8.i#b() 后进 ce8.j#a 第 6 参）；index 8 / 9 与水印绘制无关，不许置位。
 */
public class WatermarkHooksTest {

    /** 按真实签名 (Activity, String, String, String, String, Z, F1, F1, Z, Z, Z) 造参数。 */
    private static Object[] elevenArgs() {
        return new Object[]{
                "activity", "noteId", "url", "path", "traceId",
                Boolean.FALSE, "fn1", "fn2",
                Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,
        };
    }

    @Test
    public void elevenArgs_setsDisableWaterMarkAtIndex5() {
        Object[] out = WatermarkHooks.applyWatermarkFlags(elevenArgs());
        assertEquals(Boolean.TRUE, out[5]);
    }

    @Test
    public void elevenArgs_setsDisableAiWaterMarkAtIndex10() {
        Object[] out = WatermarkHooks.applyWatermarkFlags(elevenArgs());
        assertEquals(Boolean.TRUE, out[10]);
    }

    @Test
    public void elevenArgs_doesNotTouchOtherSlots() {
        Object[] in = elevenArgs();
        Object[] out = WatermarkHooks.applyWatermarkFlags(in);
        // 非 5/10 槽位原样保留：对象引用相同、无关布尔位不许被置 true。
        for (int i = 0; i < out.length; i++) {
            if (i == 5 || i == 10) {
                continue;
            }
            assertSame("slot " + i + " must keep original value", in[i], out[i]);
        }
        assertEquals(Boolean.FALSE, out[8]);
        assertEquals(Boolean.FALSE, out[9]);
    }

    @Test
    public void elevenArgs_overwritesTrueInputs() {
        Object[] in = elevenArgs();
        in[5] = Boolean.TRUE;
        in[10] = Boolean.TRUE;
        Object[] out = WatermarkHooks.applyWatermarkFlags(in);
        assertEquals(Boolean.TRUE, out[5]);
        assertEquals(Boolean.TRUE, out[10]);
    }

    @Test
    public void elevenArgs_returnsCopy_notInputArray() {
        Object[] in = elevenArgs();
        Object[] out = WatermarkHooks.applyWatermarkFlags(in);
        assertNotSame(in, out);
        // 输入数组不被原地修改，调用方拿不到半改状态。
        assertEquals(Boolean.FALSE, in[5]);
        assertEquals(Boolean.FALSE, in[10]);
    }

    @Test
    public void nonElevenArgs_returnsNull() {
        assertNull(WatermarkHooks.applyWatermarkFlags(null));
        assertNull(WatermarkHooks.applyWatermarkFlags(new Object[0]));
        assertNull(WatermarkHooks.applyWatermarkFlags(new Object[6]));
        // 9.50.0 形状（12 参 nd8.i#g）与 9.51.0 的 12 参重载 ce8.i#g 都不匹配。
        assertNull(WatermarkHooks.applyWatermarkFlags(new Object[12]));
        assertNull(WatermarkHooks.applyWatermarkFlags(new Object[13]));
    }

    /** 防止重构成实例方法/丢失 package-private 可见性时测试静默失效。 */
    @Test
    public void applyWatermarkFlags_isPackagePrivateStatic() throws Exception {
        Method m = WatermarkHooks.class.getDeclaredMethod("applyWatermarkFlags", Object[].class);
        int mods = m.getModifiers();
        assertTrue(Modifier.isStatic(mods));
        assertTrue("应保持 package-private，供同包测试直接调用",
                (mods & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) == 0);
    }
}
