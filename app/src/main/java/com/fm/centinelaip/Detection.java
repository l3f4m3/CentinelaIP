package com.fm.centinelaip;

public final class Detection {
    public final float left;
    public final float top;
    public final float right;
    public final float bottom;
    public final float confidence;
    public final int classId;
    public final String label;
    public final int color;

    /** -1 indica que la detección todavía no tiene una pista temporal estable. */
    public final int trackId;
    public final float distanceMeters;
    public final float closingSpeedMps;
    public final float ttcSeconds;
    public final RoadObjectTracker.RiskLevel riskLevel;

    Detection(float left, float top, float right, float bottom,
              float confidence, int classId, String label, int color) {
        this(left, top, right, bottom, confidence, classId, label, color,
                -1, Float.NaN, Float.NaN, Float.NaN, RoadObjectTracker.RiskLevel.UNKNOWN);
    }

    Detection(float left, float top, float right, float bottom,
              float confidence, int classId, String label, int color,
              int trackId, float distanceMeters, float closingSpeedMps,
              float ttcSeconds, RoadObjectTracker.RiskLevel riskLevel) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.confidence = confidence;
        this.classId = classId;
        this.label = label;
        this.color = color;
        this.trackId = trackId;
        this.distanceMeters = distanceMeters;
        this.closingSpeedMps = closingSpeedMps;
        this.ttcSeconds = ttcSeconds;
        this.riskLevel = riskLevel == null
                ? RoadObjectTracker.RiskLevel.UNKNOWN : riskLevel;
    }

    Detection withTracking(int id, float distance, float closingSpeed,
                           float ttc, RoadObjectTracker.RiskLevel risk, int riskColor) {
        return new Detection(left, top, right, bottom, confidence, classId, label,
                riskColor, id, distance, closingSpeed, ttc, risk);
    }
}
