package com.triki.musiccontroller.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.triki.musiccontroller.model.ImuSample;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class TrikiBleManager {

    private static final String TAG = "TrikiBleManager";

    // Nordic UART Service (NUS) UUIDs
    public static final UUID NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NUS_RX_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"); // Write
    public static final UUID NUS_TX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"); // Notify
    public static final UUID CCCD_UUID        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Standard Battery Service UUIDs
    public static final UUID BATTERY_SERVICE_UUID    = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    public static final UUID BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    // Magic Start Command to initiate IMU stream from Triki
    private static final byte[] START_COMMAND = new byte[] {
            (byte) 0x20, (byte) 0x10, (byte) 0x00, (byte) 0xD0,
            (byte) 0x07, (byte) 0x68, (byte) 0x00, (byte) 0x03
    };

    private static final double GYRO_SCALE = 131.0;
    private static final double ACCEL_SCALE = 2048.0;

    public enum ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED
    }

    public interface BleListener {
        void onConnectionStateChanged(ConnectionState state, String message);
        void onSampleReceived(ImuSample sample);
        void onButtonStateChanged(boolean isPressed);
        void onBatteryReceived(int percent);
        void onDiscoveredDevice(BluetoothDevice device, int rssi);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final DevicePreferences preferences;
    private BleListener listener;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic txCharacteristic;
    private ConnectionState state = ConnectionState.DISCONNECTED;

    private int lastKnownBattery = -1;
    private boolean isScanning = false;
    private final List<Byte> packetBuffer = new ArrayList<>();
    private boolean lastButtonState = false;

    private String targetMacAddress = null; // Specific device to connect to, or null for auto
    private BluetoothDevice connectedDevice = null;

    public TrikiBleManager(Context context) {
        this.context = context.getApplicationContext();
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.preferences = new DevicePreferences(this.context);
    }

    public DevicePreferences getPreferences() {
        return preferences;
    }

    public void setListener(BleListener listener) {
        this.listener = listener;
        if (lastKnownBattery > 0 && listener != null) {
            listener.onBatteryReceived(lastKnownBattery);
        }
    }

    public ConnectionState getState() {
        return state;
    }

    public int getLastKnownBattery() {
        return lastKnownBattery;
    }

    public BluetoothDevice getConnectedDevice() {
        return connectedDevice;
    }

    public String getConnectedDeviceName() {
        if (connectedDevice == null) return null;
        String mac = connectedDevice.getAddress();
        for (com.triki.musiccontroller.model.SavedDevice d : preferences.getSavedDevices()) {
            if (mac.equalsIgnoreCase(d.getMacAddress())) {
                return d.getCustomName();
            }
        }
        String name = connectedDevice.getName();
        return name != null ? name : "Triki";
    }

    public synchronized void startScanAndConnect() {
        startScanAndConnect(null);
    }

    public synchronized void startScanAndConnect(String specificMac) {
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
            disconnect();
            return;
        }

        this.targetMacAddress = specificMac;

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            notifyState(ConnectionState.DISCONNECTED, "Bluetooth is disabled");
            return;
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            notifyState(ConnectionState.DISCONNECTED, "BLE Scanner unavailable");
            return;
        }

        notifyState(ConnectionState.SCANNING, "Searching for Triki...");
        isScanning = true;

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        bleScanner.startScan(null, settings, scanCallback);

        // Scan timeout after 15 seconds
        mainHandler.postDelayed(() -> {
            if (isScanning) {
                stopScan();
                if (state == ConnectionState.SCANNING) {
                    notifyState(ConnectionState.DISCONNECTED, "Triki not found (timeout)");
                }
            }
        }, 15000);
    }

    public synchronized void startDiscoveryOnly() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) return;

        isScanning = true;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        bleScanner.startScan(null, settings, discoveryCallback);

        mainHandler.postDelayed(() -> {
            if (isScanning) {
                try {
                    bleScanner.stopScan(discoveryCallback);
                } catch (Exception ignored) {}
                isScanning = false;
            }
        }, 12000);
    }

    public synchronized void stopDiscovery() {
        if (isScanning && bleScanner != null) {
            try {
                bleScanner.stopScan(discoveryCallback);
            } catch (Exception ignored) {}
            isScanning = false;
        }
    }

    public synchronized void connectDirect(String macAddress) {
        if (macAddress == null) return;
        stopScan();
        stopDiscovery();

        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
            disconnect();
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            notifyState(ConnectionState.DISCONNECTED, "Bluetooth is disabled");
            return;
        }

        try {
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);
            if (device != null) {
                connectToDevice(device);
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get remote device: " + macAddress, e);
        }

        // Fallback: scan for this MAC
        startScanAndConnect(macAddress);
    }

    public synchronized void disconnect() {
        stopScan();
        stopDiscovery();
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        connectedDevice = null;
        rxCharacteristic = null;
        txCharacteristic = null;
        packetBuffer.clear();
        notifyState(ConnectionState.DISCONNECTED, "Disconnected");
    }

    private void stopScan() {
        if (isScanning && bleScanner != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception ignored) {}
            isScanning = false;
        }
    }

    private final ScanCallback discoveryCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            if (isTrikiDevice(name)) {
                if (listener != null) {
                    mainHandler.post(() -> listener.onDiscoveredDevice(device, result.getRssi()));
                }
            }
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            String mac = device.getAddress();

            if (listener != null && isTrikiDevice(name)) {
                mainHandler.post(() -> listener.onDiscoveredDevice(device, result.getRssi()));
            }

            if (!isTrikiDevice(name)) return;

            // Scenario 1: A specific device was requested by user
            if (targetMacAddress != null) {
                if (targetMacAddress.equalsIgnoreCase(mac)) {
                    Log.d(TAG, "Found target specified device: " + name + " [" + mac + "]");
                    stopScan();
                    connectToDevice(device);
                }
                return;
            }

            // Scenario 2: Check auto-connect list
            List<String> autoConnectMacs = preferences.getAutoConnectMacs();
            if (!autoConnectMacs.isEmpty()) {
                if (autoConnectMacs.contains(mac.toUpperCase())) {
                    Log.d(TAG, "Found auto-connect permitted device: " + name + " [" + mac + "]");
                    stopScan();
                    connectToDevice(device);
                }
                return;
            }

            // Scenario 3: If no devices have auto-connect configured, connect to first Triki
            Log.d(TAG, "Connecting to first detected Triki: " + name + " [" + mac + "]");
            stopScan();
            connectToDevice(device);
        }
    };

    private boolean isTrikiDevice(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.contains("triki") || lower.contains("velto");
    }

    private void connectToDevice(BluetoothDevice device) {
        connectedDevice = device;
        String devName = device.getName() != null ? device.getName() : "Triki";
        notifyState(ConnectionState.CONNECTING, "Connecting to " + devName + "...");
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT connected, requesting MTU 256...");
                try {
                    gatt.requestMtu(256);
                } catch (Exception ignored) {}
                mainHandler.postDelayed(() -> {
                    if (bluetoothGatt != null) {
                        bluetoothGatt.discoverServices();
                    }
                }, 300);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT disconnected");
                connectedDevice = null;
                notifyState(ConnectionState.DISCONNECTED, "Disconnected");
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyState(ConnectionState.DISCONNECTED, "Service discovery failed");
                return;
            }

            Log.d(TAG, "Services discovered. Checking services...");

            BluetoothGattService nus = gatt.getService(NUS_SERVICE_UUID);
            if (nus == null) {
                Log.e(TAG, "Nordic UART Service (NUS) not found!");
                notifyState(ConnectionState.DISCONNECTED, "NUS Service not found");
                return;
            }

            rxCharacteristic = nus.getCharacteristic(NUS_RX_CHAR_UUID);
            txCharacteristic = nus.getCharacteristic(NUS_TX_CHAR_UUID);

            if (txCharacteristic == null || rxCharacteristic == null) {
                Log.e(TAG, "TX or RX characteristic missing in NUS service");
                notifyState(ConnectionState.DISCONNECTED, "NUS TX/RX missing");
                return;
            }

            // Step 1: Subscribe to Battery Notifications if supported, or read battery first!
            BluetoothGattService battService = gatt.getService(BATTERY_SERVICE_UUID);
            BluetoothGattCharacteristic battChar = null;
            if (battService != null) {
                battChar = battService.getCharacteristic(BATTERY_LEVEL_CHAR_UUID);
            }

            if (battChar != null) {
                Log.d(TAG, "Battery characteristic found! Reading battery level first...");
                final BluetoothGattCharacteristic finalBattChar = battChar;
                boolean readSuccess = gatt.readCharacteristic(finalBattChar);
                Log.d(TAG, "gatt.readCharacteristic(battery) triggered: " + readSuccess);
            } else {
                Log.w(TAG, "Battery Service not found, proceeding directly to NUS TX setup");
                setupNusTx(gatt);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "onCharacteristicRead: uuid=" + characteristic.getUuid() + ", status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.getUuid().equals(BATTERY_LEVEL_CHAR_UUID)) {
                byte[] val = characteristic.getValue();
                if (val != null && val.length > 0) {
                    int battery = val[0] & 0xFF;
                    if (battery <= 100) {
                        lastKnownBattery = battery;
                        Log.d(TAG, "Battery Level read successfully: " + battery + "%");
                        if (listener != null) {
                            mainHandler.post(() -> listener.onBatteryReceived(battery));
                        }
                    }
                }
            }

            // After battery is read, proceed to configure NUS TX notifications!
            setupNusTx(gatt);
        }

        private void setupNusTx(BluetoothGatt gatt) {
            if (txCharacteristic == null) return;
            Log.d(TAG, "Setting up NUS TX characteristic notification...");
            gatt.setCharacteristicNotification(txCharacteristic, true);

            BluetoothGattDescriptor descriptor = txCharacteristic.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean descSuccess = gatt.writeDescriptor(descriptor);
                Log.d(TAG, "Write CCCD descriptor result: " + descSuccess);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.getCharacteristic().getUuid().equals(NUS_TX_CHAR_UUID)) {
                Log.d(TAG, "TX CCCD configured successfully! Sending START_COMMAND to RX...");

                // Delay 500ms to allow Triki firmware to settle, then send start packet
                mainHandler.postDelayed(() -> {
                    if (rxCharacteristic != null && bluetoothGatt != null) {
                        rxCharacteristic.setValue(START_COMMAND);
                        rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                        boolean writeOk = bluetoothGatt.writeCharacteristic(rxCharacteristic);
                        Log.d(TAG, "START_COMMAND written to RX: " + writeOk);

                        // Save device connection in preferences
                        BluetoothDevice dev = bluetoothGatt.getDevice();
                        if (dev != null) {
                            preferences.recordDeviceConnection(dev.getAddress(), dev.getName());
                        }

                        notifyState(ConnectionState.CONNECTED, "Connected to Triki!");
                    }
                }, 500);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data != null && data.length > 0) {
                handleIncomingBytes(data);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (value != null && value.length > 0) {
                handleIncomingBytes(value);
            }
        }
    };

    private synchronized void handleIncomingBytes(byte[] bytes) {
        for (byte b : bytes) {
            packetBuffer.add(b);
        }

        while (packetBuffer.size() >= 14) {
            if ((packetBuffer.get(0) & 0xFF) != 0x22) {
                packetBuffer.remove(0);
                continue;
            }

            boolean isPressed = packetBuffer.get(1) != 0;
            if (isPressed != lastButtonState) {
                lastButtonState = isPressed;
                if (listener != null) {
                    mainHandler.post(() -> listener.onButtonStateChanged(isPressed));
                }
            }

            short ax = readShortLE(packetBuffer.get(2), packetBuffer.get(3));
            short ay = readShortLE(packetBuffer.get(4), packetBuffer.get(5));
            short az = readShortLE(packetBuffer.get(6), packetBuffer.get(7));

            short gx = readShortLE(packetBuffer.get(8), packetBuffer.get(9));
            short gy = readShortLE(packetBuffer.get(10), packetBuffer.get(11));
            short gz = readShortLE(packetBuffer.get(12), packetBuffer.get(13));

            float accelX = (float) (ax / ACCEL_SCALE);
            float accelY = (float) (ay / ACCEL_SCALE);
            float accelZ = (float) (az / ACCEL_SCALE);

            float gyroX = (float) (gx / GYRO_SCALE);
            float gyroY = (float) (gy / GYRO_SCALE);
            float gyroZ = (float) (gz / GYRO_SCALE);

            ImuSample sample = new ImuSample(System.currentTimeMillis(), accelX, accelY, accelZ, gyroX, gyroY, gyroZ);

            if (listener != null) {
                mainHandler.post(() -> listener.onSampleReceived(sample));
            }

            for (int i = 0; i < 14; i++) {
                packetBuffer.remove(0);
            }
        }
    }

    private short readShortLE(byte b1, byte b2) {
        return (short) ((b1 & 0xFF) | ((b2 & 0xFF) << 8));
    }

    private void notifyState(ConnectionState newState, String message) {
        this.state = newState;
        if (listener != null) {
            mainHandler.post(() -> listener.onConnectionStateChanged(newState, message));
        }
    }
}
