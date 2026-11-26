package net.kdt.pojavlaunch.prefs.screens;

import static net.kdt.pojavlaunch.Architecture.is32BitsDevice;
import static net.kdt.pojavlaunch.Tools.getTotalDeviceMemory;

import android.app.ActivityManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.multirt.MultiRTConfigDialog;
import net.kdt.pojavlaunch.prefs.CustomSeekBarPreference;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.PerformanceProfile;
import net.kdt.pojavlaunch.utils.MemoryUtils;

public class LauncherPreferenceJavaFragment extends LauncherPreferenceFragment {
    // Memory figures do not need a 1 Hz refresh. The loop only runs while the screen is resumed.
    private static final long MEMORY_REFRESH_INTERVAL_MS = 2000L;

    private EditText mSetJavaMemory;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable memoryUpdater = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || mMemorySeek == null) return;
            updateMemoryInfo(requireContext(), mMemorySeek);
            uiHandler.postDelayed(this, MEMORY_REFRESH_INTERVAL_MS);
        }
    };
    private CustomSeekBarPreference mMemorySeek;
    private MultiRTConfigDialog mDialogScreen;
    private final ActivityResultLauncher<Object> mVmInstallLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("xz"), (data)->{
                if(data != null) Tools.installRuntimeFromUri(getContext(), data);
            });

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        int ramAllocation = LauncherPreferences.PREF_RAM_ALLOCATION;
        addPreferencesFromResource(R.xml.pref_java);

        CustomSeekBarPreference seek7 = requirePreference("allocation",
                CustomSeekBarPreference.class);
        mMemorySeek = seek7;

        int maxRAM;
        int deviceRam = getTotalDeviceMemory(seek7.getContext());

        if(is32BitsDevice() || deviceRam < 2048) maxRAM = Math.min(1024, deviceRam);
        else maxRAM = deviceRam - (deviceRam < 3064 ? 800 : 1024);

        seek7.setRange(256, maxRAM);
        seek7.setValue(ramAllocation);
        seek7.setSuffix(" MB");
        seek7.setSelectable(true);

        seek7.setOnPreferenceClickListener(preference -> {
            setMemoryAllocationDialog(seek7, ramAllocation, maxRAM);
            return true;
        });

        seek7.setOnPreferenceChangeListener((preference, newValue) -> {
            updateMemoryInfo(requireContext(), seek7);
            return true;
        });

        updateMemoryInfo(requireContext(), seek7);

        EditTextPreference editJVMArgs = findPreference("javaArgs");
        if (editJVMArgs != null) {
            editJVMArgs.setOnBindEditTextListener(TextView::setSingleLine);
        }

        requirePreference("install_jre").setOnPreferenceClickListener(preference->{
            openMultiRTDialog();
            return true;
        });

        requirePreference("applyRecommendedHeap").setOnPreferenceClickListener(preference -> {
            int recommended = PerformanceProfile.applyRecommendedHeap(requireContext());
            mMemorySeek.setValue(recommended);
            updateMemoryInfo(requireContext(), mMemorySeek);
            Toast.makeText(requireContext(),
                    getString(R.string.perf_heap_applied, recommended), Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        uiHandler.removeCallbacks(memoryUpdater);
        uiHandler.post(memoryUpdater);
    }

    @Override
    public void onPause() {
        uiHandler.removeCallbacks(memoryUpdater);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        uiHandler.removeCallbacks(memoryUpdater);
        super.onDestroy();
    }

    private void updateMemoryInfo(Context context, CustomSeekBarPreference seek) {
        ActivityManager.MemoryInfo memInfo = MemoryUtils.getMemoryInfo(context);
        long seekValue = (long) seek.getValue() * 1024 * 1024;

        boolean isMemorySizeExceeded = seekValue > memInfo.availMem;

        String summary = getString(R.string.setting_java_memory_desc);
        summary += "\r\n" + getMemoryInfoText(context, memInfo);
        if (isMemorySizeExceeded) summary += "\r\n" + getString(R.string.setting_java_memory_exceeded);

        seek.setSummary(summary);
    }

    private String getMemoryInfoText(Context context) {
        return getMemoryInfoText(context, MemoryUtils.getMemoryInfo(context));
    }

    private String getMemoryInfoText(Context context, ActivityManager.MemoryInfo memInfo) {
        return getString(
                R.string.setting_java_memory_info,
                Tools.formatFileSize(memInfo.totalMem - memInfo.availMem),
                Tools.formatFileSize(memInfo.totalMem),
                Tools.formatFileSize(memInfo.availMem)
        );
    }

    private void setMemoryAllocationDialog(CustomSeekBarPreference seek, int ramAllocation, int maxRAM) {
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_java_memory, null);
        mSetJavaMemory = view.findViewById(R.id.set_java_memory);
        mSetJavaMemory.setText(String.valueOf(seek.getValue()));
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
            .setTitle(R.string.mcl_memory_allocation)
            .setMessage(getMemoryInfoText(requireContext()) + "\r\n" + getString(R.string.setting_java_memory_max, String.format("%s MB", maxRAM)))
            .setView(view)
            .setPositiveButton(R.string.alertdialog_done, null)
            .setNegativeButton(R.string.alertdialog_cancel, null)
            .create();
        
        dialog.setOnShowListener(d -> {
            Button positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            positiveButton.setOnClickListener(v -> {
                try {
                    int memory = Integer.parseInt(mSetJavaMemory.getText().toString());
                    if (memory < 256) {
                        mSetJavaMemory.setError("Min 256 MB");
                        return;
                    }
                    if (memory > maxRAM) {
                        mSetJavaMemory.setError("Max " + maxRAM + " MB");
                        return;
                    }
                    seek.setValue(memory);
                    dialog.dismiss();
                } catch (NumberFormatException e) {
                    mSetJavaMemory.setError("Invalid number");
                }
            });
        });
        dialog.show();
    }

    private void openMultiRTDialog() {
        if (mDialogScreen == null) {
            mDialogScreen = new MultiRTConfigDialog();
            mDialogScreen.prepare(getContext(), mVmInstallLauncher);
        }
        mDialogScreen.show();
    }
}
