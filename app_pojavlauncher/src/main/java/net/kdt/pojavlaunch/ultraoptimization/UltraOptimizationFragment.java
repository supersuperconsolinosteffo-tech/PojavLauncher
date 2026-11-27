package net.kdt.pojavlaunch.ultraoptimization;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Environment;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.ItemState;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Loader;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.PlanItem;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.ProgressCallback;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.Resolution;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The dedicated Ultra-Optimization screen.
 *
 * The feature is inert until this screen is opened: one scan reads the selected installation, and
 * Modrinth is contacted only while the user is here, once per scan and once per install. There is
 * no service, receiver, polling loop, or startup work, and the existing Ultra-Lite preference is
 * only read for display so the two states can be told apart.
 */
public class UltraOptimizationFragment extends Fragment
        implements ProgressCallback, UltraOptimizationInstaller.Callback {
    public static final String TAG = "UltraOptimizationFragment";

    private TextView mStatusText;
    private TextView mLogText;
    private LinearLayout mModList;
    private ProgressBar mProgressBar;
    private ScrollView mScroll;
    private Button mScanButton;
    private Button mInstallButton;
    private Button mRestoreButton;

    private final AtomicBoolean mCancelRequested = new AtomicBoolean(false);
    private Future<?> mTask;
    private UltraOptimizationScanner.Target mTarget;
    private Resolution mResolution;
    private AlertDialog mDialog;
    private boolean mBusy;

    public UltraOptimizationFragment() {
        super(R.layout.fragment_ultra_optimization);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mStatusText = view.findViewById(R.id.ultra_opt_status);
        mLogText = view.findViewById(R.id.ultra_opt_log);
        mModList = view.findViewById(R.id.ultra_opt_modlist);
        mProgressBar = view.findViewById(R.id.ultra_opt_progress);
        mScroll = view.findViewById(R.id.ultra_opt_scroll);
        mScanButton = view.findViewById(R.id.ultra_opt_scan_button);
        mInstallButton = view.findViewById(R.id.ultra_opt_install_button);
        mRestoreButton = view.findViewById(R.id.ultra_opt_restore_button);

        Button backButton = view.findViewById(R.id.ultra_opt_back_button);
        backButton.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());

        mScanButton.setOnClickListener(v -> startScan());
        mInstallButton.setOnClickListener(v -> confirmInstall());
        mRestoreButton.setOnClickListener(v -> confirmRestore());

        if (mTarget == null) startScan();
        else showScanResult(mTarget, mResolution);
    }

    @Override
    public void onDestroyView() {
        mCancelRequested.set(true);
        Future<?> task = mTask;
        if (task != null && !task.isDone()) task.cancel(true);
        mTask = null;
        mBusy = false;
        dismissDialog();
        if (mTarget != null && mTarget.gameDirectory != null) {
            // A download that was cut short leaves only staging files behind, never a mods change.
            UltraOptimizationInstaller.discardStaging(mTarget.gameDirectory);
        }
        mScroll = null;
        mStatusText = null;
        mLogText = null;
        mModList = null;
        mProgressBar = null;
        mScanButton = null;
        mInstallButton = null;
        mRestoreButton = null;
        super.onDestroyView();
    }

    // ---------------------------------------------------------------------------------------------
    // Scan
    // ---------------------------------------------------------------------------------------------

    private void startScan() {
        if (mBusy) {
            setStatus(getString(R.string.ultra_optimization_busy));
            return;
        }
        mCancelRequested.set(false);
        mResolution = null;
        setBusy(true, getString(R.string.ultra_optimization_scanning));
        mModList.removeAllViews();
        mLogText.setText("");
        mLogText.setVisibility(View.GONE);

        final Context applicationContext = requireContext().getApplicationContext();
        mTask = PojavApplication.sExecutorService.submit(() -> {
            UltraOptimizationScanner.Target target =
                    UltraOptimizationScanner.scanSelectedInstallation(applicationContext);
            Resolution resolution = null;
            if (target.isUsable()) {
                setBusyMessage(getString(R.string.ultra_optimization_resolving));
                resolution = UltraOptimizationResolver.resolve(target.environment,
                        new ModrinthClient(), this);
            }
            final Resolution finalResolution = resolution;
            Tools.runOnUiThread(() -> showScanResult(target, finalResolution));
        });
    }

    private void showScanResult(UltraOptimizationScanner.Target target, Resolution resolution) {
        if (mStatusText == null) return;
        mTarget = target;
        mResolution = resolution;
        setBusy(false, null);
        setProgress(0, 0, null);

        if (!target.isUsable()) {
            setStatus(target.error == null
                    ? getString(R.string.ultra_optimization_status_idle) : target.error);
            setActionsEnabled(true, false, target.stateExists);
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Installation: ").append(safe(target.instanceName)).append('\n');
        Environment environment = target.environment;
        builder.append("Minecraft: ")
                .append(isBlank(environment.minecraftVersion) ? "unresolved" : environment.minecraftVersion)
                .append("   Loader: ").append(environment.loader.displayName()).append('\n');
        builder.append("Renderer: ")
                .append(UltraOptimizationDetector.rendererDisplayName(environment.renderer)).append('\n');
        builder.append("Java runtime: ");
        if (environment.runtimeJavaMajor > 0) {
            builder.append("Java ").append(environment.runtimeJavaMajor);
            builder.append(environment.runtimeExplicit ? " (set for this installation)" : " (global default)");
        } else {
            builder.append("unknown, picked at launch");
        }
        if (resolution != null && resolution.requiredJavaMajor > 0) {
            builder.append("   Minecraft needs Java ").append(resolution.requiredJavaMajor);
        }
        builder.append('\n');
        builder.append("Allocated RAM: ")
                .append(environment.totalRamMb > 0 ? environment.totalRamMb + " MB" : "unknown")
                .append(environment.is32Bit ? " (32-bit process, memory limited)" : "").append('\n');
        builder.append("This installation: ")
                .append(target.stateExists
                        ? getString(R.string.ultra_optimization_state_enabled, target.installedByUs)
                        : getString(R.string.ultra_optimization_state_none)).append('\n');
        builder.append(getString(R.string.ultra_optimization_ultralite, ultraLiteStateLabel()));
        mStatusText.setText(builder.toString());

        boolean hasPlan = resolution != null && !resolution.installableItems().isEmpty();
        setActionsEnabled(true, hasPlan, target.stateExists);
        if (resolution != null) renderResolution(resolution);
    }

    private void renderResolution(Resolution resolution) {
        mModList.removeAllViews();
        for (PlanItem item : resolution.items) addModRow(item);

        StringBuilder log = new StringBuilder();
        if (resolution.summary != null) log.append(resolution.summary).append('\n');
        for (String error : resolution.errors) log.append("Error: ").append(error).append('\n');
        for (String warning : resolution.warnings) log.append("Note: ").append(warning).append('\n');
        if (log.length() == 0) log.append(getString(R.string.ultra_optimization_nothing_to_install));
        mLogText.setText(log.toString().trim());
        mLogText.setVisibility(View.VISIBLE);
    }

    private void addModRow(PlanItem item) {
        Context context = mModList.getContext();
        int padding = (int) Tools.dpToPx(10);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.background_card);
        row.setPadding(padding, padding, padding, padding);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = (int) Tools.dpToPx(6);
        row.setLayoutParams(params);

        TextView title = new TextView(context);
        title.setTextSize(13f);
        title.setText(getString(R.string.ultra_optimization_row_title, badgeFor(item.state), safe(item.title)));
        row.addView(title);

        TextView detail = new TextView(context);
        detail.setTextSize(12f);
        detail.setTextColor(getResources().getColor(R.color.secondary_text));
        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.ultra_optimization_line_format,
                item.versionNumber == null
                        ? getString(R.string.ultra_optimization_unknown_version) : item.versionNumber,
                item.fileName == null ? "-" : item.fileName,
                item.size > 0 ? humanReadableSize(item.size) : "-"));
        if (item.dependency) builder.append("   ").append(getString(R.string.ultra_optimization_dependency));
        if (item.estimatedCostMb > 0) {
            builder.append("   ").append(getString(R.string.ultra_optimization_cost, item.estimatedCostMb));
        }
        detail.setText(builder);
        row.addView(detail);

        if (!isBlank(item.description)) {
            TextView description = new TextView(context);
            description.setTextSize(12f);
            description.setText(item.description);
            description.setTextColor(getResources().getColor(R.color.secondary_text));
            row.addView(description);
        }

        if (!isBlank(item.reason)) {
            TextView reason = new TextView(context);
            reason.setTextSize(12f);
            reason.setText(item.reason);
            reason.setTextColor(getResources().getColor(R.color.secondary_text));
            row.addView(reason);
        }
        mModList.addView(row);
    }

    // ---------------------------------------------------------------------------------------------
    // Install
    // ---------------------------------------------------------------------------------------------

    private void confirmInstall() {
        final File gameDirectory = mTarget == null ? null : mTarget.gameDirectory;
        if (gameDirectory == null || mResolution == null) {
            setStatus(getString(R.string.ultra_optimization_no_plan));
            return;
        }
        final List<PlanItem> plan = mResolution.installableItems();
        if (plan.isEmpty()) {
            setStatus(getString(R.string.ultra_optimization_nothing_to_install));
            return;
        }
        StringBuilder names = new StringBuilder();
        for (PlanItem item : plan) {
            if (names.length() > 0) names.append('\n');
            names.append("  - ").append(safe(item.title));
            if (item.versionNumber != null) names.append(' ').append(item.versionNumber);
        }
        String message = getString(R.string.ultra_optimization_install_confirm,
                plan.size(), safe(mTarget.instanceName)) + "\n\n" + names;
        showConfirmation(getString(R.string.ultra_optimization_install_title), message, () -> {
            mCancelRequested.set(false);
            setBusy(true, getString(R.string.ultra_optimization_working));
            mTask = PojavApplication.sExecutorService.submit(() -> {
                UltraOptimizationInstaller.Result result = UltraOptimizationInstaller.install(
                        gameDirectory, mResolution, false, this, mCancelRequested);
                Tools.runOnUiThread(() -> showInstallResult(result));
            });
        });
    }

    private void showInstallResult(UltraOptimizationInstaller.Result result) {
        if (mLogText == null) return;
        setBusy(false, null);
        setProgress(0, 0, null);
        StringBuilder builder = new StringBuilder();
        if (result.cancelled) {
            builder.append(getString(R.string.ultra_optimization_result_cancelled)).append('\n');
        }
        builder.append(getString(R.string.ultra_optimization_result_summary,
                result.installed, result.skipped, result.failed));
        for (String message : result.messages) builder.append('\n').append(message);
        for (String error : result.errors) builder.append("\nError: ").append(error);
        mLogText.setText(builder.toString());
        mLogText.setVisibility(View.VISIBLE);
        setStatus(result.installed > 0
                ? "Installed. Launch the game to load the new mods; nothing else was changed."
                : "Nothing was installed.");
        mBusy = false; // re-scan is allowed even though the plan was applied
        startScan();
    }

    // ---------------------------------------------------------------------------------------------
    // Restore
    // ---------------------------------------------------------------------------------------------

    private void confirmRestore() {
        final File gameDirectory = mTarget == null ? null : mTarget.gameDirectory;
        if (gameDirectory == null || !UltraOptimizationState.hasState(gameDirectory)) {
            setStatus(getString(R.string.ultra_optimization_state_none));
            return;
        }
        UltraOptimizationState state = UltraOptimizationState.load(gameDirectory);
        final int count = state == null ? 0 : state.installedCount();
        showConfirmation(getString(R.string.ultra_optimization_restore_title),
                getString(R.string.ultra_optimization_restore_confirm, count), () -> {
                    setBusy(true, getString(R.string.ultra_optimization_working));
                    mTask = PojavApplication.sExecutorService.submit(() -> {
                        UltraOptimizationInstaller.Result result =
                                UltraOptimizationInstaller.restore(gameDirectory, this);
                        Tools.runOnUiThread(() -> {
                            mBusy = false;
                            setProgress(0, 0, null);
                            if (mLogText != null) {
                                StringBuilder builder = new StringBuilder();
                                builder.append("Removed ").append(result.removed)
                                        .append(", skipped ").append(result.skipped)
                                        .append(", failed ").append(result.failed);
                                for (String message : result.messages) builder.append('\n').append(message);
                                for (String error : result.errors) builder.append("\nError: ").append(error);
                                mLogText.setText(builder.toString());
                                mLogText.setVisibility(View.VISIBLE);
                            }
                            setStatus(result.removed > 0 || result.restored
                                    ? "The installation was rolled back to its previous state."
                                    : "Nothing needed to be rolled back.");
                            startScan();
                        });
                    });
                });
    }

    // ---------------------------------------------------------------------------------------------
    // Callbacks and small helpers
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onProgress(String message, int completed, int total) {
        Tools.runOnUiThread(() -> setProgress(completed, total, message));
    }

    @Override
    public void onLog(String message) {
        Tools.runOnUiThread(() -> {
            if (mLogText == null || message == null) return;
            mLogText.setVisibility(View.VISIBLE);
            mLogText.setText(mLogText.getText() + "\n" + message);
            if (mScroll != null) mScroll.post(() -> mScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void showConfirmation(String title, String message, Runnable onConfirm) {
        dismissDialog();
        mDialog = new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> onConfirm.run())
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(dialog -> mDialog = null)
                .show();
    }

    private void dismissDialog() {
        AlertDialog dialog = mDialog;
        mDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void setProgress(int completed, int total, String message) {
        if (mProgressBar == null) return;
        if (total <= 0) {
            mProgressBar.setVisibility(View.GONE);
            mProgressBar.setProgress(0);
        } else {
            mProgressBar.setVisibility(View.VISIBLE);
            mProgressBar.setMax(total);
            mProgressBar.setProgress(Math.max(0, Math.min(completed, total)));
        }
        if (message != null) setBusyMessage(message);
    }

    private void setBusyMessage(String message) {
        Tools.runOnUiThread(() -> {
            if (mLogText == null || message == null) return;
            mLogText.setVisibility(View.VISIBLE);
            mLogText.setText(message);
        });
    }

    private void setBusy(boolean busy, String message) {
        mBusy = busy;
        if (message != null) setStatus(message);
        setActionsEnabled(!busy, false, mTarget != null && mTarget.stateExists);
    }

    private void setActionsEnabled(boolean scan, boolean install, boolean restore) {
        if (mScanButton != null) mScanButton.setEnabled(scan);
        if (mInstallButton != null) mInstallButton.setEnabled(install);
        if (mRestoreButton != null) mRestoreButton.setEnabled(restore);
    }

    private void setStatus(String message) {
        if (mStatusText != null && message != null) mStatusText.setText(message);
    }

    private String ultraLiteStateLabel() {
        String profile = LauncherPreferences.DEFAULT_PREF
                .getString(UltraOptimizationMatrix.ULTRA_LITE_KEY, "balanced");
        if (profile == null) profile = "balanced";
        if ("ultra_lite".equals(profile)) {
            return getString(R.string.ultra_optimization_ultralite_on) + " (ultra_lite)";
        }
        if ("balanced".equals(profile)) {
            return getString(R.string.ultra_optimization_ultralite_off) + " (balanced)";
        }
        return profile;
    }

    private String badgeFor(ItemState state) {
        if (state == ItemState.INSTALLED) return getString(R.string.ultra_optimization_badge_installed);
        if (state == ItemState.SKIPPED) return getString(R.string.ultra_optimization_badge_skipped);
        if (state == ItemState.FAILED) return getString(R.string.ultra_optimization_badge_failed);
        return getString(R.string.ultra_optimization_badge_will_install);
    }

    private static String humanReadableSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "?" : value;
    }

    /** Only these loaders are ever optimized; Vanilla and unknown loaders are refused. */
    static boolean loaderIsOptimizable(Loader loader) {
        return loader == Loader.FABRIC || loader == Loader.FORGE
                || loader == Loader.NEOFORGE || loader == Loader.QUILT;
    }
}
