package com.quietbox.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import io.github.yigenhuobah.core.test.SourceScan;

/**
 * 源码级约束：hook 包的“静默失败”必须被显式记录，崩溃路径必须真的同步 flush。
 *
 * <p>锚点查找存在等价静默规则：{@code HookKit.findByNameQuiet(Class, String)}
 * 不带 module/Feature，也不记 skip；其它 quiet 重载虽然也不记账，但调用点都把
 * 结果交给 {@code HookKit.install}，由 install 统一记“挂载点为空”并返回 false，
 * 因此这里只锁住没有兜底记录的 {@code findByNameQuiet}。
 *
 * <p>字段查找没有等价的静默 helper：{@code getDeclaredField} 的各调用点要么在
 * 失败分支调用 {@code HookState.markNotFound}（FeedAdHooks/FeedCardHooks/
 * LivePhotoWatermarkHooks），要么有明确的 getter 回退路径（NoteUnavailableHooks），
 * 无法用一条统一的正则在不误报回退逻辑的前提下表达，故不加字段规则。
 */
public class HookKitSourceScanTest {

    private static final SourceScan.Rule NO_EXTERNAL_SILENT_NAME_LOOKUP = new SourceScan.Rule(
            "\\bHookKit\\.findByNameQuiet\\s*\\(",
            "禁止直接调用静默的 HookKit.findByNameQuiet；请用带 Feature 的 "
                    + "HookKit.findMethod(module, f, cls, name) 以便记录 skip");

    private static final SourceScan.Rule CRASH_FLUSH = new SourceScan.Rule(
            "\\bCrashFlush\\.runBestEffort\\s*\\(",
            call -> call.contains("Report.flushNow") && call.contains("200")
                    && call.contains("CRASH_LOGGER") && call.contains("TAG"),
            "UEH 崩溃路径必须调用 CrashFlush.runBestEffort(() -> Report.flushNow(), 200, "
                    + "logger, TAG)，让超时/异常以 WARN 可见");

    /** 旧的不记日志入口：崩溃 flush 超时或任务抛异常时完全静默，必须禁止回退。 */
    private static final SourceScan.Rule NO_SILENT_CRASH_FLUSH = new SourceScan.Rule(
            "\\bCrashFlush\\.run\\s*\\(",
            "崩溃路径不得使用不记日志的 CrashFlush.run；请用 "
                    + "runBestEffort(..., CRASH_LOGGER, TAG)");

    @Test
    public void crashHandlerFlushesSynchronouslyWithinBudget() throws IOException {
        List<SourceScan.Finding> findings = SourceScan.scan(hooksSource(), CRASH_FLUSH);

        assertTrue("崩溃路径必须使用 CrashFlush.runBestEffort(...Report.flushNow()..., 200, "
                + "CRASH_LOGGER, TAG)：" + findings, findings.isEmpty());
        // 反向确认规则不是空转：至少存在一条 runBestEffort 调用。
        assertFalse("Report.java 必须实际调用 CrashFlush.runBestEffort",
                SourceScan.scan(hooksSource(),
                        new SourceScan.Rule("\\bCrashFlush\\.runBestEffort\\s*\\(",
                                "崩溃 flush 缺失"))
                        .isEmpty());
    }

    @Test
    public void crashHandlerDoesNotUseSilentFlushEntryPoint() throws IOException {
        List<SourceScan.Finding> findings = SourceScan.scan(
                hooksSource(), Collections.singletonList(NO_SILENT_CRASH_FLUSH));

        assertTrue("崩溃路径不得回退到静默的 CrashFlush.run：" + findings,
                findings.isEmpty());
    }

    /** 规则本身必须能命中旧写法，避免以后把正则改坏后门禁静默通过。 */
    @Test
    public void silentCrashFlushRuleCatchesLegacyCall() {
        String source = "class A { void a() { CrashFlush.run(() -> Report.flushNow(), 200); } }";

        List<SourceScan.Finding> findings = SourceScan.scanSource(
                source, "A.java", NO_SILENT_CRASH_FLUSH);

        assertEquals(1, findings.size());
    }

    @Test
    public void silentNameLookupIsNotUsedOutsideHookKit() throws IOException {
        List<SourceScan.Finding> findings = SourceScan.scan(
                hooksSource(), Collections.singletonList(NO_EXTERNAL_SILENT_NAME_LOOKUP));

        assertTrue("hook 包不得直接调用静默的 findByNameQuiet：" + findings,
                findings.isEmpty());
    }

    /** 规则本身必须能命中旧写法，避免以后把正则改坏后门禁静默通过。 */
    @Test
    public void silentNameLookupRuleCatchesLegacyCall() {
        String source = "class A { void a() { HookKit.findByNameQuiet(cls, \"m\"); } }";

        List<SourceScan.Finding> findings = SourceScan.scanSource(
                source, "A.java", NO_EXTERNAL_SILENT_NAME_LOOKUP);

        assertEquals(1, findings.size());
    }

    /** 测试从模块目录（app/）或仓库根目录运行都能定位源码。 */
    private static Path hooksSource() {
        Path moduleRelative = Paths.get("src/main/java/com/quietbox/app/hooks");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        return Paths.get("app/src/main/java/com/quietbox/app/hooks");
    }
}
