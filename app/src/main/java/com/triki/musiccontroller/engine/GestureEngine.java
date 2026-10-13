package com.triki.musiccontroller.engine;

import android.content.Context;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.triki.musiccontroller.model.GestureProfile;
import com.triki.musiccontroller.model.ImuSample;
import com.triki.musiccontroller.model.MusicAction;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GestureEngine {

    private static final String TAG = "GestureEngine";

    public interface GestureListener {
        void onGestureRecognized(MusicAction action, String name, float confidence);
    }

    private GestureListener listener;
    private final Map<MusicAction, GestureProfile> profiles = new HashMap<>();

    // Noise Gate from PC calibration
    private Double idleNoiseMaxAccelG = null;
    private Double idleNoiseMaxGyroDegPerSec = null;

    // Cooldown & Return-to-Neutral timing (matches C# engine)
    private long lastTriggerTime = 0;
    private static final long COOLDOWN_INTERVAL_MS = 900;
    private static final long REQUIRED_CALM_DURATION_MS = 150;
    private long calmStartTime = 0;
    private boolean isArmed = true;

    // Baseline spatial orientation tracking (gravity vector at rest)
    private double lastRestX = 0;
    private double lastRestY = 0;
    private double lastRestZ = 1.0;

    public GestureEngine() {}

    public void setListener(GestureListener listener) {
        this.listener = listener;
    }

    public int getProfileCount() {
        return profiles.size();
    }

    public void loadTrainedGestures(Context context) {
        try {
            String[] files = context.getAssets().list("gestures");
            if (files == null || files.length == 0) {
                Log.d(TAG, "No gestures found in assets");
                return;
            }

            Map<MusicAction, List<JsonObject>> grouped = new HashMap<>();
            List<JsonObject> idles = new ArrayList<>();

            for (String file : files) {
                if (!file.endsWith(".json")) continue;
                try (InputStream is = context.getAssets().open("gestures/" + file);
                     InputStreamReader reader = new InputStreamReader(is)) {
                    JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                    int targetActionInt = obj.get("TargetAction").getAsInt();
                    MusicAction action;
                    switch (targetActionInt) {
                        case 1: action = MusicAction.PLAY_PAUSE; break;
                        case 2: action = MusicAction.NEXT_TRACK; break;
                        case 3: action = MusicAction.PREVIOUS_TRACK; break;
                        case 4: action = MusicAction.VOLUME_UP; break;
                        case 5: action = MusicAction.VOLUME_DOWN; break;
                        default: action = MusicAction.NONE; break;
                    }

                    if (action == MusicAction.NONE) {
                        idles.add(obj);
                    } else {
                        grouped.computeIfAbsent(action, k -> new ArrayList<>()).add(obj);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed reading gesture file: " + file, e);
                }
            }

            // 1. Learn Idle Noise Gate
            if (!idles.isEmpty()) {
                double maxIdleAccel = 0;
                double maxIdleGyro = 0;
                for (JsonObject o : idles) {
                    double pa = o.has("PeakAccelG") ? o.get("PeakAccelG").getAsDouble() : 1.0;
                    double pg = o.has("PeakGyroDegPerSec") ? o.get("PeakGyroDegPerSec").getAsDouble() : 50.0;
                    if (pa > maxIdleAccel) maxIdleAccel = pa;
                    if (pg > maxIdleGyro) maxIdleGyro = pg;
                }
                idleNoiseMaxAccelG = Math.max(1.25, maxIdleAccel * 1.20);
                idleNoiseMaxGyroDegPerSec = Math.max(70.0, maxIdleGyro * 1.20);
                Log.d(TAG, "[TRAIN] Noise gate calibrated: Accel=" + idleNoiseMaxAccelG + "G, Gyro=" + idleNoiseMaxGyroDegPerSec + "°/s");
            }

            // 2. Build Profiles for each action
            for (Map.Entry<MusicAction, List<JsonObject>> entry : grouped.entrySet()) {
                MusicAction action = entry.getKey();
                List<JsonObject> list = entry.getValue();
                if (list.isEmpty()) continue;

                double sumDirX = 0, sumDirY = 0, sumDirZ = 0;
                double sumRestX = 0, sumRestY = 0, sumRestZ = 0;
                double minPeakAccel = Double.MAX_VALUE;
                double maxPeakAccel = 0;
                double sumPeakAccel = 0;
                double minPeakGyro = Double.MAX_VALUE;
                double sumPeakGyro = 0;
                double sumDuration = 0;
                String name = list.get(0).has("ActionName") ? list.get(0).get("ActionName").getAsString() : action.name();

                for (JsonObject o : list) {
                    double pAccel = o.has("PeakAccelG") ? o.get("PeakAccelG").getAsDouble() : 1.5;
                    double pGyro = o.has("PeakGyroDegPerSec") ? o.get("PeakGyroDegPerSec").getAsDouble() : 100.0;
                    double dur = o.has("DurationMs") ? o.get("DurationMs").getAsDouble() : 300.0;

                    if (pAccel < minPeakAccel) minPeakAccel = pAccel;
                    if (pAccel > maxPeakAccel) maxPeakAccel = pAccel;
                    sumPeakAccel += pAccel;

                    if (pGyro < minPeakGyro) minPeakGyro = pGyro;
                    sumPeakGyro += pGyro;
                    sumDuration += dur;

                    double px = o.has("PeakDirX") ? o.get("PeakDirX").getAsDouble() : 0;
                    double py = o.has("PeakDirY") ? o.get("PeakDirY").getAsDouble() : 0;
                    double pz = o.has("PeakDirZ") ? o.get("PeakDirZ").getAsDouble() : 0;
                    sumDirX += px; sumDirY += py; sumDirZ += pz;

                    double rx = o.has("RestDirX") ? o.get("RestDirX").getAsDouble() : 0;
                    double ry = o.has("RestDirY") ? o.get("RestDirY").getAsDouble() : 0;
                    double rz = o.has("RestDirZ") ? o.get("RestDirZ").getAsDouble() : 1;
                    sumRestX += rx; sumRestY += ry; sumRestZ += rz;
                }

                int count = list.size();
                double dirMag = Math.sqrt(sumDirX * sumDirX + sumDirY * sumDirY + sumDirZ * sumDirZ) + 0.0001;
                double restMag = Math.sqrt(sumRestX * sumRestX + sumRestY * sumRestY + sumRestZ * sumRestZ) + 0.0001;

                GestureProfile prof = new GestureProfile();
                prof.action = action;
                prof.name = name;
                prof.sampleCount = count;
                prof.minPeakAccelG = minPeakAccel;
                prof.maxPeakAccelG = maxPeakAccel;
                prof.avgPeakAccelG = sumPeakAccel / count;
                prof.minPeakGyro = minPeakGyro;
                prof.avgPeakGyro = sumPeakGyro / count;
                prof.avgDurationMs = sumDuration / count;
                prof.dirX = sumDirX / dirMag;
                prof.dirY = sumDirY / dirMag;
                prof.dirZ = sumDirZ / dirMag;
                prof.restDirX = sumRestX / restMag;
                prof.restDirY = sumRestY / restMag;
                prof.restDirZ = sumRestZ / restMag;

                profiles.put(action, prof);
                Log.d(TAG, "[TRAIN] Loaded " + action + ": " + count + " samples. Dir=(" +
                        String.format("%.2f, %.2f, %.2f", prof.dirX, prof.dirY, prof.dirZ) + "), Rest=(" +
                        String.format("%.2f, %.2f, %.2f", prof.restDirX, prof.restDirY, prof.restDirZ) + ")");
            }

            isArmed = true;

        } catch (Exception e) {
            Log.e(TAG, "Error loading trained gestures from assets", e);
        }
    }

    public void reset() {
        isArmed = true;
        calmStartTime = 0;
    }

    public synchronized void processSample(ImuSample sample) {
        if (profiles.isEmpty()) return;

        long now = System.currentTimeMillis();
        double ax = sample.accelX;
        double ay = sample.accelY;
        double az = sample.accelZ;
        double accelMag = Math.sqrt(ax * ax + ay * ay + az * az);
        double gyroMag = Math.sqrt(sample.gyroX * sample.gyroX + sample.gyroY * sample.gyroY + sample.gyroZ * sample.gyroZ);

        // 1. Noise Gate
        if (idleNoiseMaxAccelG != null && idleNoiseMaxGyroDegPerSec != null) {
            if (accelMag <= idleNoiseMaxAccelG && gyroMag <= idleNoiseMaxGyroDegPerSec) {
                // Hand is in idle noise floor
            }
        }

        // 2. Cooldown & Arming State Machine
        if (!isArmed) {
            long msSinceLast = now - lastTriggerTime;
            if (msSinceLast < COOLDOWN_INTERVAL_MS) {
                return;
            }

            boolean isCalm = (accelMag < 1.45 && gyroMag < 60.0);
            if (!isCalm) {
                calmStartTime = 0;
                return;
            }

            if (calmStartTime == 0) {
                calmStartTime = now;
                return;
            }

            if (now - calmStartTime < REQUIRED_CALM_DURATION_MS) {
                return;
            }

            // Arm the engine!
            isArmed = true;
            lastRestX = ax / (accelMag + 0.0001);
            lastRestY = ay / (accelMag + 0.0001);
            lastRestZ = az / (accelMag + 0.0001);
            Log.d(TAG, "[ARM] Armed! Rest orientation: (" +
                    String.format("%.2f, %.2f, %.2f", lastRestX, lastRestY, lastRestZ) + ")");
        } else {
            // While armed and relatively calm before a rapid burst, continually track rest orientation
            if (accelMag < 1.35 && gyroMag < 40.0) {
                lastRestX = ax / (accelMag + 0.0001);
                lastRestY = ay / (accelMag + 0.0001);
                lastRestZ = az / (accelMag + 0.0001);
            }
        }

        // 3. Evaluate ONLY trained profiles when armed:

        // A. PLAY / PAUSE
        GestureProfile playProf = profiles.get(MusicAction.PLAY_PAUSE);
        if (playProf != null) {
            double thresholdG = Math.max(1.65, Math.min(playProf.minPeakAccelG * 0.80, playProf.avgPeakAccelG * 0.65));
            if (accelMag >= thresholdG) {
                double cosImpact = playProf.computeDirectionCosine(ax, ay, az);
                double cosRest = playProf.computeRestOrientationCosine(lastRestX, lastRestY, lastRestZ);
                if (cosImpact >= 0.52 && cosRest >= 0.42) {
                    double deviation = Math.abs(accelMag - playProf.avgPeakAccelG) / (playProf.avgPeakAccelG + 0.1);
                    float confidence = (float) Math.min(0.99, Math.max(0.75, (1.0 - deviation * 0.5) * (0.5 + 0.5 * cosImpact)));
                    Log.d(TAG, "[MATCH PlayPause] Accel=" + accelMag + "G, ImpactCos=" + cosImpact + ", RestCos=" + cosRest);
                    trigger(MusicAction.PLAY_PAUSE, playProf.name, confidence);
                    return;
                }
            }
        }

        // B. NEXT TRACK (Trained 3D Direction, Rest Posture, or Lateral Gyro)
        GestureProfile nextProf = profiles.get(MusicAction.NEXT_TRACK);
        if (nextProf != null) {
            double cosImpact = nextProf.computeDirectionCosine(ax, ay, az);
            double cosRest = nextProf.computeRestOrientationCosine(lastRestX, lastRestY, lastRestZ);

            double lateralRate = sample.gyroY;
            if (Math.abs(sample.gyroX) > Math.abs(lateralRate)) lateralRate = sample.gyroX;

            double thresholdG = Math.max(1.5, Math.min(nextProf.minPeakAccelG * 0.75, nextProf.avgPeakAccelG * 0.65));
            double thresholdGyro = Math.max(70.0, Math.min(nextProf.minPeakGyro * 0.70, nextProf.avgPeakGyro * 0.60));

            boolean matchesDirection = (accelMag >= thresholdG && cosImpact >= 0.50 && cosRest >= 0.40);
            boolean matchesGyro = (nextProf.avgPeakGyro >= 80.0 && lateralRate >= thresholdGyro && cosRest >= 0.35);

            if (matchesDirection || matchesGyro) {
                float confidence = matchesDirection
                        ? (float) Math.min(0.99, Math.max(0.75, 0.70 + 0.30 * cosImpact))
                        : (float) Math.min(0.98, Math.max(0.75, lateralRate / (nextProf.avgPeakGyro + 1.0)));

                Log.d(TAG, "[MATCH NextTrack] Accel=" + accelMag + "G, ImpactCos=" + cosImpact + ", RestCos=" + cosRest + ", Gyro=" + lateralRate);
                trigger(MusicAction.NEXT_TRACK, nextProf.name, confidence);
                return;
            }
        }

        // C. PREVIOUS TRACK (Trained 3D Direction, Rest Posture, or Lateral Gyro)
        GestureProfile prevProf = profiles.get(MusicAction.PREVIOUS_TRACK);
        if (prevProf != null) {
            double cosImpact = prevProf.computeDirectionCosine(ax, ay, az);
            double cosRest = prevProf.computeRestOrientationCosine(lastRestX, lastRestY, lastRestZ);

            double lateralRate = sample.gyroY;
            if (Math.abs(sample.gyroX) > Math.abs(lateralRate)) lateralRate = sample.gyroX;

            double thresholdG = Math.max(1.5, Math.min(prevProf.minPeakAccelG * 0.75, prevProf.avgPeakAccelG * 0.65));
            double thresholdGyro = Math.max(70.0, Math.min(prevProf.minPeakGyro * 0.70, prevProf.avgPeakGyro * 0.60));

            boolean matchesDirection = (accelMag >= thresholdG && cosImpact >= 0.50 && cosRest >= 0.40);
            boolean matchesGyro = (prevProf.avgPeakGyro >= 80.0 && -lateralRate >= thresholdGyro && cosRest >= 0.35);

            if (matchesDirection || matchesGyro) {
                float confidence = matchesDirection
                        ? (float) Math.min(0.99, Math.max(0.75, 0.70 + 0.30 * cosImpact))
                        : (float) Math.min(0.98, Math.max(0.75, -lateralRate / (prevProf.avgPeakGyro + 1.0)));

                Log.d(TAG, "[MATCH PrevTrack] Accel=" + accelMag + "G, ImpactCos=" + cosImpact + ", RestCos=" + cosRest + ", Gyro=" + (-lateralRate));
                trigger(MusicAction.PREVIOUS_TRACK, prevProf.name, confidence);
                return;
            }
        }
    }

    private void trigger(MusicAction action, String name, float confidence) {
        lastTriggerTime = System.currentTimeMillis();
        isArmed = false;
        calmStartTime = 0;

        if (listener != null) {
            listener.onGestureRecognized(action, name, confidence);
        }
    }
}
