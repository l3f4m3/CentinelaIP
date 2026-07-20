package com.fm.centinelaip;

/** Operaciones deterministas usadas por la telemetría de conducción. */
final class DriveTelemetryMath {
    private DriveTelemetryMath() { }

    static float speedKmh(float speedMetersPerSecond, boolean available) {
        if (!available || !Float.isFinite(speedMetersPerSecond) || speedMetersPerSecond < 0f) {
            return Float.NaN;
        }
        return speedMetersPerSecond * 3.6f;
    }

    static float smooth(float previous, float current, float alpha) {
        if (!Float.isFinite(current)) return previous;
        if (!Float.isFinite(previous)) return current;
        float bounded = clamp(alpha, 0f, 1f);
        return previous + (current - previous) * bounded;
    }

    static float calibratedAngle(float rawDegrees, float offsetDegrees) {
        return normalizeDegrees(rawDegrees - offsetDegrees);
    }

    static float normalizeDegrees(float degrees) {
        if (!Float.isFinite(degrees)) return Float.NaN;
        float value = degrees % 360f;
        if (value > 180f) value -= 360f;
        if (value < -180f) value += 360f;
        return value;
    }

    static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
