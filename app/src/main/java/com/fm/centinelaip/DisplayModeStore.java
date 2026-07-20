package com.fm.centinelaip;

/** Estado de presentación compartido por video, cámara y overlays. */
final class DisplayModeStore {
    private static volatile boolean fillScreen = true;

    private DisplayModeStore() { }

    static boolean isFillScreen() {
        return fillScreen;
    }

    static void setFillScreen(boolean value) {
        fillScreen = value;
    }

    static boolean toggle() {
        fillScreen = !fillScreen;
        return fillScreen;
    }
}
