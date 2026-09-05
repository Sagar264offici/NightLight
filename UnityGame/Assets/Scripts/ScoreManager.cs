using UnityEngine;

/// <summary>
/// Manages score, combo, and hit quality tracking.
/// Score formula: Perfect=300, Great=200, Good=100, with combo multiplier.
/// </summary>
public class ScoreManager : MonoBehaviour
{
    public enum HitQuality { Perfect, Great, Good }

    public int Score { get; private set; }
    public int Combo { get; private set; }
    public int MaxCombo { get; private set; }
    public int Perfects { get; private set; }
    public int Greats { get; private set; }
    public int Goods { get; private set; }
    public int Misses { get; private set; }

    private static readonly int[] baseScores = { 300, 200, 100 };

    public void Reset()
    {
        Score = 0;
        Combo = 0;
        MaxCombo = 0;
        Perfects = 0;
        Greats = 0;
        Goods = 0;
        Misses = 0;
    }

    public void RegisterHit(HitQuality quality)
    {
        Combo++;
        if (Combo > MaxCombo) MaxCombo = Combo;

        int baseScore = baseScores[(int)quality];
        int multiplier = 1 + Combo / 10; // +1 every 10 combo
        Score += baseScore * multiplier;

        switch (quality)
        {
            case HitQuality.Perfect: Perfects++; break;
            case HitQuality.Great: Greats++; break;
            case HitQuality.Good: Goods++; break;
        }
    }

    public void RegisterMiss()
    {
        Combo = 0;
        Misses++;
    }

    public float Accuracy
    {
        get
        {
            int total = Perfects + Greats + Goods + Misses;
            if (total == 0) return 0f;
            return (Perfects * 300f + Greats * 200f + Goods * 100f) / (total * 300f) * 100f;
        }
    }

    public string Grade
    {
        get
        {
            float acc = Accuracy;
            if (acc >= 95) return "S";
            if (acc >= 90) return "A+";
            if (acc >= 80) return "A";
            if (acc >= 70) return "B";
            if (acc >= 60) return "C";
            return "D";
        }
    }
}
