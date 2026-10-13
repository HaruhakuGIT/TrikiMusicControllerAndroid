package com.triki.musiccontroller.model;

public class ImuSample {
    public final long timestampMs;
    public final float accelX;
    public final float accelY;
    public final float accelZ;
    public final float gyroX;
    public final float gyroY;
    public final float gyroZ;

    public ImuSample(long timestampMs, float accelX, float accelY, float accelZ, float gyroX, float gyroY, float gyroZ) {
        this.timestampMs = timestampMs;
        this.accelX = accelX;
        this.accelY = accelY;
        this.accelZ = accelZ;
        this.gyroX = gyroX;
        this.gyroY = gyroY;
        this.gyroZ = gyroZ;
    }

    public double getAccelMagnitude() {
        return Math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ);
    }

    public double getGyroMagnitude() {
        return Math.sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ);
    }
}
