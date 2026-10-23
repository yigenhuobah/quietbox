package com.quietbox.app;

/**
 * 一条可强开的 AB 实验。
 *
 * forceValue 是强开时喂给取值入口的字符串。真实返回类型由 9.50.0 反汇编三重证据
 * （TypeToken 泛型注解 + 默认值字面量 + 返回值 check-cast）逐条判定：
 * - BOOL 型读点：默认值是 Boolean.FALSE，强开喂 "true"；
 * - INT 型读点：默认值是 Integer.valueOf(0)，多数判定语义是 &gt;0，
 *   强开喂 "1"（个别读点判定 &gt;1 的，forceValue 写 "2"）。
 *
 * valueType 是声明式的类型真相，强开时按它规整喂进去的值，运行期 original 的
 * 类型只作兜底校验——避免 v0.4.0 那类"Boolean 型收到 '1' 反向变 false"和
 * v0.5.0 那类"Integer 型收到 'true' 静默失效"的错误。
 */
public final class AbTest {

    public static final int GROUP_DETAIL = 0;
    public static final int GROUP_PLAYBACK = 1;
    public static final int GROUP_MISC = 2;

    /** 运行期返回类型。 */
    public enum ValueType {
        /** 读点返回 Boolean。 */
        BOOL,
        /** 读点返回 Integer（组号）。 */
        INT;

        public static ValueType fromString(String raw) {
            return "int".equals(raw) ? INT : BOOL;
        }
    }

    public final String key;
    public final int group;
    public final int titleRes;
    public final int summaryRes;
    public final String forceValue;
    public final ValueType valueType;

    AbTest(String key, int group, int titleRes, int summaryRes, String forceValue,
           ValueType valueType) {
        this.key = key;
        this.group = group;
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
        this.forceValue = forceValue;
        this.valueType = valueType;
    }

    /**
     * 给「其他」列表里用户手填的未收录 key 造一个运行时条目。
     * titleRes/summaryRes 为 0 表示没有文案，UI 侧直接显示 key。
     */
    public static AbTest forKey(String key, String forceValue, ValueType type) {
        return new AbTest(key, GROUP_MISC, 0, 0, forceValue, type);
    }

    /** 该读点强开的目标原语值：BOOL → Boolean，INT → Integer。解析不出来返回 null。 */
    public Object targetPrimitive() {
        if (valueType == ValueType.BOOL) {
            // 只认 "true"/"false"：其他字面量宁可回落到原值，也不能猜成 true。
            if ("true".equalsIgnoreCase(forceValue)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(forceValue)) {
                return Boolean.FALSE;
            }
            return null;
        }
        return parseIntOrNull(forceValue);
    }

    /** stringHook 路径上喂给 mb6.o#l（9.50.0 为 eb6.o#l）的字符串，按类型规整成 Gson 可解析的形式。 */
    public String forcedLiteral() {
        Object target = targetPrimitive();
        return target == null ? null : String.valueOf(target);
    }

    private static Integer parseIntOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
