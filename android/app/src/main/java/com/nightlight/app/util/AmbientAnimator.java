package com.nightlight.app.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * The NightLight ambient motion system. One shared animator drives the
 * atmospheric "breathing" of a screen — artwork scale, ambient glow, backdrop
 * shift — with three visible intensity levels from {@link PowerModes}.
 *
 * <p>LOW: barely-there drift. BALANCED: soft breathing. HIGH: full cinematic
 * motion (deeper breathing + glow pulse). Everything is time-based
 * (ValueAnimator), repeats gently, and is fully lifecycle-safe: callers must
 * call {@link #start} when the screen becomes visible and {@link #stop} when it
 * hides or is destroyed — the animator then releases its target references so
 * nothing leaks.</p>
 */
public final class AmbientAnimator {

    private final List<Animator> animators = new ArrayList<>();
    private final List<View> targets = new ArrayList<>();
    private boolean running;

    private AmbientAnimator() {
    }

    /**
     * Builds the ambient set for the given power mode around the main artwork
     * and its backdrop. Views may be null — they are skipped.
     */
    public static AmbientAnimator forNowPlaying(String mode, View artwork, View backdrop, View glow) {
        AmbientAnimator a = new AmbientAnimator();
        long period = isHigh(mode) ? 4200 : 6000;

        if (artwork != null) {
            // Breathing artwork: subtle scale, deeper in HIGH.
            float to = isHigh(mode) ? 1.045f : isBalanced(mode) ? 1.022f : 1.008f;
            a.targets.add(artwork);
            ObjectAnimator sx = ObjectAnimator.ofFloat(artwork, View.SCALE_X, 1f, to);
            ObjectAnimator sy = ObjectAnimator.ofFloat(artwork, View.SCALE_Y, 1f, to);
            for (ObjectAnimator an : new ObjectAnimator[]{sx, sy}) {
                an.setDuration(period);
                an.setRepeatCount(ValueAnimator.INFINITE);
                an.setRepeatMode(ValueAnimator.REVERSE);
                an.setInterpolator(new AccelerateDecelerateInterpolator());
                a.animators.add(an);
            }
        }
        if (backdrop != null && !isLow(mode)) {
            // Slow ambient light drift on the blurred backdrop.
            float from = isHigh(mode) ? 0.38f : 0.46f;
            float to = isHigh(mode) ? 0.66f : 0.58f;
            a.targets.add(backdrop);
            ObjectAnimator alpha = ObjectAnimator.ofFloat(backdrop, View.ALPHA, from, to);
            alpha.setDuration(period * 2);
            alpha.setRepeatCount(ValueAnimator.INFINITE);
            alpha.setRepeatMode(ValueAnimator.REVERSE);
            alpha.setInterpolator(new AccelerateDecelerateInterpolator());
            a.animators.add(alpha);
        }
        if (glow != null && isHigh(mode)) {
            // High mode only: slow glow pulse.
            a.targets.add(glow);
            ObjectAnimator glowA = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.35f, 0.95f);
            glowA.setDuration(2600);
            glowA.setRepeatCount(ValueAnimator.INFINITE);
            glowA.setRepeatMode(ValueAnimator.REVERSE);
            glowA.setInterpolator(new AccelerateDecelerateInterpolator());
            a.animators.add(glowA);
        }
        if (artwork != null && isHigh(mode)) {
            // High mode only: slow parallax drift so the composition never
            // sits perfectly still. Deliberately small (±7px) — atmosphere,
            // not distraction.
            ObjectAnimator dx = ObjectAnimator.ofFloat(artwork, View.TRANSLATION_X, -7f, 7f);
            dx.setDuration(5600);
            dx.setRepeatCount(ValueAnimator.INFINITE);
            dx.setRepeatMode(ValueAnimator.REVERSE);
            dx.setInterpolator(new AccelerateDecelerateInterpolator());
            ObjectAnimator dy = ObjectAnimator.ofFloat(artwork, View.TRANSLATION_Y, -5f, 5f);
            dy.setDuration(7100);
            dy.setRepeatCount(ValueAnimator.INFINITE);
            dy.setRepeatMode(ValueAnimator.REVERSE);
            dy.setInterpolator(new AccelerateDecelerateInterpolator());
            a.animators.add(dx);
            a.animators.add(dy);
        }
        return a;
    }

    /** Gently brings a view in with a mode-dependent flourish (entrances). */
    public static void enter(View v, String mode) {
        if (v == null) {
            return;
        }
        if (isLow(mode)) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(220).start();
        } else {
            float overshoot = isHigh(mode) ? 1.12f : 1.05f;
            v.setAlpha(0f);
            v.setScaleX(0.94f);
            v.setScaleY(0.94f);
            v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(isHigh(mode) ? 460 : 340)
                    .setInterpolator(new OvershootInterpolator(overshoot))
                    .start();
        }
    }

    /** Starts (or restarts) all animations. Safe to call repeatedly. */
    public void start() {
        if (running || animators.isEmpty()) {
            return;
        }
        running = true;
        for (Animator a : animators) {
            a.start();
        }
    }

    /** Cancels everything and releases target references (no leaks). */
    public void stop() {
        running = false;
        for (Animator a : animators) {
            a.cancel();
        }
        animators.clear();
        // Reset visual state so a paused screen never freezes mid-breathe.
        for (View v : targets) {
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setTranslationX(0f);
            v.setTranslationY(0f);
        }
        targets.clear();
    }

    public boolean isRunning() {
        return running;
    }

    private static boolean isHigh(String m) {
        return PowerModes.HIGH.equals(m);
    }

    private static boolean isBalanced(String m) {
        return PowerModes.BALANCED.equals(m);
    }

    private static boolean isLow(String m) {
        return PowerModes.LOW.equals(m);
    }
}
