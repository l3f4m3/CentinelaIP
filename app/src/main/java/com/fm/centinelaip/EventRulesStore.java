package com.fm.centinelaip;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class EventRulesStore {
    private static final String PREFS = "centinela_event_rules";
    private static final String DETECT = "detect_labels";
    private static final String SAVE = "save_labels";
    private static final String NOTIFY = "notify_labels";
    private final Context context;

    EventRulesStore(Context context) {
        this.context = context.getApplicationContext();
    }

    Set<String> saveLabels() {
        return read(SAVE, Collections.singleton("persona"));
    }

    Set<String> detectLabels(Set<String> defaults) {
        android.content.SharedPreferences preferences = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        if (!preferences.contains(DETECT)) return new HashSet<>(defaults);
        return read(DETECT, Collections.emptySet());
    }

    Set<String> notifyLabels() {
        return read(NOTIFY, Collections.singleton("persona"));
    }

    void save(Set<String> detect, Set<String> save, Set<String> notify) {
        Set<String> allowedSave = new HashSet<>(save);
        allowedSave.retainAll(detect);
        Set<String> allowedNotify = new HashSet<>(notify);
        allowedNotify.retainAll(allowedSave);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(DETECT, new HashSet<>(detect))
                .putStringSet(SAVE, allowedSave)
                .putStringSet(NOTIFY, allowedNotify)
                .apply();
    }

    List<Detection> filterForDisplay(List<Detection> detections) {
        android.content.SharedPreferences preferences = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        if (!preferences.contains(DETECT)) return new ArrayList<>(detections);
        Set<String> selected = read(DETECT, Collections.emptySet());
        List<Detection> result = new ArrayList<>();
        for (Detection detection : detections) {
            if (selected.contains(detection.label)) result.add(detection);
        }
        return result;
    }

    List<Detection> filterForEvent(List<Detection> detections) {
        Set<String> selected = saveLabels();
        List<Detection> result = new ArrayList<>();
        for (Detection detection : detections) {
            if (selected.contains(detection.label)) result.add(detection);
        }
        return result;
    }

    boolean shouldNotify(List<Detection> detections) {
        Set<String> selected = notifyLabels();
        for (Detection detection : detections) {
            if (selected.contains(detection.label)) return true;
        }
        return false;
    }

    private Set<String> read(String key, Set<String> defaults) {
        Set<String> value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(key, null);
        return value == null ? new HashSet<>(defaults) : new HashSet<>(value);
    }
}
