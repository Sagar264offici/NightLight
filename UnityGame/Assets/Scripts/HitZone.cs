using UnityEngine;
using System;

/// <summary>
/// Detects touches in the hit zone area and maps them to lanes.
/// Supports multi-touch for simultaneous lane taps.
/// </summary>
public class HitZone : MonoBehaviour
{
    public int laneCount = 4;
    public float hitY = -4f;
    public float hitZoneHeight = 1.5f;
    public float laneWidth = 1.2f;
    public float laneGap = 0.08f;

    public event Action<int> OnLaneTapped;

    void Update()
    {
        // Handle multi-touch
        for (int i = 0; i < Input.touchCount; i++)
        {
            Touch touch = Input.GetTouch(i);
            if (touch.phase == TouchPhase.Began)
            {
                ProcessTap(touch.position);
            }
        }

        // Editor mouse support
#if UNITY_EDITOR
        if (Input.GetMouseButtonDown(0))
        {
            ProcessTap(Input.mousePosition);
        }
#endif
    }

    void ProcessTap(Vector2 screenPos)
    {
        // Convert screen position to world position
        Vector3 worldPos = Camera.main.ScreenToWorldPoint(
            new Vector3(screenPos.x, screenPos.y, 10f));

        // Check if in hit zone vertical range
        if (worldPos.y < hitY - hitZoneHeight || worldPos.y > hitY + hitZoneHeight)
            return;

        // Determine which lane
        float totalWidth = laneCount * (laneWidth + laneGap) - laneGap;
        float startX = -totalWidth / 2f;

        for (int lane = 0; lane < laneCount; lane++)
        {
            float x0 = startX + lane * (laneWidth + laneGap);
            float x1 = x0 + laneWidth;

            if (worldPos.x >= x0 && worldPos.x <= x1)
            {
                OnLaneTapped?.Invoke(lane);
                return;
            }
        }
    }
}
