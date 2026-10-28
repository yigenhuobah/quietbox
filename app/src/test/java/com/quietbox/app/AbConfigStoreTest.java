package com.quietbox.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AbConfigStore 的写入契约：remote 不可用拒绝、commit 失败不更新本地副本、
 * read-modify-write 不丢其他已强开的项。
 */
public class AbConfigStoreTest {

    private static FakeSharedPreferences remoteWith(String overrides) {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.edit().putString(Config.KEY_AB_OVERRIDES, overrides).commit();
        return remote;
    }

    @Test
    public void remoteUnavailableRejectsForcedWriteAndDoesNotMirror() {
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(null, new FakeSharedPreferences(), report);
        assertFalse(store.setForced("k", true, "1"));
        assertFalse("remote 不可用时不能写本地副本冒充成功",
                report.contains(AbReportProvider.KEY_OVERRIDES));
    }

    @Test
    public void forcedWriteCommitsRemoteAndMirrorsReport() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setForced("k", true, "1", committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(result.get());
        assertEquals("k=1", remote.getString(Config.KEY_AB_OVERRIDES, ""));
        assertEquals("k=1", report.getString(AbReportProvider.KEY_OVERRIDES, ""));
    }

    @Test
    public void failedCommitDoesNotMirrorReport() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.setCommitResult(false);
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setForced("k", true, "1", committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse("commit 失败必须回调失败", result.get());
        assertFalse("remote 写失败时不能更新本地副本",
                report.contains(AbReportProvider.KEY_OVERRIDES));
    }

    @Test
    public void readFailureDoesNotClearOtherOverrides() throws Exception {
        FakeSharedPreferences remote = remoteWith("a=1");
        remote.setThrowOnRead(true);
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setForced("b", true, "2", committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse("读失败不能拿空表回写", result.get());
        remote.setThrowOnRead(false);
        assertEquals("a=1", remote.getString(Config.KEY_AB_OVERRIDES, ""));
        assertFalse(report.contains(AbReportProvider.KEY_OVERRIDES));
    }

    @Test
    public void forcedWriteKeepsOtherOverrides() throws Exception {
        FakeSharedPreferences remote = remoteWith("a=1");
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setForced("b", true, "2", committed -> done.countDown()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("a=1\nb=2", remote.getString(Config.KEY_AB_OVERRIDES, ""));
    }

    @Test
    public void panelWriteCommitsRemoteAndMirrorsReport() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(remote, new FakeSharedPreferences(), report);
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.setPanelEnabled(true, committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(result.get());
        assertTrue(remote.getBoolean(Feature.AB_TEST_PANEL.key, false));
        assertTrue(report.getBoolean(AbReportProvider.KEY_PANEL_ENABLED, false));
    }

    @Test
    public void panelWriteRejectedWhenRemoteUnavailable() {
        FakeSharedPreferences report = new FakeSharedPreferences();
        AbConfigStore store = new AbConfigStore(null, new FakeSharedPreferences(), report);
        assertFalse(store.setPanelEnabled(true));
        assertFalse(report.contains(AbReportProvider.KEY_PANEL_ENABLED));
    }
}
