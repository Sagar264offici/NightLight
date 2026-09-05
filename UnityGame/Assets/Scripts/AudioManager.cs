using UnityEngine;

/// <summary>
/// Handles audio playback and provides the authoritative time clock
/// for gameplay synchronization. In Unity as a Library mode, the actual
/// audio plays through the host Android app's ExoPlayer — this component
/// receives position updates via UnitySendMessage and provides
/// a smooth interpolated clock for the game loop.
/// </summary>
public class AudioManager : MonoBehaviour
{
    private float currentTime;
    private bool isPlaying;
    private float lastSyncTime;
    private float lastSyncPosition;

    public float GetCurrentTime()
    {
        if (isPlaying)
        {
            float elapsed = Time.unscaledTime - lastSyncTime;
            return lastSyncPosition + elapsed;
        }
        return lastSyncPosition;
    }

    public void Prepare(string trackId)
    {
        currentTime = 0f;
        lastSyncPosition = 0f;
        lastSyncTime = Time.unscaledTime;
        isPlaying = false;
    }

    public void Play()
    {
        isPlaying = true;
        lastSyncTime = Time.unscaledTime;
        lastSyncPosition = currentTime;
    }

    public void Pause()
    {
        currentTime = GetCurrentTime();
        isPlaying = false;
    }

    public void Resume()
    {
        Play();
    }

    public void Stop()
    {
        isPlaying = false;
        currentTime = 0f;
        lastSyncPosition = 0f;
    }

    public void SeekTo(float seconds)
    {
        currentTime = seconds;
        lastSyncPosition = seconds;
        lastSyncTime = Time.unscaledTime;
    }

    /// <summary>
    /// Called from Android via UnitySendMessage.
    /// Format: "positionMs|isPlaying"
    /// UnitySendMessage can only pass one string, so we parse it here.
    /// </summary>
    public void SyncPosition(string data)
    {
        if (string.IsNullOrEmpty(data)) return;
        string[] parts = data.Split('|');
        if (parts.Length < 2) return;

        float positionMs;
        bool playing;
        if (float.TryParse(parts[0], out positionMs) && bool.TryParse(parts[1], out playing))
        {
            lastSyncPosition = positionMs / 1000f;
            lastSyncTime = Time.unscaledTime;
            isPlaying = playing;
        }
    }
}
