package com.fm.centinelaip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DriveTelemetryMathTest {
    @Test public void convertsMetersPerSecondToKilometersPerHour() {
        assertEquals(36f, DriveTelemetryMath.speedKmh(10f, true), 0.001f);
    }

    @Test public void rejectsUnavailableSpeed() {
        assertTrue(Float.isNaN(DriveTelemetryMath.speedKmh(15f, false)));
    }

    @Test public void smoothsWithoutOvershoot() {
        assertEquals(25f, DriveTelemetryMath.smooth(20f, 30f, 0.5f), 0.001f);
    }

    @Test public void calibrationHandlesAngleWrap() {
        assertEquals(10f, DriveTelemetryMath.calibratedAngle(-175f, 175f), 0.001f);
    }

    @Test public void normalizesNegativeAngles() {
        assertEquals(-170f, DriveTelemetryMath.normalizeDegrees(190f), 0.001f);
    }
}
