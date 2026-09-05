package com.nightlight.app.smartshuffle;

import com.nightlight.app.domain.model.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Smart Shuffle queue planning.
 *
 * Candidates are scored for contextual relevance (mood blending, seed artist /
 * album affinity), then re-weighted so recently played tracks, artists and
 * albums fade while other artists are deliberately introduced. Selection is a
 * weighted random pick — the best track does NOT always win — and diversity
 * bookkeeping happens inline while the queue is built.
 *
 * Pure Java, no network/DB/AI: the music provider's suggestions are the
 * candidate pool; this engine only curates the ordering. Never throws, never
 * returns an emptier queue than the input, and respects injectable randomness
 * for reproducible tests.
 */
public final class SmartShuffleEngine {

    /** Selection temperature: lower = stronger pull toward top scores, less random. */
    private static final double TEMPERATURE = 1.6;
    private static final int MAX_QUEUE = 30;

    private final Random random;

    public SmartShuffleEngine() {
        this(new Random());
    }

    public SmartShuffleEngine(Random random) {
        this.random = random != null ? random : new Random();
    }

    /** Generates an ordered queue from candidates, curating `recent` context. */
    public List<Track> generateQueue(Track seed, List<Track> candidates, List<Track> recent) {
        return generateQueue(seed, candidates, recent, null, 0.18,
                Collections.<String>emptySet(), Collections.<String>emptySet());
    }

    /**
     * Full curation path.
     *
     * @param explicitMood   user-selected mood (strongest signal; null = infer)
     * @param discoveryRatio 0..1 fraction of picks that deliberately wander
     * @param skipArtists    artists the listener skipped this session (weak negative)
     * @param likedIds       liked track ids (small familiarity bonus)
     */
    public List<Track> generateQueue(Track seed, List<Track> candidates, List<Track> recent,
                                     String explicitMood, double discoveryRatio,
                                     Set<String> skipArtists, Set<String> likedIds) {
        List<Track> pool = new ArrayList<>();
        if (candidates != null) {
            pool.addAll(candidates);
        }
        if (pool.isEmpty()) {
            return pool;
        }
        List<Track> recentSafe = recent != null ? recent : new ArrayList<>();
        ListeningContext ctx = explicitMood != null
                ? ListeningContext.explicit(explicitMood, seed)
                : ListeningContext.fromTrack(seed);
        double discovery = Math.max(0.0, Math.min(1.0, discoveryRatio));
        Set<String> skipped = skipArtists != null ? skipArtists : Collections.<String>emptySet();
        Set<String> liked = likedIds != null ? likedIds : Collections.<String>emptySet();

        // Session recency windows (max 8 tracks back).
        Map<String, Integer> recentTracks = new HashMap<>();
        Map<String, Integer> recentArtists = new HashMap<>();
        Map<String, Integer> recentAlbums = new HashMap<>();
        for (int i = Math.max(0, recentSafe.size() - 8); i < recentSafe.size(); i++) {
            Track t = recentSafe.get(i);
            recentTracks.merge(t.id, 1, Integer::sum);
            recentArtists.merge(norm(t.artists), 1, Integer::sum);
            recentAlbums.merge(norm(t.album), 1, Integer::sum);
        }

        List<Track> out = new ArrayList<>();
        Map<String, Integer> inArtists = new HashMap<>();
        Map<String, Integer> inAlbums = new HashMap<>();

        while (!pool.isEmpty()) {
            Track chosen;
            // Discovery picks wander deliberately (uniform), everything else is
            // weighted by score — the best-scoring track does NOT always win.
            if (discovery > 0 && random.nextDouble() < discovery) {
                chosen = pool.remove(random.nextInt(pool.size()));
            } else {
                double[] weights = new double[pool.size()];
                double total = 0;
                for (int i = 0; i < pool.size(); i++) {
                    double w = score(pool.get(i), ctx, recentTracks, recentArtists, recentAlbums,
                            inArtists, inAlbums, skipped, liked, out.size());
                    weights[i] = Math.max(0.0001, w);
                    total += weights[i];
                }
                chosen = pool.remove(pick(weights, total));
            }
            out.add(chosen);
            inArtists.merge(norm(chosen.artists), 1, Integer::sum);
            inAlbums.merge(norm(chosen.album), 1, Integer::sum);
            if (out.size() > 3) { // keep the artist/album window tight
                Track old = out.get(out.size() - 4);
                dec(inArtists, norm(old.artists));
                dec(inAlbums, norm(old.album));
            }
            if (out.size() >= MAX_QUEUE) {
                break;
            }
        }
        return out;
    }

    private double score(Track t, ListeningContext ctx,
                         Map<String, Integer> recentTracks,
                         Map<String, Integer> recentArtists,
                         Map<String, Integer> recentAlbums,
                         Map<String, Integer> inArtists,
                         Map<String, Integer> inAlbums,
                         Set<String> skipArtists,
                         Set<String> likedIds,
                         int position) {
        String title = ListeningContext.norm(t.name);
        String artist = norm(t.artists);
        String album = norm(t.album);

        // Mood: how well the candidate's own detected mood blends with ours.
        double mood = 0;
        if (ctx.primaryMood != null) {
            String cand = ListeningContext.detectMood(title + " " + artist + " " + album);
            Map<String, Double> blend = ListeningContext.BLENDS.get(ctx.primaryMood);
            Double value = cand != null && blend != null ? blend.get(cand) : null;
            if (value != null) {
                mood = value * (0.35 + 0.65 * ctx.moodConfidence);
            }
        }
        double seedArtist = artist.equals(norm(ctx.seedArtist)) ? 0.20 : 0;
        double seedAlbum = album.equals(norm(ctx.seedAlbum)) ? 0.12 : 0;
        double like = likedIds.contains(t.id) ? 0.12 : 0;
        double relevance = Math.min(1.0, 0.45 * mood + seedArtist + seedAlbum + like + 0.15);

        // Anti-repetition penalties.
        double penalty = 0;
        if (recentTracks.containsKey(t.id)) {
            penalty += 0.9;
        }
        int ar = recentArtists.getOrDefault(artist, 0) + inArtists.getOrDefault(artist, 0);
        if (ar > 0) {
            penalty += 0.40 * Math.min(2, ar);
        }
        int al = recentAlbums.getOrDefault(album, 0) + inAlbums.getOrDefault(album, 0);
        if (al > 0) {
            penalty += 0.20 * Math.min(2, al);
        }
        // Repeated skips this session are a weak negative signal for the artist.
        if (skipArtists.contains(artist)) {
            penalty += 0.30;
        }
        // Artist/album cooldowns relax as the queue fills.
        double cool = Math.max(0.22, 1.0 - position * 0.025);
        return Math.pow(Math.max(0.02, relevance - penalty) * cool, TEMPERATURE) + 0.0001;
    }

    private int pick(double[] weights, double total) {
        double r = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r <= 0) {
                return i;
            }
        }
        return weights.length - 1;
    }

    private static void dec(Map<String, Integer> map, String key) {
        map.computeIfPresent(key, (k, v) -> v > 1 ? v - 1 : null);
    }

    private static String norm(String raw) {
        return ListeningContext.norm(raw);
    }
}
