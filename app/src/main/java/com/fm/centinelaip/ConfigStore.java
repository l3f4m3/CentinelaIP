package com.fm.centinelaip;

import android.content.Context;
import android.content.SharedPreferences;

final class ConfigStore {
    static final String PREFS_NAME = "centinela_ip_config";
    static final String KEY_USER = "username_secure";
    static final String KEY_PASSWORD = "password_secure";

    private final SharedPreferences preferences;
    private final SecureCredentialStore secureStore;

    ConfigStore(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        secureStore = new SecureCredentialStore(context);
    }

    CameraConfig load() {
        return new CameraConfig(
                preferences.getString("name", "Entrada principal"),
                preferences.getString("source_type", CameraConfig.SOURCE_RTSP),
                preferences.getString("rtsp_url", ""),
                secureStore.decrypt(preferences.getString(KEY_USER, "")),
                secureStore.decrypt(preferences.getString(KEY_PASSWORD, "")),
                preferences.getBoolean("force_tcp", true),
                preferences.getFloat("confidence", 0.45f),
                preferences.getBoolean("person_alerts", true),
                preferences.getBoolean("front_camera", false));
    }

    void save(CameraConfig config) {
        preferences.edit()
                .putString("name", config.name)
                .putString("source_type", config.sourceType)
                .putString("rtsp_url", config.rtspUrl)
                .putString(KEY_USER, secureStore.encrypt(config.username))
                .putString(KEY_PASSWORD, secureStore.encrypt(config.password))
                .putBoolean("force_tcp", config.forceTcp)
                .putFloat("confidence", config.confidence)
                .putBoolean("person_alerts", config.personAlerts)
                .putBoolean("front_camera", config.frontCamera)
                .apply();
    }
}
