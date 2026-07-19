package com.fm.centinelaip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class AnnotationSample {
    final String id;
    final String imageFile;
    final String sourceKey;
    final long timestampUs;
    final long createdAt;
    final List<AnnotationBox> boxes;

    AnnotationSample(String id, String imageFile, String sourceKey, long timestampUs,
                     long createdAt, List<AnnotationBox> boxes) {
        this.id = id;
        this.imageFile = imageFile;
        this.sourceKey = sourceKey;
        this.timestampUs = timestampUs;
        this.createdAt = createdAt;
        this.boxes = new ArrayList<>(boxes);
    }

    JSONObject toJson() throws Exception {
        JSONObject value = new JSONObject();
        value.put("id", id);
        value.put("image", imageFile);
        value.put("source", sourceKey);
        value.put("timestamp_us", timestampUs);
        value.put("created_at", createdAt);
        JSONArray annotations = new JSONArray();
        for (AnnotationBox box : boxes) annotations.put(box.toJson());
        value.put("boxes", annotations);
        return value;
    }

    static AnnotationSample fromJson(JSONObject value) {
        List<AnnotationBox> boxes = new ArrayList<>();
        JSONArray annotations = value.optJSONArray("boxes");
        if (annotations != null) {
            for (int i = 0; i < annotations.length(); i++) {
                JSONObject annotation = annotations.optJSONObject(i);
                if (annotation != null) boxes.add(AnnotationBox.fromJson(annotation));
            }
        }
        return new AnnotationSample(
                value.optString("id"),
                value.optString("image"),
                value.optString("source", "unknown"),
                value.optLong("timestamp_us", -1L),
                value.optLong("created_at", System.currentTimeMillis()),
                boxes);
    }
}
