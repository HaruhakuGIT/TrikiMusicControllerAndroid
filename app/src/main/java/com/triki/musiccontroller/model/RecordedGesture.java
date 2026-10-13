package com.triki.musiccontroller.model;

import com.google.gson.annotations.SerializedName;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordedGesture {

    @SerializedName("ActionName")
    public String actionName = "";

    @SerializedName("TargetAction")
    public int targetAction; // 0=None, 1=PlayPause, 2=Next, 3=Prev, 4=VolUp, 5=VolDown

    @SerializedName("RecordedAt")
    public String recordedAt = "";

    @SerializedName("DurationMs")
    public double durationMs;

    @SerializedName("PeakAccelG")
    public double peakAccelG;

    @SerializedName("PeakGyroDegPerSec")
    public double peakGyroDegPerSec;

    @SerializedName("PeakGyroX")
    public double peakGyroX;

    @SerializedName("PeakGyroY")
    public double peakGyroY;

    @SerializedName("PeakGyroZ")
    public double peakGyroZ;

    @SerializedName("DominantGyroZ")
    public double dominantGyroZ;

    @SerializedName("DominantGyroY")
    public double dominantGyroY;

    @SerializedName("DominantGyroX")
    public double dominantGyroX;

    @SerializedName("PeakDirX")
    public double peakDirX;

    @SerializedName("PeakDirY")
    public double peakDirY;

    @SerializedName("PeakDirZ")
    public double peakDirZ;

    @SerializedName("RestDirX")
    public double restDirX;

    @SerializedName("RestDirY")
    public double restDirY;

    @SerializedName("RestDirZ")
    public double restDirZ;

    @SerializedName("Points")
    public List<GesturePoint> points = new ArrayList<>();

    // Runtime metadata (not serialized in JSON)
    public transient String fileName;
    public transient String filePath;

    public MusicAction getMusicAction() {
        switch (targetAction) {
            case 1: return MusicAction.PLAY_PAUSE;
            case 2: return MusicAction.NEXT_TRACK;
            case 3: return MusicAction.PREVIOUS_TRACK;
            case 4: return MusicAction.VOLUME_UP;
            case 5: return MusicAction.VOLUME_DOWN;
            default: return MusicAction.NONE;
        }
    }

    public static int toTargetActionInt(MusicAction action) {
        if (action == null) return 0;
        switch (action) {
            case PLAY_PAUSE: return 1;
            case NEXT_TRACK: return 2;
            case PREVIOUS_TRACK: return 3;
            case VOLUME_UP: return 4;
            case VOLUME_DOWN: return 5;
            default: return 0;
        }
    }

    public static RecordedGesture fromSamples(String actionName, MusicAction action, List<ImuSample> samples) {
        RecordedGesture g = new RecordedGesture();
        g.actionName = actionName;
        g.targetAction = toTargetActionInt(action);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        g.recordedAt = sdf.format(new Date());

        if (samples == null || samples.isEmpty()) {
            return g;
        }

        long start = samples.get(0).timestampMs;
        List<GesturePoint> pts = new ArrayList<>(samples.size());
        double maxAccel = 0;
        double maxGyro = 0;
        double pDirX = 0, pDirY = 0, pDirZ = 0;
        double sumGx = 0, sumGy = 0, sumGz = 0;
        double pGx = 0, pGy = 0, pGz = 0;

        int restCount = Math.min(10, Math.max(3, samples.size() / 4));
        double restAx = 0, restAy = 0, restAz = 0;
        for (int i = 0; i < restCount && i < samples.size(); i++) {
            restAx += samples.get(i).accelX;
            restAy += samples.get(i).accelY;
            restAz += samples.get(i).accelZ;
        }
        double restMag = Math.sqrt(restAx * restAx + restAy * restAy + restAz * restAz) + 0.0001;
        g.restDirX = restAx / restMag;
        g.restDirY = restAy / restMag;
        g.restDirZ = restAz / restMag;

        for (ImuSample s : samples) {
            double t = (s.timestampMs - start);
            double aMag = Math.sqrt(s.accelX * s.accelX + s.accelY * s.accelY + s.accelZ * s.accelZ);
            double gMag = Math.sqrt(s.gyroX * s.gyroX + s.gyroY * s.gyroY + s.gyroZ * s.gyroZ);

            if (aMag > maxAccel) {
                maxAccel = aMag;
                pDirX = s.accelX / (aMag + 0.0001);
                pDirY = s.accelY / (aMag + 0.0001);
                pDirZ = s.accelZ / (aMag + 0.0001);
            }

            if (gMag > maxGyro) {
                maxGyro = gMag;
                pGx = s.gyroX;
                pGy = s.gyroY;
                pGz = s.gyroZ;
            }

            sumGx += Math.abs(s.gyroX);
            sumGy += Math.abs(s.gyroY);
            sumGz += Math.abs(s.gyroZ);

            pts.add(new GesturePoint(t, s.gyroX, s.gyroY, s.gyroZ, s.accelX, s.accelY, s.accelZ, aMag, gMag));
        }

        g.durationMs = pts.isEmpty() ? 0 : pts.get(pts.size() - 1).timeMs;
        double totalSum = sumGx + sumGy + sumGz + 0.001;

        g.peakAccelG = maxAccel;
        g.peakGyroDegPerSec = maxGyro;
        g.peakGyroX = pGx;
        g.peakGyroY = pGy;
        g.peakGyroZ = pGz;
        g.dominantGyroX = sumGx / totalSum;
        g.dominantGyroY = sumGy / totalSum;
        g.dominantGyroZ = sumGz / totalSum;
        g.peakDirX = pDirX;
        g.peakDirY = pDirY;
        g.peakDirZ = pDirZ;
        g.points = pts;

        return g;
    }

    public double[] getPeakDirection() {
        if (Math.abs(peakDirX) > 0.001 || Math.abs(peakDirY) > 0.001 || Math.abs(peakDirZ) > 0.001) {
            return new double[]{peakDirX, peakDirY, peakDirZ};
        }
        if (points.isEmpty()) return new double[]{0, 0, 0};

        GesturePoint best = points.get(0);
        for (GesturePoint pt : points) {
            if (pt.totalAccel > best.totalAccel) best = pt;
        }
        double mag = best.totalAccel + 0.0001;
        return new double[]{best.accelX / mag, best.accelY / mag, best.accelZ / mag};
    }

    public double[] getPeakGyroVector() {
        if (Math.abs(peakGyroX) > 0.001 || Math.abs(peakGyroY) > 0.001 || Math.abs(peakGyroZ) > 0.001) {
            return new double[]{peakGyroX, peakGyroY, peakGyroZ};
        }
        if (points == null || points.isEmpty()) return new double[]{0, 0, 0};

        GesturePoint best = points.get(0);
        for (GesturePoint pt : points) {
            if (pt.totalGyro > best.totalGyro) best = pt;
        }
        return new double[]{best.gyroX, best.gyroY, best.gyroZ};
    }

    public double[] getRestOrientation() {
        if (Math.abs(restDirX) > 0.001 || Math.abs(restDirY) > 0.001 || Math.abs(restDirZ) > 0.001) {
            return new double[]{restDirX, restDirY, restDirZ};
        }
        if (points.isEmpty()) return new double[]{0, 0, 1.0};
        GesturePoint p0 = points.get(0);
        double mag = p0.totalAccel + 0.0001;
        return new double[]{p0.accelX / mag, p0.accelY / mag, p0.accelZ / mag};
    }

    public interface MetricSelector {
        double select(ImuSample sample);
    }

    public static List<RecordedGesture> segmentRepetitions(String actionName, MusicAction action, List<ImuSample> session) {
        List<RecordedGesture> result = new ArrayList<>();
        if (session == null || session.size() < 10) return result;

        // If Idle noise session: save whole session
        if (action == MusicAction.NONE) {
            result.add(fromSamples(actionName, action, session));
            return result;
        }

        if (action == MusicAction.PLAY_PAUSE) {
            double sessionMaxG = 0;
            for (ImuSample s : session) {
                double mag = Math.sqrt(s.accelX * s.accelX + s.accelY * s.accelY + s.accelZ * s.accelZ);
                if (mag > sessionMaxG) sessionMaxG = mag;
            }
            double thresholdG = Math.max(1.45, sessionMaxG * 0.65);
            List<Integer> peaks = findPeaks(session, s -> Math.sqrt(s.accelX * s.accelX + s.accelY * s.accelY + s.accelZ * s.accelZ), thresholdG, 15);

            for (int idx : peaks) {
                int start = Math.max(0, idx - 10);
                int end = Math.min(session.size() - 1, idx + 15);
                List<ImuSample> slice = new ArrayList<>();
                for (int i = start; i <= end; i++) slice.add(session.get(i));
                result.add(fromSamples(actionName, action, slice));
            }
        } else if (action == MusicAction.NEXT_TRACK || action == MusicAction.PREVIOUS_TRACK) {
            double maxGyro = 0;
            double maxAccel = 0;
            for (ImuSample s : session) {
                double lat = Math.max(Math.abs(s.gyroX), Math.abs(s.gyroY));
                if (lat > maxGyro) maxGyro = lat;
                double aMag = Math.sqrt(s.accelX * s.accelX + s.accelY * s.accelY + s.accelZ * s.accelZ);
                if (aMag > maxAccel) maxAccel = aMag;
            }

            if (maxGyro >= 75.0) {
                double thresh = Math.max(60.0, maxGyro * 0.60);
                List<Integer> peaks = findPeaks(session, s -> Math.max(Math.abs(s.gyroX), Math.abs(s.gyroY)), thresh, 15);
                for (int idx : peaks) {
                    int start = Math.max(0, idx - 10);
                    int end = Math.min(session.size() - 1, idx + 15);
                    List<ImuSample> slice = new ArrayList<>();
                    for (int i = start; i <= end; i++) slice.add(session.get(i));
                    result.add(fromSamples(actionName, action, slice));
                }
            } else {
                double thresh = Math.max(1.4, maxAccel * 0.65);
                List<Integer> peaks = findPeaks(session, s -> Math.sqrt(s.accelX * s.accelX + s.accelY * s.accelY + s.accelZ * s.accelZ), thresh, 15);
                for (int idx : peaks) {
                    int start = Math.max(0, idx - 10);
                    int end = Math.min(session.size() - 1, idx + 15);
                    List<ImuSample> slice = new ArrayList<>();
                    for (int i = start; i <= end; i++) slice.add(session.get(i));
                    result.add(fromSamples(actionName, action, slice));
                }
            }
        } else { // Volume Up / Volume Down
            double maxZ = 0;
            for (ImuSample s : session) {
                double z = Math.abs(s.gyroZ);
                if (z > maxZ) maxZ = z;
            }
            double thresh = Math.max(30.0, maxZ * 0.55);
            List<Integer> peaks = findPeaks(session, s -> Math.abs(s.gyroZ), thresh, 15);
            for (int idx : peaks) {
                int start = Math.max(0, idx - 12);
                int end = Math.min(session.size() - 1, idx + 15);
                List<ImuSample> slice = new ArrayList<>();
                for (int i = start; i <= end; i++) slice.add(session.get(i));
                result.add(fromSamples(actionName, action, slice));
            }
        }

        if (result.isEmpty()) {
            result.add(fromSamples(actionName, action, session));
        }

        return result;
    }

    private static List<Integer> findPeaks(List<ImuSample> samples, MetricSelector selector, double threshold, int minDistance) {
        List<Integer> peaks = new ArrayList<>();
        int lastPeak = -minDistance;

        for (int i = 1; i < samples.size() - 1; i++) {
            double val = selector.select(samples.get(i));
            double prev = selector.select(samples.get(i - 1));
            double next = selector.select(samples.get(i + 1));

            if (val >= threshold && val >= prev && val >= next && (i - lastPeak) >= minDistance) {
                peaks.add(i);
                lastPeak = i;
            }
        }

        return peaks;
    }
}
