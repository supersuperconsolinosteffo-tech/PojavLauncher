package net.kdt.pojavlaunch.prefs.screens;

import android.Manifest;
import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;

import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.PerformanceProfile;
import net.kdt.pojavlaunch.tasks.DataMigrator;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class LauncherPreferenceMiscellaneousFragment extends LauncherPreferenceFragment {

    private final ActivityResultLauncher<Uri> mMigrateLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), (uri) -> {
                if(uri != null) {
                    new AlertDialog.Builder(getLauncherActivity())
                            .setTitle(R.string.migration_progress_warning_title)
                            .setMessage(R.string.migration_progress_warning_summary)
                            .setPositiveButton(android.R.string.ok, (d, w) -> new DataMigrator(getLauncherActivity(), uri).migrateData())
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
            }
    );

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        mVisibilityUpdater = this::updateVisibility;
        addPreferencesFromResource(R.xml.pref_misc);
        Preference importPreference = requirePreference("runDataMigration");
        importPreference.setOnPreferenceClickListener(preference -> {
            if(!ProgressLayout.ensureNoOngoingTasks(getContext())) return true;
            mMigrateLauncher.launch(null);
            return true;
        });
        setupCacheClearPreference();
        setupMicrophoneRequestPreference();
        setupPerformanceProfilePreferences();
        updateVisibility();
    }

    private void updateVisibility(){
        requirePreference("microphoneAccessRequest").setVisible(!getLauncherActivity().checkForPermissionRationale(33, Manifest.permission.RECORD_AUDIO));
        requirePreference("clearMetadataCache").setVisible(new File(Tools.DIR_CACHE, "string_cache").exists());
        boolean custom = PerformanceProfile.PROFILE_CUSTOM.equals(PerformanceProfile.getProfile());
        for(String key : PERFORMANCE_SWITCHES) requirePreference(key).setEnabled(custom);
        PerformanceProfile.DeviceInfo info = PerformanceProfile.DeviceInfo.detect(requireContext());
        int suggestedName = PerformanceProfile.PROFILE_ULTRA_LITE.equals(info.suggestProfile())
                ? R.string.perf_profile_ultra_lite : R.string.perf_profile_balanced;
        requirePreference("performanceProfileInfo").setSummary(info.describe(requireContext(), suggestedName));
    }

    private static final String[] PERFORMANCE_SWITCHES = {
            PerformanceProfile.KEY_MINIMAL_BACKGROUND,
            PerformanceProfile.KEY_SMALL_ICON_CACHE,
            PerformanceProfile.KEY_LOW_PROGRESS_RATE,
            PerformanceProfile.KEY_DISABLE_LOGCAT
    };

    private void setupPerformanceProfilePreferences() {
        requirePreference("performanceProfileInfo").setOnPreferenceClickListener(preference -> {
            String suggested = PerformanceProfile.DeviceInfo.detect(requireContext()).suggestProfile();
            ListPreference profile = requirePreference(PerformanceProfile.KEY_PROFILE, ListPreference.class);
            profile.setValue(suggested);
            updateVisibility();
            Toast.makeText(getContext(), R.string.perf_profile_applied, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void setupMicrophoneRequestPreference() {
        Preference mRequestMicrophonePermissionPreference = requirePreference("microphoneAccessRequest");
        Activity activity = getActivity();
        if(activity instanceof LauncherActivity) {
            mRequestMicrophonePermissionPreference.setOnPreferenceClickListener(preference -> {
                ((LauncherActivity) activity).askForPermission(23, Manifest.permission.RECORD_AUDIO);
                return true;
            });
        } else {
            mRequestMicrophonePermissionPreference.setVisible(false);
        }
    }
    private void setupCacheClearPreference() {
        Preference clearPreference = requirePreference("clearMetadataCache");
        clearPreference.setOnPreferenceClickListener(preference -> {
            if(!ProgressLayout.ensureNoOngoingTasks(getContext())) return true;
            PojavApplication.sExecutorService.submit(() -> {
                try {
                    FileUtils.deleteDirectory(new File(Tools.DIR_CACHE, "string_cache"));
                } catch (IOException e) {
                    Tools.showErrorRemote(getLauncherActivity(), R.string.preference_metadata_clear_fail, e);
                    return;
                }
                Tools.runOnUiThread(() -> {
                    Toast.makeText(getLauncherActivity(), R.string.preference_metadata_clear_complete, Toast.LENGTH_LONG).show();
                    updateVisibility();
                });
            });
            return true;
        });
    }
}
