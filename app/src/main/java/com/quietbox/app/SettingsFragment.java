package com.quietbox.app;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;

/**
 * 设置 tab：状态卡 + 异常横幅 + AD/UI/PV/EN/EX 分组开关 + 页脚。
 *
 * 设置页零诊断：这里不展示逐功能 registered/hit/skip，只保留状态卡和聚合横幅；
 * 细节统一放到 {@link DiagnosticsFragment}（底栏的“诊断”tab）。
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String TAG = "QuietBox";
    private static final String TARGET = "com.xingin.xhs";

    /** 两次 refresh 的最小间隔，压掉 onCreatePreferences / onResume / 绑定回调三连。 */
    private static final long REFRESH_MIN_INTERVAL_MS = 1000L;

    /**
     * 状态卡的框架查询是同步 binder（getApiVersion/getScope/getRunningTargets），
     * 放后台单线程执行，避免框架卡顿时拖住设置页首帧。
     */
    private static final ExecutorService STATUS_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qb-settings-status");
                t.setDaemon(true);
                return t;
            });

    private ConfigStore mStore;
    /** mStore 对应的 service 实例；同一实例内 remote prefs 不变，可复用。 */
    private XposedService mStoreService;
    private long mLastRefreshMs;
    private boolean mHasRefreshed;
    private XposedService mLastRefreshService;
    /** 后台状态查询句柄：新查询提交前取消旧的，onDestroyView 取消全部。 */
    private Future<?> mStatusFuture;
    /** versionName 在 Fragment 生命周期内不变，只读一次 PackageManager。 */
    private String mVersionName;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs, rootKey);
        mStoreService = ServiceBridge.get();
        mStore = new ConfigStore(requireContext());
        bindFeatures();
        bindPages();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroyView() {
        // view 已销毁，后台查询结果不再有用；cancel(true) 让阻塞中的 binder 尽快返回。
        if (mStatusFuture != null) {
            mStatusFuture.cancel(true);
            mStatusFuture = null;
        }
        // 二级页返回或旋转后 view 会重建：取消过的查询不能靠 1 秒节流挡掉，
        // 否则状态卡可能停在被取消前的旧状态。
        mHasRefreshed = false;
        super.onDestroyView();
    }

    /** 把 Feature 的标题/说明/开关接到 XML 声明的偏好项上。 */
    private void bindFeatures() {
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                // blocked 功能保留编号与 Feature 条目，但不在设置页渲染。
                continue;
            }
            Preference p = findPreference(f.key);
            if (p == null) {
                Log.e(TAG, "preference missing for " + f.key);
                continue;
            }
            p.setTitle(f.titleRes);
            p.setSummary(f.summaryRes);
            p.setOnPreferenceChangeListener((pref, value) -> {
                if (value instanceof Boolean) {
                    boolean ok = mStore.put(f.key, (Boolean) value);
                    if (!ok) {
                        Toast.makeText(requireContext(), R.string.action_write_failed,
                                Toast.LENGTH_SHORT).show();
                        // 返回 false：MaterialSwitch 回滚到旧状态。
                        return false;
                    }
                }
                return true;
            });
        }

        Preference token = findPreference(Config.KEY_STRIP_XSEC_TOKEN);
        if (token != null) {
            token.setTitle(R.string.f_p1_9_strip_xsec_token_title);
            token.setSummary(R.string.f_p1_9_strip_xsec_token_summary);
            token.setOnPreferenceChangeListener((pref, value) -> {
                if (value instanceof Boolean) {
                    boolean ok = mStore.put(Config.KEY_STRIP_XSEC_TOKEN, (Boolean) value);
                    if (!ok) {
                        Toast.makeText(requireContext(), R.string.action_write_failed,
                                Toast.LENGTH_SHORT).show();
                        return false;
                    }
                }
                return true;
            });
        }
    }

    /** 页脚说明。 */
    private void bindPages() {
        Preference footer = findPreference("footer");
        if (footer != null) {
            footer.setSummary(getString(R.string.footer_restart,
                    getString(R.string.app_target_name))
                    + "\n" + getString(R.string.footer_compat,
                    getString(R.string.app_target_name), VersionGate.SUPPORTED_VERSION));
        }
    }

    /** 重新读取框架状态与开关值。service 绑定后由 MainActivity 调一次。 */
    void refresh() {
        if (getContext() == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        XposedService service = ServiceBridge.get();
        // 1 秒内的重复调用丢弃；binder 刚绑上/断开属于新信息，必须放行。
        if (mHasRefreshed
                && now - mLastRefreshMs < REFRESH_MIN_INTERVAL_MS
                && service == mLastRefreshService) {
            return;
        }
        mHasRefreshed = true;
        mLastRefreshMs = now;
        mLastRefreshService = service;

        // 同一 service 实例内 remote prefs 不会变，复用 mStore 可省掉每次 refresh 的 binder。
        if (mStore == null || service != mStoreService) {
            mStore = new ConfigStore(requireContext());
            mStoreService = service;
        }
        boolean writable = mStore.remote();

        Preference status = findPreference("status");
        if (status != null) {
            if (service == null) {
                status.setTitle(R.string.status_connecting);
                status.setSummary(getString(R.string.status_detail_connecting,
                        getString(R.string.app_target_name), shownTargetVersion()));
                appendRemoteState(status, writable);
            } else {
                loadStatusAsync(service, writable);
            }
        }

        for (Feature f : Feature.values()) {
            Preference p = findPreference(f.key);
            if (p instanceof MaterialSwitchPreference) {
                MaterialSwitchPreference sw = (MaterialSwitchPreference) p;
                sw.setCheckedValue(mStore.get(f.key, f.defaultEnabled));
                // 框架没绑定时写不进去，置灰避免“改了没用”；blocked 始终置灰。
                sw.setEnabled(writable && !f.blocked());
            }
        }

        Preference token = findPreference(Config.KEY_STRIP_XSEC_TOKEN);
        if (token instanceof MaterialSwitchPreference) {
            MaterialSwitchPreference sw = (MaterialSwitchPreference) token;
            sw.setCheckedValue(mStore.get(Config.KEY_STRIP_XSEC_TOKEN,
                    Config.DEFAULT_STRIP_XSEC_TOKEN));
            sw.setEnabled(writable && mStore.get(Feature.COPY_LINK_SANITIZE.key,
                    Feature.COPY_LINK_SANITIZE.defaultEnabled));
        }

        updateBanner();
    }

    /**
     * 状态卡的 service 查询全在后台单线程跑，结果回主线程写 UI。
     * Fragment 已 detach 或 service 已换绑时整份结果丢弃。
     */
    private void loadStatusAsync(XposedService service, boolean writable) {
        final XposedService bound = service;
        final boolean remoteWritable = writable;
        final String version = versionName();
        final String targetVersion = shownTargetVersion();
        final Activity host = requireActivity();

        if (mStatusFuture != null) {
            mStatusFuture.cancel(false);
        }
        mStatusFuture = STATUS_EXECUTOR.submit(() -> {
            int api = 0;
            boolean scoped = false;
            try {
                api = bound.getApiVersion();
            } catch (Throwable ignored) {
            }
            try {
                List<String> scope = bound.getScope();
                scoped = scope != null && scope.contains(TARGET);
            } catch (Throwable ignored) {
            }
            // getRunningTargets 是“当前真的被注入的进程”，只有它命中目标才能说明已激活。
            boolean injected = isTargetRunning(bound);
            final int finalApi = api;
            final boolean finalScoped = scoped;
            final boolean finalInjected = injected;

            if (!isAdded() || getActivity() != host) {
                return;
            }
            host.runOnUiThread(() -> {
                if (!isAdded() || getActivity() != host) {
                    return;
                }
                if (ServiceBridge.get() != bound) {
                    return;
                }
                Preference p = findPreference("status");
                if (p == null) {
                    return;
                }
                String appName = getString(R.string.app_target_name);
                if (!finalScoped) {
                    p.setTitle(R.string.status_inactive);
                    p.setSummary(getString(R.string.status_detail_no_scope,
                            finalApi, appName, targetVersion, version));
                } else if (finalInjected) {
                    p.setTitle(R.string.status_active);
                    p.setSummary(getString(R.string.status_detail_active,
                            finalApi, appName, targetVersion, version));
                } else {
                    p.setTitle(R.string.status_connected);
                    p.setSummary(getString(R.string.status_detail_not_running,
                            finalApi, appName, targetVersion, version));
                }
                appendRemoteState(p, remoteWritable);
                updateBanner();
            });
        });
    }

    /** 版本不匹配或 ≥2 个功能失效时显示红色横幅；不新增第五种状态。 */
    private void updateBanner() {
        Preference banner = findPreference("warning");
        if (banner == null || getContext() == null) {
            return;
        }
        String target = targetVersion();
        if (target == null) {
            // 目标应用未安装或 PackageManager 读不到版本：不显示横幅，避免误导。
            banner.setVisible(false);
            return;
        }
        String message = null;
        if (!VersionGate.isSupported(target)) {
            message = getString(R.string.status_banner_version, target,
                    VersionGate.SUPPORTED_VERSION);
        } else {
            int failed = StatusProvider.failedFeatureCount(requireContext());
            if (failed >= 2) {
                message = getString(R.string.status_banner_failed, failed);
            }
        }
        if (message == null) {
            banner.setVisible(false);
        } else {
            banner.setSummary(message);
            banner.setVisible(true);
        }
    }

    /** remote prefs 可用性挂在状态卡副标题第二行，抓“开关写不进框架”这个坑。 */
    private void appendRemoteState(Preference status, boolean writable) {
        status.setSummary(status.getSummary() + "\n"
                + getString(writable ? R.string.status_remote_ok : R.string.status_remote_fail));
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

    private String targetVersion() {
        try {
            PackageInfo pi = requireContext().getPackageManager().getPackageInfo(TARGET, 0);
            return pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    private String shownTargetVersion() {
        String v = targetVersion();
        return v != null ? v : getString(R.string.status_unknown_version);
    }

    private String versionName() {
        if (mVersionName != null) {
            return mVersionName;
        }
        try {
            String v = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            mVersionName = v != null ? v : "?";
        } catch (Throwable t) {
            mVersionName = "?";
        }
        return mVersionName;
    }
}
