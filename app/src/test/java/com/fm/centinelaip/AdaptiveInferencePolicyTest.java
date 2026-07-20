package com.fm.centinelaip;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AdaptiveInferencePolicyTest {
    @Test public void fastAndCoolUsesMinimumInterval() {
        assertEquals(240L, AdaptiveInferencePolicy.nextIntervalMs(120L, 0));
    }

    @Test public void slowInferenceReducesFrequency() {
        assertEquals(650L, AdaptiveInferencePolicy.nextIntervalMs(500L, 0));
        assertEquals(900L, AdaptiveInferencePolicy.nextIntervalMs(800L, 0));
    }

    @Test public void moderateThermalStatusOverridesFastLatency() {
        assertEquals(520L, AdaptiveInferencePolicy.nextIntervalMs(120L, 2));
    }

    @Test public void severeThermalStatusForcesSafeInterval() {
        assertEquals(850L, AdaptiveInferencePolicy.nextIntervalMs(120L, 3));
        assertEquals(1_100L, AdaptiveInferencePolicy.nextIntervalMs(120L, 4));
    }
}
