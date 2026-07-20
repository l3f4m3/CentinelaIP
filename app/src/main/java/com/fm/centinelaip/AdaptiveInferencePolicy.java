package com.fm.centinelaip;

/**
 * Política determinista para reducir carga cuando la inferencia se vuelve lenta
 * o Android reporta presión térmica.
 */
final class AdaptiveInferencePolicy {
    private static final long MIN_INTERVAL_MS = 240L;
    private static final long MAX_INTERVAL_MS = 1_200L;

    private AdaptiveInferencePolicy() { }

    static long nextIntervalMs(long lastLatencyMs, int thermalStatus) {
        long interval;
        if (lastLatencyMs >= 700L) interval = 900L;
        else if (lastLatencyMs >= 450L) interval = 650L;
        else if (lastLatencyMs >= 250L) interval = 430L;
        else interval = MIN_INTERVAL_MS;

        if (thermalStatus >= 4) interval = Math.max(interval, 1_100L);
        else if (thermalStatus >= 3) interval = Math.max(interval, 850L);
        else if (thermalStatus >= 2) interval = Math.max(interval, 520L);

        return Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, interval));
    }
}
