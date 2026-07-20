package com.fm.centinelaip;

/** Geometría pura para ubicar una fuente dentro de una vista sin deformarla. */
final class MediaViewport {
    private MediaViewport() { }

    static float[] fitCenter(int viewWidth, int viewHeight, int sourceWidth, int sourceHeight) {
        return scaledRect(viewWidth, viewHeight, sourceWidth, sourceHeight, false);
    }

    static float[] centerCrop(int viewWidth, int viewHeight, int sourceWidth, int sourceHeight) {
        return scaledRect(viewWidth, viewHeight, sourceWidth, sourceHeight, true);
    }

    static float[] current(int viewWidth, int viewHeight, int sourceWidth, int sourceHeight) {
        return DisplayModeStore.isFillScreen()
                ? centerCrop(viewWidth, viewHeight, sourceWidth, sourceHeight)
                : fitCenter(viewWidth, viewHeight, sourceWidth, sourceHeight);
    }

    static float[] visible(int viewWidth, int viewHeight, int sourceWidth, int sourceHeight) {
        float[] content = current(viewWidth, viewHeight, sourceWidth, sourceHeight);
        if (!valid(content)) return new float[]{0f, 0f, 0f, 0f};
        float left = Math.max(0f, content[0]);
        float top = Math.max(0f, content[1]);
        float right = Math.min(viewWidth, content[2]);
        float bottom = Math.min(viewHeight, content[3]);
        return right > left && bottom > top
                ? new float[]{left, top, right, bottom}
                : new float[]{0f, 0f, 0f, 0f};
    }

    private static float[] scaledRect(int viewWidth, int viewHeight,
                                      int sourceWidth, int sourceHeight,
                                      boolean crop) {
        if (viewWidth <= 0 || viewHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return new float[]{0f, 0f, 0f, 0f};
        }
        float widthScale = (float) viewWidth / sourceWidth;
        float heightScale = (float) viewHeight / sourceHeight;
        float scale = crop ? Math.max(widthScale, heightScale) : Math.min(widthScale, heightScale);
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
