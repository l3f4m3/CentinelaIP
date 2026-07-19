package com.fm.centinelaip.adas;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RiskEstimatorTest {

    private static final double EPSILON = 0.0001d;

    @Test
    public void invalidDistanceReturnsUnknown() {
        RiskEstimator.RiskAssessment result = RiskEstimator.evaluate(0.0d, 4.0d, 0.9d);
        assertEquals(RiskEstimator.RiskLevel.UNKNOWN, result.getLevel());
        assertFalse(result.isActionable());
    }

    @Test
    public void lowConfidenceReturnsUnknown() {
        RiskEstimator.RiskAssessment result = RiskEstimator.evaluate(10.0d, 4.0d, 0.2d);
        assertEquals(RiskEstimator.RiskLevel.UNKNOWN, result.getLevel());
    }

    @Test
    public void recedingObjectIsSafeWhenFarAway() {
        RiskEstimator.RiskAssessment result = RiskEstimator.evaluate(30.0d, -2.0d, 0.95d);
        assertEquals(RiskEstimator.RiskLevel.SAFE, result.getLevel());
        assertTrue(Double.isInfinite(result.getTimeToCollisionSeconds()));
        assertFalse(result.isActionable());
    }

    @Test
    public void shortTimeToCollisionIsCritical() {
        RiskEstimator.RiskAssessment result = RiskEstimator.evaluate(12.0d, 10.0d, 0.95d);
        assertEquals(RiskEstimator.RiskLevel.CRITICAL, result.getLevel());
        assertEquals(1.2d, result.getTimeToCollisionSeconds(), EPSILON);
        assertTrue(result.isActionable());
    }

    @Test
    public void mediumTimeToCollisionIsWarning() {
        RiskEstimator.RiskAssessment result = RiskEstimator.evaluate(10.0d, 4.0d, 0.95d);
        assertEquals(RiskEstimator.RiskLevel.WARNING, result.getLevel());
        assertEquals(2.5d, result.getTimeToCollisionSeconds(), EPSILON);
    }

    @Test
    public void moderateDistanceIsAttention() {
        RiskEstimator.RiskAssessment result = RiskEstimator.evaluate(10.0d, 0.0d, 0.8d);
        assertEquals(RiskEstimator.RiskLevel.ATTENTION, result.getLevel());
    }

    @Test
    public void distantSlowClosingObjectIsSafe() {
        RiskEstimator.RiskAssessment result = RiskEstimator.evaluate(40.0d, 2.0d, 0.8d);
        assertEquals(RiskEstimator.RiskLevel.SAFE, result.getLevel());
        assertEquals(20.0d, result.getTimeToCollisionSeconds(), EPSILON);
    }
}
