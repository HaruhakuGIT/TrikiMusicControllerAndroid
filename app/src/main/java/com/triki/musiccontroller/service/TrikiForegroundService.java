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

    public interface ServiceCallback {
        void onConnectionStateChanged(TrikiBleManager.ConnectionState state, String message);
        void onSampleReceived(ImuSample sample);
        void onButtonStateChanged(boolean isPressed);
        void onGestureRecognized(MusicAction action, String name, float confidence);
        void onBatteryReceived(int percent);
        void onDiscoveredDevice(android.bluetooth.BluetoothDevice device, int rssi);
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
        gestureEngine.loadTrainedGestures(this);
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
                if (isButtonPressed) {
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
        } else {
            if (gestureTriggeredDuringHold) {
                return; // User completed a gesture, release is not a click
            }

            long duration = System.currentTimeMillis() - buttonPressTime;
            if (duration < 450) { // Short tap
                clickCount++;

                if (clickCount == 1) {
                    doubleClickHandler.removeCallbacks(doubleClickTimeoutRunnable);
                    doubleClickHandler.postDelayed(doubleClickTimeoutRunnable, 380);
                } else if (clickCount >= 2) {
                    doubleClickHandler.removeCallbacks(doubleClickTimeoutRunnable);
                    clickCount = 0;

                    // Double click triggered: Play / Pause
                    Log.d(TAG, "Double click detected -> Play/Pause");
                    vibrateFeedback(60);
                    mediaController.playPause();

                    if (serviceCallback != null) {
                        serviceCallback.onGestureRecognized(MusicAction.PLAY_PAUSE, "Double Click", 1.0f);
                    }
                }
            }
        }
    }

    private final Runnable doubleClickTimeoutRunnable = () -> {
        clickCount = 0;
    };

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
