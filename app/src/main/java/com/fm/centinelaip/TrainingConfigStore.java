package com.fm.centinelaip;

import android.content.Context;
import android.content.SharedPreferences;

final class TrainingConfigStore {
    private static final String PREFS = "centinela_training";
    private final SharedPreferences preferences;
    private final SecureCredentialStore secrets;

    TrainingConfigStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        secrets = new SecureCredentialStore(context);
    }

    TrainingConfig load() {
        return new TrainingConfig(
                preferences.getString("endpoint", ""),
                secrets.decrypt(preferences.getString("token", "")),
                preferences.getInt("epochs", 100),
                preferences.getBoolean("auto_curation", true),
                preferences.getBoolean("purge_rejected", false),
                preferences.getFloat("accept_at", .75f),
                preferences.getFloat("reject_below", .35f),
                preferences.getString("job_id", ""));
    }

    void save(TrainingConfig value) {
        preferences.edit()
                .putString("endpoint", value.endpoint)
                .putString("token", secrets.encrypt(value.token))
                .putInt("epochs", value.epochs)
                .putBoolean("auto_curation", value.autoCuration)
                .putBoolean("purge_rejected", value.purgeRejected)
                .putFloat("accept_at", value.acceptAt)
                .putFloat("reject_below", value.rejectBelow)
                .putString("job_id", value.jobId)
                .apply();
    }

    void setJobId(String id) {
        preferences.edit().putString("job_id", id == null ? "" : id).apply();
    }
}
