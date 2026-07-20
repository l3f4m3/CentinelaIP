package com.fm.centinelaip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MediaViewportTest {
    @Test public void videoVerticalSeCentraEnModoAjustar() {
        assertArrayEquals(new float[]{375f, 0f, 625f, 500f},
                MediaViewport.fitCenter(1000, 500, 500, 1000), 0.01f);
    }

    @Test public void videoVerticalLlenaLaVistaConRecorteCentrado() {
        assertArrayEquals(new float[]{0f, -750f, 1000f, 1250f},
                MediaViewport.centerCrop(1000, 500, 500, 1000), 0.01f);
    }

    @Test public void modoActualRespetaElEstadoCompartido() {
        DisplayModeStore.setFillScreen(true);
        assertArrayEquals(new float[]{0f, -750f, 1000f, 1250f},
                MediaViewport.current(1000, 500, 500, 1000), 0.01f);
        assertArrayEquals(new float[]{0f, 0f, 1000f, 500f},
                MediaViewport.visible(1000, 500, 500, 1000), 0.01f);

        DisplayModeStore.setFillScreen(false);
        assertArrayEquals(new float[]{375f, 0f, 625f, 500f},
                MediaViewport.current(1000, 500, 500, 1000), 0.01f);
    }

    @Test public void videoHorizontalCompatibleOcupaTodaLaVista() {
        assertArrayEquals(new float[]{0f, 0f, 1000f, 500f},
                MediaViewport.fitCenter(1000, 500, 1920, 960), 0.01f);
        assertArrayEquals(new float[]{0f, 0f, 1000f, 500f},
                MediaViewport.centerCrop(1000, 500, 1920, 960), 0.01f);
    }

    @Test public void dimensionesInvalidasNoProducenViewport() {
        float[] value = MediaViewport.fitCenter(1000, 500, 0, 800);
        assertFalse(MediaViewport.valid(value));
        assertTrue(MediaViewport.valid(new float[]{1f, 2f, 3f, 4f}));
    }
}
