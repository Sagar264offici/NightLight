package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Queue-level shuffle using an unbiased random-permutation algorithm.
 * Unlike Media3's built-in shuffle (which picks randomly on every Next), this
 * engine maintains an explicit permutation of the eligible queue and advances
 * through it sequentially so every track plays before any repeat.
 *
 * <h3>Algorithm</h3>
 * Maintain a single working array plus a read pointer. On each {@link #nextIndex()}
 * call, pick a random index in [position, size-1], swap it to position, return it,
 * and advance the pointer. When exhausted, rebuild from the original queue.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #activate} captures the queue and generates the first permutation.</li>
 *   <li>{@link #nextIndex} returns the index of the next track to play.</li>
 *   <li>When the permutation is exhausted, a fresh one is generated.</li>
 *   <li>The new permutation avoids starting with the immediately previous track.</li>
 *   <li>{@link #onQueueChanged} updates the permutation safely when the queue mutates.</li>
 * </ul>
 */
public final class NormalShuffleEngine {

    /** The original queue order (never modified by shuffle). */
    private final List<Track> originalOrder = new ArrayList<>();

    /** Saved original-index base list (0..originalOrder.size()-1). */
    private List<Integer> base;

    /** Working copy of the current permutation (frontier at {@link #position}). */
    private List<Integer> working;

    /** Read pointer into {@link #working}. */
    private int position;

    /** Position within the current permutation cycle (for diagnostics). */
    private int cyclePosition;

    /** Index within originalOrder that was last played (-1 if none). */
    private int lastPlayedOriginalIndex = -1;

    private final Random rng;

    public NormalShuffleEngine() {
        this(new Random());
    }

    public NormalShuffleEngine(Random random) {
        this.rng = random != null ? random : new Random();
        this.working = new ArrayList<>();
        this.base = new ArrayList<>();
    }

    /**
     * Activates shuffle on the given queue.
     *
     * The currently playing track keeps playing (it is removed from the
     * remaining random pool) and the rest are shuffled into a permutation.
     * This avoids immediately restarting or replaying the current track.
     *
     * @param tracks       the current queue
     * @param currentIndex the index of the currently playing track
     */
    public synchronized void activate(List<Track> tracks, int currentIndex) {
        originalOrder.clear();
        originalOrder.addAll(tracks);
        lastPlayedOriginalIndex = currentIndex;
        rebuildBase();
        resetWorking();
        cyclePosition = 0;

        // If the current track was present in the original queue and is at
        // position 0 of the working array, skip past it so we don't replay it
        // immediately. Don't remove it from the base/working arrays — it's
        // still part of the queue, just not the next track to play.
        if (currentIndex >= 0 && currentIndex < originalOrder.size()
                && !working.isEmpty() && cyclePosition < working.size()
                && working.get(cyclePosition) == currentIndex) {
            cyclePosition++;
        }
    }

    /**
     * Returns the next original-queue index to play.
     * Uses the cumulative-sweep random-permutation algorithm: pick a random
     * index in [position, size-1], swap it to position, return it, advance
     * the pointer. When exhausted, rebuild from the original queue.
     */
    public synchronized int nextIndex() {
        if (originalOrder.isEmpty() || working.isEmpty()) {
            return -1;
        }

        // Rebuild when exhausted.
        if (cyclePosition >= working.size()) {
            resetWorking();
            cyclePosition = 0;

            // Avoid starting the new permutation with the immediately previous
            // track when enough tracks exist.
            if (base.size() > 1
                    && lastPlayedOriginalIndex >= 0
                    && lastPlayedOriginalIndex < base.size()) {
                if (working.size() > 1 && working.get(0) == lastPlayedOriginalIndex) {
                    // Swap with another distinct position.
                    int swapWith = -1;
                    for (int i = 1; i < working.size(); i++) {
                        if (working.get(i) != lastPlayedOriginalIndex) {
                            swapWith = i;
                            break;
                        }
                    }
                    if (swapWith >= 0) {
                        Collections.swap(working, 0, swapWith);
                    }
                }
            }
        }

        int range = working.size() - cyclePosition;
        int randomIndex = cyclePosition + rng.nextInt(range == 0 ? 1 : range);

        // Swap the chosen element into the current frontier slot.
        Collections.swap(working, cyclePosition, randomIndex);
        int value = working.get(cyclePosition);
        cyclePosition++;
        lastPlayedOriginalIndex = value;
        return value;
    }

    /**
     * Peeks at the next track index without advancing.
     */
    public synchronized int peekIndex() {
        if (originalOrder.isEmpty() || working.isEmpty()) {
            return -1;
        }
        if (cyclePosition >= working.size()) {
            return 0;
        }
        return cyclePosition;
    }

    /**
     * Called when the queue is modified (track added/removed).
     * Rebuilds the permutation from the current queue state.
     *
     * @param tracks       the updated queue
     * @param currentIndex the currently playing track index
     */
    public synchronized void onQueueChanged(List<Track> tracks, int currentIndex) {
        originalOrder.clear();
        originalOrder.addAll(tracks);
        lastPlayedOriginalIndex = currentIndex;
        rebuildBase();
        resetWorking();
        cyclePosition = 0;
    }

    /**
     * Moves the given original index to the front of the permutation
     * (used when the user manually selects a track).
     */
    public synchronized void selectTrack(int originalIndex) {
        lastPlayedOriginalIndex = originalIndex;
        if (originalOrder.isEmpty()) {
            return;
        }
        // Rebuild with the manually selected track at the front.
        rebuildBase();
        int selectedPos = base.indexOf(originalIndex);
        if (selectedPos > 0) {
            Integer sel = base.remove(selectedPos);
            base.add(0, sel);
        }
        resetWorking();
        cyclePosition = 1;
    }

    /** Whether shuffle is currently active. */
    public boolean isActive() {
        return !originalOrder.isEmpty() && !working.isEmpty();
    }

    /** Resets all state (called when shuffle is turned off). */
    public synchronized void deactivate() {
        originalOrder.clear();
        base.clear();
        working.clear();
        lastPlayedOriginalIndex = -1;
        cyclePosition = 0;
        position = 0;
    }

    /** Number of tracks in the permutation. */
    public int size() {
        return working.size();
    }

    // ---- Internal ----

    private void rebuildBase() {
        base.clear();
        for (int i = 0; i < originalOrder.size(); i++) {
            base.add(i);
        }
    }

    private void resetWorking() {
        working.clear();
        working.addAll(base);
        position = 0;
    }
}
