package com.fm.centinelaip;

/** Cálculos puros para conservar la geometría de una fuente de video. */
final class VideoGeometry {
    private VideoGeometry() { }

    /**
     * Escala correctiva para un TextureView que, por defecto, estira el buffer
     * hasta ocupar todo el área disponible.
     */
    static float[] fitScale(int viewWidth, int viewHeight, int videoWidth, int videoHeight) {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return new float[]{1f, 1f};
        }
        float viewAspect = (float) viewWidth / viewHeight;
        float videoAspect = (float) videoWidth / videoHeight;
        if (videoAspect > viewAspect) {
            return new float[]{1f, viewAspect / videoAspect};
        }
        return new float[]{videoAspect / viewAspect, 1f};
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
