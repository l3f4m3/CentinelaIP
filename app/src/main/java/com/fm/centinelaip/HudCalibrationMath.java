package com.fm.centinelaip;

/** Cálculos puros para ajustar y limitar la guía de perspectiva del HUD. */
final class HudCalibrationMath {
    private HudCalibrationMath() { }

    static final class State {
        final float horizonFraction;
        final float centerFraction;
        final float nearHalfFraction;
        final float farHalfFraction;
        final float manualRollDegrees;

        State(float horizonFraction, float centerFraction, float nearHalfFraction,
              float farHalfFraction, float manualRollDegrees) {
            this.horizonFraction = clamp(horizonFraction, 0.18f, 0.78f);
            this.centerFraction = clamp(centerFraction, 0.12f, 0.88f);
            this.nearHalfFraction = clamp(nearHalfFraction, 0.16f, 0.49f);
            this.farHalfFraction = clamp(farHalfFraction, 0.015f, 0.22f);
            this.manualRollDegrees = clamp(manualRollDegrees, -18f, 18f);
        }

        static State defaults() {
            return new State(0.43f, 0.50f, 0.42f, 0.07f, 0f);
        }
    }

    static State applyGesture(State start, float deltaXFraction, float deltaYFraction,
                              float scale, float deltaAngleDegrees) {
        float safeScale = Float.isFinite(scale) ? clamp(scale, 0.55f, 1.80f) : 1f;
        return new State(
                start.horizonFraction + deltaYFraction,
                start.centerFraction + deltaXFraction,
                start.nearHalfFraction * safeScale,
                start.farHalfFraction * safeScale,
                start.manualRollDegrees + normalizeDeltaAngle(deltaAngleDegrees));
    }

    static float normalizeDeltaAngle(float degrees) {
        if (!Float.isFinite(degrees)) return 0f;
        float value = degrees;
        while (value > 180f) value -= 360f;
        while (value < -180f) value += 360f;
        return value;
    }

    static float clamp(float value, float min, float max) {
        if (!Float.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}