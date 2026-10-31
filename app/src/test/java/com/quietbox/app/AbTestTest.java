package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * AbTest 的强开值规整契约（纯 JVM）。
 *
 * targetPrimitive / forcedLiteral 是 forceValue 按 valueType 规整的唯一防线，
 * 这里的断言对应两次真实静默失效：v0.4.0 的「Boolean 型收到 '1' 反向变 false」
 * 和 v0.5.0 的「Integer 型收到 'true' 静默失效」（见 AbTests 里的 key 注释）。
 * 回归时 AbTestHooks 会原样回落，EX-02 强开不生效但宿主不报错，只能靠这些测试发现。
 */
public class AbTestTest {

    private static AbTest of(String forceValue, AbTest.ValueType type) {
        return AbTest.forKey("andr_some_experiment", forceValue, type);
    }

    @Test
    public void boolDeclaration_onlyAcceptsTrueOrFalse() {
        assertEquals(Boolean.TRUE, of("true", AbTest.ValueType.BOOL).targetPrimitive());
        assertEquals(Boolean.FALSE, of("false", AbTest.ValueType.BOOL).targetPrimitive());
        // 组号字面量落到 BOOL 读点上必须放弃改写，不能猜成 true。
        assertNull(of("1", AbTest.ValueType.BOOL).targetPrimitive());
        assertNull(of("0", AbTest.ValueType.BOOL).targetPrimitive());
        assertNull(of("yes", AbTest.ValueType.BOOL).targetPrimitive());
        assertNull(of("", AbTest.ValueType.BOOL).targetPrimitive());
        assertNull(of(null, AbTest.ValueType.BOOL).targetPrimitive());
    }

    @Test
    public void boolDeclaration_ignoresCase() {
        assertEquals(Boolean.TRUE, of("TRUE", AbTest.ValueType.BOOL).targetPrimitive());
        assertEquals(Boolean.FALSE, of("False", AbTest.ValueType.BOOL).targetPrimitive());
    }

    @Test
    public void intDeclaration_parsesIntegerLiterals() {
        assertEquals(Integer.valueOf(1), of("1", AbTest.ValueType.INT).targetPrimitive());
        assertEquals(Integer.valueOf(2), of("2", AbTest.ValueType.INT).targetPrimitive());
        assertEquals(Integer.valueOf(0), of("0", AbTest.ValueType.INT).targetPrimitive());
        assertEquals(Integer.valueOf(-1), of("-1", AbTest.ValueType.INT).targetPrimitive());
    }

    @Test
    public void intDeclaration_rejectsNonIntegerLiterals() {
        // v0.5.0 事故：Integer 读点收到 "true"，Integer.valueOf 抛异常后被吞，开关静默失效。
        assertNull(of("true", AbTest.ValueType.INT).targetPrimitive());
        assertNull(of("1.5", AbTest.ValueType.INT).targetPrimitive());
        assertNull(of("", AbTest.ValueType.INT).targetPrimitive());
        assertNull(of(null, AbTest.ValueType.INT).targetPrimitive());
    }

    @Test
    public void forcedLiteral_isTheGsonParseableFormOfTheDeclaredType() {
        assertEquals("true", of("true", AbTest.ValueType.BOOL).forcedLiteral());
        assertEquals("false", of("false", AbTest.ValueType.BOOL).forcedLiteral());
        assertEquals("1", of("1", AbTest.ValueType.INT).forcedLiteral());
        assertEquals("2", of("2", AbTest.ValueType.INT).forcedLiteral());
        // 解析不出来时不能再喂字面量：ConfigCenter#l 的调用方 Gson 解析失败会静默回落。
        assertNull(of("1", AbTest.ValueType.BOOL).forcedLiteral());
        assertNull(of("true", AbTest.ValueType.INT).forcedLiteral());
    }

    @Test
    public void curatedTests_forceValueMatchesDeclaredType() {
        for (AbTest test : AbTests.ALL) {
            assertNotNull("精选实验缺少 forceValue: " + test.key, test.forceValue);
            Object primitive = test.targetPrimitive();
            assertNotNull("forceValue 与 valueType 不一致，强开会静默失效: " + test.key
                    + " valueType=" + test.valueType
                    + " forceValue=" + test.forceValue, primitive);
            if (test.valueType == AbTest.ValueType.BOOL) {
                assertTrue("BOOL 型的 forceValue 只能是 true/false: " + test.key,
                        primitive instanceof Boolean);
            } else {
                assertTrue("INT 型的 forceValue 必须是整数: " + test.key,
                        primitive instanceof Integer);
            }
            assertNotNull("forcedLiteral 不能为空: " + test.key, test.forcedLiteral());
        }
    }
}
