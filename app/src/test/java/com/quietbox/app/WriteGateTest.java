package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 写回状态机的契约测试（{@link WriteGate}）。
 *
 * 锁住两条曾经会导致「界面与远端不一致」的语义：
 * <ul>
 *   <li>成功写入一律记入确认值——即使已被更新的写入取代，后续失败仍要回滚到它；</li>
 *   <li>只有最新一次写入的结果才能更新界面，旧结果不能覆盖用户的新选择。</li>
 * </ul>
 */
public class WriteGateTest {

    @Test
    public void supersededSuccessStillBecomesConfirmed() {
        WriteGate gate = new WriteGate();
        long first = gate.begin("k");
        long second = gate.begin("k");

        assertFalse("旧写入已被更新的写入取代，不能改 UI",
                gate.onResult("k", first, true, true));
        assertEquals("但它确实落盘了，必须记入确认值", Boolean.TRUE, gate.rollback("k"));

        assertTrue("最新一次写入的结果仍要应用",
                gate.onResult("k", second, false, false));
        assertEquals("最新写入失败时回滚到最近确认值，而不是更旧的乐观值",
                Boolean.TRUE, gate.rollback("k"));
    }

    @Test
    public void failureRollsBackToLastConfirmedNotOptimisticValue() {
        WriteGate gate = new WriteGate();
        long first = gate.begin("k");
        assertTrue(gate.onResult("k", first, true, true));

        long second = gate.begin("k");
        assertTrue("最新写入的失败结果要应用到 UI",
                gate.onResult("k", second, false, false));

        assertEquals("回滚目标应是已确认落盘的 true", Boolean.TRUE, gate.rollback("k"));
    }

    @Test
    public void staleFailureCannotRollbackNewerSelection() {
        WriteGate gate = new WriteGate();
        long first = gate.begin("k");
        assertTrue(gate.onResult("k", first, true, true));

        long second = gate.begin("k");
        assertFalse("旧写入的失败结果不能应用到界面",
                gate.onResult("k", first, false, false));
        assertTrue("最新写入的结果仍要应用",
                gate.onResult("k", second, false, false));
        assertEquals("回滚目标仍是最近确认落盘的 true", Boolean.TRUE, gate.rollback("k"));
    }

    @Test
    public void noConfirmedValueBeforeFirstSuccess() {
        WriteGate gate = new WriteGate();
        long seq = gate.begin("k");
        assertTrue(gate.onResult("k", seq, false, true));

        assertNull("从未成功落盘时没有确认值，由调用方兜底", gate.rollback("k"));
    }

    @Test
    public void clearDropsInFlightResults() {
        WriteGate gate = new WriteGate();
        long seq = gate.begin("k");
        gate.clear();

        assertFalse("Fragment 销毁后在途结果不能再改界面",
                gate.onResult("k", seq, false, true));
        assertNull("确认值随 clear 一并丢弃", gate.rollback("k"));
    }
}
