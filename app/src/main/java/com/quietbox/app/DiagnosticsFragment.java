package com.quietbox.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.util.Date;

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
