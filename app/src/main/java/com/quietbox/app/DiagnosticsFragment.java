package com.quietbox.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 诊断页：逐功能显示 registered / hit / skip。
 *
 * 主页面零诊断，所有细节集中在这里。数据由目标进程的 HookState 经
 * hooks/Report 回传到 StatusProvider，再落到 status_report。
 * 进入页面和 onResume 时刷新，不自动轮询。
 */
public class DiagnosticsFragment extends PreferenceFragmentCompat {

    private long mLastRefreshMs;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        build(screen);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /** 重新读取回传数据；MainActivity 在 service 绑定后也会调用。 */
    void refresh() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }
        screen.removeAll();
        build(screen);
    }

    private void build(PreferenceScreen screen) {
        mLastRefreshMs = System.currentTimeMillis();

        Preference header = new Preference(requireContext());
        header.setTitle(R.string.diag_title);
        header.setSummary(getString(R.string.diag_header, refreshTime())
                + "\n" + getString(R.string.diag_hint));
        header.setSelectable(false);
        screen.addPreference(header);

        boolean hasData = false;
        for (Feature.Group group : Feature.Group.values()) {
            PreferenceCategory category = new PreferenceCategory(requireContext());
            category.setTitle(groupTitle(group));
            // 先把 category 挂到 screen，它才会拿到 PreferenceManager；
            // 否则 category.addPreference() 在 getNextId() 上 NPE。
            screen.addPreference(category);
            for (Feature f : Feature.values()) {
                if (f.group != group) {
                    continue;
                }
                category.addPreference(row(f));
                if (!f.blocked() && hasData(f)) {
                    hasData = true;
                }
            }
        }

        if (!hasData) {
            Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.diag_empty_title);
            empty.setSummary(getString(R.string.diag_empty,
                    getString(R.string.app_target_name)));
            empty.setSelectable(false);
            screen.addPreference(empty);
        }

        addCrashSection(screen);

        Preference refresh = new Preference(requireContext());
        refresh.setTitle(R.string.diag_refresh);
        refresh.setSummary(R.string.diag_refresh_summary);
        refresh.setOnPreferenceClickListener(p -> {
            refresh();
            return true;
        });
        screen.addPreference(refresh);

        Preference copy = new Preference(requireContext());
        copy.setTitle(R.string.diag_copy);
        copy.setSummary(R.string.diag_copy_summary);
        copy.setOnPreferenceClickListener(p -> {
            copyDiagnostics();
            return true;
        });
        screen.addPreference(copy);

        // 新文案无法进 strings.xml（不在本次修复的文件清单内），用字面量并在此说明。
        Preference export = new Preference(requireContext());
        export.setTitle("导出诊断包");
        export.setSummary("系统分享诊断文本：版本/系统/框架 API/目标版本/功能状态/最近命中/最近崩溃");
        export.setOnPreferenceClickListener(p -> {
            exportDiagnostics();
            return true;
        });
        screen.addPreference(export);
    }

    /** 最近崩溃区（crash0 最新）：无崩溃时不显示。 */
    private void addCrashSection(PreferenceScreen screen) {
        PreferenceCategory category = null;
        for (int i = 0; i < 3; i++) {
            String crash = StatusProvider.reportCrash(requireContext(), i);
            if (crash == null || crash.isEmpty()) {
                continue;
            }
            if (category == null) {
                category = new PreferenceCategory(requireContext());
                category.setTitle("最近崩溃");
                screen.addPreference(category);
            }
            category.addPreference(crashRow(crash));
        }
    }

    /** 崩溃行：标题为格式化时间，摘要为线程/异常/栈帧明细（首段解析失败则整行原文）。 */
    private Preference crashRow(String crash) {
        Preference p = new Preference(requireContext());
        String title = crash;
        String detail = crash;
        int sep = crash.indexOf('|');
        if (sep > 0) {
            try {
                long ms = Long.parseLong(crash.substring(0, sep));
                title = DateFormat.getTimeFormat(requireContext()).format(new Date(ms));
                detail = crash.substring(sep + 1);
            } catch (NumberFormatException ignored) {
            }
        }
        p.setTitle(title);
        p.setSummary(detail);
        p.setSelectable(false);
        return p;
    }

    /** 最近崩溃文本（crashN 原文逐行）；无崩溃返回空串。 */
    private String crashText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            String crash = StatusProvider.reportCrash(requireContext(), i);
            if (crash != null && !crash.isEmpty()) {
                sb.append("crash").append(i).append(' ').append(crash).append('\n');
            }
        }
        return sb.toString();
    }

    private Preference row(Feature f) {
        Preference p = new Preference(requireContext());
        p.setKey("diag_" + f.key);
        p.setTitle(f.id + "  " + getString(f.titleRes));
        p.setSummary(summary(f));
        p.setSelectable(false);
        return p;
    }

    /** 单功能状态：registered · hit N · skip 原因；无数据用“—”。 */
    private String summary(Feature f) {
        if (f.blocked()) {
            return getString(R.string.diag_blocked, f.blockedReason);
        }
        Context context = requireContext();
        String reg = StatusProvider.reportString(context, StatusProvider.PREFIX_REG, f.key);
        long hit = StatusProvider.reportHit(context, f.key);
        String skip = StatusProvider.reportString(context, StatusProvider.PREFIX_SKIP, f.key);

        boolean hasReg = reg != null && !reg.isEmpty();
        boolean hasSkip = skip != null && !skip.isEmpty();
        boolean hasHit = hit > 0;
        if (!hasReg && !hasSkip && !hasHit) {
            return getString(R.string.diag_no_data);
        }

        String dash = getString(R.string.diag_no_data);
        StringBuilder sb = new StringBuilder();
        sb.append(hasReg ? getString(R.string.diag_registered) : dash);
        sb.append(" · ").append(getString(R.string.diag_hit, hit));
        sb.append(" · ").append(getString(R.string.diag_skip, hasSkip ? skip : dash));
        return sb.toString();
    }

    /** 是否有任一有效回传，用于决定是否显示空状态提示。 */
    private boolean hasData(Feature f) {
        Context context = requireContext();
        String reg = StatusProvider.reportString(context, StatusProvider.PREFIX_REG, f.key);
        String skip = StatusProvider.reportString(context, StatusProvider.PREFIX_SKIP, f.key);
        long hit = StatusProvider.reportHit(context, f.key);
        return (reg != null && !reg.isEmpty())
                || (skip != null && !skip.isEmpty())
                || hit > 0;
    }

    private void copyDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.app_name)).append(' ').append(versionName()).append('\n');
        sb.append(getString(R.string.diag_header, refreshTime())).append('\n');
        for (Feature f : Feature.values()) {
            sb.append(f.id).append(' ').append(getString(f.titleRes))
                    .append(": ").append(summary(f)).append('\n');
        }
        String crashes = crashText();
        if (!crashes.isEmpty()) {
            sb.append("最近崩溃:\n").append(crashes);
        }
        try {
            ClipboardManager cm = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("diagnostics", sb.toString()));
                Toast.makeText(requireContext(), R.string.diag_copied, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
            // 剪贴板不可用不阻塞诊断页。
        }
    }

    /**
     * 导出诊断包：聚合模块版本/系统版本/框架 API/目标版本/各功能状态/最近命中/
     * 最近崩溃为一段文本，ACTION_SEND text/plain 系统分享——不写文件、不依赖
     * FileProvider。分享面板拉不起来只弹 Toast，不阻塞诊断页。
     */
    private void exportDiagnostics() {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, buildExportText());
            startActivity(Intent.createChooser(send, "导出诊断包"));
        } catch (Throwable ignored) {
            Toast.makeText(requireContext(), "诊断包导出失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String buildExportText() {
        StringBuilder header = new StringBuilder();
        header.append(getString(R.string.app_name)).append(' ')
                .append(versionName()).append('\n');
        header.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        header.append("框架 API: ").append(frameworkApi()).append('\n');
        header.append("目标版本: ").append(targetVersion()).append('\n');
        header.append(getString(R.string.diag_header, refreshTime()));

        List<String> featureLines = new ArrayList<>();
        for (Feature f : Feature.values()) {
            featureLines.add(f.id + " " + getString(f.titleRes) + ": " + summary(f));
        }
        List<String> hitLines = new ArrayList<>();
        for (Feature f : Feature.values()) {
            long hit = StatusProvider.reportHit(requireContext(), f.key);
            if (hit > 0) {
                hitLines.add(f.id + " " + getString(f.titleRes) + ": hit " + hit);
            }
        }
        return joinExportText(header.toString(), featureLines, hitLines,
                crashText(), getString(R.string.diag_no_data));
    }

    /**
     * 导出文本的纯拼装（无 Android 依赖，静态包级可见供 JVM 单测锁定关键段，
     * 见 ReportProtocolTest）：headerBlock 为版本/系统/框架 API/目标版本/数据时间，
     * 功能状态/最近命中/最近崩溃三个段按固定顺序追加，空段用 noData 占位。
     */
    static String joinExportText(String headerBlock, List<String> featureLines,
            List<String> hitLines, String crashBlock, String noData) {
        StringBuilder sb = new StringBuilder();
        sb.append(headerBlock);
        sb.append("\n[功能状态]\n");
        for (String line : featureLines) {
            sb.append(line).append('\n');
        }
        sb.append("\n[最近命中]\n");
        if (hitLines.isEmpty()) {
            sb.append(noData).append('\n');
        } else {
            for (String line : hitLines) {
                sb.append(line).append('\n');
            }
        }
        sb.append("\n[最近崩溃]\n");
        sb.append(crashBlock.isEmpty() ? noData + '\n' : crashBlock);
        return sb.toString();
    }

    /** Xposed 框架 API 版本（模块 App 进程经 XposedService 绑定得知）；未绑定返回提示。 */
    private String frameworkApi() {
        try {
            io.github.libxposed.service.XposedService service = ServiceBridge.get();
            return service != null ? String.valueOf(service.getApiVersion()) : "未绑定";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 目标应用（小红书）版本；未安装或查询失败返回提示。 */
    private String targetVersion() {
        try {
            android.content.pm.PackageInfo info = requireContext().getPackageManager()
                    .getPackageInfo("com.xingin.xhs", 0);
            String version = info.versionName != null ? info.versionName : "?";
            return version + " (" + info.versionCode + ")";
        } catch (Throwable t) {
            return "未安装";
        }
    }

    private String refreshTime() {
        return DateFormat.getTimeFormat(requireContext()).format(new Date(mLastRefreshMs));
    }

    private int groupTitle(Feature.Group group) {
        switch (group) {
            case AD:
                return R.string.group_ad;
            case UI:
                return R.string.group_ui;
            case PV:
                return R.string.group_pv;
            case EN:
                return R.string.group_en;
            case EX:
            default:
                return R.string.group_ex;
        }
    }

    private String versionName() {
        try {
            String v = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            return v != null ? v : "?";
        } catch (Throwable t) {
            return "?";
        }
    }
}
