package com.triki.musiccontroller.ble;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.triki.musiccontroller.model.SavedDevice;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class DevicePreferences {

    private static final String PREF_NAME = "triki_devices_pref";
    private static final String KEY_SAVED_DEVICES = "saved_devices";
    private static final String KEY_LAST_TARGET_MAC = "last_target_mac";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public DevicePreferences(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public synchronized List<SavedDevice> getSavedDevices() {
        String json = prefs.getString(KEY_SAVED_DEVICES, null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            Type type = new TypeToken<ArrayList<SavedDevice>>() {}.getType();
            List<SavedDevice> list = gson.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public synchronized void saveDevices(List<SavedDevice> devices) {
        String json = gson.toJson(devices);
        prefs.edit().putString(KEY_SAVED_DEVICES, json).apply();
    }

    public synchronized void recordDeviceConnection(String macAddress, String name) {
        if (macAddress == null || macAddress.trim().isEmpty()) return;

        List<SavedDevice> list = getSavedDevices();
        SavedDevice existing = null;
        for (SavedDevice d : list) {
            if (macAddress.equalsIgnoreCase(d.getMacAddress())) {
                existing = d;
                break;
            }
        }

        long now = System.currentTimeMillis();
        if (existing != null) {
            existing.setLastConnectedTime(now);
            if (name != null && !name.trim().isEmpty() && !existing.getCustomName().equals(name)) {
                // Keep custom name if modified or update if default
                if (existing.getCustomName().startsWith("Triki")) {
                    existing.setCustomName(name);
                }
            }
        } else {
            // First time this device connects: default autoConnect to true if it's the only one
            boolean autoConnect = list.isEmpty();
            SavedDevice newDev = new SavedDevice(macAddress, name != null ? name : "Triki", autoConnect, now);
            list.add(0, newDev);
        }

        saveDevices(list);
        setLastTargetMac(macAddress);
    }

    public synchronized void setAutoConnect(String macAddress, boolean autoConnect) {
        List<SavedDevice> list = getSavedDevices();
        for (SavedDevice d : list) {
            if (macAddress.equalsIgnoreCase(d.getMacAddress())) {
                d.setAutoConnect(autoConnect);
                break;
            }
        }
        saveDevices(list);
    }

    public synchronized void updateDeviceName(String macAddress, String newName) {
        List<SavedDevice> list = getSavedDevices();
        for (SavedDevice d : list) {
            if (macAddress.equalsIgnoreCase(d.getMacAddress())) {
                d.setCustomName(newName);
                break;
            }
        }
        saveDevices(list);
    }

    public synchronized void removeDevice(String macAddress) {
        List<SavedDevice> list = getSavedDevices();
        list.removeIf(d -> macAddress.equalsIgnoreCase(d.getMacAddress()));
        saveDevices(list);

        if (macAddress.equalsIgnoreCase(getLastTargetMac())) {
            setLastTargetMac(null);
        }
    }

    public synchronized String getLastTargetMac() {
        return prefs.getString(KEY_LAST_TARGET_MAC, null);
    }

    public synchronized void setLastTargetMac(String mac) {
        prefs.edit().putString(KEY_LAST_TARGET_MAC, mac).apply();
    }

    public synchronized List<String> getAutoConnectMacs() {
        List<String> result = new ArrayList<>();
        for (SavedDevice d : getSavedDevices()) {
            if (d.isAutoConnect()) {
                result.add(d.getMacAddress().toUpperCase());
            }
        }
        return result;
    }
}
