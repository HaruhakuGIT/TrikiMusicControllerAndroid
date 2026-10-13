package com.triki.musiccontroller;

import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.triki.musiccontroller.ble.DevicePreferences;
import com.triki.musiccontroller.ble.TrikiBleManager;
import com.triki.musiccontroller.model.SavedDevice;

import java.util.ArrayList;
import java.util.List;

public class DeviceManagerBottomSheet {

    public interface DeviceActionListener {
        void onConnectRequested(String macAddress);
        void onDisconnectRequested();
        void onStartScanRequested();
        void onStopScanRequested();
    }

    private final Context context;
    private final DevicePreferences preferences;
    private final DeviceActionListener actionListener;
    private final TrikiBleManager bleManager;

    private BottomSheetDialog dialog;
    private SavedDevicesAdapter savedAdapter;
    private DiscoveredDevicesAdapter discoveredAdapter;

    private final List<DiscoveredItem> discoveredList = new ArrayList<>();
    private ProgressBar scanProgressBar;
    private TextView lblAvailable;
    private RecyclerView recyclerDiscovered;
    private MaterialButton btnScan;

    public static class DiscoveredItem {
        public final BluetoothDevice device;
        public final int rssi;

        public DiscoveredItem(BluetoothDevice device, int rssi) {
            this.device = device;
            this.rssi = rssi;
        }
    }

    public DeviceManagerBottomSheet(Context context, TrikiBleManager bleManager, DeviceActionListener actionListener) {
        this.context = context;
        this.bleManager = bleManager;
        this.preferences = bleManager.getPreferences();
        this.actionListener = actionListener;
    }

    public void show() {
        dialog = new BottomSheetDialog(context);
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_device_manager, null);
        dialog.setContentView(view);

        RecyclerView recyclerSaved = view.findViewById(R.id.recyclerSavedDevices);
        recyclerDiscovered = view.findViewById(R.id.recyclerDiscoveredDevices);
        TextView txtEmptySaved = view.findViewById(R.id.txtEmptySaved);
        lblAvailable = view.findViewById(R.id.lblAvailable);
        scanProgressBar = view.findViewById(R.id.scanProgressBar);
        btnScan = view.findViewById(R.id.btnScanNearby);
        MaterialButton btnClose = view.findViewById(R.id.btnCloseDialog);

        recyclerSaved.setLayoutManager(new LinearLayoutManager(context));
        recyclerDiscovered.setLayoutManager(new LinearLayoutManager(context));

        savedAdapter = new SavedDevicesAdapter();
        recyclerSaved.setAdapter(savedAdapter);

        discoveredAdapter = new DiscoveredDevicesAdapter();
        recyclerDiscovered.setAdapter(discoveredAdapter);

        refreshSavedList(txtEmptySaved);

        btnScan.setOnClickListener(v -> {
            discoveredList.clear();
            discoveredAdapter.notifyDataSetChanged();
            lblAvailable.setVisibility(View.VISIBLE);
            scanProgressBar.setVisibility(View.VISIBLE);
            recyclerDiscovered.setVisibility(View.VISIBLE);
            btnScan.setEnabled(false);
            btnScan.setText(R.string.device_scanning);

            if (actionListener != null) {
                actionListener.onStartScanRequested();
            }

            v.postDelayed(() -> {
                scanProgressBar.setVisibility(View.GONE);
                btnScan.setEnabled(true);
                btnScan.setText(R.string.device_scan);
                if (actionListener != null) {
                    actionListener.onStopScanRequested();
                }
            }, 12000);
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.setOnDismissListener(d -> {
            if (actionListener != null) {
                actionListener.onStopScanRequested();
            }
        });

        dialog.show();
    }

    public void addDiscoveredDevice(BluetoothDevice device, int rssi) {
        if (dialog == null || !dialog.isShowing()) return;

        String mac = device.getAddress();
        for (int i = 0; i < discoveredList.size(); i++) {
            if (discoveredList.get(i).device.getAddress().equalsIgnoreCase(mac)) {
                discoveredList.set(i, new DiscoveredItem(device, rssi));
                if (discoveredAdapter != null) discoveredAdapter.notifyItemChanged(i);
                return;
            }
        }

        discoveredList.add(new DiscoveredItem(device, rssi));
        if (lblAvailable != null) lblAvailable.setVisibility(View.VISIBLE);
        if (recyclerDiscovered != null) recyclerDiscovered.setVisibility(View.VISIBLE);
        if (discoveredAdapter != null) discoveredAdapter.notifyItemInserted(discoveredList.size() - 1);
    }

    public void notifyConnectionChanged() {
        if (savedAdapter != null) {
            savedAdapter.notifyDataSetChanged();
        }
    }

    private void refreshSavedList(TextView txtEmpty) {
        List<SavedDevice> list = preferences.getSavedDevices();
        if (list.isEmpty()) {
            txtEmpty.setVisibility(View.VISIBLE);
        } else {
            txtEmpty.setVisibility(View.GONE);
        }
        savedAdapter.setItems(list);
    }

    // --- RECYCLER ADAPTER FOR SAVED DEVICES ---
    private class SavedDevicesAdapter extends RecyclerView.Adapter<SavedDevicesAdapter.ViewHolder> {
        private final List<SavedDevice> items = new ArrayList<>();

        public void setItems(List<SavedDevice> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_saved_device, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SavedDevice dev = items.get(position);
            holder.txtName.setText(dev.getCustomName());
            holder.txtMac.setText(dev.getMacAddress());

            boolean isCurrentConnected = bleManager.getState() == TrikiBleManager.ConnectionState.CONNECTED
                    && bleManager.getConnectedDevice() != null
                    && dev.getMacAddress().equalsIgnoreCase(bleManager.getConnectedDevice().getAddress());

            holder.badgeActive.setVisibility(isCurrentConnected ? View.VISIBLE : View.GONE);
            if (isCurrentConnected) {
                holder.btnConnect.setText(R.string.device_disconnect_btn);
                holder.btnConnect.setTextColor(Color.parseColor("#EF5350"));
                holder.btnConnect.setStrokeColorResource(android.R.color.holo_red_light);
            } else {
                holder.btnConnect.setText(R.string.device_connect_btn);
                holder.btnConnect.setTextColor(Color.parseColor("#00E5FF"));
                holder.btnConnect.setStrokeColorResource(android.R.color.holo_blue_light);
            }

            holder.btnConnect.setOnClickListener(v -> {
                if (isCurrentConnected) {
                    if (actionListener != null) actionListener.onDisconnectRequested();
                } else {
                    if (actionListener != null) actionListener.onConnectRequested(dev.getMacAddress());
                    dialog.dismiss();
                }
            });

            // Prevent switch listener trigger on bind
            holder.switchAuto.setOnCheckedChangeListener(null);
            holder.switchAuto.setChecked(dev.isAutoConnect());
            holder.switchAuto.setOnCheckedChangeListener((btn, isChecked) -> {
                preferences.setAutoConnect(dev.getMacAddress(), isChecked);
                dev.setAutoConnect(isChecked);
            });

            holder.btnRename.setOnClickListener(v -> {
                EditText input = new EditText(context);
                input.setText(dev.getCustomName());
                input.setSelection(dev.getCustomName().length());

                new AlertDialog.Builder(context)
                        .setTitle(R.string.device_rename)
                        .setView(input)
                        .setPositiveButton("OK", (d, w) -> {
                            String name = input.getText().toString().trim();
                            if (!name.isEmpty()) {
                                preferences.updateDeviceName(dev.getMacAddress(), name);
                                dev.setCustomName(name);
                                notifyItemChanged(holder.getAdapterPosition());
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });

            holder.btnForget.setOnClickListener(v -> {
                new AlertDialog.Builder(context)
                        .setTitle(R.string.device_forget)
                        .setMessage(dev.getCustomName() + " (" + dev.getMacAddress() + ")?")
                        .setPositiveButton("OK", (d, w) -> {
                            preferences.removeDevice(dev.getMacAddress());
                            items.remove(holder.getAdapterPosition());
                            notifyItemRemoved(holder.getAdapterPosition());
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView txtName, txtMac, badgeActive;
            MaterialButton btnConnect;
            SwitchMaterial switchAuto;
            ImageView btnRename, btnForget;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                txtName = itemView.findViewById(R.id.txtDeviceName);
                txtMac = itemView.findViewById(R.id.txtDeviceMac);
                badgeActive = itemView.findViewById(R.id.txtConnectedBadge);
                btnConnect = itemView.findViewById(R.id.btnConnectDevice);
                switchAuto = itemView.findViewById(R.id.switchAutoConnect);
                btnRename = itemView.findViewById(R.id.btnRename);
                btnForget = itemView.findViewById(R.id.btnForget);
            }
        }
    }

    // --- RECYCLER ADAPTER FOR DISCOVERED NEARBY DEVICES ---
    private class DiscoveredDevicesAdapter extends RecyclerView.Adapter<DiscoveredDevicesAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_discovered_device, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            DiscoveredItem item = discoveredList.get(position);
            String name = item.device.getName();
            holder.txtName.setText(name != null ? name : "Triki");
            holder.txtMac.setText(item.device.getAddress());
            holder.txtRssi.setText(item.rssi + " dBm");

            holder.btnConnect.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onConnectRequested(item.device.getAddress());
                }
                dialog.dismiss();
            });
        }

        @Override
        public int getItemCount() {
            return discoveredList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView txtName, txtMac, txtRssi;
            MaterialButton btnConnect;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                txtName = itemView.findViewById(R.id.txtDiscoveredName);
                txtMac = itemView.findViewById(R.id.txtDiscoveredMac);
                txtRssi = itemView.findViewById(R.id.txtRssi);
                btnConnect = itemView.findViewById(R.id.btnConnectDiscovered);
            }
        }
    }
}
