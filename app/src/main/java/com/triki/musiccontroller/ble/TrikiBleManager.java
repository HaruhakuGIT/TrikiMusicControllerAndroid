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
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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

    public TrikiBleManager(Context context) {
        this.context = context.getApplicationContext();
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
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

    public synchronized void startScanAndConnect() {
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
            disconnect();
            return;
        }

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

    public synchronized void disconnect() {
        stopScan();
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
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

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();

            if (name != null && (name.contains("Triki") || name.contains("triki") || name.contains("Velto"))) {
                Log.d(TAG, "Found target device: " + name + " [" + device.getAddress() + "]");
                stopScan();
                connectToDevice(device);
            }
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        notifyState(ConnectionState.CONNECTING, "Connecting to " + device.getName() + "...");
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

        // FrameParser logic (same as PC FrameParser):
        // Each Triki packet starts with 0x22 and has length 14 bytes
        final int FRAME_LENGTH = 14;

        while (true) {
            int headerIndex = -1;
            for (int i = 0; i < packetBuffer.size() - 1; i++) {
                if (packetBuffer.get(i) == (byte) 0x22) {
                    headerIndex = i;
                    break;
                }
            }

            if (headerIndex < 0) {
                if (!packetBuffer.isEmpty()) {
                    boolean keepTrailing = packetBuffer.get(packetBuffer.size() - 1) == (byte) 0x22;
                    packetBuffer.clear();
                    if (keepTrailing) packetBuffer.add((byte) 0x22);
                }
                break;
            }

            if (headerIndex > 0) {
                packetBuffer.subList(0, headerIndex).clear();
            }

            if (packetBuffer.size() < FRAME_LENGTH) {
                break;
            }

            byte[] frame = new byte[FRAME_LENGTH];
            for (int i = 0; i < FRAME_LENGTH; i++) {
                frame[i] = packetBuffer.get(i);
            }
            packetBuffer.subList(0, FRAME_LENGTH).clear();

            // Byte 1: Button Status (0 = released, 1 = pressed)
            boolean isPressed = frame[1] != 0;
            if (isPressed != lastButtonState) {
                lastButtonState = isPressed;
                if (listener != null) {
                    final boolean pressed = isPressed;
                    mainHandler.post(() -> listener.onButtonStateChanged(pressed));
                }
            }

            // Bytes 2..13: Int16 Little Endian (GyroX, GyroY, GyroZ, AccelX, AccelY, AccelZ)
            ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
            short rawGx = buf.getShort(2);
            short rawGy = buf.getShort(4);
            short rawGz = buf.getShort(6);
            short rawAx = buf.getShort(8);
            short rawAy = buf.getShort(10);
            short rawAz = buf.getShort(12);

            float gx = (float) (rawGx / GYRO_SCALE);
            float gy = (float) (rawGy / GYRO_SCALE);
            float gz = (float) (rawGz / GYRO_SCALE);
            float ax = (float) (rawAx / ACCEL_SCALE);
            float ay = (float) (rawAy / ACCEL_SCALE);
            float az = (float) (rawAz / ACCEL_SCALE);

            ImuSample sample = new ImuSample(System.currentTimeMillis(), ax, ay, az, gx, gy, gz);
            if (listener != null) {
                mainHandler.post(() -> listener.onSampleReceived(sample));
            }
        }
    }

    private void notifyState(ConnectionState newState, String message) {
        this.state = newState;
        if (listener != null) {
            mainHandler.post(() -> listener.onConnectionStateChanged(newState, message));
        }
    }
}
