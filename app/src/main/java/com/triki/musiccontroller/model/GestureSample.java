package com.triki.musiccontroller.model;

import java.util.List;

public class GestureSample {
    public MusicAction action;
    public String name;
    public long durationMs;
    public double restAccelX;
    public double restAccelY;
    public double restAccelZ;
    public List<SamplePoint> points;

    public static class SamplePoint {
        public long t;
        public float ax, ay, az;
        public float gx, gy, gz;

        public SamplePoint() {}

        public SamplePoint(long t, float ax, float ay, float az, float gx, float gy, float gz) {
            this.t = t;
            this.ax = ax; this.ay = ay; this.az = az;
            this.gx = gx; this.gy = gy; this.gz = gz;
        }
    }
}
