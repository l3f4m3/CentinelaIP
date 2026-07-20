package com.fm.centinelaip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Fusión semántica posterior al tracking.
 *
 * Evita presentar por separado a la persona y a la bicicleta/motocicleta cuando
 * ambas cajas describen al mismo actor vial. Solo fusiona asociaciones geométricas
 * fuertes; ante duda conserva las detecciones originales.
 */
final class RoadActorFusion {
    static final int CLASS_CYCLIST = 1_001;
    static final int CLASS_MOTORCYCLIST = 1_002;
    private static final float MIN_ASSOCIATION = 0.58f;

    private RoadActorFusion() { }

    static List<Detection> fuse(List<Detection> values) {
        if (values == null || values.size() < 2) {
            return values == null ? new ArrayList<>() : new ArrayList<>(values);
        }

        List<Detection> input = new ArrayList<>(values);
        boolean[] used = new boolean[input.size()];
        List<Detection> result = new ArrayList<>();

        for (int personIndex = 0; personIndex < input.size(); personIndex++) {
            Detection person = input.get(personIndex);
            if (used[personIndex] || !isPerson(person.label)) continue;

            int bestIndex = -1;
            float bestScore = MIN_ASSOCIATION;
            for (int vehicleIndex = 0; vehicleIndex < input.size(); vehicleIndex++) {
                if (vehicleIndex == personIndex || used[vehicleIndex]) continue;
                Detection vehicle = input.get(vehicleIndex);
                if (!isRideable(vehicle.label)) continue;
                float score = associationScore(person, vehicle);
                if (score > bestScore) {
                    bestScore = score;
                    bestIndex = vehicleIndex;
                }
            }

            if (bestIndex >= 0) {
                Detection vehicle = input.get(bestIndex);
                used[personIndex] = true;
                used[bestIndex] = true;
                result.add(merge(person, vehicle));
            }
        }

        for (int index = 0; index < input.size(); index++) {
            if (!used[index]) result.add(input.get(index));
        }
        result.sort(Comparator.comparingDouble((Detection value) -> value.confidence).reversed());
        return result;
    }

    static float associationScore(Detection person, Detection vehicle) {
        if (person == null || vehicle == null || !isPerson(person.label)
                || !isRideable(vehicle.label)) return 0f;

        float personWidth = Math.max(1f, person.right - person.left);
        float personHeight = Math.max(1f, person.bottom - person.top);
        float vehicleWidth = Math.max(1f, vehicle.right - vehicle.left);
        float vehicleHeight = Math.max(1f, vehicle.bottom - vehicle.top);
        float personCenterX = (person.left + person.right) * 0.5f;
        float vehicleCenterX = (vehicle.left + vehicle.right) * 0.5f;

        float horizontalTolerance = Math.max(personWidth, vehicleWidth) * 0.72f;
        float horizontalDistance = Math.abs(personCenterX - vehicleCenterX);
        if (horizontalDistance > horizontalTolerance) return 0f;

        float overlapWidth = Math.max(0f,
                Math.min(person.right, vehicle.right) - Math.max(person.left, vehicle.left));
        float overlapRatio = overlapWidth / Math.max(1f, Math.min(personWidth, vehicleWidth));

        float expectedContactY = vehicle.top + vehicleHeight * 0.38f;
        float verticalDistance = Math.abs(person.bottom - expectedContactY);
        float verticalTolerance = Math.max(personHeight, vehicleHeight) * 0.62f;
        if (verticalDistance > verticalTolerance) return 0f;

        boolean plausibleStack = person.top < vehicle.bottom
                && person.bottom > vehicle.top - personHeight * 0.18f;
        if (!plausibleStack) return 0f;

        float horizontal = 1f - clamp(horizontalDistance / horizontalTolerance, 0f, 1f);
        float vertical = 1f - clamp(verticalDistance / verticalTolerance, 0f, 1f);
        return clamp(horizontal * 0.42f + vertical * 0.34f
                + clamp(overlapRatio, 0f, 1f) * 0.24f, 0f, 1f);
    }

    private static Detection merge(Detection person, Detection vehicle) {
        boolean bicycle = isBicycle(vehicle.label);
        String label = bicycle ? "ciclista" : "motociclista";
        int classId = bicycle ? CLASS_CYCLIST : CLASS_MOTORCYCLIST;
        RoadObjectTracker.RiskLevel risk = highestRisk(person.riskLevel, vehicle.riskLevel);

        float distance = finiteOr(person.distanceMeters, vehicle.distanceMeters);
        float ttc = minimumFinite(person.ttcSeconds, vehicle.ttcSeconds);
        float closing = finiteOr(person.closingSpeedMps, vehicle.closingSpeedMps);
        int trackId = person.trackId >= 0 ? person.trackId : vehicle.trackId;
        int color = colorFor(risk, person.color);

        return new Detection(
                Math.min(person.left, vehicle.left),
                Math.min(person.top, vehicle.top),
                Math.max(person.right, vehicle.right),
                Math.max(person.bottom, vehicle.bottom),
                Math.max(person.confidence, vehicle.confidence),
                classId,
                label,
                color,
                trackId,
                distance,
                closing,
                ttc,
                risk);
    }

    private static boolean isPerson(String label) {
        String value = normalized(label);
        return "persona".equals(value) || "person".equals(value);
    }

    private static boolean isRideable(String label) {
        return isBicycle(label) || isMotorcycle(label);
    }

    private static boolean isBicycle(String label) {
        String value = normalized(label);
        return "bicicleta".equals(value) || "bicycle".equals(value) || "bike".equals(value);
    }

    private static boolean isMotorcycle(String label) {
        String value = normalized(label);
        return "motocicleta".equals(value) || "motorcycle".equals(value)
                || "motorbike".equals(value);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static float finiteOr(float preferred, float fallback) {
        return Float.isFinite(preferred) ? preferred : fallback;
    }

    private static float minimumFinite(float first, float second) {
        if (Float.isFinite(first) && Float.isFinite(second)) return Math.min(first, second);
        return Float.isFinite(first) ? first : second;
    }

    private static RoadObjectTracker.RiskLevel highestRisk(
            RoadObjectTracker.RiskLevel first, RoadObjectTracker.RiskLevel second) {
        return severity(first) >= severity(second) ? safeRisk(first) : safeRisk(second);
    }

    private static RoadObjectTracker.RiskLevel safeRisk(RoadObjectTracker.RiskLevel value) {
        return value == null ? RoadObjectTracker.RiskLevel.UNKNOWN : value;
    }

    private static int severity(RoadObjectTracker.RiskLevel value) {
        if (value == null) return 0;
        switch (value) {
            case CRITICAL: return 4;
            case WARNING: return 3;
            case ATTENTION: return 2;
            case SAFE: return 1;
            default: return 0;
        }
    }

    private static int colorFor(RoadObjectTracker.RiskLevel risk, int fallback) {
        switch (safeRisk(risk)) {
            case CRITICAL: return 0xFFFF334F;
            case WARNING: return 0xFFFF9F1C;
            case ATTENTION: return 0xFFFFD166;
            case SAFE: return 0xFF22D3EE;
            default: return fallback;
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
