package com.fm.centinelaip;

import java.util.Arrays;

/** Resultado inmutable del baseline local de percepción de vía. */
final class RoadPerception {
    static final RoadPerception EMPTY = new RoadPerception(
            0, 0, LanePath.EMPTY, LanePath.EMPTY, 0f, 0L);

    final int sourceWidth;
    final int sourceHeight;
    final LanePath left;
    final LanePath right;
    final float corridorConfidence;
    final long timestampNanos;

    RoadPerception(int sourceWidth, int sourceHeight,
                   LanePath left, LanePath right,
                   float corridorConfidence, long timestampNanos) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.left = left == null ? LanePath.EMPTY : left;
        this.right = right == null ? LanePath.EMPTY : right;
        this.corridorConfidence = RoadPerceptionMath.clamp(corridorConfidence, 0f, 1f);
        this.timestampNanos = timestampNanos;
    }

    boolean hasCorridor() {
        return left.isValid() && right.isValid() && corridorConfidence >= 0.25f;
    }

    static final class LanePath {
        static final int COLOR_UNKNOWN = 0;
        static final int COLOR_WHITE = 1;
        static final int COLOR_YELLOW = 2;
        static final LanePath EMPTY = new LanePath(new float[0], new float[0],
                COLOR_UNKNOWN, 0f);

        final float[] x;
        final float[] y;
        final int markingColor;
        final float confidence;

        LanePath(float[] x, float[] y, int markingColor, float confidence) {
            this.x = x == null ? new float[0] : Arrays.copyOf(x, x.length);
            this.y = y == null ? new float[0] : Arrays.copyOf(y, y.length);
            this.markingColor = markingColor;
            this.confidence = RoadPerceptionMath.clamp(confidence, 0f, 1f);
        }

        boolean isValid() {
            return x.length >= 2 && x.length == y.length && confidence >= 0.20f;
        }
    }
}
