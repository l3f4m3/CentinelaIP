package com.fm.centinelaip;

import android.graphics.Bitmap;

/** Baseline ligero para marcas blancas/amarillas y límites físicos de la vía. */
final class RoadPerceptionEstimator {
    private static final int TARGET_WIDTH = 320;
    private static final int ROW_SAMPLES = 24;
    private static final int PATH_POINTS = 14;

    RoadPerception estimate(Bitmap source) {
        if (source == null || source.getWidth() < 32 || source.getHeight() < 32) {
            return RoadPerception.EMPTY;
        }

        float scale = Math.min(1f, TARGET_WIDTH / (float) source.getWidth());
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap sample = width == source.getWidth() && height == source.getHeight()
                ? source : Bitmap.createScaledBitmap(source, width, height, true);

        try {
            int[] pixels = new int[width * height];
            sample.getPixels(pixels, 0, width, 0, 0, width, height);
            int horizon = Math.round(height * 0.36f);
            LaneCandidate left = scan(pixels, width, height, horizon, false);
            LaneCandidate right = scan(pixels, width, height, horizon, true);

            float scaleX = source.getWidth() / (float) width;
            float scaleY = source.getHeight() / (float) height;
            RoadPerception.LanePath leftPath = toPath(left, width, height, horizon, scaleX, scaleY);
            RoadPerception.LanePath rightPath = toPath(right, width, height, horizon, scaleX, scaleY);
            float corridor = corridorConfidence(left, right, horizon / (float) height);

            return new RoadPerception(source.getWidth(), source.getHeight(),
                    leftPath, rightPath, corridor, System.nanoTime());
        } finally {
            if (sample != source) sample.recycle();
        }
    }

    private LaneCandidate scan(int[] pixels, int width, int height, int horizon, boolean rightSide) {
        float[] normalizedY = new float[ROW_SAMPLES];
        float[] normalizedX = new float[ROW_SAMPLES];
        float scoreSum = 0f;
        float whiteWeight = 0f;
        float yellowWeight = 0f;
        float markingWeight = 0f;
        float boundaryWeight = 0f;
        float previousX = Float.NaN;
        int count = 0;
        int bottom = height - 3;

        for (int index = 0; index < ROW_SAMPLES; index++) {
            float fraction = index / (float) (ROW_SAMPLES - 1);
            int y = Math.round(bottom - fraction * (bottom - horizon));
            float near = (y - horizon) / (float) Math.max(1, bottom - horizon);
            float defaultX = rightSide
                    ? width * (0.52f + 0.30f * near)
                    : width * (0.48f - 0.30f * near);
            float expected = Float.isFinite(previousX)
                    ? previousX * 0.72f + defaultX * 0.28f : defaultX;
            int minimum = rightSide ? Math.round(width * 0.51f) : Math.round(width * 0.04f);
            int maximum = rightSide ? Math.round(width * 0.96f) : Math.round(width * 0.49f);
            int radius = Math.round(width * (0.11f + near * 0.15f));
            int from = Math.max(minimum, Math.round(expected) - radius);
            int to = Math.min(maximum, Math.round(expected) + radius);

            Candidate best = Candidate.NONE;
            for (int x = Math.max(10, from); x <= Math.min(width - 11, to); x++) {
                int color = pixels[y * width + x];
                int red = (color >> 16) & 0xFF;
                int green = (color >> 8) & 0xFF;
                int blue = color & 0xFF;
                boolean white = RoadPerceptionMath.isWhite(red, green, blue);
                boolean yellow = RoadPerceptionMath.isYellow(red, green, blue);
                if (!white && !yellow) continue;

                float lineLuminance = RoadPerceptionMath.luminance(color);
                float leftLuminance = averageLuminance(pixels, width, height, x - 9, x - 4, y);
                float rightLuminance = averageLuminance(pixels, width, height, x + 4, x + 9, y);
                int kind = RoadPerceptionMath.classifyLineContext(
                        lineLuminance, leftLuminance, rightLuminance);

                float horizontalContrast = Math.abs(rightLuminance - leftLuminance);
                int upperY = Math.max(0, y - 3);
                int lowerY = Math.min(height - 1, y + 3);
                float verticalContrast = Math.abs(
                        RoadPerceptionMath.luminance(pixels[lowerY * width + x])
                                - RoadPerceptionMath.luminance(pixels[upperY * width + x]));
                float edgeStrength = Math.max(horizontalContrast, verticalContrast * 0.55f);
                float proximity = 1f - Math.min(1f, Math.abs(x - expected) / Math.max(1f, radius));
                float score = 0.36f + Math.min(0.28f, edgeStrength * 0.75f) + proximity * 0.18f;
                if (kind == RoadPerception.LanePath.KIND_MARKING) score += 0.14f;
                else if (kind == RoadPerception.LanePath.KIND_BOUNDARY) score += 0.07f;
                if (yellow) score += 0.04f;
                if (score > best.score) best = new Candidate(x, score, white, yellow, kind);
            }

            if (best.score < 0.52f) continue;
            previousX = best.x;
            normalizedY[count] = y / (float) height;
            normalizedX[count] = best.x / (float) width;
            scoreSum += best.score;
            if (best.white) whiteWeight += best.score;
            if (best.yellow) yellowWeight += best.score;
            if (best.kind == RoadPerception.LanePath.KIND_MARKING) markingWeight += best.score;
            if (best.kind == RoadPerception.LanePath.KIND_BOUNDARY) boundaryWeight += best.score;
            count++;
        }

        RoadPerceptionMath.Fit fit = RoadPerceptionMath.fitLine(normalizedY, normalizedX, count);
        if (!fit.valid || count < 5) return LaneCandidate.INVALID;
        if (!rightSide && fit.slope > -0.055f) return LaneCandidate.INVALID;
        if (rightSide && fit.slope < 0.055f) return LaneCandidate.INVALID;

        float topX = RoadPerceptionMath.evaluate(fit, horizon / (float) height);
        float bottomX = RoadPerceptionMath.evaluate(fit, 0.98f);
        if (!rightSide && (topX < 0.16f || topX > 0.58f || bottomX < 0.01f || bottomX > 0.49f)) {
            return LaneCandidate.INVALID;
        }
        if (rightSide && (topX < 0.42f || topX > 0.84f || bottomX < 0.51f || bottomX > 0.99f)) {
            return LaneCandidate.INVALID;
        }

        int kind;
        if (markingWeight > boundaryWeight * 1.12f) kind = RoadPerception.LanePath.KIND_MARKING;
        else if (boundaryWeight > 0f) kind = RoadPerception.LanePath.KIND_BOUNDARY;
        else kind = RoadPerception.LanePath.KIND_UNKNOWN;

        float averageScore = scoreSum / count;
        float rawConfidence = RoadPerceptionMath.confidence(count, ROW_SAMPLES,
                RoadPerceptionMath.clamp(averageScore, 0f, 1f), fit.rSquared);
        float confidence = RoadPerceptionMath.capHeuristicConfidence(rawConfidence, kind);
        int color = yellowWeight > whiteWeight * 1.08f
                ? RoadPerception.LanePath.COLOR_YELLOW
                : whiteWeight > 0f ? RoadPerception.LanePath.COLOR_WHITE
                : RoadPerception.LanePath.COLOR_UNKNOWN;
        return new LaneCandidate(fit, confidence, color, kind, count);
    }

    private float averageLuminance(int[] pixels, int width, int height,
                                   int fromX, int toX, int y) {
        int safeY = Math.max(0, Math.min(height - 1, y));
        int start = Math.max(0, Math.min(width - 1, fromX));
        int end = Math.max(start, Math.min(width - 1, toX));
        float sum = 0f;
        int count = 0;
        for (int x = start; x <= end; x++) {
            sum += RoadPerceptionMath.luminance(pixels[safeY * width + x]);
            count++;
        }
        return count == 0 ? 0f : sum / count;
    }

    private RoadPerception.LanePath toPath(LaneCandidate lane, int width, int height,
                                            int horizon, float scaleX, float scaleY) {
        if (!lane.valid()) return RoadPerception.LanePath.EMPTY;
        float[] x = new float[PATH_POINTS];
        float[] y = new float[PATH_POINTS];
        for (int index = 0; index < PATH_POINTS; index++) {
            float fraction = index / (float) (PATH_POINTS - 1);
            float sampleY = horizon + (height - 1 - horizon) * fraction;
            float normalizedY = sampleY / height;
            float normalizedX = RoadPerceptionMath.evaluate(lane.fit, normalizedY);
            x[index] = RoadPerceptionMath.clamp(normalizedX * width * scaleX,
                    0f, width * scaleX);
            y[index] = sampleY * scaleY;
        }
        return new RoadPerception.LanePath(x, y, lane.color, lane.kind, lane.confidence);
    }

    private float corridorConfidence(LaneCandidate left, LaneCandidate right, float horizonY) {
        if (!left.valid() || !right.valid()) return 0f;
        float leftTop = RoadPerceptionMath.evaluate(left.fit, horizonY);
        float rightTop = RoadPerceptionMath.evaluate(right.fit, horizonY);
        float leftBottom = RoadPerceptionMath.evaluate(left.fit, 0.98f);
        float rightBottom = RoadPerceptionMath.evaluate(right.fit, 0.98f);
        float topWidth = rightTop - leftTop;
        float bottomWidth = rightBottom - leftBottom;
        if (topWidth < 0.025f || topWidth > 0.42f) return 0f;
        if (bottomWidth < 0.24f || bottomWidth > 0.92f) return 0f;
        if (topWidth >= bottomWidth * 0.72f) return 0f;
        if (leftBottom >= 0.52f || rightBottom <= 0.48f) return 0f;

        float convergence = RoadPerceptionMath.convergenceScore(left.fit, right.fit);
        if (convergence <= 0f) return 0f;
        float confidence = (left.confidence + right.confidence) * 0.5f;
        confidence *= 0.62f + convergence * 0.38f;
        boolean physicalBoundary = left.kind == RoadPerception.LanePath.KIND_BOUNDARY
                || right.kind == RoadPerception.LanePath.KIND_BOUNDARY;
        return RoadPerceptionMath.clamp(confidence, 0f, physicalBoundary ? 0.64f : 0.82f);
    }

    private static final class Candidate {
        static final Candidate NONE = new Candidate(-1, -1f, false, false,
                RoadPerception.LanePath.KIND_UNKNOWN);
        final int x;
        final float score;
        final boolean white;
        final boolean yellow;
        final int kind;

        Candidate(int x, float score, boolean white, boolean yellow, int kind) {
            this.x = x;
            this.score = score;
            this.white = white;
            this.yellow = yellow;
            this.kind = kind;
        }
    }

    private static final class LaneCandidate {
        static final LaneCandidate INVALID = new LaneCandidate(
                RoadPerceptionMath.Fit.INVALID, 0f,
                RoadPerception.LanePath.COLOR_UNKNOWN,
                RoadPerception.LanePath.KIND_UNKNOWN, 0);
        final RoadPerceptionMath.Fit fit;
        final float confidence;
        final int color;
        final int kind;
        final int samples;

        LaneCandidate(RoadPerceptionMath.Fit fit, float confidence,
                      int color, int kind, int samples) {
            this.fit = fit;
            this.confidence = confidence;
            this.color = color;
            this.kind = kind;
            this.samples = samples;
        }

        boolean valid() {
            return fit.valid && samples >= 5 && confidence >= 0.20f;
        }
    }
}
