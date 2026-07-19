package com.fm.centinelaip;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoadObjectTrackerTest {
    private static Detection car(float left, float top, float right, float bottom) {
        return new Detection(left, top, right, bottom, 0.90f, 2,
                "automóvil", 0xFF22D3EE);
    }

    @Test public void distanceIsFiniteForKnownRoadClass() {
        float distance = RoadObjectTracker.estimateDistanceMeters(
                car(300f, 200f, 500f, 400f), 720);
        assertTrue(Float.isFinite(distance));
        assertTrue(distance > 4f);
        assertTrue(distance < 15f);
    }

    @Test public void unknownClassHasNoMetricDistance() {
        Detection chair = new Detection(100f, 100f, 250f, 300f,
                0.8f, 56, "silla", 0xFFFFFFFF);
        assertFalse(Float.isFinite(RoadObjectTracker.estimateDistanceMeters(chair, 720)));
    }

    @Test public void sameObjectKeepsTrackIdAndProducesClosingSpeed() {
        RoadObjectTracker tracker = new RoadObjectTracker();
        long first = 1_000_000_000L;
        RoadObjectTracker.Result one = tracker.update(
                Collections.singletonList(car(300f, 220f, 500f, 420f)),
                800, 720, first);
        RoadObjectTracker.Result two = tracker.update(
                Collections.singletonList(car(290f, 190f, 510f, 440f)),
                800, 720, first + 500_000_000L);

        assertEquals(one.detections.get(0).trackId, two.detections.get(0).trackId);
        assertTrue(Float.isFinite(two.detections.get(0).closingSpeedMps));
        assertTrue(two.detections.get(0).closingSpeedMps > 0f);
        assertTrue(Float.isFinite(two.detections.get(0).ttcSeconds));
    }

    @Test public void objectOutsideCentralCorridorDoesNotBecomeCritical() {
        RoadObjectTracker tracker = new RoadObjectTracker();
        long first = 1_000_000_000L;
        tracker.update(Collections.singletonList(car(10f, 150f, 150f, 650f)),
                800, 720, first);
        RoadObjectTracker.Result result = tracker.update(
                Collections.singletonList(car(5f, 100f, 160f, 690f)),
                800, 720, first + 400_000_000L);
        assertEquals(RoadObjectTracker.RiskLevel.SAFE,
                result.detections.get(0).riskLevel);
    }

    @Test public void longGapResetsTrackIdentifiers() {
        RoadObjectTracker tracker = new RoadObjectTracker();
        RoadObjectTracker.Result first = tracker.update(
                Collections.singletonList(car(300f, 220f, 500f, 420f)),
                800, 720, 1_000_000_000L);
        RoadObjectTracker.Result second = tracker.update(
                Collections.singletonList(car(300f, 220f, 500f, 420f)),
                800, 720, 5_000_000_000L);
        assertEquals(1, first.detections.get(0).trackId);
        assertEquals(1, second.detections.get(0).trackId);
    }
}
