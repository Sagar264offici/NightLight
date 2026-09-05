using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;
using System.IO;

/// <summary>
/// Build script that exports this Unity project as an Android Library (AAR).
/// Run from command line:
///   Unity -batchmode -nographics -projectPath /path/to/UnityGame \
///         -executeMethod BuildLibrary.BuildAndroidLibrary -quit
/// </summary>
public static class BuildLibrary
{
    private const string LIBRARY_NAME = "NightLightGame";

    [MenuItem("Build/Android Library")]
    public static void BuildAndroidLibrary()
    {
        Debug.Log("[BuildLibrary] Starting Android Library build...");

        // Set Android as build target
        EditorUserBuildSettings.SwitchActiveBuildTarget(
            BuildTargetGroup.Android, BuildTarget.Android);

        // Configure Android settings for library mode
        PlayerSettings.Android.targetArchitectures =
            AndroidArchitecture.ARM64 | AndroidArchitecture.ARMv7;

        // Set to export as library
        EditorUserBuildSettings.exportAsGoogleAndroidProject = true;

        // Build scenes
        string[] scenes = {
            "Assets/Scenes/GameScene.unity"
        };

        // Create output directory
        string outputDir = Path.Combine(
            Directory.GetParent(Application.dataPath).FullName,
            "Build");

        if (!Directory.Exists(outputDir))
            Directory.CreateDirectory(outputDir);

        // Build
        BuildPlayerOptions options = new BuildPlayerOptions
        {
            scenes = scenes,
            locationPathName = outputDir,
            target = BuildTarget.Android,
            targetGroup = BuildTargetGroup.Android,
            options = BuildOptions.None
        };

        BuildReport report = BuildPipeline.BuildPlayer(options);
        BuildSummary summary = report.summary;

        if (summary.result == BuildResult.Succeeded)
        {
            Debug.Log($"[BuildLibrary] Android Library build succeeded: {summary.totalSize} bytes");

            // Copy AAR to NightLight project
            CopyLibraryToNightLight(outputDir);
        }
        else
        {
            Debug.LogError($"[BuildLibrary] Build failed: {summary.result}");
            foreach (var step in report.steps)
            {
                foreach (var message in step.messages)
                {
                    if (message.type == LogType.Error)
                        Debug.LogError($"  {message.content}");
                }
            }
        }
    }

    static void CopyLibraryToNightLight(string buildOutput)
    {
        string nightLightLibs = Path.Combine(
            Directory.GetParent(Application.dataPath).FullName,
            "..", "..", "android", "app", "libs");

        string nightLightSrc = Path.Combine(nightLightLibs, LIBRARY_NAME);

        if (Directory.Exists(nightLightSrc))
            Directory.Delete(nightLightSrc, true);

        // Copy the exported gradle project
        if (Directory.Exists(buildOutput))
        {
            CopyDirectory(buildOutput, nightLightSrc);
            Debug.Log($"[BuildLibrary] Copied library to {nightLightSrc}");
        }
    }

    static void CopyDirectory(string source, string dest)
    {
        Directory.CreateDirectory(dest);
        foreach (string file in Directory.GetFiles(source))
        {
            File.Copy(file, Path.Combine(dest, Path.GetFileName(file)));
        }
        foreach (string dir in Directory.GetDirectories(source))
        {
            CopyDirectory(dir, Path.Combine(dest, Path.GetFileName(dir)));
        }
    }
}
