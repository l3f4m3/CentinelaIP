package com.fm.centinelaip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RoadPerceptionMathTest {
    @Test
    public void reconoceBlancoConBajaSaturacion() {
        assertTrue(RoadPerceptionMath.isWhite(235, 231, 228));
        assertTrue(RoadPerceptionMath.isWhite(185, 190, 182));
        assertFalse(RoadPerceptionMath.isWhite(150, 95, 45));
        assertFalse(RoadPerceptionMath.isWhite(80, 85, 82));
    }

    @Test
    public void reconoceAmarilloYRechazaNaranjaOscuro() {
        assertTrue(RoadPerceptionMath.isYellow(240, 205, 65));
        assertTrue(RoadPerceptionMath.isYellow(190, 155, 70));
        assertFalse(RoadPerceptionMath.isYellow(110, 70, 25));
        assertFalse(RoadPerceptionMath.isYellow(230, 225, 215));
    }

    @Test
    public void clasificaDemarcacionConPavimentoSimilarAmbosLados() {
        assertEquals(RoadPerception.LanePath.KIND_MARKING,
                RoadPerceptionMath.classifyLineContext(0.92f, 0.38f, 0.41f));
    }

    @Test
    public void clasificaBordeCuandoSeparaSuperficiesDistintas() {
        assertEquals(RoadPerception.LanePath.KIND_BOUNDARY,
                RoadPerceptionMath.classifyLineContext(0.90f, 0.28f, 0.62f));
    }

    @Test
    public void confianzaDeBordeNoSePresentaComoCertidumbre() {
        assertEquals(0.68f, RoadPerceptionMath.capHeuristicConfidence(
                0.98f, RoadPerception.LanePath.KIND_BOUNDARY), 0.0001f);
        assertEquals(0.86f, RoadPerceptionMath.capHeuristicConfidence(
                0.98f, RoadPerception.LanePath.KIND_MARKING), 0.0001f);
    }

    @Test
    public void ajustaLineaIzquierdaNormalizada() {
        float[] y = {0.40f, 0.55f, 0.70f, 0.85f, 0.98f};
        float[] x = {0.46f, 0.39f, 0.32f, 0.25f, 0.19f};
        RoadPerceptionMath.Fit fit = RoadPerceptionMath.fitLine(y, x, x.length);
        assertTrue(fit.valid);
        assertTrue(fit.slope < -0.40f);
        assertTrue(fit.rSquared > 0.99f);
        assertEquals(0.19f, RoadPerceptionMath.evaluate(fit, 0.98f), 0.02f);
    }

    @Test
    public void ajustaLineaDerechaNormalizada() {
        float[] y = {0.40f, 0.55f, 0.70f, 0.85f, 0.98f};
        float[] x = {0.54f, 0.61f, 0.68f, 0.75f, 0.81f};
        RoadPerceptionMath.Fit fit = RoadPerceptionMath.fitLine(y, x, x.length);
        assertTrue(fit.valid);
        assertTrue(fit.slope > 0.40f);
        assertTrue(fit.rSquared > 0.99f);
    }

    @Test
    public void convergenciaCompatibleRecibePuntaje() {
        RoadPerceptionMath.Fit left = new RoadPerceptionMath.Fit(-0.48f, 0.67f, 0.98f, true);
        RoadPerceptionMath.Fit right = new RoadPerceptionMath.Fit(0.48f, 0.33f, 0.98f, true);
        assertTrue(RoadPerceptionMath.convergenceScore(left, right) > 0.70f);
    }

    @Test
    public void lineasParalelasNoFormanCorredor() {
        RoadPerceptionMath.Fit left = new RoadPerceptionMath.Fit(-0.04f, 0.30f, 0.98f, true);
        RoadPerceptionMath.Fit right = new RoadPerceptionMath.Fit(0.04f, 0.70f, 0.98f, true);
        assertEquals(0f, RoadPerceptionMath.convergenceScore(left, right), 0.0001f);
    }

    @Test
    public void rechazaAjusteSinVariacionVertical() {
        float[] y = {0.5f, 0.5f, 0.5f};
        float[] x = {0.2f, 0.3f, 0.4f};
        assertFalse(RoadPerceptionMath.fitLine(y, x, x.length).valid);
    }

    @Test
    public void confianzaPremiaCoberturaYConsistencia() {
        float alta = RoadPerceptionMath.confidence(22, 24, 0.9f, 0.95f);
        float baja = RoadPerceptionMath.confidence(5, 24, 0.55f, 0.3f);
        assertTrue(alta > 0.85f);
        assertTrue(baja < 0.40f);
    }
}
