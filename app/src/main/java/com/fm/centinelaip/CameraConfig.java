package com.fm.centinelaip;

import java.util.UUID;

public final class CameraConfig {
    public static final String SOURCE_RTSP = "rtsp";
    public static final String SOURCE_PHONE = "phone";
    public static final String SOURCE_HCAM = "hcam";

    public final String id;
    public final String name;
    public final String sourceType;
    public final String rtspUrl;
    public final String uuid;
    public final String expectedSsid;
    public final String username;
    public final String password;
    public final boolean forceTcp;
    public final float confidence;
    public final boolean personAlerts;
    public final boolean frontCamera;
    public final boolean enabled;
    public final boolean detectEnabled;

    public CameraConfig(
            String id,
            String name,
            String sourceType,
            String rtspUrl,
            String uuid,
            String expectedSsid,
            String username,
            String password,
            boolean forceTcp,
            float confidence,
            boolean personAlerts,
            boolean frontCamera,
            boolean enabled,
            boolean detectEnabled) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.name = name == null || name.isBlank() ? "Fuente de video" : name.trim();
        if (SOURCE_PHONE.equals(sourceType)) this.sourceType = SOURCE_PHONE;
        else if (SOURCE_HCAM.equals(sourceType)) this.sourceType = SOURCE_HCAM;
        else this.sourceType = SOURCE_RTSP;
        this.rtspUrl = rtspUrl == null ? "" : rtspUrl.trim();
        this.uuid = normalizeUuid(uuid);
        this.expectedSsid = expectedSsid == null ? "" : expectedSsid.trim();
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.forceTcp = forceTcp;
        this.confidence = Math.max(0.20f, Math.min(confidence, 0.90f));
        this.personAlerts = personAlerts;
        this.frontCamera = frontCamera;
        this.enabled = enabled;
        this.detectEnabled = detectEnabled;
    }

    /** Constructor conservado para migrar la configuración de Centinela 0.3. */
    public CameraConfig(
            String name,
            String sourceType,
            String rtspUrl,
            String username,
            String password,
            boolean forceTcp,
            float confidence,
            boolean personAlerts,
            boolean frontCamera) {
        this(null, name, sourceType, rtspUrl, "", "", username, password,
                forceTcp, confidence, personAlerts, frontCamera, true, true);
    }

    public boolean isConfigured() {
        return usesPhoneCamera() || !rtspUrl.isBlank();
    }

    public boolean usesPhoneCamera() {
        return SOURCE_PHONE.equals(sourceType);
    }

    public boolean usesHcam() {
        return SOURCE_HCAM.equals(sourceType);
    }

    public CameraConfig withEnabled(boolean value) {
        return new CameraConfig(id, name, sourceType, rtspUrl, uuid, expectedSsid,
                username, password, forceTcp, confidence, personAlerts, frontCamera,
                value, detectEnabled);
    }

    public String shortDescription() {
        if (usesPhoneCamera()) return "Teléfono · " + (frontCamera ? "frontal" : "trasera");
        if (usesHcam()) {
            if (!rtspUrl.isBlank()) return "HCAM " + uuid + " · enlace local";
            return "HCAM " + uuid + " · pendiente de detectar";
        }
        return rtspUrl.isBlank() ? "RTSP sin configurar" : rtspUrl;
    }

    static String normalizeUuid(String value) {
        if (value == null) return "";
        return value.trim().replace(" ", "").replace("-", "").toUpperCase();
    }
}
