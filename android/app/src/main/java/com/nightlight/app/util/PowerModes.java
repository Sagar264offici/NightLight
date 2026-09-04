package com.nightlight.app.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Playback power & experience mode — a device-local preference that tunes how
 * much the player invests in extras (artwork size, ambient motion, radio
 * pre-fetching) against battery and data.
 *
 * <ul>
 *   <li>{@code low}      — Ambient screen: minimal decoration, radio off at queue end.</li>
 *   <li>{@code balanced} — Default: rounded album art, smooth playback, radio continue on.</li>
 *   <li>{@code high}     — Maximum richness: larger artwork, subtle breathing motion.</li>
 * </ul>
 */
public final class PowerModes {

    public static final String LOW = "low";
    public static final String BALANCED = "balanced";
    public static final String HIGH = "high";
    public static final String DEFAULT = BALANCED;

    private static final String PREFS = "nightlight_ui";
    private static final String KEY = "power_mode";

    private PowerModes() {
    }

    public static String get(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY, DEFAULT);
        return isKnown(mode) ? mode : DEFAULT;
    }

    public static void set(Context context, String mode) {
        if (!isKnown(mode)) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, mode)
                .apply();
    }

    public static boolean isLow(Context context) {
        return LOW.equals(get(context));
    }

    public static boolean isHigh(Context context) {
        return HIGH.equals(get(context));
    }

    private static boolean isKnown(String mode) {
        return LOW.equals(mode) || BALANCED.equals(mode) || HIGH.equals(mode);
    }
}
