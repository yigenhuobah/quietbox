package com.quietbox.app;

import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.List;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

/**
 * 设置页。界面结构照 LSPosed 管理器：顶部状态卡 + 按用途分组的开关 + 页脚说明。
 *
 * 诊断信息（挂载点数 / 命中次数）不在 UI 展示 —— 按用户要求完全隐藏，
 * 排障看 logcat（tag QuietBox）。
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String TARGET = "com.xingin.xhs";

    private ConfigStore mStore;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs, rootKey);
        mStore = new ConfigStore(requireContext());
        bindFeatures();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /** 把 Feature 枚举里的标题/说明/开关接到 XML 声明的偏好项上。 */
    private void bindFeatures() {
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            Preference p = findPreference(f.key);
            if (p == null) {
                continue;
            }
            p.setTitle(f.titleRes);
            p.setSummary(f.summaryRes);
            p.setOnPreferenceChangeListener((pref, value) -> {
                if (value instanceof Boolean) {
                    mStore.put(f.key, (Boolean) value);
                }
                return true;
            });
        }

        Preference token = findPreference(Config.KEY_STRIP_XSEC_TOKEN);
        if (token != null) {
            token.setTitle(R.string.p1_9_strip_token_title);
            token.setSummary(R.string.p1_9_strip_token_summary);
            token.setOnPreferenceChangeListener((pref, value) -> {
                if (value instanceof Boolean) {
                    mStore.put(Config.KEY_STRIP_XSEC_TOKEN, (Boolean) value);
                }
                return true;
            });
        }

        Preference limits = findPreference("footer_limits");
        if (limits != null) {
            limits.setSummary(R.string.footer_limits);
        }
        Preference hint = findPreference("footer_hint");
        if (hint != null) {
            hint.setSummary(R.string.footer_hint);
        }
    }

    /** 重新读取框架状态与开关值。service 绑定后由 Activity 调一次。 */
    void refresh() {
        mStore = new ConfigStore(requireContext());
        boolean writable = mStore.remote();

        Preference status = findPreference("status");
        if (status != null) {
            status.setTitle(R.string.status_title);
            XposedService service = ServiceBridge.get();
            if (service == null) {
                status.setTitle(R.string.status_connecting);
                status.setSummary(R.string.status_detail_connecting);
            } else {
                int api = 0;
                boolean scoped = false;
                try {
                    api = service.getApiVersion();
                } catch (Throwable ignored) {
                }
                try {
                    List<String> scope = service.getScope();
                    scoped = scope != null && scope.contains(TARGET);
                } catch (Throwable ignored) {
                }
                // getRunningTargets 是 API 102 的"当前真的被注入的进程"，
                // 只有它非空才能说明模块已启用——光看 service 绑定会误报"已激活"。
                boolean injected = isTargetRunning(service);
                if (!scoped) {
                    status.setTitle(R.string.status_inactive);
                    status.setSummary(R.string.status_detail_inactive);
                } else if (injected) {
                    status.setTitle(R.string.status_active);
                    status.setSummary(getString(R.string.status_detail_active, api));
                } else {
                    status.setTitle(R.string.status_connected);
                    status.setSummary(R.string.status_detail_not_running);
                }
            }
        }

        // 版本不兼容横幅：只在目标进程上报"挂载大面积失败"时显示，平时隐藏。
        Preference warning = findPreference("warning_mount");
        if (warning != null) {
            MountReport report = new MountReport(requireContext());
            if (report.incompatible()) {
                warning.setSummary(getString(R.string.mount_warning, report.miss(),
                        report.ok() + report.miss()));
                warning.setVisible(true);
            } else {
                warning.setVisible(false);
            }
        }

        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            Preference p = findPreference(f.key);
            if (p instanceof MaterialSwitchPreference) {
                MaterialSwitchPreference sw = (MaterialSwitchPreference) p;
                sw.setCheckedValue(mStore.get(f.key, f.defaultEnabled));
                // 框架没绑定时写不进去，置灰避免"改了没用"。
                sw.setEnabled(writable);
            }
        }

        Preference token = findPreference(Config.KEY_STRIP_XSEC_TOKEN);
        if (token instanceof MaterialSwitchPreference) {
            MaterialSwitchPreference sw = (MaterialSwitchPreference) token;
            sw.setCheckedValue(mStore.get(Config.KEY_STRIP_XSEC_TOKEN,
                    Config.DEFAULT_STRIP_XSEC_TOKEN));
            sw.setEnabled(writable);
        }
    }

    /** 判断目标进程当前是否真的被注入（API 102 getRunningTargets）。 */
    private boolean isTargetRunning(XposedService service) {
        try {
            List<HookedTarget> targets = service.getRunningTargets();
            if (targets == null) {
                return false;
            }
            for (HookedTarget t : targets) {
                String name = t.getProcessName();
                if (name != null && (name.equals(TARGET) || name.startsWith(TARGET + ":"))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
