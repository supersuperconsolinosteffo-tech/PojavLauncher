package net.kdt.pojavlaunch.ultraoptimization;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

/**
 * Settings entry for the Ultra-Optimization screen.
 *
 * It stores nothing itself: the feature's state lives inside the selected installation, while the
 * existing global Ultra-Lite profile keeps its own preference, screen and behaviour. This follows
 * the same pattern as the other custom preferences in this launcher (see GamepadRemapPreference).
 */
public class UltraOptimizationPreference extends Preference {
    public UltraOptimizationPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @SuppressWarnings("unused")
    public UltraOptimizationPreference(@NonNull Context context) {
        super(context);
        init();
    }

    private void init() {
        if (getTitle() == null) setTitle(R.string.ultra_optimization_title);
        if (getSummary() == null) setSummary(R.string.ultra_optimization_pref_summary);
        if (getIcon() == null) setIcon(R.drawable.ic_px_zap);
    }

    @Override
    protected void onClick() {
        Context context = getContext();
        if (!(context instanceof FragmentActivity)) return;
        Tools.swapFragment((FragmentActivity) context, UltraOptimizationFragment.class,
                UltraOptimizationFragment.TAG, null);
    }
}
