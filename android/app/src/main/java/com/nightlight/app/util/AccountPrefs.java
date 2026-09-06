package com.nightlight.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Account-level UI state: the verified email and the onboarding selections
 * (languages + categories). The bearer token itself lives in TokenStore
 * (EncryptedSharedPreferences); this holds non-secret account metadata.
 */
public final class AccountPrefs {

    private static final String PREFS = "nightlight_account";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_ONBOARDED = "onboarded";
    private static final String KEY_LANGUAGES = "languages";
    private static final String KEY_CATEGORIES = "categories";
    private static final String KEY_GUEST = "guest";
    private static final String KEY_PENDING_CONVERSION = "pending_conversion";
    private static final String KEY_SKIP_PUSH = "skip_local_playlist_push";

    private AccountPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Explicit guest flag. Guests never receive a server token, so every
     * token-gated sync path stays off; this flag additionally drives the UI
     * (account-required prompts) and splash routing.
     */
    public static void setGuest(Context context, boolean guest) {
        prefs(context).edit().putBoolean(KEY_GUEST, guest).apply();
    }

    public static boolean isGuest(Context context) {
        return prefs(context).getBoolean(KEY_GUEST, false);
    }

    public static void setEmail(Context context, String email) {
        prefs(context).edit().putString(KEY_EMAIL, email).apply();
    }

    public static String email(Context context) {
        return prefs(context).getString(KEY_EMAIL, null);
    }

    public static boolean isOnboarded(Context context) {
        return prefs(context).getBoolean(KEY_ONBOARDED, false);
    }

    public static void markOnboarded(Context context, List<String> languages, List<String> categories) {
        prefs(context).edit()
                .putBoolean(KEY_ONBOARDED, true)
                .putStringSet(KEY_LANGUAGES, new HashSet<>(languages))
                .putStringSet(KEY_CATEGORIES, new HashSet<>(categories))
                .apply();
    }

    public static List<String> languages(Context context) {
        Set<String> set = prefs(context).getStringSet(KEY_LANGUAGES, new HashSet<>());
        return set == null ? List.of() : List.copyOf(set);
    }

    public static List<String> categories(Context context) {
        Set<String> set = prefs(context).getStringSet(KEY_CATEGORIES, new HashSet<>());
        return set == null ? List.of() : List.copyOf(set);
    }

    /**
     * Derives a default listening mood from the onboarded categories, used when
     * the user has not picked an explicit mood. Returns null when no mapping
     * applies so Home can fall back to its existing behavior.
     */
    public static String defaultMood(Context context) {
        List<String> categories = categories(context);
        if (categories.isEmpty()) {
            return null;
        }
        // Ordered by preference: first matching category wins.
        String[][] map = {
                {"love", "love"},
                {"romantic", "love"},
                {"chill", "chill"},
                {"lofi", "chill"},
                {"indie", "chill"},
                {"happy", "happy"},
                {"pop", "happy"},
                {"sad", "sad"},
                {"party", "party"},
                {"workout", "workout"},
                {"focus", "focus"},
                {"bollywood", "bollywood"},
                {"hip-hop", "energy"}
        };
        for (String category : categories) {
            for (String[] pair : map) {
                if (pair[0].equalsIgnoreCase(category.trim())) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    /**
     * The context mood used by Home + Smart Shuffle: an explicit mood wins;
     * otherwise onboarding categories seed a personalized default mood.
     */
    public static String effectiveMood(Context context) {
        String explicit = MoodPrefs.active(context);
        return explicit != null ? explicit : defaultMood(context);
    }

    /** Marks the account as converted from guest after a successful signup. */
    public static void clearGuest(Context context) {
        prefs(context).edit().remove(KEY_GUEST).apply();
    }

    /**
     * Set when a guest taps Create/Login from the account-required prompt, so
     * onboarding can offer to save their temporary local playlists (explicit
     * intent — guest data is never uploaded silently).
     */
    public static void setPendingGuestConversion(Context context, boolean pending) {
        prefs(context).edit().putBoolean(KEY_PENDING_CONVERSION, pending).apply();
    }

    public static boolean isPendingGuestConversion(Context context) {
        return prefs(context).getBoolean(KEY_PENDING_CONVERSION, false);
    }

    /**
     * When the user declines the save offer, their pre-existing local
     * playlists stay device-only forever; syncFromServer skips pushing them.
     */
    public static void setSkipLocalPlaylistPush(Context context, boolean skip) {
        prefs(context).edit().putBoolean(KEY_SKIP_PUSH, skip).apply();
    }

    public static boolean skipLocalPlaylistPush(Context context) {
        return prefs(context).getBoolean(KEY_SKIP_PUSH, false);
    }

    public static void clear(Context context) {
        prefs(context).edit()
                .remove(KEY_EMAIL)
                .remove(KEY_ONBOARDED)
                .remove(KEY_LANGUAGES)
                .remove(KEY_CATEGORIES)
                .apply();
    }

    public static boolean hasAnyOnboarding(Context context) {
        return !languages(context).isEmpty() || !categories(context).isEmpty();
    }

    // Referenced by unit-ish callers; keeps constants visible for logging/tests.
    public static Set<String> languageChoices() {
        return new HashSet<>(Arrays.asList(
                "Hindi", "English", "Punjabi", "Tamil", "Telugu",
                "Bengali", "Marathi", "Kannada", "Malayalam", "Gujarati",
                "Bhojpuri", "Odia"));
    }
}