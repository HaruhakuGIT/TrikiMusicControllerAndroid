package com.triki.musiccontroller.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.triki.musiccontroller.MainActivity;
import com.triki.musiccontroller.R;
import com.triki.musiccontroller.ble.TrikiBleManager;
import com.triki.musiccontroller.engine.GestureEngine;
import com.triki.musiccontroller.media.MediaController;
import com.triki.musiccontroller.model.ImuSample;
import com.triki.musiccontroller.model.MusicAction;

public class TrikiForegroundService extends Service {

    private static final String TAG = "TrikiForegroundService";
    private static final String CHANNEL_ID = "triki_foreground_channel_v6";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_TOGGLE_CONNECT = "com.triki.musiccontroller.ACTION_TOGGLE_CONNECT";
    public static final String ACTION_STOP_SERVICE = "com.triki.musiccontroller.ACTION_STOP_SERVICE";

    private final IBinder binder = new LocalBinder();
    private TrikiBleManager bleManager;
    private GestureEngine gestureEngine;
    private MediaController mediaController;
    private Vibrator vibrator;

    private boolean isButtonPressed = false;
    private long buttonPressTime = 0;
    private int clickCount = 0;
    private final Handler doubleClickHandler = new Handler(Looper.getMainLooper());
    private boolean gestureTriggeredDuringHold = false;

    // VOLUME MODE STATE (Triple Click Activated)
    private boolean isVolumeModeActive = false;
    private long volumeModeStartTime = 0;
    private long stillStartTime = 0;
    private long lastVolumeStepTime = 0;
    private double gravX = 0, gravY = 0, gravZ = 1.0;
    private boolean awaitingNeutralReturn = false;
    private ImuSample lastKnownSample = null;

    public interface ServiceCallback {
        void onConnectionStateChanged(TrikiBleManager.ConnectionState state, String message);
        void onSampleReceived(ImuSample sample);
        void onButtonStateChanged(boolean isPressed);
        void onGestureRecognized(MusicAction action, String name, float confidence);
        void onBatteryReceived(int percent);
        void onDiscoveredDevice(android.bluetooth.BluetoothDevice device, int rssi);
        void onVolumeModeStateChanged(boolean isActive, String message);
        void onVolumeStepped(boolean isUp);
    }

    private ServiceCallback serviceCallback;

    public class LocalBinder extends Binder {
        public TrikiForegroundService getService() {
            return TrikiForegroundService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        mediaController = new MediaController(this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        gestureEngine = new GestureEngine();
        gestureEngine.trainFromSavedRecordings(this);
        bleManager = new TrikiBleManager(this);

        gestureEngine.setListener((action, name, confidence) -> {
            gestureTriggeredDuringHold = true;
            handleGestureAction(action, name, confidence);
        });

        bleManager.setListener(new TrikiBleManager.BleListener() {
            @Override
            public void onConnectionStateChanged(TrikiBleManager.ConnectionState state, String message) {
                updateNotification(state);
                if (serviceCallback != null) {
                    serviceCallback.onConnectionStateChanged(state, message);
                }
            }

            @Override
            public void onSampleReceived(ImuSample sample) {
                lastKnownSample = sample;

                if (isVolumeModeActive) {
                    processVolumeMotion(sample);
                } else if (isButtonPressed) {
                    gestureEngine.processSample(sample);
                }

                if (serviceCallback != null) {
                    serviceCallback.onSampleReceived(sample);
                }
            }

            @Override
            public void onButtonStateChanged(boolean isPressed) {
                handleButtonState(isPressed);
                if (serviceCallback != null) {
                    serviceCallback.onButtonStateChanged(isPressed);
                }
            }

            @Override
            public void onBatteryReceived(int percent) {
                if (serviceCallback != null) {
                    serviceCallback.onBatteryReceived(percent);
                }
            }

            @Override
            public void onDiscoveredDevice(android.bluetooth.BluetoothDevice device, int rssi) {
                if (serviceCallback != null) {
                    serviceCallback.onDiscoveredDevice(device, rssi);
                }
            }
        });
    }

    private void handleButtonState(boolean isPressed) {
        this.isButtonPressed = isPressed;

        if (isPressed) {
            buttonPressTime = System.currentTimeMillis();
            gestureTriggeredDuringHold = false;
            gestureEngine.reset();

            // If Volume Mode is active, pressing the button confirms and exits immediately!
            if (isVolumeModeActive) {
                confirmAndExitVolumeMode(false); // Confirmed by physical button
                clickCount = 0;
                return;
            }
        } else {
            if (gestureTriggeredDuringHold) {
                return; // User completed a gesture, release is not a click
            }

            if (isVolumeModeActive) {
                return;
            }

            long duration = System.currentTimeMillis() - buttonPressTime;
            if (duration < 450) { // Short tap
                clickCount++;

                if (clickCount == 1) {
                    doubleClickHandler.removeCallbacks(doubleClickTimeoutRunnable);
                    doubleClickHandler.postDelayed(doubleClickTimeoutRunnable, 350);
                } else if (clickCount == 2) {
                    doubleClickHandler.removeCallbacks(doubleClickTimeoutRunnable);
                    doubleClickHandler.postDelayed(doubleClickTimeoutRunnable, 300);
                } else if (clickCount >= 3) {
                    doubleClickHandler.removeCallbacks(doubleClickTimeoutRunnable);
                    clickCount = 0;

                    // TRIPLE CLICK TRIGGERED: ENTER VOLUME CONTROL MODE!
                    Log.d(TAG, "Triple click detected -> Enter Volume Control Mode");
                    enterVolumeMode();
                }
            }
        }
    }

    private final Runnable doubleClickTimeoutRunnable = () -> {
        if (clickCount == 2) {
            // Double click confirmed -> Play / Pause
            Log.d(TAG, "Double click confirmed -> Play/Pause");
            vibrateFeedback(60);
            mediaController.playPause();

            if (serviceCallback != null) {
                serviceCallback.onGestureRecognized(MusicAction.PLAY_PAUSE, "Double Click", 1.0f);
            }
        }
        clickCount = 0;
    };

    private void enterVolumeMode() {
        isVolumeModeActive = true;
        long now = System.currentTimeMillis();
        volumeModeStartTime = now;
        stillStartTime = now;
        lastVolumeStepTime = now;
        awaitingNeutralReturn = false;
        vibrateDoubleTick();

        if (lastKnownSample != null) {
            gravX = lastKnownSample.accelX;
            gravY = lastKnownSample.accelY;
            gravZ = lastKnownSample.accelZ;
        } else {
            gravX = 0; gravY = 0; gravZ = 1.0;
        }

        Log.d(TAG, "Entered Volume Mode! Settling for 500ms to ignore button click release artifacts.");

        if (serviceCallback != null) {
            serviceCallback.onVolumeModeStateChanged(true, getString(R.string.volume_mode_hint));
        }
    }

    private void confirmAndExitVolumeMode(boolean isAuto) {
        if (!isVolumeModeActive) return;
        isVolumeModeActive = false;
        vibrateFeedback(70);

        Log.d(TAG, "Exited Volume Mode (auto=" + isAuto + ")");
        if (serviceCallback != null) {
            serviceCallback.onVolumeModeStateChanged(false, isAuto ? getString(R.string.volume_mode_auto_confirmed) : getString(R.string.volume_mode_confirmed));
        }
    }

    private void processVolumeMotion(ImuSample sample) {
        long now = System.currentTimeMillis();

        // 1. Settling window: ignore first 500ms after entering mode so button release motion doesn't falsely trigger volume down/up
        if (now - volumeModeStartTime < 500) {
            gravX = gravX * 0.90 + sample.accelX * 0.10;
            gravY = gravY * 0.90 + sample.accelY * 0.10;
            gravZ = gravZ * 0.90 + sample.accelZ * 0.10;
            stillStartTime = now;
            return;
        }

        // 2. Dynamic gravity estimation (slow complementary filter tracking natural hand posture changes)
        gravX = gravX * 0.96 + sample.accelX * 0.04;
        gravY = gravY * 0.96 + sample.accelY * 0.04;
        gravZ = gravZ * 0.96 + sample.accelZ * 0.04;

        double gravMag = Math.sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ);
        if (gravMag < 0.01) gravMag = 1.0;

        double ugX = gravX / gravMag;
        double ugY = gravY / gravMag;
        double ugZ = gravZ / gravMag;

        // Acceleration along current vertical gravity axis
        double totalGAlongGravity = (sample.accelX * ugX + sample.accelY * ugY + sample.accelZ * ugZ);
        double rawDelta = totalGAlongGravity - gravMag;

        // Strict noise gate / deadband: ignore all micro-movements, trembling, and twitches less than 0.12G
        double verticalDelta = (Math.abs(rawDelta) < 0.12) ? 0.0 : rawDelta;
        double gMag = sample.getGyroMagnitude();

        // 3. Stillness check: hand is still when vertical deviation is 0.0 (below noise gate) and angular rate is modest (< 75 deg/s)
        boolean isStill = (verticalDelta == 0.0 && gMag < 75.0);

        if (isStill) {
            long stillDuration = now - stillStartTime;
            if (stillDuration >= 1000) { // 1.0 second of stillness -> Auto-confirm!
                confirmAndExitVolumeMode(true);
                return;
            }
        } else {
            stillStartTime = now;
        }

        // 4. Hysteresis / neutral return deadband
        if (awaitingNeutralReturn) {
            if (verticalDelta == 0.0 || (now - lastVolumeStepTime >= 600)) {
                awaitingNeutralReturn = false;
            }
        }

        // 5. Deliberate vertical motion detection:
        // Strong, intentional movement required:
        // Upward (Louder): verticalDelta >= +0.32G (or >= +0.20G with intentional rotation >= 110 deg/s)
        // Downward (Quieter): verticalDelta <= -0.32G (or <= -0.20G with intentional rotation >= 110 deg/s)
        boolean isMovingUp = (verticalDelta >= 0.32 || (verticalDelta >= 0.20 && gMag >= 110.0));
        boolean isMovingDown = (verticalDelta <= -0.32 || (verticalDelta <= -0.20 && gMag >= 110.0));

        if (!awaitingNeutralReturn && (now - lastVolumeStepTime >= 450)) {
            if (isMovingUp) {
                lastVolumeStepTime = now;
                stillStartTime = now;
                awaitingNeutralReturn = true;
                mediaController.volumeUp();
                vibrateFeedback(30);
                Log.d(TAG, "Volume UP! verticalDelta=" + String.format(java.util.Locale.US, "%.2f", verticalDelta));
                if (serviceCallback != null) {
                    serviceCallback.onVolumeStepped(true);
                }
            } else if (isMovingDown) {
                lastVolumeStepTime = now;
                stillStartTime = now;
                awaitingNeutralReturn = true;
                mediaController.volumeDown();
                vibrateFeedback(30);
                Log.d(TAG, "Volume DOWN! verticalDelta=" + String.format(java.util.Locale.US, "%.2f", verticalDelta));
                if (serviceCallback != null) {
                    serviceCallback.onVolumeStepped(false);
                }
            }
        }
    }

    private void vibrateDoubleTick() {
        try {
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 45, 60, 45}, -1));
                } else {
                    vibrator.vibrate(new long[]{0, 45, 60, 45}, -1);
                }
            }
        } catch (Exception ignored) {}
    }

    private void handleGestureAction(MusicAction action, String name, float confidence) {
        vibrateFeedback(40);
        switch (action) {
            case PLAY_PAUSE:
                mediaController.playPause();
                break;
            case NEXT_TRACK:
                mediaController.nextTrack();
                break;
            case PREVIOUS_TRACK:
                mediaController.previousTrack();
                break;
            case VOLUME_UP:
                mediaController.volumeUp();
                break;
            case VOLUME_DOWN:
                mediaController.volumeDown();
                break;
        }

        if (serviceCallback != null) {
            serviceCallback.onGestureRecognized(action, name, confidence);
        }
    }

    private void vibrateFeedback(long ms) {
        try {
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(ms);
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            if (ACTION_TOGGLE_CONNECT.equals(intent.getAction())) {
                toggleConnect();
            } else if (ACTION_STOP_SERVICE.equals(intent.getAction())) {
                bleManager.disconnect();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        startForegroundWithNotification();
        return START_STICKY;
    }

    public void setCallback(ServiceCallback callback) {
        this.serviceCallback = callback;
    }

    public TrikiBleManager getBleManager() {
        return bleManager;
    }

    public GestureEngine getGestureEngine() {
        return gestureEngine;
    }

    public void toggleConnect() {
        if (bleManager.getState() == TrikiBleManager.ConnectionState.CONNECTED ||
            bleManager.getState() == TrikiBleManager.ConnectionState.CONNECTING) {
            bleManager.disconnect();
        } else {
            bleManager.startScanAndConnect();
        }
    }

    private void startForegroundWithNotification() {
        Notification notification = buildNotification(bleManager.getState());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(TrikiBleManager.ConnectionState state) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(state));
        }
    }


    private Notification buildNotification(TrikiBleManager.ConnectionState state) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String statusStr;
        switch (state) {
            case CONNECTED: statusStr = getString(R.string.status_connected); break;
            case CONNECTING: statusStr = getString(R.string.status_connecting); break;
            case SCANNING: statusStr = getString(R.string.status_scanning); break;
            default: statusStr = getString(R.string.status_disconnected); break;
        }

        android.graphics.Bitmap largeIcon = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.ic_triki_cap);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(statusStr)
                .setSmallIcon(R.drawable.ic_stat_triki)
                .setLargeIcon(largeIcon)
                .setColor(0xFF00E5FF)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                try {
                    nm.deleteNotificationChannel("triki_foreground_channel");
                    nm.deleteNotificationChannel("triki_foreground_channel_v2");
                    nm.deleteNotificationChannel("triki_foreground_channel_v3");
                    nm.deleteNotificationChannel("triki_foreground_channel_v4");
                    nm.deleteNotificationChannel("triki_foreground_channel_v5");
                } catch (Exception ignored) {}

                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                channel.setDescription(getString(R.string.notification_channel_desc));
                channel.setShowBadge(true);
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bleManager != null) {
            bleManager.disconnect();
        }
    }
}
