package com.fm.centinelaip;

/** Matemática pura y clasificadores cromáticos del baseline de vía. */
final class RoadPerceptionMath {
    private RoadPerceptionMath() { }

    static boolean isWhite(int red, int green, int blue) {
        int max = Math.max(red, Math.max(green, blue));
        int min = Math.min(red, Math.min(green, blue));
        int spread = max - min;
        int luminance = (red * 54 + green * 183 + blue * 19) >> 8;
        return luminance >= 165 && spread <= 52;
    }

    static boolean isYellow(int red, int green, int blue) {
        return red >= 135
                && green >= 95
                && blue <= 155
                && red - blue >= 38
                && green - blue >= 22
                && Math.abs(red - green) <= 95;
    }

    static float luminance(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        return (red * 0.2126f + green * 0.7152f + blue * 0.0722f) / 255f;
    }

    /**
     * Clasifica el contexto de una línea brillante.
     * Una marca suele tener pavimento parecido a ambos lados; un borde físico separa superficies distintas.
     */
    static int classifyLineContext(float lineLuminance, float leftLuminance, float rightLuminance) {
        float leftContrast = lineLuminance - leftLuminance;
        float rightContrast = lineLuminance - rightLuminance;
        float sideDifference = Math.abs(leftLuminance - rightLuminance);
        boolean brightOnBothSides = leftContrast >= 0.09f && rightContrast >= 0.09f;
        if (brightOnBothSides && sideDifference <= 0.16f) {
            return RoadPerception.LanePath.KIND_MARKING;
        }
        if (sideDifference >= 0.14f && Math.max(leftContrast, rightContrast) >= 0.08f) {
            return RoadPerception.LanePath.KIND_BOUNDARY;
        }
        return RoadPerception.LanePath.KIND_UNKNOWN;
    }

    /** Un baseline heurístico nunca debe mostrar una confianza cercana a certificación. */
    static float capHeuristicConfidence(float confidence, int kind) {
        float maximum;
        if (kind == RoadPerception.LanePath.KIND_MARKING) maximum = 0.86f;
        else if (kind == RoadPerception.LanePath.KIND_BOUNDARY) maximum = 0.68f;
        else maximum = 0.52f;
        return clamp(confidence, 0f, maximum);
    }

    static Fit fitLine(float[] y, float[] x, int count) {
        if (x == null || y == null || count < 2 || count > x.length || count > y.length) {
            return Fit.INVALID;
        }
        double sumY = 0d;
        double sumX = 0d;
        double sumYY = 0d;
        double sumYX = 0d;
        for (int index = 0; index < count; index++) {
            sumY += y[index];
            sumX += x[index];
            sumYY += y[index] * y[index];
            sumYX += y[index] * x[index];
        }
        double denominator = count * sumYY - sumY * sumY;
        if (Math.abs(denominator) < 1e-8d) return Fit.INVALID;
        float slope = (float) ((count * sumYX - sumY * sumX) / denominator);
        float intercept = (float) ((sumX - slope * sumY) / count);

        double error = 0d;
        double meanX = sumX / count;
        double total = 0d;
        for (int index = 0; index < count; index++) {
            double predicted = slope * y[index] + intercept;
            double residual = x[index] - predicted;
            error += residual * residual;
            double centered = x[index] - meanX;
            total += centered * centered;
        }
        float rSquared = total <= 1e-8d ? 1f : clamp((float) (1d - error / total), 0f, 1f);
        return new Fit(slope, intercept, rSquared, true);
    }

    static float evaluate(Fit fit, float y) {
        return fit.valid ? fit.slope * y + fit.intercept : Float.NaN;
    }

    static float confidence(int samples, int targetSamples, float averageScore, float rSquared) {
        float coverage = clamp(samples / (float) Math.max(1, targetSamples), 0f, 1f);
        return clamp(coverage * 0.55f + averageScore * 0.25f + rSquared * 0.20f, 0f, 1f);
    }

    static float convergenceScore(Fit left, Fit right) {
        if (left == null || right == null || !left.valid || !right.valid) return 0f;
        float denominator = left.slope - right.slope;
        if (Math.abs(denominator) < 0.08f) return 0f;
        float y = (right.intercept - left.intercept) / denominator;
        float x = evaluate(left, y);
        if (!Float.isFinite(x) || !Float.isFinite(y)) return 0f;
        if (y < 0.12f || y > 0.72f || x < 0.24f || x > 0.76f) return 0f;
        float xScore = 1f - Math.abs(x - 0.5f) / 0.26f;
        float yScore = 1f - Math.abs(y - 0.38f) / 0.34f;
        return clamp(xScore * 0.55f + yScore * 0.45f, 0f, 1f);
    }

    static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Fit {
        static final Fit INVALID = new Fit(Float.NaN, Float.NaN, 0f, false);
        final float slope;
        final float intercept;
        final float rSquared;
        final boolean valid;

        Fit(float slope, float intercept, float rSquared, boolean valid) {
            this.slope = slope;
            this.intercept = intercept;
            this.rSquared = rSquared;
            this.valid = valid;
        }
    }
}
