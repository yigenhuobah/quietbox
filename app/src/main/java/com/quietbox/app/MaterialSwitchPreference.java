package com.quietbox.app;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Material 3 风格的开关行。
 *
 * 不用 androidx.preference.SwitchPreferenceCompat：它的默认外观是旧版 SwitchCompat，
 * 与 Material 3 主题不搭。这里自己绑 MaterialSwitch，并关掉本地持久化 ——
 * 开关值统一由 {@link ConfigStore} 写进框架的 remote preferences，
 * 否则小红书进程读不到。
 */
public class MaterialSwitchPreference extends Preference {

    private boolean mChecked;

    public MaterialSwitchPreference(Context context) {
        this(context, null);
    }

    public MaterialSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_material_switch);
        setPersistent(false);
    }

    public void setCheckedValue(boolean checked) {
        if (mChecked != checked) {
            mChecked = checked;
            notifyDependencyChange(shouldDisableDependents());
            notifyChanged();
        }
    }

    public boolean isCheckedValue() {
        return mChecked;
    }

    /**
     * android:dependency 依赖的是这个：父开关关闭时把子项置灰。
     * 基类 Preference 的默认实现看的是 isEnabled()，对开关语义不对。
     */
    @Override
    public boolean shouldDisableDependents() {
        return !mChecked || super.shouldDisableDependents();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        MaterialSwitch sw = (MaterialSwitch) holder.findViewById(R.id.preference_switch);
        if (sw == null) {
            return;
        }
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(mChecked);
        sw.setOnCheckedChangeListener((button, checked) -> {
            // 只有用户真正点按才处理；程序 setChecked 引起的回调忽略，避免回环。
            if (!button.isPressed()) {
                return;
            }
            if (callChangeListener(checked)) {
                mChecked = checked;
                notifyDependencyChange(shouldDisableDependents());
            } else {
                button.setChecked(mChecked);
            }
        });
    }

    @Override
    protected void onClick() {
        if (!isEnabled()) {
            return;
        }
        boolean next = !mChecked;
        if (callChangeListener(next)) {
            mChecked = next;
            notifyDependencyChange(shouldDisableDependents());
            notifyChanged();
        }
    }
}
