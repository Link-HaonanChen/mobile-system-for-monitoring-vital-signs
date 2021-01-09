package com.grp.application.pages;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.example.application.R;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.grp.application.monitor.Monitor;
import com.grp.application.polar.PolarDevice;

import polar.com.sdk.api.PolarBleApiCallback;
import polar.com.sdk.api.errors.PolarInvalidArgument;
import polar.com.sdk.api.model.PolarDeviceInfo;
import polar.com.sdk.api.model.PolarHrData;

public class SettingsFragment extends Fragment {

    private Monitor monitor;
    private PolarDevice polarDevice;
    private ImageView symbolHrDevice;
    private ImageView symbolBrainWaveDevice;
    private Button hrConnectButton;
    private Button brainWaveConnectButton;
    private SwitchMaterial msgOnNotWearDeviceSwitch;
    private SwitchMaterial msgOnNotCaptureDataSwitch;
    private SwitchMaterial msgOnReportGenerated;


    public SettingsFragment() {}

    public View onCreateView(@NonNull LayoutInflater inflater,
            ViewGroup container, Bundle savedInstanceState) {

        View root = inflater.inflate(R.layout.fragment_settings, container, false);
        monitor = Monitor.getInstance();
        polarDevice = PolarDevice.getInstance();
        symbolHrDevice = root.findViewById(R.id.symbol_hr_device);
        symbolBrainWaveDevice = root.findViewById(R.id.symbol_scale_device);
        hrConnectButton = root.findViewById(R.id.button_connect_hr_device);
        brainWaveConnectButton = root.findViewById(R.id.button_connect_scale_device);
        msgOnNotWearDeviceSwitch = root.findViewById(R.id.switch_msg_not_wear_device);
        msgOnNotCaptureDataSwitch = root.findViewById(R.id.switch_msg_not_capture_data);
        msgOnReportGenerated = root.findViewById(R.id.switch_msg_report_generated);

        resetUI();
        initDevice();
        hrConnectButton.setOnClickListener(this::showPolarDeviceDialog);

        brainWaveConnectButton.setOnClickListener((buttonView) -> {
            monitor.getMonitorState().connectScaleDeviceConnected();
            monitor.getViewSetter().setDeviceView(symbolBrainWaveDevice, brainWaveConnectButton, monitor.getMonitorState().isScaleDeviceConnected());
        });

        msgOnNotWearDeviceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                monitor.showToast("Start Message");
                monitor.getMonitorState().enableMsgOnNotWearDevice();
            } else {
                monitor.showToast("Stop Message");
                monitor.getMonitorState().disableMsgOnNotWearDevice();
            }
        });
        msgOnNotCaptureDataSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                monitor.showToast("Start Warning");
                monitor.getMonitorState().enableMsgOnNotCaptureData();
            } else {
                monitor.showToast("Stop Warning");
                monitor.getMonitorState().disableMsgOnNotCaptureData();
            }
        });
        msgOnReportGenerated.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                monitor.showToast("Start Alert");
                monitor.getMonitorState().enableMsgOnReportGenerated();
            } else {
                monitor.showToast("Stop Alert");
                monitor.getMonitorState().disableMsgOnReportGenerated();
            }
        });

        return root;
    }

    private void initDevice() {
        polarDevice.api().setApiCallback(new PolarBleApiCallback() {
            @Override
            public void deviceConnected(@NonNull PolarDeviceInfo polarDeviceInfo) {
                monitor.showToast(polarDeviceInfo.deviceId + " is Connected");
                monitor.getMonitorState().connectHRDevice();
                resetUI();
            }

            @Override
            public void hrNotificationReceived(@NonNull String identifier, @NonNull PolarHrData data) {
                    monitor.getPlotterHR().addValues(data);
            }

            @Override
            public void ecgFeatureReady(@NonNull String identifier) {
                monitor.streamECG();
            }
        });
    }

    private void resetUI() {
        monitor.getViewSetter().setDeviceView(symbolHrDevice, hrConnectButton, monitor.getMonitorState().isHRDeviceConnected());
        monitor.getViewSetter().setDeviceView(symbolBrainWaveDevice, brainWaveConnectButton, monitor.getMonitorState().isScaleDeviceConnected());
        monitor.getViewSetter().setSwitchView(msgOnNotWearDeviceSwitch, monitor.getMonitorState().isMsgOnNotWearDeviceEnabled());
        monitor.getViewSetter().setSwitchView(msgOnNotCaptureDataSwitch, monitor.getMonitorState().isMsgOnNotCaptureDataEnabled());
        monitor.getViewSetter().setSwitchView(msgOnReportGenerated, monitor.getMonitorState().isMsgOnReportGeneratedEnabled());
    }

    private void showPolarDeviceDialog(View view) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this.getContext(), R.style.PolarTheme);
        dialog.setTitle("Enter your Polar device's ID");

        View viewInflated = LayoutInflater.from(monitor.getContext()).inflate(R.layout.device_id_dialog_layout, (ViewGroup) view.getRootView(), false);

        final EditText input = viewInflated.findViewById(R.id.input);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        dialog.setView(viewInflated);

        dialog.setPositiveButton("OK", (dialog1, which) -> {
            polarDevice.setDeviceId(input.getText().toString());
            try {
                polarDevice.api().connectToDevice(polarDevice.getDeviceId());
            } catch (PolarInvalidArgument polarInvalidArgument) {
                polarInvalidArgument.printStackTrace();
            }
            //SharedPreferences.Editor editor = sharedPreferences.edit();
            //editor.putString(SHARED_PREFS_KEY, deviceId);
            //editor.apply();
        });
        dialog.setNegativeButton("Cancel", (dialog12, which) -> dialog12.cancel());
        dialog.show();
    }
}