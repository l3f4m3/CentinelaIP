package com.fm.centinelaip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ModelSpec {
    final String id;
    final String name;
    final String fileName;
    final List<String> labels;
    final boolean bundled;
    final long createdAt;

    ModelSpec(String id, String name, String fileName, List<String> labels,
              boolean bundled, long createdAt) {
        this.id = id;
        this.name = name;
        this.fileName = fileName;
        this.labels = Collections.unmodifiableList(new ArrayList<>(labels));
        this.bundled = bundled;
        this.createdAt = createdAt;
    }

    JSONObject toJson() throws Exception {
        JSONObject value = new JSONObject();
        value.put("id", id);
        value.put("name", name);
        value.put("file", fileName);
        value.put("created_at", createdAt);
        JSONArray names = new JSONArray();
        for (String label : labels) names.put(label);
        value.put("labels", names);
        return value;
    }

    static ModelSpec fromJson(JSONObject value) {
        List<String> labels = new ArrayList<>();
        JSONArray names = value.optJSONArray("labels");
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String label = names.optString(i, "").trim();
                if (!label.isEmpty()) labels.add(label);
            }
        }
        return new ModelSpec(
                value.optString("id"),
                value.optString("name", "Modelo importado"),
                value.optString("file", "model.onnx"),
                labels,
                false,
                value.optLong("created_at", System.currentTimeMillis()));
    }
}
