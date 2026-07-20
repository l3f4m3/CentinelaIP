package com.fm.centinelaip;

/** Cálculos puros para conservar la geometría de una fuente de video. */
final class VideoGeometry {
    private VideoGeometry() { }

    /** Escala correctiva del TextureView según el modo Llenar/Ajustar. */
    static float[] fitScale(int viewWidth, int viewHeight, int videoWidth, int videoHeight) {
        return displayScale(viewWidth, viewHeight, videoWidth, videoHeight,
                DisplayModeStore.isFillScreen());
    }

    static float[] displayScale(int viewWidth, int viewHeight,
                                int videoWidth, int videoHeight,
                                boolean fillScreen) {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return new float[]{1f, 1f};
        }
        float viewAspect = (float) viewWidth / viewHeight;
        float videoAspect = (float) videoWidth / videoHeight;
        if (!fillScreen) {
            return videoAspect > viewAspect
                    ? new float[]{1f, viewAspect / videoAspect}
                    : new float[]{videoAspect / viewAspect, 1f};
        }
        return videoAspect > viewAspect
                ? new float[]{videoAspect / viewAspect, 1f}
                : new float[]{1f, viewAspect / videoAspect};
    }

    /** Dimensiones máximas sin deformar ni ampliar el fotograma original. */
    static int[] scaledSize(int width, int height, int maxDimension) {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return new int[]{0, 0};
        int largest = Math.max(width, height);
        if (largest <= maxDimension) return new int[]{width, height};
        float scale = (float) maxDimension / largest;
        return new int[]{
                Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale))
        };
    }

    /** Intercambia ancho y alto cuando la rotación codificada es 90° o 270°. */
    static int[] orientedSize(int width, int height, int rotationDegrees) {
        int normalized = ((rotationDegrees % 360) + 360) % 360;
        return normalized == 90 || normalized == 270
                ? new int[]{height, width}
                : new int[]{width, height};
    }
}
