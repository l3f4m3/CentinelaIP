package com.fm.centinelaip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tracker ligero por IoU para estabilizar detecciones y producir métricas longitudinales.
 *
 * La distancia es monocular y usa alturas nominales por clase; no sustituye una medición
 * calibrada por estéreo, LiDAR o radar. El resultado siempre debe presentarse como estimado.
 */
final class RoadObjectTracker {
    enum RiskLevel { UNKNOWN, SAFE, ATTENTION, WARNING, CRITICAL }

    static final class Result {
        final List<Detection> detections;
        final Detection primaryTarget;
        final RiskLevel highestRisk;

        Result(List<Detection> detections, Detection primaryTarget, RiskLevel highestRisk) {
            this.detections = detections;
            this.primaryTarget = primaryTarget;
            this.highestRisk = highestRisk;
        }
    }

    private static final float DEFAULT_VERTICAL_FOV_DEGREES = 52f;
    private static final float MATCH_IOU = 0.22f;
    private static final long RESET_GAP_NS = 2_500_000_000L;
    private static final int MAX_MISSED = 4;
    private static final float DISTANCE_ALPHA = 0.32f;
    private static final float SPEED_ALPHA = 0.30f;

    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;
    private long lastTimestampNs;
    private int lastFrameWidth;
    private int lastFrameHeight;

    synchronized Result update(List<Detection> values, int frameWidth, int frameHeight,
                               long timestampNs) {
        List<Detection> detections = values == null ? new ArrayList<>() : values;
        if (shouldReset(frameWidth, frameHeight, timestampNs)) reset();
        lastTimestampNs = timestampNs;
        lastFrameWidth = frameWidth;
        lastFrameHeight = frameHeight;

        List<Detection> ordered = new ArrayList<>(detections);
        ordered.sort(Comparator.comparingDouble((Detection d) -> d.confidence).reversed());
        Set<Integer> usedTracks = new HashSet<>();
        List<Detection> enriched = new ArrayList<>();

        for (Detection detection : ordered) {
            Track track = bestMatch(detection, usedTracks);
            float rawDistance = estimateDistanceMeters(detection, frameHeight);
            if (track == null) {
                track = new Track(nextId++, detection, rawDistance, timestampNs);
                tracks.add(track);
            } else {
                track.update(detection, rawDistance, timestampNs);
            }
            usedTracks.add(track.id);
            enriched.add(track.enrich(detection, frameWidth, frameHeight));
        }

        for (Track track : tracks) {
            if (!usedTracks.contains(track.id)) track.missedFrames++;
        }
        Iterator<Track> iterator = tracks.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().missedFrames > MAX_MISSED) iterator.remove();
        }

        Detection primary = choosePrimary(enriched, frameWidth, frameHeight);
        RiskLevel highest = RiskLevel.UNKNOWN;
        for (Detection detection : enriched) {
            if (severity(detection.riskLevel) > severity(highest)) highest = detection.riskLevel;
        }
        return new Result(enriched, primary, highest);
    }

    synchronized void reset() {
        tracks.clear();
        nextId = 1;
        lastTimestampNs = 0L;
        lastFrameWidth = 0;
        lastFrameHeight = 0;
    }

    private boolean shouldReset(int width, int height, long timestampNs) {
        if (lastTimestampNs == 0L) return false;
        if (timestampNs <= lastTimestampNs || timestampNs - lastTimestampNs > RESET_GAP_NS) return true;
        if (lastFrameWidth <= 0 || lastFrameHeight <= 0 || width <= 0 || height <= 0) return false;
        float previousAspect = (float) lastFrameWidth / lastFrameHeight;
        float currentAspect = (float) width / height;
        return Math.abs(previousAspect - currentAspect) > 0.18f;
    }

    private Track bestMatch(Detection detection, Set<Integer> usedTracks) {
        Track best = null;
        float bestScore = MATCH_IOU;
        for (Track track : tracks) {
            if (usedTracks.contains(track.id) || track.classId != detection.classId) continue;
            float score = iou(track.left, track.top, track.right, track.bottom,
                    detection.left, detection.top, detection.right, detection.bottom);
            if (score > bestScore) {
                bestScore = score;
                best = track;
            }
        }
        return best;
    }

    static float estimateDistanceMeters(Detection detection, int frameHeight) {
        if (detection == null || frameHeight <= 0) return Float.NaN;
        float objectHeight = nominalHeightMeters(detection.label);
        float boxHeight = detection.bottom - detection.top;
        if (!Float.isFinite(objectHeight) || boxHeight < 5f) return Float.NaN;
        double radians = Math.toRadians(DEFAULT_VERTICAL_FOV_DEGREES);
        float focalPixels = (float) (frameHeight / (2d * Math.tan(radians / 2d)));
        float distance = objectHeight * focalPixels / boxHeight;
        if (!Float.isFinite(distance)) return Float.NaN;
        return clamp(distance, 0.8f, 180f);
    }

    private static float nominalHeightMeters(String label) {
        if (label == null) return Float.NaN;
        String value = label.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "persona": return 1.70f;
            case "bicicleta": return 1.10f;
            case "automóvil": return 1.48f;
            case "motocicleta": return 1.22f;
            case "autobús": return 3.15f;
            case "camión": return 3.20f;
            case "perro": return 0.62f;
            case "caballo": return 1.65f;
            case "vaca": return 1.45f;
            case "señal de pare": return 0.75f;
            case "semáforo": return 0.85f;
            default: return Float.NaN;
        }
    }

    private static Detection choosePrimary(List<Detection> detections, int width, int height) {
        Detection best = null;
        float bestScore = Float.POSITIVE_INFINITY;
        for (Detection detection : detections) {
            if (!Float.isFinite(detection.distanceMeters)) continue;
            float centerX = (detection.left + detection.right) * 0.5f;
            float bottom = detection.bottom;
            boolean corridor = centerX >= width * 0.27f && centerX <= width * 0.73f
                    && bottom >= height * 0.30f;
            if (!corridor) continue;
            float score = Float.isFinite(detection.ttcSeconds)
                    ? detection.ttcSeconds : 1000f + detection.distanceMeters;
            if (score < bestScore) {
                bestScore = score;
                best = detection;
            }
        }
        return best;
    }

    private static RiskLevel classify(float distance, float closingSpeed, float ttc,
                                      int stableFrames, boolean corridor) {
        if (!Float.isFinite(distance)) return RiskLevel.UNKNOWN;
        if (!corridor || stableFrames < 2) return RiskLevel.SAFE;
        if ((Float.isFinite(ttc) && ttc <= 2.0f) || distance <= 4.5f) return RiskLevel.CRITICAL;
        if ((Float.isFinite(ttc) && ttc <= 4.0f) || distance <= 10f) return RiskLevel.WARNING;
        if ((Float.isFinite(ttc) && ttc <= 7.0f) || distance <= 22f
                || (Float.isFinite(closingSpeed) && closingSpeed >= 5f)) return RiskLevel.ATTENTION;
        return RiskLevel.SAFE;
    }

    private static int colorFor(RiskLevel risk, int fallback) {
        switch (risk) {
            case CRITICAL: return 0xFFFF334F;
            case WARNING: return 0xFFFF9F1C;
            case ATTENTION: return 0xFFFFD166;
            case SAFE: return 0xFF22D3EE;
            default: return fallback;
        }
    }

    private static int severity(RiskLevel value) {
        switch (value) {
            case CRITICAL: return 4;
            case WARNING: return 3;
            case ATTENTION: return 2;
            case SAFE: return 1;
            default: return 0;
        }
    }

    private static float iou(float aLeft, float aTop, float aRight, float aBottom,
                             float bLeft, float bTop, float bRight, float bBottom) {
        float left = Math.max(aLeft, bLeft);
        float top = Math.max(aTop, bTop);
        float right = Math.min(aRight, bRight);
        float bottom = Math.min(aBottom, bBottom);
        float intersection = Math.max(0f, right - left) * Math.max(0f, bottom - top);
        float areaA = Math.max(0f, aRight - aLeft) * Math.max(0f, aBottom - aTop);
        float areaB = Math.max(0f, bRight - bLeft) * Math.max(0f, bBottom - bTop);
        float union = areaA + areaB - intersection;
        return union <= 0f ? 0f : intersection / union;
    }

    private static float smooth(float previous, float measured, float alpha) {
        if (!Float.isFinite(measured)) return previous;
        if (!Float.isFinite(previous)) return measured;
        return previous + alpha * (measured - previous);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Track {
        final int id;
        final int classId;
        float left;
        float top;
        float right;
        float bottom;
        float distance;
        float closingSpeed = Float.NaN;
        float ttc = Float.NaN;
        long timestampNs;
        int stableFrames = 1;
        int missedFrames;

        Track(int id, Detection detection, float rawDistance, long timestampNs) {
            this.id = id;
            this.classId = detection.classId;
            this.left = detection.left;
            this.top = detection.top;
            this.right = detection.right;
            this.bottom = detection.bottom;
            this.distance = rawDistance;
            this.timestampNs = timestampNs;
        }

        void update(Detection detection, float rawDistance, long nowNs) {
            float previousDistance = distance;
            long previousTimestamp = timestampNs;
            left = detection.left;
            top = detection.top;
            right = detection.right;
            bottom = detection.bottom;
            distance = smooth(distance, rawDistance, DISTANCE_ALPHA);
            timestampNs = nowNs;
            missedFrames = 0;
            stableFrames++;

            float deltaSeconds = (nowNs - previousTimestamp) / 1_000_000_000f;
            if (deltaSeconds >= 0.08f && deltaSeconds <= 2.5f
                    && Float.isFinite(previousDistance) && Float.isFinite(distance)) {
                float measuredClosing = clamp((previousDistance - distance) / deltaSeconds,
                        -35f, 35f);
                closingSpeed = smooth(closingSpeed, measuredClosing, SPEED_ALPHA);
                ttc = closingSpeed > 0.45f ? distance / closingSpeed : Float.NaN;
                if (Float.isFinite(ttc)) ttc = clamp(ttc, 0f, 99f);
            }
        }

        Detection enrich(Detection detection, int frameWidth, int frameHeight) {
            float centerX = (detection.left + detection.right) * 0.5f;
            boolean corridor = centerX >= frameWidth * 0.27f && centerX <= frameWidth * 0.73f
                    && detection.bottom >= frameHeight * 0.30f;
            RiskLevel risk = classify(distance, closingSpeed, ttc, stableFrames, corridor);
            return detection.withTracking(id, distance, closingSpeed, ttc, risk,
                    colorFor(risk, detection.color));
        }
    }
}
