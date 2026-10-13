package com.triki.musiccontroller.model;

import java.util.Objects;

public class SavedDevice {
    private String macAddress;
    private String customName;
    private boolean autoConnect;
    private long lastConnectedTime;

    public SavedDevice() {
    }

    public SavedDevice(String macAddress, String customName, boolean autoConnect, long lastConnectedTime) {
        this.macAddress = macAddress;
        this.customName = customName;
        this.autoConnect = autoConnect;
        this.lastConnectedTime = lastConnectedTime;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getCustomName() {
        return customName != null && !customName.trim().isEmpty() ? customName : "Triki";
    }

    public void setCustomName(String customName) {
        this.customName = customName;
    }

    public boolean isAutoConnect() {
        return autoConnect;
    }

    public void setAutoConnect(boolean autoConnect) {
        this.autoConnect = autoConnect;
    }

    public long getLastConnectedTime() {
        return lastConnectedTime;
    }

    public void setLastConnectedTime(long lastConnectedTime) {
        this.lastConnectedTime = lastConnectedTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SavedDevice that = (SavedDevice) o;
        return Objects.equals(macAddress, that.macAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(macAddress);
    }
}
