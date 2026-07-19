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
