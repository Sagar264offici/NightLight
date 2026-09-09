package com.nightlight.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Power modes must behave measurably differently — these tests pin the
 * per-mode behavior contract so the modes cannot silently converge again.
 */
public class PlaybackProfileTest {

    @Test
    public void low_savesResources() {
        PlaybackProfile low = PlaybackProfile.forMode(PowerModes.LOW);
        assertEquals(1000L, low.tickerMs);
        assertEquals(-1, low.topupThreshold);
        assertFalse(low.preferHighQuality);
        assertEquals(480, low.artworkDecodePx);
        assertFalse(low.ambientMotion);
    }

    @Test
    public void balanced_isDefault() {
        PlaybackProfile balanced = PlaybackProfile.forMode(PowerModes.BALANCED);
        assertEquals(500L, balanced.tickerMs);
        assertEquals(6, balanced.topupThreshold);
        assertTrue(balanced.preferHighQuality);
        assertEquals(900, balanced.artworkDecodePx);
        assertTrue(balanced.ambientMotion);
    }

    @Test
    public void high_prefetchesDeeperAndTicksFaster() {
        PlaybackProfile high = PlaybackProfile.forMode(PowerModes.HIGH);
        assertEquals(250L, high.tickerMs);
        assertEquals(10, high.topupThreshold);
        assertTrue(high.preferHighQuality);
        assertEquals(1400, high.artworkDecodePx);
        assertTrue(high.ambientMotion);
    }

    @Test
    public void modes_arePairwiseDistinct() {
        PlaybackProfile low = PlaybackProfile.forMode(PowerModes.LOW);
        PlaybackProfile balanced = PlaybackProfile.forMode(PowerModes.BALANCED);
        PlaybackProfile high = PlaybackProfile.forMode(PowerModes.HIGH);
        // Every mode differs from every other in at least tick cadence,
        // prefetch behavior and stream-quality preference.
        assertTrue(low.tickerMs != balanced.tickerMs && balanced.tickerMs != high.tickerMs);
        assertTrue(low.topupThreshold != balanced.topupThreshold
                || low.preferHighQuality != balanced.preferHighQuality);
        assertTrue(high.topupThreshold != balanced.topupThreshold
                || high.tickerMs != balanced.tickerMs);
    }

    @Test
    public void unknownMode_fallsBackToBalanced() {
        PlaybackProfile unknown = PlaybackProfile.forMode("turbo");
        PlaybackProfile balanced = PlaybackProfile.forMode(PowerModes.BALANCED);
        assertEquals(balanced.tickerMs, unknown.tickerMs);
        assertEquals(balanced.topupThreshold, unknown.topupThreshold);
        assertEquals(balanced.preferHighQuality, unknown.preferHighQuality);
    }

    @Test
    public void nullMode_fallsBackToBalanced() {
        assertEquals(500L, PlaybackProfile.forMode(null).tickerMs);
    }
}
