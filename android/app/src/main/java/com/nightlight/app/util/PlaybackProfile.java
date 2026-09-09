package com.nightlight.app.util;

/**
 * Measurable per-mode playback behavior, derived purely from the mode name
 * (no Android dependencies — unit testable). Storage stays in
 * {@link PowerModes}; behavior lives here so modes cannot silently converge.
 *
 * <ul>
 *   <li>LOW — 1000ms UI ticks, no radio top-up, 160kbps-first streams,
 *       small artwork decodes, dim backdrop, no ambient motion.</li>
 *   <li>BALANCED — 500ms ticks, top-up below 6 remaining, 320kbps-first,
 *       standard artwork, full atmosphere + ambient motion.</li>
 *   <li>HIGH — 250ms ticks, top-up below 10 remaining, 320kbps-first,
 *       large artwork decodes, full atmosphere + ambient motion.</li>
 * </ul>
 */
public final class PlaybackProfile {

    /** Ticker interval driving position UI + threshold checks. */
    public final long tickerMs;
    /** Remaining-queue threshold that triggers a radio top-up; -1 disables. */
    public final int topupThreshold;
    /** True = prefer 320kbps streams; false = prefer 160kbps (data/battery). */
    public final boolean preferHighQuality;
    /** Artwork decode edge in px (memory/CPU bound). */
    public final int artworkDecodePx;
    /** Backdrop atmosphere opacity. */
    public final float backdropAlpha;
    /** Whether the ambient motion system runs. */
    public final boolean ambientMotion;

    private PlaybackProfile(long tickerMs, int topupThreshold, boolean preferHighQuality,
                            int artworkDecodePx, float backdropAlpha, boolean ambientMotion) {
        this.tickerMs = tickerMs;
        this.topupThreshold = topupThreshold;
        this.preferHighQuality = preferHighQuality;
        this.artworkDecodePx = artworkDecodePx;
        this.backdropAlpha = backdropAlpha;
        this.ambientMotion = ambientMotion;
    }

    /** Unknown modes fall back to balanced so behavior is always defined. */
    public static PlaybackProfile forMode(String mode) {
        if (PowerModes.LOW.equals(mode)) {
            return new PlaybackProfile(1000L, -1, false, 480, 0.30f, false);
        }
        if (PowerModes.HIGH.equals(mode)) {
            return new PlaybackProfile(250L, 10, true, 1400, 0.52f, true);
        }
        return new PlaybackProfile(500L, 6, true, 900, 0.52f, true);
    }
}
