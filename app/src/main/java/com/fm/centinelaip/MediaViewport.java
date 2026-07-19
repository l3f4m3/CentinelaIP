package com.fm.centinelaip;

/** Geometría pura para ubicar contenido FIT_CENTER dentro de una vista. */
final class MediaViewport {
    private MediaViewport() { }

    static float[] fitCenter(int viewWidth, int viewHeight, int sourceWidth, int sourceHeight) {
        if (viewWidth <= 0 || viewHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return new float[]{0f, 0f, 0f, 0f};
        }
        float scale = Math.min((float) viewWidth / sourceWidth, (float) viewHeight / sourceHeight);
        float width = sourceWidth * scale;
        float height = sourceHeight * scale;
        float left = (viewWidth - width) / 2f;
        float top = (viewHeight - height) / 2f;
        return new float[]{left, top, left + width, top + height};
    }

    static boolean valid(float[] rect) {
        return rect != null && rect.length >= 4 && rect[2] > rect[0] && rect[3] > rect[1];
    }
}