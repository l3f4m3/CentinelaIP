package com.fm.centinelaip;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class RoadActorFusionTest {
    private Detection detection(float left, float top, float right, float bottom,
                                float confidence, int classId, String label,
                                int trackId, float distance, float ttc,
                                RoadObjectTracker.RiskLevel risk) {
        return new Detection(left, top, right, bottom, confidence, classId, label,
                0xFF22D3EE, trackId, distance, Float.NaN, ttc, risk);
    }

    @Test public void fusionaPersonaYBicicletaSuperpuestas() {
        Detection person = detection(410f, 90f, 555f, 455f,
                0.86f, 0, "persona", 12, 8f, 6f,
                RoadObjectTracker.RiskLevel.ATTENTION);
        Detection bicycle = detection(365f, 300f, 615f, 610f,
                0.62f, 1, "bicicleta", 13, 7.5f, 5.5f,
                RoadObjectTracker.RiskLevel.WARNING);

        List<Detection> result = RoadActorFusion.fuse(Arrays.asList(person, bicycle));

        assertEquals(1, result.size());
        Detection cyclist = result.get(0);
        assertEquals("ciclista", cyclist.label);
        assertEquals(RoadActorFusion.CLASS_CYCLIST, cyclist.classId);
        assertEquals(12, cyclist.trackId);
        assertEquals(5.5f, cyclist.ttcSeconds, 0.001f);
        assertEquals(RoadObjectTracker.RiskLevel.WARNING, cyclist.riskLevel);
        assertTrue(cyclist.left <= bicycle.left);
        assertTrue(cyclist.bottom >= bicycle.bottom);
    }

    @Test public void conservaActoresSeparadosCuandoNoHayAsociacion() {
        Detection person = detection(50f, 80f, 170f, 430f,
                0.9f, 0, "persona", 1, 4f, Float.NaN,
                RoadObjectTracker.RiskLevel.SAFE);
        Detection bicycle = detection(520f, 320f, 760f, 620f,
                0.7f, 1, "bicicleta", 2, 8f, Float.NaN,
                RoadObjectTracker.RiskLevel.SAFE);

        List<Detection> result = RoadActorFusion.fuse(Arrays.asList(person, bicycle));

        assertEquals(2, result.size());
    }

    @Test public void fusionaMotociclistaConEtiquetaEspecifica() {
        Detection person = detection(300f, 100f, 440f, 430f,
                0.82f, 0, "persona", 7, 10f, Float.NaN,
                RoadObjectTracker.RiskLevel.SAFE);
        Detection motorcycle = detection(255f, 285f, 500f, 600f,
                0.76f, 3, "motocicleta", 8, 9.5f, Float.NaN,
                RoadObjectTracker.RiskLevel.SAFE);

        List<Detection> result = RoadActorFusion.fuse(Arrays.asList(person, motorcycle));

        assertEquals(1, result.size());
        assertEquals("motociclista", result.get(0).label);
        assertEquals(RoadActorFusion.CLASS_MOTORCYCLIST, result.get(0).classId);
    }
}
