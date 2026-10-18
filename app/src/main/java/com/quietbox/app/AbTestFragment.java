package com.quietbox.app;

import android.content.Context;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「测试」页。
 *
 * 顶部是面板总开关，下面是精选的高价值实验，最底部是模块探测到但未收录的 key。
 * 未收录项只读——它们可能是广告/埋点/服务端调参，强改容易出问题。
 *
 * 「其他」按 50 条一批增量加载：滚到底自动加载下一批，底部同时保留可点的
 * 「加载更多」兜底（列表不够长、滚不动时用）。
 */
public class AbTestFragment extends PreferenceFragmentCompat {

    private static final int OTHER_BATCH = 50;

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
            AbTest test = findByKey(p.getKey());
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

    private static AbTest findByKey(String key) {
        if (key == null) {
            return null;
        }
        for (AbTest t : AbTests.ALL) {
            if (t.key.equals(key)) {
                return t;
            }
        }
        return null;
    }
}
