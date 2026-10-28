package com.quietbox.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 配置写入的纯决策：remote 不可用必须拒绝、commit 失败必须回滚、
 * store 只在同一 service 实例内复用。
 */
public class ConfigWritePolicyTest {

    @Test
    public void remoteUnavailableRejectsWrite() {
        ConfigWritePolicy.Decision decision = ConfigWritePolicy.decide(false);
        assertTrue(decision == ConfigWritePolicy.Decision.REJECTED_NO_REMOTE);
        assertFalse("remote 不可用时 put 必须返回 false",
                ConfigWritePolicy.accepted(decision));
    }

    @Test
    public void remoteAvailableQueuesWrite() {
        ConfigWritePolicy.Decision decision = ConfigWritePolicy.decide(true);
        assertTrue(decision == ConfigWritePolicy.Decision.QUEUED);
        assertTrue(ConfigWritePolicy.accepted(decision));
    }

    @Test
    public void committedWriteKeepsNewUiState() {
        assertTrue(ConfigWritePolicy.keepUiState(true, true));
        assertFalse(ConfigWritePolicy.mustRollback(true, true));
    }

    @Test
    public void failedCommitDoesNotKeepNewUiState() {
        assertFalse("commit 失败不能把新值当成已保存",
                ConfigWritePolicy.keepUiState(true, false));
        assertTrue(ConfigWritePolicy.mustRollback(true, false));
    }

    @Test
    public void rejectedWriteMustRollback() {
        assertFalse(ConfigWritePolicy.keepUiState(false, false));
        assertTrue(ConfigWritePolicy.mustRollback(false, false));
    }

    @Test
    public void sameServiceReusesStore() {
        Object service = new Object();
        assertTrue(ConfigWritePolicy.reuseStore(true, service, service));
    }

    @Test
    public void serviceChangeRebuildsStore() {
        assertFalse(ConfigWritePolicy.reuseStore(true, new Object(), new Object()));
    }

    @Test
    public void missingStoreIsAlwaysBuilt() {
        assertFalse(ConfigWritePolicy.reuseStore(false, null, null));
        assertFalse(ConfigWritePolicy.reuseStore(false, new Object(), new Object()));
    }

    @Test
    public void nullServiceCanReuseStore() {
        // 框架未绑定时 service 一直是 null，store 可复用（remote 不可用的状态不变）。
        assertTrue(ConfigWritePolicy.reuseStore(true, null, null));
    }
}
