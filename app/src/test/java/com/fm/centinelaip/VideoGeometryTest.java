package com.fm.centinelaip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VideoGeometryTest {
    @Test public void portraitVideoInWideViewFillsByGrowingVerticalScale() {
        float[] scale = VideoGeometry.displayScale(1500, 340, 478, 850, true);
        assertEquals(1f, scale[0], 0.0001f);
        assertEquals((1500f / 340f) / (478f / 850f), scale[1], 0.0001f);
    }

    @Test public void portraitVideoInWideViewFitsByReducingHorizontalScale() {
        float[] scale = VideoGeometry.displayScale(1500, 340, 478, 850, false);
        assertEquals(1f, scale[1], 0.0001f);
        assertEquals((478f / 850f) / (1500f / 340f), scale[0], 0.0001f);
    }

    @Test public void wideVideoInTallViewFillsByGrowingHorizontalScale() {
        float[] scale = VideoGeometry.displayScale(500, 1000, 1920, 1080, true);
        assertEquals((1920f / 1080f) / (500f / 1000f), scale[0], 0.0001f);
        assertEquals(1f, scale[1], 0.0001f);
    }

    @Test public void sharedModeControlsTextureScale() {
        DisplayModeStore.setFillScreen(true);
        float[] fill = VideoGeometry.fitScale(1000, 500, 500, 1000);
        assertEquals(1f, fill[0], 0.0001f);
        assertEquals(4f, fill[1], 0.0001f);

        DisplayModeStore.setFillScreen(false);
        float[] fit = VideoGeometry.fitScale(1000, 500, 500, 1000);
        assertEquals(0.25f, fit[0], 0.0001f);
        assertEquals(1f, fit[1], 0.0001f);
    }

    @Test public void scaledSizePreservesAspectAndDoesNotUpscale() {
        assertArrayEquals(new int[]{478, 850}, VideoGeometry.scaledSize(478, 850, 960));
        assertArrayEquals(new int[]{540, 960}, VideoGeometry.scaledSize(1080, 1920, 960));
    }

    @Test public void rotationSwapsDimensions() {
        assertArrayEquals(new int[]{850, 478}, VideoGeometry.orientedSize(478, 850, 90));
        assertArrayEquals(new int[]{478, 850}, VideoGeometry.orientedSize(478, 850, 0));
    }
}
