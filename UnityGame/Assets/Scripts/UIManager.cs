using UnityEngine;
using UnityEngine.UI;
using System.Collections;

/// <summary>
/// Manages all game UI: HUD (score, combo, progress), pause overlay,
/// results screen, and popup text effects.
/// </summary>
public class UIManager : MonoBehaviour
{
    [Header("HUD")]
    public Text scoreText;
    public Text comboText;
    public Slider progressBar;
    public CanvasGroup hudGroup;

    [Header("Pause")]
    public CanvasGroup pauseGroup;
    public Button resumeButton;
    public Button quitButton;

    [Header("Results")]
    public CanvasGroup resultsGroup;
    public Text resultsScore;
    public Text resultsCombo;
    public Text resultsGrade;
    public Text resultsPerfects;
    public Text resultsGreats;
    public Text resultsGoods;
    public Text resultsMisses;
    public Text resultsAccuracy;
    public Button resultsCloseButton;

    [Header("Popup")]
    public Text popupText;
    public CanvasGroup popupGroup;

    private Coroutine popupCoroutine;

    void Start()
    {
        HideAll();
        if (resumeButton) resumeButton.onClick.AddListener(() => GameEngine.Instance.ResumeGame());
        if (quitButton) quitButton.onClick.AddListener(() => GameEngine.Instance.ExitGame());
        if (resultsCloseButton) resultsCloseButton.onClick.AddListener(() => GameEngine.Instance.ExitGame());
    }

    public void ShowGame()
    {
        HideAll();
        SetAlpha(hudGroup, 1f);
    }

    public void ShowPause()
    {
        SetAlpha(pauseGroup, 1f);
    }

    public void HidePause()
    {
        SetAlpha(pauseGroup, 0f);
    }

    public void ShowResults(int score, int maxCombo, int perfects, int greats, int goods, int misses)
    {
        SetAlpha(hudGroup, 0f);
        SetAlpha(resultsGroup, 1f);

        int total = perfects + greats + goods + misses;
        float accuracy = total > 0 ? (perfects * 300f + greats * 200f + goods * 100f) / (total * 300f) * 100f : 0;
        string grade = accuracy >= 95 ? "S" : accuracy >= 90 ? "A+" : accuracy >= 80 ? "A" :
                       accuracy >= 70 ? "B" : accuracy >= 60 ? "C" : "D";

        if (resultsScore) resultsScore.text = score.ToString("N0");
        if (resultsCombo) resultsCombo.text = "Max Combo: " + maxCombo;
        if (resultsGrade) resultsGrade.text = grade;
        if (resultsPerfects) resultsPerfects.text = "Perfect: " + perfects;
        if (resultsGreats) resultsGreats.text = "Great: " + greats;
        if (resultsGoods) resultsGoods.text = "Good: " + goods;
        if (resultsMisses) resultsMisses.text = "Miss: " + misses;
        if (resultsAccuracy) resultsAccuracy.text = accuracy.ToString("F1") + "%";
    }

    public void UpdateScore(int score, int combo)
    {
        if (scoreText) scoreText.text = score.ToString("N0");
        if (comboText)
        {
            comboText.text = combo > 1 ? combo + "x" : "";
            // Pulse combo text on milestones
            if (combo > 0 && combo % 10 == 0)
            {
                comboText.transform.localScale = Vector3.one * 1.4f;
                StartCoroutine(Pulse(comboText.transform));
            }
        }
    }

    public void UpdateProgress(float progress)
    {
        if (progressBar) progressBar.value = Mathf.Clamp01(progress);
    }

    public void ShowPopup(string text, Color color)
    {
        if (popupText == null || popupGroup == null) return;

        popupText.text = text;
        popupText.color = color;
        SetAlpha(popupGroup, 1f);
        popupText.transform.localScale = Vector3.one * 1.3f;

        if (popupCoroutine != null) StopCoroutine(popupCoroutine);
        popupCoroutine = StartCoroutine(FadePopup());
    }

    IEnumerator FadePopup()
    {
        yield return StartCoroutine(Pulse(popupText.transform));
        float t = 0;
        while (t < 0.5f)
        {
            t += Time.deltaTime;
            SetAlpha(popupGroup, 1f - t / 0.5f);
            yield return null;
        }
        SetAlpha(popupGroup, 0f);
    }

    IEnumerator Pulse(Transform target)
    {
        float t = 0;
        while (t < 0.15f)
        {
            t += Time.deltaTime;
            float s = Mathf.Lerp(1.4f, 1f, t / 0.15f);
            target.localScale = Vector3.one * s;
            yield return null;
        }
        target.localScale = Vector3.one;
    }

    void HideAll()
    {
        SetAlpha(hudGroup, 0f);
        SetAlpha(pauseGroup, 0f);
        SetAlpha(resultsGroup, 0f);
        SetAlpha(popupGroup, 0f);
    }

    void SetAlpha(CanvasGroup group, float alpha)
    {
        if (group != null)
        {
            group.alpha = alpha;
            group.interactable = alpha > 0.5f;
            group.blocksRaycasts = alpha > 0.5f;
        }
    }
}
