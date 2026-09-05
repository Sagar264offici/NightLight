using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.UI;
using UnityEngine.UI;

public static class CreateScene
{
    [MenuItem("Tools/Create Game Scene")]
    public static void Create()
    {
        var scene = EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);

        // Camera
        var cam = Camera.main;
        cam.orthographic = true;
        cam.orthographicSize = 6f;
        cam.backgroundColor = new Color(0.02f, 0.03f, 0.06f);
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.transform.position = new Vector3(0, 0, -10);

        // Game Engine
        var engineObj = new GameObject("GameEngine");
        var engine = engineObj.AddComponent<GameEngine>();

        // Tile Spawner
        var spawnerObj = new GameObject("TileSpawner");
        var spawner = spawnerObj.AddComponent<TileSpawner>();
        var laneContainer = new GameObject("LaneContainer");
        laneContainer.transform.SetParent(spawnerObj.transform);
        spawner.laneContainer = laneContainer.transform;

        // Twist Effect
        var twistObj = new GameObject("TwistEffect");
        var twist = twistObj.AddComponent<TwistEffect>();
        twist.boardContainer = laneContainer.transform;

        // Score Manager
        var scoreObj = new GameObject("ScoreManager");
        var score = scoreObj.AddComponent<ScoreManager>();

        // Audio Manager
        var audioObj = new GameObject("AudioManager");
        var audioMgr = audioObj.AddComponent<AudioManager>();

        // Hit Zone
        var hitObj = new GameObject("HitZone");
        var hitZone = hitObj.AddComponent<HitZone>();
        hitZone.transform.position = new Vector3(0, -4, 0);

        // UI Canvas
        var canvasObj = new GameObject("UICanvas");
        var canvas = canvasObj.AddComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        var scaler = canvasObj.AddComponent<CanvasScaler>();
        scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
        scaler.referenceResolution = new Vector2(1080, 1920);
        canvasObj.AddComponent<GraphicRaycaster>();

        // HUD
        var hudObj = new GameObject("HUD");
        hudObj.transform.SetParent(canvasObj.transform, false);
        var hudGroup = hudObj.AddComponent<CanvasGroup>();
        var hudRect = hudObj.AddComponent<RectTransform>();
        hudRect.anchorMin = Vector2.zero;
        hudRect.anchorMax = Vector2.one;
        hudRect.sizeDelta = Vector2.zero;

        // Score Text (TMP)
        var scoreTextObj = CreateText("ScoreText", hudObj.transform, "0", 64,
            new Vector2(0.5f, 1f), new Vector2(0, -60), new Vector2(400, 80), Color.white);

        // Combo Text (TMP)
        var comboTextObj = CreateText("ComboText", hudObj.transform, "", 48,
            new Vector2(0.5f, 1f), new Vector2(0, -130), new Vector2(200, 60),
            new Color(1f, 0.85f, 0.29f));

        // Popup (TMP)
        var popupObj = new GameObject("PopupText");
        popupObj.transform.SetParent(hudObj.transform, false);
        var popupGroup = popupObj.AddComponent<CanvasGroup>();
        var popupTMP = popupObj.AddComponent<Text>();
        popupTMP.text = "";
        popupTMP.fontSize = 64;
        popupTMP.color = Color.yellow;
        popupTMP.alignment = TextAnchor.MiddleCenter;
        var popupRect = popupObj.GetComponent<RectTransform>();
        popupRect.anchorMin = new Vector2(0.5f, 0.4f);
        popupRect.anchorMax = new Vector2(0.5f, 0.4f);
        popupRect.sizeDelta = new Vector2(400, 80);

        // Progress bar
        var sliderObj = new GameObject("ProgressBar");
        sliderObj.transform.SetParent(hudObj.transform, false);
        var slider = sliderObj.AddComponent<Slider>();
        slider.minValue = 0;
        slider.maxValue = 1;
        var sliderRect = sliderObj.GetComponent<RectTransform>();
        sliderRect.anchorMin = new Vector2(0.1f, 0f);
        sliderRect.anchorMax = new Vector2(0.9f, 0f);
        sliderRect.anchoredPosition = new Vector2(0, 30);
        sliderRect.sizeDelta = new Vector2(0, 8);

        // Wire references
        engine.tileSpawner = spawner;
        engine.twistEffect = twist;
        engine.scoreManager = score;
        engine.audioManager = audioMgr;
        engine.hitZone = hitZone;

        var ui = canvasObj.AddComponent<UIManager>();
        engine.uiManager = ui;
        ui.scoreText = scoreTextObj.GetComponent<Text>();
        ui.comboText = comboTextObj.GetComponent<Text>();
        ui.popupText = popupTMP;
        ui.popupGroup = popupGroup;
        ui.hudGroup = hudGroup;
        ui.progressBar = slider;

        EditorSceneManager.SaveScene(scene, "Assets/Scenes/GameScene.unity");
        Debug.Log("[CreateScene] Game scene created and saved!");
    }

    static GameObject CreateText(string name, Transform parent, string text, float fontSize,
        Vector2 anchor, Vector2 pos, Vector2 size, Color color)
    {
        var obj = new GameObject(name);
        obj.transform.SetParent(parent, false);
        var tmp = obj.AddComponent<Text>();
        tmp.text = text;
        tmp.fontSize = (int)fontSize;
        tmp.color = color;
        tmp.alignment = TextAnchor.MiddleCenter;
        var rect = obj.GetComponent<RectTransform>();
        rect.anchorMin = anchor;
        rect.anchorMax = anchor;
        rect.anchoredPosition = pos;
        rect.sizeDelta = size;
        return obj;
    }
}
