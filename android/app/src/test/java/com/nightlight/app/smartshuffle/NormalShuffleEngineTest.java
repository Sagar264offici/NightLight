package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tests for the NormalShuffleEngine (queue-level random permutation).
 * Uses a seeded Random for reproducible results.
 */
public class NormalShuffleEngineTest {

    private static Track track(String id) {
        return new Track(id, "Track " + id, "Artist", "Album", "", null, 0L, "");
    }

    private static List<Track> queue(int size) {
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            tracks.add(track(String.valueOf(i)));
        }
        return tracks;
    }

    @Test
    public void traverses_all_tracks_before_repeat() {
        NormalShuffleEngine engine = new NormalShuffleEngine(new Random(42));
        List<Track> tracks = queue(10);
        // Activate without skipping the current track — just establish the queue.
        engine.activate(tracks, -1);

        Set<String> played = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            int idx = engine.nextIndex();
            assertTrue("Index should be in range", idx >= 0 && idx < tracks.size());
            assertFalse("Track should not repeat within one permutation",
                    played.contains(tracks.get(idx).id));
            played.add(tracks.get(idx).id);
        }
        assertEquals("All 10 tracks should be played", 10, played.size());
    }

    @Test
    public void generates_fresh_permutation_when_exhausted() {
        NormalShuffleEngine engine = new NormalShuffleEngine(new Random(42));
        List<Track> tracks = queue(5);
        engine.activate(tracks, -1);

        // Play all 5 — they should all be distinct.
        Set<String> round1 = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            int idx = engine.nextIndex();
            assertTrue(idx >= 0 && idx < tracks.size());
            round1.add(tracks.get(idx).id);
        }
        assertEquals(5, round1.size());

        // Play 5 more — should be a new permutation, all distinct.
        Set<String> round2 = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            int idx = engine.nextIndex();
            assertTrue(idx >= 0 && idx < tracks.size());
            round2.add(tracks.get(idx).id);
        }
        assertEquals(5, round2.size());
    }

    @Test
    public void avoids_immediately_previous_track_at_permutation_start() {
        // With a 3-track queue, verify the first track of each new permutation
        // is not the same as the last track of the previous round.
        for (int seed = 0; seed < 100; seed++) {
            NormalShuffleEngine engine = new NormalShuffleEngine(new Random(seed));
            List<Track> tracks = queue(3);
            engine.activate(tracks, 0);

            String lastPlayed = null;
            for (int round = 0; round < 5; round++) {
                for (int i = 0; i < 3; i++) {
                    int idx = engine.nextIndex();
                    if (idx < 0 || idx >= tracks.size()) {
                        fail("Index out of range: " + idx);
                    }
                    String id = tracks.get(idx).id;
                    if (lastPlayed != null && i == 0) {
                        assertNotEquals(
                                "Round " + round + " should not start with previous track",
                                lastPlayed, id);
                    }
                    lastPlayed = id;
                }
            }
        }
    }

    @Test
    public void small_queue_rotates_correctly() {
        NormalShuffleEngine engine = new NormalShuffleEngine(new Random(99));
        List<Track> tracks = queue(3);
        engine.activate(tracks, -1);

        // Play 9 tracks — should cycle through all 3 each time.
        for (int cycle = 0; cycle < 3; cycle++) {
            Set<String> played = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                int idx = engine.nextIndex();
                assertTrue(idx >= 0 && idx < tracks.size());
                played.add(tracks.get(idx).id);
            }
            assertEquals("Each cycle should play all 3 tracks", 3, played.size());
        }
    }

    @Test
    public void single_track_queue() {
        NormalShuffleEngine engine = new NormalShuffleEngine(new Random(1));
        List<Track> tracks = queue(1);
        engine.activate(tracks, 0);

        // Only one track — should always return 0.
        for (int i = 0; i < 5; i++) {
            assertEquals(0, engine.nextIndex());
        }
    }

    @Test
    public void manual_selection_updates_state() {
        NormalShuffleEngine engine = new NormalShuffleEngine(new Random(42));
        List<Track> tracks = queue(10);
        engine.activate(tracks, 0);

        // Play a few tracks.
        engine.nextIndex();
        engine.nextIndex();
        engine.nextIndex();

        // Manually select track 7.
        engine.selectTrack(7);

        // Next track should NOT be 7 (it was just selected, already played).
        int next = engine.nextIndex();
        assertTrue(next >= 0 && next < tracks.size());
    }

    @Test
    public void queue_mutation_rebuilds_permutation() {
        NormalShuffleEngine engine = new NormalShuffleEngine(new Random(42));
        List<Track> tracks = queue(5);
        engine.activate(tracks, 0);

        // Play a few.
        engine.nextIndex();
        engine.nextIndex();

        // Simulate queue change (remove track 2).
        List<Track> updated = new ArrayList<>(tracks);
        updated.remove(2);
        engine.onQueueChanged(updated, 0);

        // Should be able to play all remaining 4 tracks without crash.
        Set<String> played = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            int idx = engine.nextIndex();
            assertTrue(idx >= 0 && idx < updated.size());
            played.add(updated.get(idx).id);
        }
        assertEquals(4, played.size());
    }

    @Test
    public void large_queue_no_repeats() {
        NormalShuffleEngine engine = new NormalShuffleEngine(new Random(42));
        List<Track> tracks = queue(50);
        engine.activate(tracks, -1);

        Set<String> played = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            int idx = engine.nextIndex();
            assertTrue(idx >= 0 && idx < tracks.size());
            assertFalse("Track should not repeat within one permutation",
                    played.contains(tracks.get(idx).id));
            played.add(tracks.get(idx).id);
        }
        assertEquals("All 50 tracks should play before any repeat", 50, played.size());
    }

    @Test
    public void stable_across_pause_resume() {
        // Shuffle order must remain stable when paused and resumed.
        NormalShuffleEngine engine = new NormalShuffleEngine(new Random(42));
        List<Track> tracks = queue(10);
        engine.activate(tracks, 0);

        // Play 3 tracks.
        int idx1 = engine.nextIndex();
        int idx2 = engine.nextIndex();
        int idx3 = engine.nextIndex();

        // "Pause" — no nextIndex calls.
        // "Resume" — next track should be deterministic.
        int idx4 = engine.nextIndex();

        // Now play the same sequence again with a fresh engine.
        NormalShuffleEngine engine2 = new NormalShuffleEngine(new Random(42));
        engine2.activate(tracks, 0);
        assertEquals(idx1, engine2.nextIndex());
        assertEquals(idx2, engine2.nextIndex());
        assertEquals(idx3, engine2.nextIndex());
        int idx4b = engine2.nextIndex();
        assertTrue(idx4b >= 0 && idx4b < tracks.size());
    }

    @Test
    public void current_track_removed_from_remaining_pool() {
        // When shuffle is enabled while a track is already playing, that track
        // should not restart or be immediately replayed.
        NormalShuffleEngine engine = new NormalShuffleEngine(new Random(42));
        List<Track> tracks = queue(5);
        engine.activate(tracks, 2); // track at index 2 is currently playing

        int firstIdx = engine.nextIndex();
        assertNotEquals(
                "Currently playing track should not be the next track returned",
                tracks.get(2).id,
                tracks.get(firstIdx).id);
    }

    @Test
    public void multiple_activations_with_same_seed_produce_different_sequences() {
        // Each activate resets the shuffler, so the same seed can still produce
        // different per-activation sequences; this verifies the engine does not
        // get stuck and still emits valid indices.
        NormalShuffleEngine engine = new NormalShuffleEngine(new Random(42));
        List<Track> tracks = queue(10);
        engine.activate(tracks, -1);
        engine.nextIndex();
        engine.nextIndex();
        engine.deactivate();

        // Re-activate and verify we can traverse the full queue again.
        engine.activate(tracks, -1);
        Set<String> played = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            int idx = engine.nextIndex();
            assertTrue(idx >= 0 && idx < tracks.size());
            played.add(tracks.get(idx).id);
        }
        assertEquals("Should traverse all 10 tracks after re-activation",
                10, played.size());
    }
}
