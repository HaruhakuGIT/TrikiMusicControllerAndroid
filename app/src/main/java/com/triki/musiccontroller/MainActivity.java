package com.triki.musiccontroller;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.triki.musiccontroller.ble.TrikiBleManager;
import com.triki.musiccontroller.databinding.ActivityMainBinding;
import com.triki.musiccontroller.model.ImuSample;
import com.triki.musiccontroller.model.MusicAction;
import com.triki.musiccontroller.service.TrikiForegroundService;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements TrikiForegroundService.ServiceCallback {

    private ActivityMainBinding binding;
    private TrikiForegroundService service;
    private boolean isBound = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            TrikiForegroundService.LocalBinder localBinder = (TrikiForegroundService.LocalBinder) binder;
            service = localBinder.getService();
            service.setCallback(MainActivity.this);
            isBound = true;
            updateConnectionUi(service.getBleManager().getState(), "");

            int batt = service.getBleManager().getLastKnownBattery();
            if (batt > 0) {
                binding.txtBattery.setText("🔋 " + batt + "%");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            service = null;
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) { allGranted = false; break; }
                }
                if (allGranted) {
                    startAndBindService();
                } else {
                    Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Respect status bar, display cutouts (camera notch/hole-punch), and navigation bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() |
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            );
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });

        binding.txtTrainedCount.setText(getString(R.string.trained_models, 2));

        binding.btnDeviceManager.setOnClickListener(v -> {
            if (isBound && service != null) {
                showDeviceManager();
            } else {
                checkPermissionsAndStart();
            }
        });

        binding.btnConnect.setOnClickListener(v -> {
            if (isBound && service != null) {
                service.toggleConnect();
            } else {
                checkPermissionsAndStart();
            }
        });

        checkPermissionsAndStart();
    }

    private DeviceManagerBottomSheet deviceManagerSheet;

    private void showDeviceManager() {
        if (service == null) return;
        deviceManagerSheet = new DeviceManagerBottomSheet(this, service.getBleManager(), new DeviceManagerBottomSheet.DeviceActionListener() {
            @Override
            public void onConnectRequested(String macAddress) {
                if (service != null) {
                    service.getBleManager().connectDirect(macAddress);
                }
            }

            @Override
            public void onDisconnectRequested() {
                if (service != null) {
                    service.getBleManager().disconnect();
                }
            }

            @Override
            public void onStartScanRequested() {
                if (service != null) {
                    service.getBleManager().startDiscoveryOnly();
                }
            }

            @Override
            public void onStopScanRequested() {
                if (service != null) {
                    service.getBleManager().stopDiscovery();
                }
            }
        });
        deviceManagerSheet.show();
    }

    private void checkPermissionsAndStart() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.BLUETOOTH);
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        boolean needRequest = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }

        if (needRequest) {
            permissionLauncher.launch(permissions.toArray(new String[0]));
        } else {
            startAndBindService();
        }
    }

    private void startAndBindService() {
        Intent serviceIntent = new Intent(this, TrikiForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            if (service != null) {
                service.setCallback(null);
            }
            unbindService(connection);
            isBound = false;
        }
    }

    // SERVICE CALLBACKS
    @Override
    public void onConnectionStateChanged(TrikiBleManager.ConnectionState state, String message) {
        runOnUiThread(() -> updateConnectionUi(state, message));
    }

    private void updateConnectionUi(TrikiBleManager.ConnectionState state, String message) {
        if (deviceManagerSheet != null) {
            deviceManagerSheet.notifyConnectionChanged();
        }

        switch (state) {
            case CONNECTED:
                binding.txtStatus.setText("🟢 " + getString(R.string.status_connected));
                binding.txtStatus.setTextColor(Color.parseColor("#00E676"));
                binding.btnConnect.setText(R.string.btn_disconnect);
                binding.btnConnect.setBackgroundColor(Color.parseColor("#D32F2F"));

                if (service != null && service.getBleManager() != null) {
                    String devName = service.getBleManager().getConnectedDeviceName();
                    if (devName != null) {
                        binding.txtConnectedDevice.setText(getString(R.string.device_connected_prefix, devName));
                        binding.txtConnectedDevice.setVisibility(View.VISIBLE);
                    }
                }
                break;
            case CONNECTING:
            case SCANNING:
                binding.txtStatus.setText("🟡 " + (state == TrikiBleManager.ConnectionState.SCANNING ? getString(R.string.status_scanning) : getString(R.string.status_connecting)));
                binding.txtStatus.setTextColor(Color.parseColor("#F57F17"));
                binding.btnConnect.setText(R.string.btn_stop);
                binding.btnConnect.setBackgroundColor(Color.parseColor("#455A64"));
                break;
            default:
                binding.txtStatus.setText("🔴 " + getString(R.string.status_disconnected));
                binding.txtStatus.setTextColor(Color.parseColor("#EF5350"));
                binding.btnConnect.setText(R.string.btn_connect);
                binding.btnConnect.setBackgroundColor(Color.parseColor("#00C853"));
                binding.txtBattery.setText("🔋 --%");
                binding.txtConnectedDevice.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public void onSampleReceived(ImuSample sample) {
        double aMag = sample.getAccelMagnitude();
        double gMag = sample.getGyroMagnitude();

        runOnUiThread(() -> {
            // Accel
            binding.txtTotalG.setText(String.format("%.2f G", aMag));
            binding.pbTotalG.setProgress((int) Math.min(500, aMag * 100));

            binding.txtAccelX.setText(String.format("%.2f G", sample.accelX));
            binding.pbAccelX.setProgress((int) Math.max(0, Math.min(600, (sample.accelX + 3.0f) * 100)));

            binding.txtAccelY.setText(String.format("%.2f G", sample.accelY));
            binding.pbAccelY.setProgress((int) Math.max(0, Math.min(600, (sample.accelY + 3.0f) * 100)));

            binding.txtAccelZ.setText(String.format("%.2f G", sample.accelZ));
            binding.pbAccelZ.setProgress((int) Math.max(0, Math.min(600, (sample.accelZ + 3.0f) * 100)));

            // Gyro
            String gUnit = getString(R.string.gyro_unit);
            binding.txtTotalGyro.setText(String.format(java.util.Locale.US, "%.0f %s", gMag, gUnit));
            binding.pbTotalGyro.setProgress((int) Math.min(500, gMag));

            binding.txtGyroX.setText(String.format(java.util.Locale.US, "%.1f %s", sample.gyroX, gUnit));
            binding.pbGyroX.setProgress((int) Math.max(0, Math.min(1000, sample.gyroX + 500)));

            binding.txtGyroY.setText(String.format(java.util.Locale.US, "%.1f %s", sample.gyroY, gUnit));
            binding.pbGyroY.setProgress((int) Math.max(0, Math.min(1000, sample.gyroY + 500)));

            binding.txtGyroZ.setText(String.format(java.util.Locale.US, "%.1f %s", sample.gyroZ, gUnit));
            binding.pbGyroZ.setProgress((int) Math.max(0, Math.min(1000, sample.gyroZ + 500)));
        });
    }

    @Override
    public void onButtonStateChanged(boolean isPressed) {
        runOnUiThread(() -> {
            if (isPressed) {
                binding.txtButtonStateBadge.setText(R.string.button_state_held);
                binding.txtButtonStateBadge.setBackgroundColor(Color.parseColor("#00E5FF"));
                binding.txtButtonStateBadge.setTextColor(Color.BLACK);

                binding.txtGestureHint.setText("🟢 " + getString(R.string.gesture_hint_listening));
                binding.txtGestureHint.setTextColor(Color.parseColor("#00E676"));
            } else {
                binding.txtButtonStateBadge.setText(R.string.button_state_released);
                binding.txtButtonStateBadge.setBackgroundColor(Color.parseColor("#2A3142"));
                binding.txtButtonStateBadge.setTextColor(Color.parseColor("#ECEFF1"));

                binding.txtGestureHint.setText("⚪ " + getString(R.string.gesture_hint_idle));
                binding.txtGestureHint.setTextColor(Color.parseColor("#00E5FF"));
            }
        });
    }

    @Override
    public void onGestureRecognized(MusicAction action, String name, float confidence) {
        runOnUiThread(() -> {
            MaterialCardView targetCard = null;
            int glowColor = Color.parseColor("#00E676");

            switch (action) {
                case PLAY_PAUSE:
                    targetCard = binding.cardPlayPause;
                    glowColor = Color.parseColor("#00E676");
                    binding.txtPlayConf.setText(String.format("%.0f%%", confidence * 100));
                    break;
                case NEXT_TRACK:
                    targetCard = binding.cardNext;
                    glowColor = Color.parseColor("#00E5FF");
                    binding.txtNextConf.setText(String.format("%.0f%%", confidence * 100));
                    break;
                case PREVIOUS_TRACK:
                    targetCard = binding.cardPrev;
                    glowColor = Color.parseColor("#00E5FF");
                    binding.txtPrevConf.setText(String.format("%.0f%%", confidence * 100));
                    break;
            }

            if (targetCard != null) {
                flashCard(targetCard, glowColor);
            }
        });
    }

    @Override
    public void onBatteryReceived(int percent) {
        runOnUiThread(() -> {
            binding.txtBattery.setText("🔋 " + percent + "%");
        });
    }

    @Override
    public void onDiscoveredDevice(android.bluetooth.BluetoothDevice device, int rssi) {
        runOnUiThread(() -> {
            if (deviceManagerSheet != null) {
                deviceManagerSheet.addDiscoveredDevice(device, rssi);
            }
        });
    }

    private void flashCard(MaterialCardView card, int color) {
        card.setStrokeColor(color);
        card.setCardBackgroundColor(Color.parseColor("#263238"));
        handler.postDelayed(() -> {
            card.setStrokeColor(Color.parseColor("#2A3142"));
            card.setCardBackgroundColor(Color.parseColor("#171A23"));
        }, 350);
    }
}
