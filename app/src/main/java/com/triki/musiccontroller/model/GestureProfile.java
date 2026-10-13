package com.triki.musiccontroller.model;

public class GestureProfile {
    public MusicAction action;
    public String name;
    public int sampleCount;
    public double minPeakAccelG;
    public double maxPeakAccelG;
    public double avgPeakAccelG;
    public double minPeakGyro;
    public double avgPeakGyro;
    public double avgDurationMs;

    // Normalized Direction Vector of Peak Impact/Motion (Linear Acceleration)
    public double dirX, dirY, dirZ;
    // Normalized Direction Vector of Peak Angular Velocity (Gyroscope Rotation)
    public double gyroDirX, gyroDirY, gyroDirZ;
    // Normalized Rest Orientation Vector of Triki in hand
    public double restDirX, restDirY, restDirZ;

    public double computeDirectionCosine(double ax, double ay, double az) {
        double mag = Math.sqrt(ax * ax + ay * ay + az * az);
        double dirMag = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (mag < 0.001 || dirMag < 0.001) return 0.0;
        return (ax * dirX + ay * dirY + az * dirZ) / (mag * dirMag);
    }

    public double computeGyroCosine(double gx, double gy, double gz) {
        double mag = Math.sqrt(gx * gx + gy * gy + gz * gz);
        double gDirMag = Math.sqrt(gyroDirX * gyroDirX + gyroDirY * gyroDirY + gyroDirZ * gyroDirZ);
        if (mag < 0.001 || gDirMag < 0.001) return 0.0;
        return (gx * gyroDirX + gy * gyroDirY + gz * gyroDirZ) / (mag * gDirMag);
    }

    public double computeRestOrientationCosine(double rx, double ry, double rz) {
        double rMag = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double baseMag = Math.sqrt(restDirX * restDirX + restDirY * restDirY + restDirZ * restDirZ);
        if (rMag < 0.001 || baseMag < 0.001) return 1.0;
        return (rx * restDirX + ry * restDirY + rz * restDirZ) / (rMag * baseMag);
    }
}
