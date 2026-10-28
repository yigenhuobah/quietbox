package com.quietbox.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ConfigStore 的写入契约（纯 JVM，注入 SharedPreferences 替身）：
 * - remote 不可用时 put 返回 false，且绝不写本地冒充成功；
 * - commit 的真实结果通过回调返回，false / 抛异常都算失败；
 * - 所有写入走同一个后台线程，按提交顺序落盘。
 */
public class ConfigStoreTest {

    @Test
    public void remoteUnavailableReturnsFalseAndDoesNotTouchLocal() {
        FakeSharedPreferences local = new FakeSharedPreferences();
        ConfigStore store = new ConfigStore(null, local);
        assertFalse("remote 不可用时 put 必须返回 false", store.put("k", true));
        assertFalse("remote 不可用时不能写本地冒充成功", local.contains("k"));
    }

    @Test
    public void remoteCommitSuccessReportsTrueAndWritesValue() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences());
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);

        assertTrue("remote 可用时应排入后台提交",
                store.put("k", true, committed -> {
                    result.set(committed);
                    done.countDown();
                }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue("commit 成功必须回调 true", result.get());
        assertTrue(remote.getBoolean("k", false));
    }

    @Test
    public void remoteCommitFalseReportsFailure() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.setCommitResult(false);
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences());
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.put("k", true, committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse("commit 返回 false 必须回调失败", result.get());
        assertFalse(remote.contains("k"));
    }

    @Test
    public void remoteCommitThrowReportsFailure() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        remote.setThrowOnCommit(true);
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences());
        AtomicBoolean result = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        assertTrue(store.put("k", true, committed -> {
            result.set(committed);
            done.countDown();
        }));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse("commit 抛异常必须回调失败", result.get());
    }

    @Test
    public void writesAreSerializedInOrder() throws Exception {
        FakeSharedPreferences remote = new FakeSharedPreferences();
        ConfigStore store = new ConfigStore(remote, new FakeSharedPreferences());
        CountDownLatch done = new CountDownLatch(2);

        assertTrue(store.put("k", true, committed -> done.countDown()));
        assertTrue(store.put("k", false, committed -> done.countDown()));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse("后提交的写入必须最后落盘", remote.getBoolean("k", true));
    }
}
