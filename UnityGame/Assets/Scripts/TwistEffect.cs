using UnityEngine;

/// <summary>
/// Creates the signature Music Twist rotation effect. The game board
/// periodically twists/rotates with smooth easing. Reacts to gameplay:
/// perfect hits create small pulses, misses cause visible wobbles.
/// </summary>
public class TwistEffect : MonoBehaviour
{
    [Header("Twist Config")]
    public float maxAngle = 12f;
    public float twistInterval = 4f;
    public float rotationSpeed = 2.5f;
    public Transform boardContainer;

    [Header("Reactive")]
    public float perfectPulse = 3f;
    public float missWobble = 5f;
    public float wobbleDecay = 4f;

    private float currentAngle;
    private float targetAngle;
    private float twistTimer;
    private int direction = 1;
    private float reactiveOffset;
    private float wobbleAmount;
    private float wobbleValue;

    void Start()
    {
        twistTimer = twistInterval * 0.5f;
    }

    public void Reset()
    {
        currentAngle = 0;
        targetAngle = 0;
        twistTimer = twistInterval * 0.5f;
        direction = 1;
        reactiveOffset = 0;
        wobbleAmount = 0;
        ApplyRotation();
    }

    public void Tick(float dt)
    {
        // Periodic twist
        twistTimer -= dt;
        if (twistTimer <= 0)
        {
            twistTimer = twistInterval + Random.Range(-1f, 1f);
            direction = -direction;
            targetAngle = direction * maxAngle * Random.Range(0.4f, 1f);
        }

        // Smooth interpolation
        currentAngle = Mathf.Lerp(currentAngle, targetAngle, dt * rotationSpeed);

        // Reactive offset decays
        reactiveOffset = Mathf.Lerp(reactiveOffset, 0, dt * 3f);

        // Wobble from misses
        wobbleAmount = Mathf.Lerp(wobbleAmount, 0, dt * wobbleDecay);
        wobbleValue = wobbleAmount * Mathf.Sin(Time.time * 15f);

        ApplyRotation();
    }

    void ApplyRotation()
    {
        if (boardContainer != null)
        {
            boardContainer.localRotation = Quaternion.Euler(
                0, 0, currentAngle + reactiveOffset + wobbleValue);
        }
    }

    public void OnPerfectHit()
    {
        reactiveOffset = perfectPulse * (Random.value > 0.5f ? 1 : -1);
    }

    public void OnGreatHit()
    {
        reactiveOffset = perfectPulse * 0.5f * (Random.value > 0.5f ? 1 : -1);
    }

    public void OnMiss()
    {
        wobbleAmount = missWobble;
    }
}
