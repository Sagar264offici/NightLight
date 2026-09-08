package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Queue-level random shuffle.
 *
 * RANDOM is deliberately different from Smart Shuffle:
 * it never creates recommendations and never performs network work. It only
 * permutes the tracks already present in the active playback queue.
 *
 * A cycle contains every eligible track exactly once. The current track at
 * activation/manual selection is considered already played for the cycle.
 * Queue mutations preserve that played state by stable track id so adding or
 * removing a track does not unexpectedly replay the whole queue.
 */
public final class NormalShuffleEngine {

    private final List<Track> originalOrder = new ArrayList<>();
    private final List<Integer> working = new ArrayList<>();
    private final Set<String> playedIds = new HashSet<>();
    private final Random rng;

    /** Frontier in the cumulative-sweep permutation. */
    private int position;

    /** Last returned original queue index, used to avoid a cycle boundary repeat. */
    private int lastPlayedOriginalIndex = -1;

    /** Stable id of the last returned track. */
    private String lastPlayedId;

    public NormalShuffleEngine() {
        this(new Random());
    }

    public NormalShuffleEngine(Random random) {
        this.rng = random != null ? random : new Random();
    }

    /**
     * Activates RANDOM on the supplied queue.
     *
     * @param tracks current playback queue
     * @param currentIndex currently playing queue index, or -1 when none is playing
     */
    public synchronized void activate(List<Track> tracks, int currentIndex) {
        replaceQueue(tracks);
        playedIds.clear();

        if (isValidIndex(currentIndex)) {
            Track current = originalOrder.get(currentIndex);
            if (current != null) {
                playedIds.add(key(current, currentIndex));
                lastPlayedOriginalIndex = currentIndex;
                lastPlayedId = current.id;
            }
        } else {
            lastPlayedOriginalIndex = -1;
            lastPlayedId = null;
        }

        rebuildWorking(false);
    }

    /**
     * Returns the next original-queue index.
     *
     * Within a cycle this is the cumulative-sweep algorithm:
     * choose from the unconsumed suffix, swap into the frontier, advance.
     */
    public synchronized int nextIndex() {
        if (originalOrder.isEmpty()) {
            return -1;
        }

        if (position >= working.size()) {
            // A full cycle has completed. Start a fresh permutation.
            playedIds.clear();
            rebuildWorking(true);
        }

        if (working.isEmpty()) {
            // Single-track queue or an otherwise empty eligible set.
            working.clear();
            for (int i = 0; i < originalOrder.size(); i++) {
                working.add(i);
            }
            position = 0;
        }

        int randomIndex = position + rng.nextInt(working.size() - position);
        Collections.swap(working, position, randomIndex);

        int value = working.get(position);
        position++;

        lastPlayedOriginalIndex = value;
        Track selected = originalOrder.get(value);
        lastPlayedId = selected != null ? selected.id : null;
        playedIds.add(key(selected, value));

        return value;
    }

    /** Returns the next original queue index without advancing the permutation. */
    public synchronized int peekIndex() {
        if (originalOrder.isEmpty()) {
            return -1;
        }
        if (position >= working.size()) {
            return -1;
        }
        return working.get(position);
    }

    /**
     * Called after queue mutation. Tracks that were already consumed in the
     * current cycle stay consumed when their stable ids still exist.
     */
    public synchronized void onQueueChanged(List<Track> tracks, int currentIndex) {
        Set<String> oldPlayed = new HashSet<>(playedIds);
        String oldLastId = lastPlayedId;

        replaceQueue(tracks);
        playedIds.clear();

        for (int i = 0; i < originalOrder.size(); i++) {
            Track track = originalOrder.get(i);
            if (oldPlayed.contains(key(track, i))) {
                playedIds.add(key(track, i));
            }
        }

        if (isValidIndex(currentIndex)) {
            Track current = originalOrder.get(currentIndex);
            if (current != null) {
                playedIds.add(key(current, currentIndex));
                lastPlayedOriginalIndex = currentIndex;
                lastPlayedId = current.id;
            }
        } else {
            // Keep the previous last id only if that track still exists.
            lastPlayedId = containsId(oldLastId) ? oldLastId : null;
            lastPlayedOriginalIndex = findId(lastPlayedId);
        }

        rebuildWorking(false);
    }

    /**
     * Marks a manually selected track as played for the current cycle and makes
     * the remaining queue the next shuffle pool.
     */
    public synchronized void selectTrack(int originalIndex) {
        if (!isValidIndex(originalIndex)) {
            return;
        }

        Track selected = originalOrder.get(originalIndex);
        playedIds.add(key(selected, originalIndex));
        lastPlayedOriginalIndex = originalIndex;
        lastPlayedId = selected != null ? selected.id : null;

        rebuildWorking(false);
    }

    public synchronized boolean isActive() {
        return !originalOrder.isEmpty();
    }

    public synchronized void deactivate() {
        originalOrder.clear();
        working.clear();
        playedIds.clear();
        position = 0;
        lastPlayedOriginalIndex = -1;
        lastPlayedId = null;
    }

    public synchronized int size() {
        return originalOrder.size();
    }

    private void replaceQueue(List<Track> tracks) {
        originalOrder.clear();
        if (tracks != null) {
            originalOrder.addAll(tracks);
        }
        working.clear();
        position = 0;
    }

    /**
     * Rebuilds the unplayed pool.
     *
     * @param newCycle when true, avoid starting with the immediately previous
     *                 track where another track exists.
     */
    private void rebuildWorking(boolean newCycle) {
        working.clear();
        position = 0;

        for (int i = 0; i < originalOrder.size(); i++) {
            Track t = originalOrder.get(i);
            if (!playedIds.contains(key(t, i))) {
                working.add(i);
            }
        }

        if (working.isEmpty() && !originalOrder.isEmpty()) {
            // No unplayed tracks remain in this cycle. If there are multiple
            // tracks, start the next cycle while avoiding the previous track.
            playedIds.clear();
            for (int i = 0; i < originalOrder.size(); i++) {
                Track t = originalOrder.get(i);
                if (newCycle && originalOrder.size() > 1 && isSameTrack(t, lastPlayedId)) {
                    continue;
                }
                working.add(i);
            }

            // Defensive fallback for pathological queues where all entries
            // share a missing/duplicate id.
            if (working.isEmpty()) {
                for (int i = 0; i < originalOrder.size(); i++) {
                    working.add(i);
                }
            }
        }
    }

    private boolean isValidIndex(int index) {
        return index >= 0 && index < originalOrder.size();
    }

    private String key(Track track, int index) {
        if (track != null && track.id != null && !track.id.isEmpty()) {
            return "id:" + track.id;
        }
        return "index:" + index;
    }

    private boolean containsId(String id) {
        return id != null && findId(id) >= 0;
    }

    private int findId(String id) {
        if (id == null) return -1;
        for (int i = 0; i < originalOrder.size(); i++) {
            Track t = originalOrder.get(i);
            if (t != null && id.equals(t.id)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSameTrack(Track track, String id) {
        return track != null && id != null && id.equals(track.id);
    }
}
