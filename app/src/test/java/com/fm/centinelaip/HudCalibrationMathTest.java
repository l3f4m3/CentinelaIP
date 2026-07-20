package com.fm.centinelaip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HudCalibrationMathTest {
    @Test public void desplazaHorizonteYPuntoDeFuga() {
        HudCalibrationMath.State start = HudCalibrationMath.State.defaults();
        HudCalibrationMath.State value = HudCalibrationMath.applyGesture(
                start, 0.10f, 0.12f, 1f, 0f);
        assertEquals(0.60f, value.centerFraction, 0.0001f);
        assertEquals(0.55f, value.horizonFraction, 0.0001f);
    }

    @Test public void pellizcoCambiaAnchoSinSalirDeLimites() {
        HudCalibrationMath.State start = HudCalibrationMath.State.defaults();
        HudCalibrationMath.State wide = HudCalibrationMath.applyGesture(
                start, 0f, 0f, 2f, 0f);
        assertEquals(0.49f, wide.nearHalfFraction, 0.0001f);
        assertTrue(wide.farHalfFraction <= 0.22f);

        HudCalibrationMath.State narrow = HudCalibrationMath.applyGesture(
                start, 0f, 0f, 0.2f, 0f);
        assertEquals(0.231f, narrow.nearHalfFraction, 0.001f);
    }

    @Test public void giroSeNormalizaYLimita() {
        HudCalibrationMath.State start = HudCalibrationMath.State.defaults();
        HudCalibrationMath.State value = HudCalibrationMath.applyGesture(
                start, 0f, 0f, 1f, 350f);
        assertEquals(-10f, value.manualRollDegrees, 0.0001f);
    }

    @Test public void estadoSiempreQuedaDentroDeRango() {
        HudCalibrationMath.State value = new HudCalibrationMath.State(
                -5f, 8f, 9f, -2f, 90f);
        assertEquals(0.18f, value.horizonFraction, 0.0001f);
        assertEquals(0.88f, value.centerFraction, 0.0001f);
        assertEquals(0.49f, value.nearHalfFraction, 0.0001f);
        assertEquals(0.015f, value.farHalfFraction, 0.0001f);
        assertEquals(18f, value.manualRollDegrees, 0.0001f);
    }
}