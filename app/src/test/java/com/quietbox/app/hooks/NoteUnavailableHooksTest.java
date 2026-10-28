package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * NoteUnavailableHooks.readString 的 JVM 单测。
 *
 * 9.52.0 的 NoteFeed 混淆 getter c1/u0 被 R8 改成了别的返回类型，
 * 但 modelType/id 字段名稳定，所以取值策略是“字段优先、getter 兜底”。
 */
public class NoteUnavailableHooksTest {

    /** 字段优先：即使同名 getter 返回别的值，也以字段为准。 */
    static class FieldWins {
        String modelType = "error";

        public String c1() {
            return "note";
        }
    }

    /** 字段缺失：回退旧 getter。 */
    static class GetterFallback {
        public String c1() {
            return "note";
        }
    }

    /** 字段为 null：回退旧 getter。 */
    static class NullFieldFallsBack {
        String modelType;

        public String c1() {
            return "note";
        }
    }

    /** 字段存在但类型不符：返回 null，不猜 String.valueOf。 */
    static class NonStringField {
        int modelType = 1;

        public String c1() {
            return "note";
        }
    }

    /** 父类字段也要能找到（与旧 getMethod 的继承查找一致）。 */
    static class Base {
        String id = "note-id";
    }

    static class Child extends Base {
    }

    /** 字段和 getter 都没有：null。 */
    static class Empty {
    }

    @Test
    public void fieldTakesPrecedenceOverGetter() {
        assertEquals("error", NoteUnavailableHooks.readString(new FieldWins(), "modelType", "c1"));
    }

    @Test
    public void missingFieldFallsBackToGetter() {
        assertEquals("note", NoteUnavailableHooks.readString(new GetterFallback(), "modelType", "c1"));
    }

    @Test
    public void nullFieldFallsBackToGetter() {
        assertEquals("note", NoteUnavailableHooks.readString(new NullFieldFallsBack(), "modelType", "c1"));
    }

    @Test
    public void nonStringFieldReturnsNullWithoutGuessing() {
        assertNull(NoteUnavailableHooks.readString(new NonStringField(), "modelType", "c1"));
    }

    @Test
    public void inheritedFieldIsFound() {
        assertEquals("note-id", NoteUnavailableHooks.readString(new Child(), "id", "u0"));
    }

    @Test
    public void nullTargetAndMissingMembersReturnNull() {
        assertNull(NoteUnavailableHooks.readString(null, "modelType", "c1"));
        assertNull(NoteUnavailableHooks.readString(new Empty(), "modelType", "c1"));
    }
}
