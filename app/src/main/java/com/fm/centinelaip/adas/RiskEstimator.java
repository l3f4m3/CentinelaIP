package com.fm.centinelaip.adas;

/**
 * Estimador determinista de riesgo longitudinal.
 *
 * <p>Esta clase no sustituye un sistema ADAS homologado. Su propósito es fijar
 * una interfaz y reglas reproducibles para que las fases posteriores conecten
 * distancia, velocidad relativa y confianza provenientes del pipeline de
 * percepción.</p>
 */
public final class RiskEstimator {

    public static final double MIN_CONFIDENCE = 0.35d;
    private static final double MIN_CLOSING_SPEED_MPS = 0.10d;

    private RiskEstimator() {
    }

    public static RiskAssessment evaluate(
            double distanceMeters,
            double closingSpeedMetersPerSecond,
            double confidence) {

        if (!Double.isFinite(distanceMeters)
                || distanceMeters <= 0.0d
                || !Double.isFinite(closingSpeedMetersPerSecond)
                || !Double.isFinite(confidence)
                || confidence < MIN_CONFIDENCE
                || confidence > 1.0d) {
            return RiskAssessment.unknown();
        }

        final double timeToCollisionSeconds = closingSpeedMetersPerSecond > MIN_CLOSING_SPEED_MPS
                ? distanceMeters / closingSpeedMetersPerSecond
                : Double.POSITIVE_INFINITY;

        final RiskLevel level;
        if (distanceMeters <= 2.0d || timeToCollisionSeconds <= 1.5d) {
            level = RiskLevel.CRITICAL;
        } else if (distanceMeters <= 5.0d || timeToCollisionSeconds <= 3.0d) {
            level = RiskLevel.WARNING;
        } else if (distanceMeters <= 12.0d || timeToCollisionSeconds <= 6.0d) {
            level = RiskLevel.ATTENTION;
        } else {
            level = RiskLevel.SAFE;
        }

        return new RiskAssessment(
                level,
                distanceMeters,
                closingSpeedMetersPerSecond,
                timeToCollisionSeconds,
                confidence);
    }

    public enum RiskLevel {
        UNKNOWN,
        SAFE,
        ATTENTION,
        WARNING,
        CRITICAL
    }

    public static final class RiskAssessment {
        private final RiskLevel level;
        private final double distanceMeters;
        private final double closingSpeedMetersPerSecond;
        private final double timeToCollisionSeconds;
        private final double confidence;

        private RiskAssessment(
                RiskLevel level,
                double distanceMeters,
                double closingSpeedMetersPerSecond,
                double timeToCollisionSeconds,
                double confidence) {
            this.level = level;
            this.distanceMeters = distanceMeters;
            this.closingSpeedMetersPerSecond = closingSpeedMetersPerSecond;
            this.timeToCollisionSeconds = timeToCollisionSeconds;
            this.confidence = confidence;
        }

        private static RiskAssessment unknown() {
            return new RiskAssessment(
                    RiskLevel.UNKNOWN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    0.0d);
        }

        public RiskLevel getLevel() {
            return level;
        }

        public double getDistanceMeters() {
            return distanceMeters;
        }

        public double getClosingSpeedMetersPerSecond() {
            return closingSpeedMetersPerSecond;
        }

        public double getTimeToCollisionSeconds() {
            return timeToCollisionSeconds;
        }

        public double getConfidence() {
            return confidence;
        }

        public boolean isActionable() {
            return level == RiskLevel.ATTENTION
                    || level == RiskLevel.WARNING
                    || level == RiskLevel.CRITICAL;
        }
    }
}
