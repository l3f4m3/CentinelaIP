package com.fm.centinelaip;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Catálogo persistente de fuentes. Las credenciales se cifran antes de entrar al JSON. */
final class SourceStore {
    static final int MAX_SOURCES = 6;
    private static final String PREFS = "centinela_ip_sources_v1";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_MIGRATED = "migrated_v03";

    private final Context context;
    private final SharedPreferences preferences;
    private final SecureCredentialStore secureStore;

    SourceStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        secureStore = new SecureCredentialStore(this.context);
    }

    synchronized List<CameraConfig> loadAll() {
        migrateLegacyIfNeeded();
        ArrayList<CameraConfig> sources = new ArrayList<>();
        String raw = preferences.getString(KEY_SOURCES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                sources.add(fromJson(item));
            }
        } catch (Exception ignored) { }
        return sources;
    }

    synchronized CameraConfig find(String id) {
        for (CameraConfig source : loadAll()) {
            if (source.id.equals(id)) return source;
        }
        return null;
    }

    synchronized void upsert(CameraConfig source) {
        List<CameraConfig> sources = loadAll();
        int existing = -1;
        for (int index = 0; index < sources.size(); index++) {
            if (sources.get(index).id.equals(source.id)) {
                existing = index;
                break;
            }
        }
        if (existing >= 0) sources.set(existing, source);
        else {
            if (sources.size() >= MAX_SOURCES) {
                throw new IllegalStateException("Puedes mantener hasta " + MAX_SOURCES + " fuentes simultáneas");
            }
            sources.add(source);
        }
        saveAll(sources);
    }

    synchronized void delete(String id) {
        List<CameraConfig> sources = loadAll();
        sources.removeIf(source -> source.id.equals(id));
        saveAll(sources);
    }

    synchronized void setEnabled(String id, boolean enabled) {
        List<CameraConfig> sources = loadAll();
        for (int index = 0; index < sources.size(); index++) {
            CameraConfig source = sources.get(index);
            if (source.id.equals(id)) sources.set(index, source.withEnabled(enabled));
        }
        saveAll(sources);
    }

    synchronized boolean hasOtherPhoneSource(String editingId) {
        for (CameraConfig source : loadAll()) {
            if (source.usesPhoneCamera() && !source.id.equals(editingId)) return true;
        }
        return false;
    }

    synchronized void saveAll(List<CameraConfig> sources) {
        JSONArray array = new JSONArray();
        for (CameraConfig source : sources) array.put(toJson(source));
        preferences.edit().putString(KEY_SOURCES, array.toString()).apply();
    }

    private JSONObject toJson(CameraConfig source) {
        JSONObject item = new JSONObject();
        try {
            item.put("id", source.id);
            item.put("name", source.name);
            item.put("type", source.sourceType);
            item.put("url", source.rtspUrl);
            item.put("uuid", source.uuid);
            item.put("ssid", source.expectedSsid);
            item.put("user", secureStore.encrypt(source.username));
            item.put("password", secureStore.encrypt(source.password));
            item.put("tcp", source.forceTcp);
            item.put("confidence", source.confidence);
            item.put("alerts", source.personAlerts);
            item.put("front", source.frontCamera);
            item.put("enabled", source.enabled);
            item.put("detect", source.detectEnabled);
        } catch (Exception error) {
            throw new IllegalStateException("No fue posible guardar la fuente", error);
        }
        return item;
    }

    private CameraConfig fromJson(JSONObject item) {
        return new CameraConfig(
                item.optString("id"),
                item.optString("name", "Fuente de video"),
                item.optString("type", CameraConfig.SOURCE_RTSP),
                item.optString("url"),
                item.optString("uuid"),
                item.optString("ssid"),
                secureStore.decrypt(item.optString("user")),
                secureStore.decrypt(item.optString("password")),
                item.optBoolean("tcp", true),
                (float) item.optDouble("confidence", 0.45),
                item.optBoolean("alerts", true),
                item.optBoolean("front", false),
                item.optBoolean("enabled", true),
                item.optBoolean("detect", true));
    }

    private void migrateLegacyIfNeeded() {
        if (preferences.getBoolean(KEY_MIGRATED, false)) return;
        SharedPreferences legacy = context.getSharedPreferences(ConfigStore.PREFS_NAME, Context.MODE_PRIVATE);
        String legacyUrl = legacy.getString("rtsp_url", "");
        String legacyType = legacy.getString("source_type", CameraConfig.SOURCE_RTSP);
        if (!legacyUrl.isBlank() || CameraConfig.SOURCE_PHONE.equals(legacyType)) {
            CameraConfig source = new CameraConfig(
                    legacy.getString("name", "Entrada principal"),
                    legacyType,
                    legacyUrl,
                    secureStore.decrypt(legacy.getString(ConfigStore.KEY_USER, "")),
                    secureStore.decrypt(legacy.getString(ConfigStore.KEY_PASSWORD, "")),
                    legacy.getBoolean("force_tcp", true),
                    legacy.getFloat("confidence", 0.45f),
                    legacy.getBoolean("person_alerts", true),
                    legacy.getBoolean("front_camera", false));
            JSONArray array = new JSONArray();
            array.put(toJson(source));
            preferences.edit()
                    .putString(KEY_SOURCES, array.toString())
                    .putBoolean(KEY_MIGRATED, true)
                    .apply();
        } else {
            preferences.edit().putBoolean(KEY_MIGRATED, true).apply();
        }
    }
}
