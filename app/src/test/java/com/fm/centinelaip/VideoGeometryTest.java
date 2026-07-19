package com.fm.centinelaip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VideoGeometryTest {
    @Test public void portraitVideoInsideWideViewReducesHorizontalScale() {
        float[] scale = VideoGeometry.fitScale(1500, 340, 478, 850);
        assertEquals(1f, scale[1], 0.0001f);
        assertEquals((478f / 850f) / (1500f / 340f), scale[0], 0.0001f);
    }

    @Test public void wideVideoInsideTallViewReducesVerticalScale() {
        float[] scale = VideoGeometry.fitScale(500, 1000, 1920, 1080);
        assertEquals(1f, scale[0], 0.0001f);
        assertEquals((500f / 1000f) / (1920f / 1080f), scale[1], 0.0001f);
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
