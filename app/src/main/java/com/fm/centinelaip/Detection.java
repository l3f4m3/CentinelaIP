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

    Detection(float left, float top, float right, float bottom,
              float confidence, int classId, String label, int color) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.confidence = confidence;
        this.classId = classId;
        this.label = label;
        this.color = color;
    }
}
