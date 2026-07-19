package com.fm.centinelaip;

import org.json.JSONObject;

final class AnnotationBox {
    static final String MANUAL = "manual";
    static final String AUTO = "auto";
    static final String ACCEPTED = "accepted";
    static final String REVIEW = "review";
    static final String REJECTED = "rejected";

    float left;
    float top;
    float right;
    float bottom;
    String label;
    float confidence;
    String origin;
    String state;

    AnnotationBox(float left, float top, float right, float bottom, String label,
                  float confidence, String origin, String state) {
        this.left = clamp(Math.min(left, right));
        this.top = clamp(Math.min(top, bottom));
        this.right = clamp(Math.max(left, right));
        this.bottom = clamp(Math.max(top, bottom));
        this.label = label == null ? "objeto" : label.trim();
        this.confidence = Math.max(0f, Math.min(1f, confidence));
        this.origin = AUTO.equals(origin) ? AUTO : MANUAL;
        this.state = REJECTED.equals(state) ? REJECTED
                : REVIEW.equals(state) ? REVIEW : ACCEPTED;
    }

    JSONObject toJson() throws Exception {
        JSONObject value = new JSONObject();
        value.put("left", left);
        value.put("top", top);
        value.put("right", right);
        value.put("bottom", bottom);
        value.put("label", label);
        value.put("confidence", confidence);
        value.put("origin", origin);
        value.put("state", state);
        return value;
    }

    static AnnotationBox fromJson(JSONObject value) {
        return new AnnotationBox(
                (float) value.optDouble("left"),
                (float) value.optDouble("top"),
                (float) value.optDouble("right"),
                (float) value.optDouble("bottom"),
                value.optString("label", "objeto"),
                (float) value.optDouble("confidence", 1.0),
                value.optString("origin", MANUAL),
                value.optString("state", ACCEPTED));
    }

    boolean isTrainingReady() {
        return !REJECTED.equals(state) && !REVIEW.equals(state) && !label.isEmpty()
                && right - left >= 0.001f && bottom - top >= 0.001f;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
