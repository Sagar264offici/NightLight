using UnityEngine;
using System.Collections.Generic;

/// <summary>
/// Deterministic beat map generator. Uses the track ID hash to create
/// a consistent beat pattern for each song. Beats are placed on musical
/// subdivisions (quarter notes, eighth notes) with rhythmic patterns
/// that feel musical, not random.
/// </summary>
public static class BeatMapGenerator
{
    public struct Beat
    {
        public float timeSeconds;
        public int lane;
        public float holdDuration; // 0 = tap, >0 = hold tile

        public Beat(float time, int lane, float hold = 0f)
        {
            this.timeSeconds = time;
            this.lane = lane;
            this.holdDuration = hold;
        }
    }

    // Musical patterns — sequences of lane assignments
    // Each pattern is a series of (lane, subdivision_offset) pairs
    private static readonly int[][] rhythmicPatterns = {
        new[] { 0, 1, 2, 3 },                    // ascending
        new[] { 3, 2, 1, 0 },                    // descending
        new[] { 0, 2, 1, 3 },                    // zigzag
        new[] { 1, 2, 1, 2 },                    // center bounce
        new[] { 0, 3, 0, 3 },                    // outer bounce
        new[] { 0, 1, 3, 2 },                    // mixed
        new[] { 2, 1, 3, 0 },                    // mixed reverse
        new[] { 0, 0, 1, 1 },                    // doubles
        new[] { 3, 3, 2, 2 },                    // doubles reverse
    };

    // BPM ranges by "energy" derived from tempo hints
    private static readonly float[] bpms = { 90f, 100f, 110f, 120f, 128f, 140f };

    public static List<Beat> Generate(string trackId, float durationMs, int lanes)
    {
        var beats = new List<Beat>();
        float durationSec = durationMs / 1000f;
        if (durationSec <= 0f) return beats;

        // Deterministic seed from track ID
        int seed = trackId != null ? trackId.GetHashCode() : 42;
        var rng = new System.Random(seed);

        // Pick BPM and base pattern from seed
        float bpm = bpms[rng.Next(bpms.Length)];
        float beatInterval = 60f / bpm;           // quarter note
        float halfBeat = beatInterval / 2f;        // eighth note
        float quarterBeat = beatInterval / 4f;     // sixteenth note

        // Intro delay — first beat after 1.5s
        float startTime = 1.5f;
        float endTime = durationSec - 2f; // stop 2s before end
        if (endTime <= startTime) return beats;

        // Select pattern families
        int patternIdx = rng.Next(rhythmicPatterns.Length);
        int[] pattern = rhythmicPatterns[patternIdx];

        float t = startTime;
        int beatInBar = 0;
        int barLength = 4;
        bool useEighthNotes = bpm < 125f; // slower songs use eighths more

        while (t < endTime)
        {
            // Pattern rotation every bar
            if (beatInBar % barLength == 0 && beatInBar > 0)
            {
                patternIdx = rng.Next(rhythmicPatterns.Length);
                pattern = rhythmicPatterns[patternIdx];
            }

            int patternPos = beatInBar % pattern.Length;
            int lane = pattern[patternPos] % lanes;

            // Occasional hold tiles (every ~8th beat, 20% chance)
            float holdDur = 0f;
            if (beatInBar % 8 == 0 && rng.NextDouble() < 0.20)
            {
                holdDur = beatInterval * (1f + rng.Next(3));
            }

            beats.Add(new Beat(t, lane, holdDur));

            // Vary subdivision: sometimes use eighth notes for faster feel
            if (useEighthNotes && rng.NextDouble() < 0.35)
            {
                t += halfBeat;
            }
            else if (rng.NextDouble() < 0.10)
            {
                // Occasional syncopation
                t += quarterBeat;
            }
            else
            {
                t += beatInterval;
            }

            beatInBar++;
        }

        return beats;
    }
}
