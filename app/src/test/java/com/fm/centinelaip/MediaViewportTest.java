package com.fm.centinelaip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MediaViewportTest {
    @Test public void videoVerticalSeCentraSinInvadirMargenes() {
        assertArrayEquals(new float[]{375f, 0f, 625f, 500f},
                MediaViewport.fitCenter(1000, 500, 500, 1000), 0.01f);
    }

    @Test public void videoHorizontalOcupaTodaLaVistaCompatible() {
        assertArrayEquals(new float[]{0f, 0f, 1000f, 500f},
                MediaViewport.fitCenter(1000, 500, 1920, 960), 0.01f);
    }

    @Test public void fuenteCuadradaGeneraMargenesLaterales() {
        assertArrayEquals(new float[]{250f, 0f, 750f, 500f},
                MediaViewport.fitCenter(1000, 500, 800, 800), 0.01f);
    }

    @Test public void dimensionesInvalidasNoProducenViewport() {
        float[] value = MediaViewport.fitCenter(1000, 500, 0, 800);
        assertFalse(MediaViewport.valid(value));
        assertTrue(MediaViewport.valid(new float[]{1f, 2f, 3f, 4f}));
    }
}