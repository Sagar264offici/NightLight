using UnityEngine;

/// <summary>
/// Attached to each tile GameObject. Holds beat data and state.
/// </summary>
public class Tile : MonoBehaviour
{
    public int lane;
    public float targetTime;
    public float holdDuration;
    public bool hit;
}
