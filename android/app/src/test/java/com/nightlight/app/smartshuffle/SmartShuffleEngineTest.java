package com.nightlight.app.smartshuffle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nightlight.app.domain.model.Track;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Behavioral tests for the Smart Shuffle engine. Selection is stochastic by
 * design (weighted random), so statistical assertions are used instead of
 * single deterministic outcomes, and production randomness is injectable.
 */
public class SmartShuffleEngineTest {

    private static Track track(String id, String name, String artist, String album) {
        return new Track(id, name, artist, album, "", null, 200_000L, "2024");
    }

    private static List<Track> loveTracks(int n) {
        String[] titles = {"Love You Forever", "Dil Se", "Jaan-e-Man", "Ishq Wala Love",
                "Mera Dil", "Tere Bina", "Pyaar Ka Safar", "Mohabbat", "Heartbeat", "Tu Hai"};
        String[] artists = {"Artist Love 1", "Artist Love 2", "Artist Love 3", "Artist Love 4", "Artist Love 5"};
        List<Track> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(track("love" + i, titles[i % titles.length],
                    artists[i % artists.length], "Love Album " + (i % 3)));
        }
        return out;
    }

    private static List<Track> rockTracks(int n) {
        String[] titles = {"Midnight Drive", "Neon City", "Concrete Jungle", "Iron Sky",
                "Desert Run", "Static", "Glass House", "Paper Walls", "Hollow", "Echo Chamber"};
        String[] artists = {"Band 1", "Band 2", "Band 3", "Band 4", "Band 5"};
        List<Track> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(track("rock" + i, titles[i % titles.length],
                    artists[i % artists.length], "Rock Album " + (i % 3)));
        }
        return out;
    }

    private static int loveCount(List<Track> queue) {
        int count = 0;
        for (Track t : queue) {
            if (t.id.startsWith("love")) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void emptyCandidates_returnsEmpty() {
        SmartShuffleEngine engine = new SmartShuffleEngine(new Random(1));
        assertEquals(0, engine.generateQueue(null, null, null).size());
        assertEquals(0, engine.generateQueue(track("s", "Song", "A", "Alb"), Collections.<Track>emptyList(), null).size());
    }

    @Test
    public void seededRandom_isDeterministic() {
        SmartShuffleEngine engine1 = new SmartShuffleEngine(new Random(123));
        SmartShuffleEngine engine2 = new SmartShuffleEngine(new Random(123));
        List<Track> pool = new ArrayList<>();
        pool.addAll(loveTracks(10));
        Track seed = track("seed", "Dil Se", "Artist Love 1", "Love Album 0");

        List<Track> q1 = engine1.generateQueue(seed, pool, null);
        List<Track> q2 = engine2.generateQueue(seed, pool, null);
        assertEquals(q1.size(), q2.size());
        for (int i = 0; i < q1.size(); i++) {
            assertEquals(q1.get(i).id, q2.get(i).id);
        }
    }

    @Test
    public void loveContext_stronglyPrefersLoveTracks() {
        // 10 love + 10 rock candidates, LOVE seed: Smart Shuffle must
        // statistically favor love-compatible music, not random choice.
        List<Track> pool = new ArrayList<>();
        pool.addAll(loveTracks(10));
        pool.addAll(rockTracks(10));
        Track seed = track("seed", "Dil Se", "Artist Love 1", "Love Album 0");

        int totalLove = 0;
        int totalPicks = 0;
        for (int run = 0; run < 30; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null);
            List<Track> firstTen = queue.size() > 10 ? queue.subList(0, 10) : queue;
            totalLove += loveCount(firstTen);
            totalPicks += firstTen.size();
        }
        // Strong preference (spec: NOT a coin flip). Random selection would
        // land near 50%; artist/album diversity pulls some picks off-mood,
        // which is intended — but the context must still dominate.
        assertTrue("love picks: " + totalLove + "/" + totalPicks,
                totalLove >= totalPicks * 0.60);
    }

    @Test
    public void sameArtist_doesNotDominate() {
        // 6 tracks, 3 artists — the same artist must never appear 3x in a row.
        List<Track> pool = Arrays.asList(
                track("a1", "Dil Se", "Artist A", "Alb"),
                track("a2", "Tere Bina", "Artist A", "Alb2"),
                track("b1", "Jaan-e-Man", "Artist B", "Alb"),
                track("b2", "Mera Dil", "Artist B", "Alb2"),
                track("c1", "Pyaar Ka Safar", "Artist C", "Alb"),
                track("c2", "Mohabbat", "Artist C", "Alb2"));
        Track seed = track("seed", "Dil Se", "Artist A", "Alb");

        for (int run = 0; run < 30; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null);
            assertEquals(6, queue.size());
            for (int i = 2; i < queue.size(); i++) {
                boolean threeInARow = queue.get(i).artists.equals(queue.get(i - 1).artists)
                        && queue.get(i).artists.equals(queue.get(i - 2).artists);
                assertFalse("artist run of 3 at " + i + " run " + run, threeInARow);
            }
        }
    }

    @Test
    public void recentlyPlayedTrack_isDeprioritized() {
        Track played = track("love1", "Ishq Wala Love", "Artist Love 2", "Love Album 1");
        List<Track> pool = loveTracks(6);
        pool.add(played);
        Track seed = track("seed", "Dil Se", "Artist Love 1", "Love Album 0");

        int earlyAppearances = 0;
        int runs = 20;
        for (int run = 0; run < runs; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, Collections.singletonList(played));
            if (queue.indexOf(played) <= 2) {
                earlyAppearances++;
            }
        }
        // Recently played must almost never land in the first three slots.
        assertTrue("early appearances: " + earlyAppearances, earlyAppearances <= runs / 5);
    }

    @Test
    public void explicitMood_overridesAmbiguousInference() {
        // Seed with a neutral title; explicit LOVE should still favor love picks.
        List<Track> pool = new ArrayList<>();
        pool.addAll(loveTracks(8));
        pool.addAll(rockTracks(8));
        Track neutralSeed = track("seed", "Sunset Drive", "Band 2", "Rock Album 1");

        int totalLove = 0;
        int totalPicks = 0;
        for (int run = 0; run < 15; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(neutralSeed, pool, null,
                            ListeningContext.LOVE, 0.0,
                            Collections.<String>emptySet(), Collections.<String>emptySet());
            List<Track> firstSix = queue.size() > 6 ? queue.subList(0, 6) : queue;
            totalLove += loveCount(firstSix);
            totalPicks += firstSix.size();
        }
        assertTrue("explicit love picks: " + totalLove + "/" + totalPicks,
                totalLove >= totalPicks * 0.6);
    }

    @Test
    public void skippedArtist_losesRankingThisSession() {
        List<Track> pool = new ArrayList<>();
        String[] skipTitles = {"Dil Se", "Tere Bina", "Jaan-e-Man", "Mera Dil"};
        for (int i = 0; i < 4; i++) {
            pool.add(track("s" + i, skipTitles[i], "Skipped Artist", "Alb"));
        }
        String[] keepTitles = {"Pyaar Ka Safar", "Mohabbat", "Heartbeat", "Tu Hai"};
        for (int i = 0; i < 4; i++) {
            pool.add(track("k" + i, keepTitles[i], "Kept Artist", "Alb2"));
        }
        Track seed = track("seed", "Dil Se", "Skipped Artist", "Alb");
        Set<String> skips = new HashSet<>(Collections.singletonList("Skipped Artist"));

        int skippedFirst = 0;
        int runs = 25;
        for (int run = 0; run < runs; run++) {
            List<Track> queue = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null,
                            ListeningContext.LOVE, 0.0, skips, Collections.<String>emptySet());
            if (!queue.isEmpty() && queue.get(0).artists.equals("Skipped Artist")) {
                skippedFirst++;
            }
        }
        // The skipped artist should rarely win the first slot even though the
        // seed is one of their songs (weak negative signal during the session).
        assertTrue("skipped artist first: " + skippedFirst + "/" + runs, skippedFirst <= runs / 3);
    }

    @Test
    public void likedTrack_getsFamiliarityBonus() {
        // A like is a small familiarity bonus — it must shift ranking upward,
        // not guarantee "play next". Compare average position with and without.
        List<Track> pool = loveTracks(6);
        Track liked = pool.get(3);
        Track seed = track("seed", "Dil Se", "Artist Love 1", "Love Album 0");
        Set<String> likedIds = new HashSet<>(Collections.singletonList(liked.id));

        int runs = 40;
        double avgWith = 0;
        double avgWithout = 0;
        for (int run = 0; run < runs; run++) {
            List<Track> withBonus = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null,
                            ListeningContext.LOVE, 0.0,
                            Collections.<String>emptySet(), likedIds);
            List<Track> withoutBonus = new SmartShuffleEngine(new Random(run))
                    .generateQueue(seed, pool, null,
                            ListeningContext.LOVE, 0.0,
                            Collections.<String>emptySet(), Collections.<String>emptySet());
            avgWith += withBonus.indexOf(liked);
            avgWithout += withoutBonus.indexOf(liked);
        }
        avgWith /= runs;
        avgWithout /= runs;
        assertTrue("avg position with=" + avgWith + " without=" + avgWithout,
                avgWith < avgWithout);
    }

    @Test
    public void nullSeedAndNullRecents_areSafe() {
        List<Track> pool = loveTracks(4);
        List<Track> queue = new SmartShuffleEngine(new Random(9))
                .generateQueue(null, pool, null);
        assertEquals(4, queue.size());
        List<Track> queue2 = new SmartShuffleEngine(new Random(9))
                .generateQueue(null, pool, null);
        assertEquals(queue.get(0).id, queue2.get(0).id);
    }
}