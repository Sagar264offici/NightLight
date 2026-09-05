using UnityEngine;
using UnityEngine.UI;
using System.Collections;
using System.Collections.Generic;

/// <summary>
/// Main game controller. Manages game state, coordinates tile spawning,
/// audio sync, scoring, and twist effects. Receives commands from Android
/// via UnityPlayer.UnitySendMessage and sends events back via
/// AndroidJavaObject.
/// </summary>
public class GameEngine : MonoBehaviour
{
    public static GameEngine Instance { get; private set; }

    [Header("References")]
    public TileSpawner tileSpawner;
    public TwistEffect twistEffect;
    public ScoreManager scoreManager;
    public AudioManager audioManager;
    public UIManager uiManager;
    public HitZone hitZone;

    [Header("Game Config")]
    public int laneCount = 4;
    public float travelTime = 2.3f;
    public float tileHeight = 0.8f;

    // Timing windows (seconds)
    public float perfectWindow = 0.08f;
    public float greatWindow = 0.135f;
    public float goodWindow = 0.21f;

    public enum GameState { Idle, Playing, Paused, GameOver }
    public GameState State { get; private set; } = GameState.Idle;

    private List<BeatMapGenerator.Beat> currentBeats = new List<BeatMapGenerator.Beat>();
    private string currentTrackId;
    private float trackDurationMs;

    void Awake()
    {
        Instance = this;
    }

    void Start()
    {
        // Auto-discover components if not set via inspector
        if (tileSpawner == null) tileSpawner = FindObjectOfType<TileSpawner>();
        if (twistEffect == null) twistEffect = FindObjectOfType<TwistEffect>();
        if (scoreManager == null) scoreManager = FindObjectOfType<ScoreManager>();
        if (audioManager == null) audioManager = FindObjectOfType<AudioManager>();
        if (uiManager == null) uiManager = FindObjectOfType<UIManager>();
        if (hitZone == null) hitZone = FindObjectOfType<HitZone>();

        if (hitZone != null)
            hitZone.OnLaneTapped += OnLaneTapped;

        Debug.Log("[GameEngine] Initialized. TileSpawner=" + (tileSpawner != null) +
            " ScoreManager=" + (scoreManager != null) +
            " UIManager=" + (uiManager != null));
    }

    /// <summary>
    /// Called from Android via UnitySendMessage (single string param).
    /// Format: "trackId|durationMs"
    /// </summary>
    public void StartGame(string data)
    {
        if (string.IsNullOrEmpty(data)) return;
        string[] parts = data.Split('|');
        if (parts.Length < 2) return;
        string trackId = parts[0];
        float durationMs;
        if (!float.TryParse(parts[1], out durationMs)) durationMs = 180000f;

        currentTrackId = trackId;
        trackDurationMs = durationMs;
        currentBeats = BeatMapGenerator.Generate(trackId, durationMs, laneCount);

        scoreManager.Reset();
        tileSpawner.Initialize(currentBeats, travelTime, laneCount);
        twistEffect.Reset();
        audioManager.Prepare(trackId);

        State = GameState.Playing;
        uiManager.ShowGame();

        NotifyAndroid("onGameStarted", currentTrackId);
    }

    public void PauseGame()
    {
        if (State != GameState.Playing) return;
        State = GameState.Paused;
        Time.timeScale = 0f;
        audioManager.Pause();
        uiManager.ShowPause();
    }

    public void ResumeGame()
    {
        if (State != GameState.Paused) return;
        State = GameState.Playing;
        Time.timeScale = 1f;
        audioManager.Resume();
        uiManager.HidePause();
    }

    public void EndGame()
    {
        State = GameState.GameOver;
        Time.timeScale = 1f;
        audioManager.Stop();
        uiManager.ShowResults(scoreManager.Score, scoreManager.MaxCombo,
            scoreManager.Perfects, scoreManager.Greats, scoreManager.Goods, scoreManager.Misses);
        NotifyAndroid("onGameEnded", scoreManager.Score.ToString());
    }

    public void ExitGame()
    {
        State = GameState.Idle;
        Time.timeScale = 1f;
        audioManager.Stop();
        tileSpawner.Clear();
        NotifyAndroid("onGameExited", "");
    }

    void Update()
    {
        if (State != GameState.Playing) return;

        float currentTime = audioManager.GetCurrentTime();
        tileSpawner.UpdateTiles(currentTime, travelTime);

        // Check for misses
        tileSpawner.CheckMisses(currentTime, goodWindow, OnMiss);

        // Update twist
        twistEffect.Tick(Time.deltaTime);

        // Update UI
        uiManager.UpdateProgress(currentTime / (trackDurationMs / 1000f));
        uiManager.UpdateScore(scoreManager.Score, scoreManager.Combo);

        // Auto-end
        if (currentTime >= trackDurationMs / 1000f)
        {
            EndGame();
        }
    }

    void OnLaneTapped(int lane)
    {
        if (State != GameState.Playing) return;

        float currentTime = audioManager.GetCurrentTime();
        Tile tile = tileSpawner.GetClosestTile(lane, currentTime, goodWindow);

        if (tile == null) return;

        float delta = Mathf.Abs(tile.targetTime - currentTime);

        if (delta <= perfectWindow)
        {
            scoreManager.RegisterHit(ScoreManager.HitQuality.Perfect);
            tileSpawner.DestroyTile(tile, true);
            uiManager.ShowPopup("PERFECT", Color.yellow);
            twistEffect.OnPerfectHit();
        }
        else if (delta <= greatWindow)
        {
            scoreManager.RegisterHit(ScoreManager.HitQuality.Great);
            tileSpawner.DestroyTile(tile, true);
            uiManager.ShowPopup("GREAT", new Color(0.18f, 0.91f, 0.6f));
            twistEffect.OnGreatHit();
        }
        else
        {
            scoreManager.RegisterHit(ScoreManager.HitQuality.Good);
            tileSpawner.DestroyTile(tile, true);
            uiManager.ShowPopup("GOOD", Color.white);
        }
    }

    void OnMiss(Tile tile)
    {
        scoreManager.RegisterMiss();
        tileSpawner.DestroyTile(tile, false);
        uiManager.ShowPopup("MISS", new Color(1f, 0.3f, 0.4f));
        twistEffect.OnMiss();
    }

    // Android bridge
    void NotifyAndroid(string method, string data)
    {
        try
        {
            using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
            {
                activity.Call(method, data);
            }
        }
        catch (System.Exception) { }
    }

    // Called from Android
    public void AndroidPause() => PauseGame();
    public void AndroidResume() => ResumeGame();
    public void AndroidSeekTo(float positionMs) => audioManager.SeekTo(positionMs / 1000f);
}
