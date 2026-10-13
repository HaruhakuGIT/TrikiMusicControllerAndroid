package com.triki.musiccontroller.engine;

import android.content.Context;
import android.util.Log;

import com.triki.musiccontroller.model.GestureProfile;
import com.triki.musiccontroller.model.ImuSample;
import com.triki.musiccontroller.model.MusicAction;
import com.triki.musiccontroller.model.RecordedGesture;

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
    private GestureRepository repository;
    private final Map<MusicAction, GestureProfile> profiles = new HashMap<>();
    private int totalRecordingsCount = 0;

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

    // Volume tracking (finger spin around Z)
    private double volumeAccumulator = 0;
    private long lastVolumeStepTime = 0;

    public GestureEngine() {}

    public void setListener(GestureListener listener) {
        this.listener = listener;
    }

    public GestureRepository getRepository() {
        return repository;
    }

    public int getProfileCount() {
        return profiles.size();
    }

    public int getTotalRecordingsCount() {
        return totalRecordingsCount;
    }

    public Double getIdleNoiseMaxAccelG() {
        return idleNoiseMaxAccelG;
    }

    public Double getIdleNoiseMaxGyroDegPerSec() {
        return idleNoiseMaxGyroDegPerSec;
    }

    public boolean isActionTrained(MusicAction action) {
        return profiles.containsKey(action);
    }

    public Map<MusicAction, GestureProfile> getProfiles() {
        return new HashMap<>(profiles);
    }

    public synchronized void trainFromSavedRecordings(Context context) {
        if (repository == null) {
            repository = new GestureRepository(context);
        }

        List<RecordedGesture> recordings = repository.loadAllRecordings();
        totalRecordingsCount = recordings.size();
        profiles.clear();
        idleNoiseMaxAccelG = null;
        idleNoiseMaxGyroDegPerSec = null;

        Log.d(TAG, "[TRAIN] Loaded " + recordings.size() + " gesture recordings from repository");

        if (recordings.isEmpty()) {
            isArmed = false;
            return;
        }

        // 1. Learn Idle Noise Gate
        List<RecordedGesture> idles = new ArrayList<>();
        Map<MusicAction, List<RecordedGesture>> grouped = new HashMap<>();

        for (RecordedGesture r : recordings) {
            MusicAction act = r.getMusicAction();
            if (act == MusicAction.NONE) {
                idles.add(r);
            } else {
                grouped.computeIfAbsent(act, k -> new ArrayList<>()).add(r);
            }
        }

        if (!idles.isEmpty()) {
            double maxIdleAccel = 0;
            double maxIdleGyro = 0;
            for (RecordedGesture o : idles) {
                if (o.peakAccelG > maxIdleAccel) maxIdleAccel = o.peakAccelG;
                if (o.peakGyroDegPerSec > maxIdleGyro) maxIdleGyro = o.peakGyroDegPerSec;
            }
            idleNoiseMaxAccelG = Math.min(1.50, Math.max(1.25, maxIdleAccel * 1.20));
            idleNoiseMaxGyroDegPerSec = Math.min(60.0, Math.max(35.0, maxIdleGyro * 1.20));
            Log.d(TAG, "[TRAIN] Noise gate calibrated: Accel=" + idleNoiseMaxAccelG + "G, Gyro=" + idleNoiseMaxGyroDegPerSec + "°/s");
        }

        // 2. Build Profiles for each trained action
        for (Map.Entry<MusicAction, List<RecordedGesture>> entry : grouped.entrySet()) {
            MusicAction action = entry.getKey();
            List<RecordedGesture> list = entry.getValue();
            if (list.isEmpty()) continue;

            String name = (list.get(0).actionName != null && !list.get(0).actionName.isEmpty())
                    ? list.get(0).actionName : action.name();

            // 2a. Find reference sample (strongest motion in the trained set)
            RecordedGesture refSample = list.get(0);
            for (RecordedGesture r : list) {
                if (r.peakGyroDegPerSec > refSample.peakGyroDegPerSec) {
                    refSample = r;
                }
            }
            double[] refG = refSample.getPeakGyroVector();
            double refGMag = Math.sqrt(refG[0] * refG[0] + refG[1] * refG[1] + refG[2] * refG[2]) + 0.0001;

            // 2b. Filter out rebound / recovery repetitions in opposite direction
            List<RecordedGesture> validSamples = new ArrayList<>();
            for (RecordedGesture r : list) {
                double[] gVec = r.getPeakGyroVector();
                double gMag = Math.sqrt(gVec[0] * gVec[0] + gVec[1] * gVec[1] + gVec[2] * gVec[2]) + 0.0001;
                double cosG = (gVec[0] * refG[0] + gVec[1] * refG[1] + gVec[2] * refG[2]) / (gMag * refGMag);
                if (refGMag >= 70.0 && cosG < 0.0) {
                    Log.d(TAG, "[TRAIN Filter] Filtered out rebound sample for " + action + ": cos=" + cosG);
                    continue;
                }
                validSamples.add(r);
            }
            if (validSamples.isEmpty()) validSamples = list; // Safety fallback

            double sumDirX = 0, sumDirY = 0, sumDirZ = 0;
            double sumGyroX = 0, sumGyroY = 0, sumGyroZ = 0;
            double sumRestX = 0, sumRestY = 0, sumRestZ = 0;
            double minPeakAccel = Double.MAX_VALUE;
            double maxPeakAccel = 0;
            double sumPeakAccel = 0;
            double minPeakGyro = Double.MAX_VALUE;
            double sumPeakGyro = 0;
            double sumDuration = 0;

            for (RecordedGesture r : validSamples) {
                double pAccel = r.peakAccelG > 0 ? r.peakAccelG : 1.5;
                double pGyro = r.peakGyroDegPerSec > 0 ? r.peakGyroDegPerSec : 100.0;
                double dur = r.durationMs > 0 ? r.durationMs : 300.0;

                if (pAccel < minPeakAccel) minPeakAccel = pAccel;
                if (pAccel > maxPeakAccel) maxPeakAccel = pAccel;
                sumPeakAccel += pAccel;

                if (pGyro < minPeakGyro) minPeakGyro = pGyro;
                sumPeakGyro += pGyro;
                sumDuration += dur;

                double[] dir = r.getPeakDirection();
                sumDirX += dir[0]; sumDirY += dir[1]; sumDirZ += dir[2];

                double[] gVec = r.getPeakGyroVector();
                sumGyroX += gVec[0]; sumGyroY += gVec[1]; sumGyroZ += gVec[2];

                double[] rest = r.getRestOrientation();
                sumRestX += rest[0]; sumRestY += rest[1]; sumRestZ += rest[2];
            }

            int count = validSamples.size();
            double dirMag = Math.sqrt(sumDirX * sumDirX + sumDirY * sumDirY + sumDirZ * sumDirZ) + 0.0001;
            double gyroMag = Math.sqrt(sumGyroX * sumGyroX + sumGyroY * sumGyroY + sumGyroZ * sumGyroZ) + 0.0001;
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
            prof.gyroDirX = sumGyroX / gyroMag;
            prof.gyroDirY = sumGyroY / gyroMag;
            prof.gyroDirZ = sumGyroZ / gyroMag;
            prof.restDirX = sumRestX / restMag;
            prof.restDirY = sumRestY / restMag;
            prof.restDirZ = sumRestZ / restMag;

            profiles.put(action, prof);
            Log.d(TAG, "[TRAIN] Loaded " + action + " (" + count + " samples): " +
                    "GyroDir=(" + String.format(java.util.Locale.US, "%.2f, %.2f, %.2f", prof.gyroDirX, prof.gyroDirY, prof.gyroDirZ) +
                    "), AccelDir=(" + String.format(java.util.Locale.US, "%.2f, %.2f, %.2f", prof.dirX, prof.dirY, prof.dirZ) +
                    "), RestDir=(" + String.format(java.util.Locale.US, "%.2f, %.2f, %.2f", prof.restDirX, prof.restDirY, prof.restDirZ) + ")");
        }

        isArmed = true;
    }

    public synchronized boolean deleteRecordingAndRetrain(Context context, String fileName) {
        if (repository == null) {
            repository = new GestureRepository(context);
        }
        boolean deleted = repository.deleteRecording(fileName);
        if (deleted) {
            trainFromSavedRecordings(context);
        }
        return deleted;
    }

    public synchronized void reset() {
        isArmed = true;
        calmStartTime = 0;
        volumeAccumulator = 0;
    }

    public synchronized void updateRestOrientation(ImuSample sample) {
        double ax = sample.accelX;
        double ay = sample.accelY;
        double az = sample.accelZ;
        double accelMag = Math.sqrt(ax * ax + ay * ay + az * az);
        double gyroMag = Math.sqrt(sample.gyroX * sample.gyroX + sample.gyroY * sample.gyroY + sample.gyroZ * sample.gyroZ);
        if (accelMag < 1.35 && gyroMag < 40.0) {
            lastRestX = ax / (accelMag + 0.0001);
            lastRestY = ay / (accelMag + 0.0001);
            lastRestZ = az / (accelMag + 0.0001);
        }
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
                volumeAccumulator = 0;
            }
        }

        // 2. Cooldown & Arming State Machine
        if (!isArmed) {
            long msSinceLast = now - lastTriggerTime;
            if (msSinceLast < COOLDOWN_INTERVAL_MS) {
                volumeAccumulator = 0;
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
                    String.format(java.util.Locale.US, "%.2f, %.2f, %.2f", lastRestX, lastRestY, lastRestZ) + ")");
        } else {
            // While armed and relatively calm before a rapid burst, continually track rest orientation
            if (accelMag < 1.35 && gyroMag < 40.0) {
                lastRestX = ax / (accelMag + 0.0001);
                lastRestY = ay / (accelMag + 0.0001);
                lastRestZ = az / (accelMag + 0.0001);
            }
        }

        // 3. Evaluate trained profiles competitively when armed:
        GestureProfile bestProf = null;
        MusicAction bestAction = null;
        float bestConfidence = 0.0f;
        String matchReason = "";

        // A. PLAY / PAUSE
        GestureProfile playProf = profiles.get(MusicAction.PLAY_PAUSE);
        if (playProf != null) {
            double thresholdG = Math.max(1.65, Math.min(playProf.minPeakAccelG * 0.80, playProf.avgPeakAccelG * 0.65));
            if (accelMag >= thresholdG) {
                double cosImpact = playProf.computeDirectionCosine(ax, ay, az);
                double cosRest = playProf.computeRestOrientationCosine(lastRestX, lastRestY, lastRestZ);
                if (cosImpact >= 0.52 && cosRest >= 0.42) {
                    double deviation = Math.abs(accelMag - playProf.avgPeakAccelG) / (playProf.avgPeakAccelG + 0.1);
                    float conf = (float) Math.min(0.99, Math.max(0.75, (1.0 - deviation * 0.5) * (0.5 + 0.5 * cosImpact)));
                    if (conf > bestConfidence) {
                        bestConfidence = conf;
                        bestProf = playProf;
                        bestAction = MusicAction.PLAY_PAUSE;
                        matchReason = "Accel=" + String.format(java.util.Locale.US, "%.2f", accelMag) + "G" +
                                ", ImpactCos=" + String.format(java.util.Locale.US, "%.2f", cosImpact) +
                                ", RestCos=" + String.format(java.util.Locale.US, "%.2f", cosRest);
                    }
                }
            }
        }

        // B. TRACK NAVIGATION (NEXT_TRACK & PREVIOUS_TRACK) - Competitive & Direction-Preserving
        for (MusicAction candidateAction : new MusicAction[]{MusicAction.NEXT_TRACK, MusicAction.PREVIOUS_TRACK}) {
            GestureProfile prof = profiles.get(candidateAction);
            if (prof == null) continue;

            double cosImpact = prof.computeDirectionCosine(ax, ay, az);
            double cosGyro = prof.computeGyroCosine(sample.gyroX, sample.gyroY, sample.gyroZ);
            double cosRest = prof.computeRestOrientationCosine(lastRestX, lastRestY, lastRestZ);

            // STRICT DIRECTION DISCRIMINATION:
            // If the user's hand is rotating (gyroMag >= 45 deg/s) in the OPPOSITE direction (cosGyro < 0.15),
            // this profile is completely incompatible and must be rejected!
            if (gyroMag >= 45.0 && cosGyro < 0.15) {
                continue;
            }

            // Normal wrist flick thresholds (sensitive enough for natural hand jerks)
            double thresholdGyro = Math.max(55.0, Math.min(prof.minPeakGyro * 0.45, 75.0));
            double thresholdG = Math.max(1.35, Math.min(prof.minPeakAccelG * 0.45, 1.65));

            boolean matchesGyro = (gyroMag >= thresholdGyro && cosGyro >= 0.40 && cosRest >= 0.20);
            boolean matchesDirection = (accelMag >= thresholdG && cosImpact >= 0.45 && cosGyro >= 0.20 && cosRest >= 0.25);

            if (matchesGyro || matchesDirection) {
                float conf = matchesGyro
                        ? (float) Math.min(0.99, Math.max(0.75, 0.70 + 0.29 * cosGyro))
                        : (float) Math.min(0.99, Math.max(0.75, 0.70 + 0.29 * cosImpact));

                if (conf > bestConfidence) {
                    bestConfidence = conf;
                    bestProf = prof;
                    bestAction = candidateAction;
                    matchReason = "GyroCos=" + String.format(java.util.Locale.US, "%.2f", cosGyro) +
                            ", ImpactCos=" + String.format(java.util.Locale.US, "%.2f", cosImpact) +
                            ", RestCos=" + String.format(java.util.Locale.US, "%.2f", cosRest) +
                            ", Gyro=" + (int) gyroMag + "°/s, Accel=" + String.format(java.util.Locale.US, "%.2f", accelMag) + "G";
                }
            }
        }

        if (bestProf != null && bestConfidence >= 0.75f) {
            Log.d(TAG, "[MATCH " + bestAction + "] " + matchReason + " => Confidence=" + (int)(bestConfidence * 100) + "%");
            trigger(bestAction, bestProf.name, bestConfidence);
            return;
        }

        // D. VOLUME UP / DOWN (Finger spin around Z)
        processTrainedVolume(now, sample, accelMag);
    }

    private void processTrainedVolume(long now, ImuSample sample, double accelMag) {
        GestureProfile volUpProf = profiles.get(MusicAction.VOLUME_UP);
        GestureProfile volDownProf = profiles.get(MusicAction.VOLUME_DOWN);
        boolean hasVolUp = volUpProf != null;
        boolean hasVolDown = volDownProf != null;

        if (!hasVolUp && !hasVolDown) return;

        if (accelMag > 1.6) {
            volumeAccumulator = 0;
            return;
        }

        double spinSpeed = sample.gyroZ;
        double minSpeed = Math.min(
                hasVolUp ? volUpProf.minPeakGyro * 0.35 : 1000.0,
                hasVolDown ? volDownProf.minPeakGyro * 0.35 : 1000.0
        );

        if (minSpeed > 500) minSpeed = 25.0;

        if (Math.abs(spinSpeed) >= minSpeed) {
            double dt = 0.02;
            volumeAccumulator += spinSpeed * dt;

            double stepDegrees = 14.0;
            if (Math.abs(volumeAccumulator) >= stepDegrees) {
                long msSinceStep = now - lastVolumeStepTime;
                if (msSinceStep >= 80) {
                    lastVolumeStepTime = now;
                    if (volumeAccumulator > 0 && hasVolUp) {
                        trigger(MusicAction.VOLUME_UP, volUpProf.name, 0.95f);
                        volumeAccumulator -= stepDegrees;
                    } else if (volumeAccumulator < 0 && hasVolDown) {
                        trigger(MusicAction.VOLUME_DOWN, volDownProf.name, 0.95f);
                        volumeAccumulator += stepDegrees;
                    }
                }
            }
        } else {
            volumeAccumulator = 0;
        }
    }

    private void trigger(MusicAction action, String name, float confidence) {
        lastTriggerTime = System.currentTimeMillis();
        isArmed = false;
        calmStartTime = 0;
        volumeAccumulator = 0;

        if (listener != null) {
            listener.onGestureRecognized(action, name, confidence);
        }
    }
}
