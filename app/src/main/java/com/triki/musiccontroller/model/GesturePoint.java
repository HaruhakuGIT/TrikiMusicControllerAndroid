package com.triki.musiccontroller.model;

import com.google.gson.annotations.SerializedName;

public class GesturePoint {
    @SerializedName("TimeMs")
    public double timeMs;

    @SerializedName("GyroX")
    public double gyroX;

    @SerializedName("GyroY")
    public double gyroY;

    @SerializedName("GyroZ")
    public double gyroZ;

    @SerializedName("AccelX")
    public double accelX;

    @SerializedName("AccelY")
    public double accelY;

    @SerializedName("AccelZ")
    public double accelZ;

    @SerializedName("TotalAccel")
    public double totalAccel;

    @SerializedName("TotalGyro")
    public double totalGyro;

    public GesturePoint() {}

    public GesturePoint(double timeMs, double gx, double gy, double gz, double ax, double ay, double az, double tAccel, double tGyro) {
        this.timeMs = timeMs;
        this.gyroX = gx;
        this.gyroY = gy;
        this.gyroZ = gz;
        this.accelX = ax;
        this.accelY = ay;
        this.accelZ = az;
        this.totalAccel = tAccel;
        this.totalGyro = tGyro;
    }
}
