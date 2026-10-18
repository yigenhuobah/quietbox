package com.quietbox.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「测试」页。
 *
 * 顶部是面板总开关和「导出全量实验」，下面是精选的高价值实验，
 * 最底部是模块探测到但未收录的 key（50 条一批，滚到底自动加载）。
 */
public class AbTestFragment extends PreferenceFragmentCompat {

    private static final int OTHER_BATCH = 50;
    private static final String CATALOG = "ab_catalog.tsv";

    private final ActivityResultLauncher<String> mCreateDoc =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                    this::onExportUri);

    private ConfigStore mStore;
    private AbConfigStore mAb;
    private MaterialSwitchPreference mMaster;
    private PreferenceCategory mOther;
    private Preference mMore;

    private final List<String> mOtherKeys = new ArrayList<>();
    private final Map<String, String> mOtherReport = new LinkedHashMap<>();
    private int mOtherLoaded;
    private boolean mLoadingMore;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        mStore = new ConfigStore(requireContext());
        mAb = new AbConfigStore(requireContext());
        build(screen);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RecyclerView list = getListView();
        if (list == null) {
            return;
        }
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || rv.canScrollVertically(1)) {
                    return;
                }
                // 不能在 RecyclerView 布局/滚动过程中直接改 adapter，post 到下一帧。
                if (mLoadingMore) {
                    return;
                }
                mLoadingMore = true;
                rv.post(() -> {
                    mLoadingMore = false;
                    loadMoreOther();
                });
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void build(PreferenceScreen screen) {
        Context ctx = requireContext();

        PreferenceCategory intro = new PreferenceCategory(ctx);
        intro.setTitle(R.string.ab_warning_title);
        screen.addPreference(intro);

        Preference warning = new Preference(ctx);
        warning.setLayoutResource(R.layout.preference_footer);
        warning.setSelectable(false);
        warning.setSummary(R.string.ab_warning);
        intro.addPreference(warning);

        mMaster = new MaterialSwitchPreference(ctx);
        mMaster.setIcon(R.drawable.ic_test);
        mMaster.setTitle(R.string.ab_panel_title);
        mMaster.setSummary(R.string.ab_panel_summary);
        mMaster.setOnPreferenceChangeListener((pref, value) -> {
            mAb.setPanelEnabled((Boolean) value);
            refresh();
            return true;
        });
        screen.addPreference(mMaster);

        Preference export = new Preference(ctx);
        export.setIcon(R.drawable.ic_test);
        export.setTitle(R.string.ab_export_title);
        export.setSummary(R.string.ab_export_summary);
        export.setOnPreferenceClickListener(pref -> {
            mCreateDoc.launch(exportName());
            return true;
        });
        screen.addPreference(export);

        int lastGroup = -1;
        PreferenceCategory category = null;
        for (AbTest test : AbTests.ALL) {
            if (test.group != lastGroup) {
                category = new PreferenceCategory(ctx);
                category.setTitle(AbTests.groupTitle(test.group));
                screen.addPreference(category);
                lastGroup = test.group;
            }
            MaterialSwitchPreference item = new MaterialSwitchPreference(ctx);
            item.setIcon(R.drawable.ic_test);
            item.setTitle(test.titleRes);
            item.setOnPreferenceChangeListener((pref, value) -> {
                mAb.setForced(test.key, (Boolean) value, test.forceValue);
                updateItem(item, test);
                return true;
            });
            item.setKey(test.key);
            category.addPreference(item);
        }

        mOther = new PreferenceCategory(ctx);
        mOther.setTitle(R.string.ab_other_title);
        screen.addPreference(mOther);
    }

    void refresh() {
        if (mMaster == null) {
            return;
        }
        mStore = new ConfigStore(requireContext());
        mAb = new AbConfigStore(requireContext());
        boolean master = mStore.get(Feature.AB_TEST_PANEL.key, false);
        // remote 是启动时的权威值；本地副本给运行中的拉取通道用，二者保持一致。
        mAb.syncPanelEnabled(master);
        mMaster.setCheckedValue(master);
        mMaster.setEnabled(mStore.remote());
        mMaster.setSummary(mStore.remote()
                ? R.string.ab_panel_summary : R.string.ab_need_framework);

        boolean active = master && mStore.remote();
        PreferenceScreen screen = getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference p = screen.getPreference(i);
            if (!(p instanceof MaterialSwitchPreference) || p == mMaster) {
                continue;
            }
            AbTest test = AbTests.byKey(p.getKey());
            if (test == null) {
                continue;
            }
            MaterialSwitchPreference item = (MaterialSwitchPreference) p;
            item.setEnabled(active);
            item.setCheckedValue(mAb.isForced(test.key));
            updateItem(item, test);
        }
        refreshOther();
    }

    private void updateItem(MaterialSwitchPreference item, AbTest test) {
        boolean forced = mAb.isForced(test.key);
        String current = mAb.report().get(test.key);
        String state;
        if (forced) {
            state = getString(R.string.ab_forced_value, test.forceValue);
        } else if (current != null) {
            state = getString(R.string.ab_current_value, current);
        } else {
            state = getString(R.string.ab_not_read);
        }
        item.setSummary(test.key + " · " + state + "\n" + getString(test.summaryRes));
    }

    /** 重置分页并加载第一批；后续由滚动监听 / 「加载更多」增量追加。 */
    private void refreshOther() {
        if (mOther == null) {
            return;
        }
        Context ctx = requireContext();
        mOther.removeAll();
        mMore = null;
        mOtherLoaded = 0;
        mOtherKeys.clear();
        mOtherReport.clear();

        long last = mAb.lastReport();
        Preference info = new Preference(ctx);
        info.setSelectable(false);
        info.setSummary(last == 0
                ? getString(R.string.ab_other_empty)
                : getString(R.string.ab_other_last,
                DateFormat.format("MM-dd HH:mm:ss", last)));
        mOther.addPreference(info);

        Map<String, String> report = mAb.report();
        for (Map.Entry<String, String> e : report.entrySet()) {
            if (!AbTests.isCurated(e.getKey())) {
                mOtherReport.put(e.getKey(), e.getValue());
            }
        }
        mOtherKeys.addAll(mOtherReport.keySet());
        Collections.sort(mOtherKeys);

        loadMoreOther();
    }

    private void loadMoreOther() {
        if (mOther == null || mOtherLoaded >= mOtherKeys.size()) {
            updateMore();
            return;
        }
        Context ctx = requireContext();
        int end = Math.min(mOtherLoaded + OTHER_BATCH, mOtherKeys.size());
        for (; mOtherLoaded < end; mOtherLoaded++) {
            String key = mOtherKeys.get(mOtherLoaded);
            Preference p = new Preference(ctx);
            p.setTitle(key);
            p.setSummary(getString(R.string.ab_other_summary,
                    mOtherReport.get(key), otherTag(key)));
            p.setSelectable(false);
            mOther.addPreference(p);
        }

        if (mMore == null) {
            mMore = new Preference(ctx);
            mMore.setOnPreferenceClickListener(p -> {
                loadMoreOther();
                return true;
            });
        }
        // 把「加载更多」始终挪到最底部。
        if (mMore.getParent() != null) {
            ((PreferenceGroup) mMore.getParent()).removePreference(mMore);
        }
        mOther.addPreference(mMore);
        updateMore();
    }

    private void updateMore() {
        if (mMore == null) {
            return;
        }
        int total = mOtherKeys.size();
        if (mOtherLoaded >= total) {
            mMore.setTitle(R.string.ab_other_done);
            mMore.setSummary(getString(R.string.ab_other_done_summary, total));
            mMore.setSelectable(false);
        } else {
            mMore.setTitle(R.string.ab_other_load_more);
            mMore.setSummary(getString(R.string.ab_other_loaded, mOtherLoaded, total));
            mMore.setSelectable(true);
        }
    }

    private String otherTag(String key) {
        String k = key.toLowerCase();
        if (k.contains("_ad") || k.contains("ads_") || k.contains("track")
                || k.contains("apm") || k.startsWith("andr_live")
                || k.contains("_config") || k.contains("_interval")) {
            return getString(R.string.ab_other_risky);
        }
        return getString(R.string.ab_other_unknown);
    }

    // ---------- 全量导出 ----------

    private static String exportName() {
        return "quietbox-abtest-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".csv";
    }

    private void onExportUri(Uri uri) {
        if (uri == null) {
            return;
        }
        Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            try {
                String csv = buildCsv(app);
                int rows = countRows(csv);
                try (OutputStream os = app.getContentResolver().openOutputStream(uri)) {
                    if (os == null) {
                        throw new IOException("openOutputStream returned null");
                    }
                    // UTF-8 BOM：Excel 直接打开不乱码。
                    os.write(0xEF);
                    os.write(0xBB);
                    os.write(0xBF);
                    os.write(csv.getBytes(StandardCharsets.UTF_8));
                }
                toast(app, app.getString(R.string.ab_export_done, rows));
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                toast(app, app.getString(R.string.ab_export_failed, msg));
            }
        }, "QuietBox-Export").start();
    }

    /** 全量目录（assets/ab_catalog.tsv） + 本次探测值，合成与 PC 导出同口径的 CSV。 */
    private String buildCsv(Context app) throws IOException {
        Map<String, String> report = new AbConfigStore(app).report();
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("key,current_value,status,primary_category,recommendation,")
                .append("curated,force_value,curated_title\n");

        Set<String> seen = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                app.getAssets().open(CATALOG), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 3 || !seen.add(parts[0])) {
                    continue;
                }
                appendRow(sb, app, parts[0], parts[1], parts[2], report);
            }
        }

        // 运行中探测到、但不在 9.50.0 目录里的 key（服务端新增等）。
        for (String key : report.keySet()) {
            if (seen.add(key)) {
                appendRow(sb, app, key, "其他", "未收录", report);
            }
        }
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, Context app, String key, String category,
                           String recommendation, Map<String, String> report) {
        AbTest curated = AbTests.byKey(key);
        String value = report.get(key);
        String status = value != null ? "已读取" : "未读取";
        String rec = curated != null ? "精选（已收录）" : recommendation;
        String force = curated != null ? curated.forceValue : "";
        String title = curated != null ? app.getString(curated.titleRes) : "";
        sb.append(esc(key)).append(',')
                .append(esc(value == null ? "" : value)).append(',')
                .append(status).append(',')
                .append(esc(category)).append(',')
                .append(esc(rec)).append(',')
                .append(curated != null ? "是" : "否").append(',')
                .append(esc(force)).append(',')
                .append(esc(title)).append('\n');
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        String escaped = value.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    private static int countRows(String csv) {
        int lines = 0;
        for (int i = 0; i < csv.length(); i++) {
            if (csv.charAt(i) == '\n') {
                lines++;
            }
        }
        return Math.max(0, lines - 1);
    }

    private static void toast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
