package com.nightlight.app;

import android.app.Application;

import com.nightlight.app.data.repo.AuthRepository;
import com.nightlight.app.data.repo.LibraryRepository;
import com.nightlight.app.data.repo.MusicRepository;
import com.nightlight.app.data.repo.PlaylistRepository;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.util.AppExecutors;
import com.nightlight.app.util.TokenStore;

public final class NightLightApp extends Application {

    private static NightLightApp instance;

    private LibraryRepository libraryRepository;
    private PlaylistRepository playlistRepository;
    private MusicRepository musicRepository;
    private AuthRepository authRepository;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        TokenStore.init(this);
        com.nightlight.app.data.api.FirebaseAuthClient.apiKey = BuildConfig.FIREBASE_API_KEY;
        com.nightlight.app.data.api.GoogleSignInHelper.serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID;
        // Kick off the media-session connection immediately (async, non-blocking).
        PlaybackManager playback = PlaybackManager.get(this);
        playback.setTrackStartedListener(track -> getLibraryRepository().recordPlay(track));

        // Background sync: reconcile user data only for authenticated users.
        // Guests (no token) keep everything local: no server account is ever
        // created for them, and their playlists/likes never reach MongoDB.
        // Room shows local data instantly; this never blocks startup.
        if (TokenStore.hasToken()) {
            AppExecutors.get().network().execute(() -> {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                }
                getLibraryRepository().syncFromServer();
                getPlaylistRepository().syncFromServer();
            });
        }
    }

    public static NightLightApp get() {
        return instance;
    }

    public LibraryRepository getLibraryRepository() {
        if (libraryRepository == null) {
            libraryRepository = new LibraryRepository(this);
        }
        return libraryRepository;
    }

    public PlaylistRepository getPlaylistRepository() {
        if (playlistRepository == null) {
            playlistRepository = new PlaylistRepository(this);
        }
        return playlistRepository;
    }

    public MusicRepository getMusicRepository() {
        if (musicRepository == null) {
            musicRepository = new MusicRepository(this);
        }
        return musicRepository;
    }

    public AuthRepository getAuthRepository() {
        if (authRepository == null) {
            authRepository = new AuthRepository(this);
        }
        return authRepository;
    }
}