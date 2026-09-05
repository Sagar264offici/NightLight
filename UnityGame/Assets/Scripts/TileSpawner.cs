using UnityEngine;
using System.Collections.Generic;

/// <summary>
/// Manages tile lifecycle: spawning from beat map data, positioning
/// in 3D space with perspective, recycling, and destruction with effects.
/// </summary>
public class TileSpawner : MonoBehaviour
{
    [Header("Tile Prefab")]
    public GameObject tilePrefab;
    public GameObject holdTilePrefab;

    [Header("Lane Config")]
    public Transform laneContainer;
    public float laneWidth = 1.2f;
    public float laneGap = 0.08f;
    public float topY = 6f;
    public float hitY = -4f;

    private List<BeatMapGenerator.Beat> beats;
    private List<Tile> activeTiles = new List<Tile>();
    private float travelTime;
    private int laneCount;
    private int nextBeatIndex;

    // Tile colors per lane (Magic Twist vibrant palette)
    private static readonly Color[] laneColors = {
        new Color(1f, 0.23f, 0.36f),      // Red
        new Color(0.23f, 0.56f, 1f),       // Blue
        new Color(0.18f, 0.91f, 0.6f),     // Green
        new Color(1f, 0.85f, 0.29f),       // Yellow
    };

    public void Initialize(List<BeatMapGenerator.Beat> beatMap, float travel, int lanes)
    {
        Clear();
        beats = beatMap;
        travelTime = travel;
        laneCount = lanes;
        nextBeatIndex = 0;
    }

    public void UpdateTiles(float currentTime, float travel)
    {
        // Spawn tiles that are entering the visible range
        while (nextBeatIndex < beats.Count)
        {
            BeatMapGenerator.Beat beat = beats[nextBeatIndex];
            float spawnTime = beat.timeSeconds - travel;

            if (spawnTime > currentTime + 0.5f) break; // not yet

            SpawnTile(beat);
            nextBeatIndex++;
        }

        // Update positions
        for (int i = activeTiles.Count - 1; i >= 0; i--)
        {
            Tile t = activeTiles[i];
            if (t == null || t.gameObject == null)
            {
                activeTiles.RemoveAt(i);
                continue;
            }

            float progress = (currentTime - (t.targetTime - travel)) / travel;
            Vector3 pos = t.transform.localPosition;
            pos.y = Mathf.Lerp(topY, hitY, progress);
            t.transform.localPosition = pos;

            // Scale based on perspective (smaller at top, larger at bottom)
            float scale = Mathf.Lerp(0.4f, 1f, progress);
            t.transform.localScale = Vector3.one * scale;

            // Slight rotation for depth feel
            t.transform.localRotation = Quaternion.Euler(0, 0, Mathf.Sin(progress * Mathf.PI) * 2f);
        }
    }

    public void CheckMisses(float currentTime, float goodWindow, System.Action<Tile> onMiss)
    {
        for (int i = activeTiles.Count - 1; i >= 0; i--)
        {
            Tile t = activeTiles[i];
            if (t != null && t.targetTime + goodWindow < currentTime && !t.hit)
            {
                onMiss?.Invoke(t);
            }
        }
    }

    public Tile GetClosestTile(int lane, float currentTime, float window)
    {
        Tile closest = null;
        float closestDelta = float.MaxValue;

        foreach (Tile t in activeTiles)
        {
            if (t == null || t.lane != lane || t.hit) continue;
            float delta = Mathf.Abs(t.targetTime - currentTime);
            if (delta < window && delta < closestDelta)
            {
                closest = t;
                closestDelta = delta;
            }
        }
        return closest;
    }

    public void DestroyTile(Tile tile, bool wasHit)
    {
        if (tile == null) return;
        activeTiles.Remove(tile);

        if (wasHit)
        {
            // Spawn hit particles
            ParticleSystem ps = tile.GetComponentInChildren<ParticleSystem>();
            if (ps != null)
            {
                var main = ps.main;
                main.startColor = laneColors[tile.lane % laneColors.Length];
                ps.transform.SetParent(null);
                ps.Play();
                Destroy(ps.gameObject, 2f);
            }
        }

        Destroy(tile.gameObject);
    }

    public void Clear()
    {
        foreach (Tile t in activeTiles)
        {
            if (t != null) Destroy(t.gameObject);
        }
        activeTiles.Clear();
        nextBeatIndex = 0;
    }

    void SpawnTile(BeatMapGenerator.Beat beat)
    {
        GameObject prefab = beat.holdDuration > 0 ? holdTilePrefab : tilePrefab;
        if (prefab == null) return;

        float totalWidth = laneCount * (laneWidth + laneGap) - laneGap;
        float startX = -totalWidth / 2f;
        float x = startX + beat.lane * (laneWidth + laneGap) + laneWidth / 2f;

        GameObject obj = Instantiate(prefab, laneContainer);
        obj.transform.localPosition = new Vector3(x, topY, 0);

        Tile tile = obj.GetComponent<Tile>();
        if (tile == null) tile = obj.AddComponent<Tile>();

        tile.lane = beat.lane;
        tile.targetTime = beat.timeSeconds;
        tile.holdDuration = beat.holdDuration;
        tile.hit = false;

        // Set lane color
        Renderer rend = obj.GetComponent<Renderer>();
        if (rend != null)
        {
            rend.material.color = laneColors[beat.lane % laneColors.Length];
        }

        // Scale tile to lane width
        obj.transform.localScale = new Vector3(laneWidth * 0.9f, 0.15f + beat.holdDuration * 0.3f, 1f);

        activeTiles.Add(tile);
    }
}
