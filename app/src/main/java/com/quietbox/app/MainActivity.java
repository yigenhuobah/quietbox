package com.quietbox.app;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 模块设置入口。
 *
 * 界面结构照 LSPosed 管理器：顶部工具栏（点标题弹关于）+ 底栏（功能 / 测试）。
 * 主题是 Material 3 DayNight，API 31+ 跟随系统取色（Monet），以下回退静态色板。
 */
public class MainActivity extends AppCompatActivity {

    private static final String STATE_TAB = "tab";

    private BottomNavigationView mNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate 之前调用，否则动态取色不会生效。
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setSubtitle("v" + versionName());
        // 照 LSPosed 的做法：点标题弹出关于。
        toolbar.setOnClickListener(v -> showAbout());

        mNav = findViewById(R.id.bottom_nav);
        int tab = savedInstanceState == null
                ? R.id.nav_features
                : savedInstanceState.getInt(STATE_TAB, R.id.nav_features);
        // 先设置选中项再挂监听：旋转恢复时由 FragmentManager 还原页面，
        // 不能再走一次 showTab，否则会重建 Fragment。
        mNav.setSelectedItemId(tab);
        mNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
        if (savedInstanceState == null) {
            showTab(tab);
        }

        applyInsets();

        // 顺序不能反：registerListener 对已缓存的 binder 会立即回调，
        // 回调必须先挂上，否则首次绑定会被空回调吃掉、永远等不到重绘。
        ServiceBridge.setOnBound(() -> runOnUiThread(this::refreshCurrent));
        ServiceBridge.bind(getApplicationContext());
        ServiceBridge.poke(getApplicationContext());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, mNav == null ? R.id.nav_features : mNav.getSelectedItemId());
    }

    /** targetSdk 36 强制 edge-to-edge，内容会画到状态栏/导航栏下面，必须自己吃 insets。 */
    private void applyInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // 底部留给 BottomNavigationView，否则导航栏和底栏之间会多出一条空隙。
            v.setPadding(bars.left, bars.top, bars.right, 0);
            mNav.setPadding(0, 0, 0, bars.bottom);
            return windowInsets;
        });
    }

    private void showTab(int id) {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.settings_container);
        boolean wantAb = id == R.id.nav_ab;
        if (current != null && (current instanceof AbTestFragment) == wantAb) {
            return;
        }
        Fragment next = wantAb ? new AbTestFragment() : new SettingsFragment();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings_container, next)
                .commit();
    }

    private void refreshCurrent() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.settings_container);
        if (f instanceof SettingsFragment) {
            ((SettingsFragment) f).refresh();
        } else if (f instanceof AbTestFragment) {
            ((AbTestFragment) f).refresh();
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private void showAbout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_body, versionName()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
