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
        // Kick off the media-session connection immediately (async, non-blocking).
        PlaybackManager playback = PlaybackManager.get(this);
        playback.setTrackStartedListener(track -> getLibraryRepository().recordPlay(track));

        // Background sync: authenticate the device, then reconcile user data.
        // Room shows local data instantly; this never blocks startup.
        AppExecutors.get().network().execute(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
            }
            getAuthRepository().ensureAuthenticated(
                    () -> {
                        getLibraryRepository().syncFromServer();
                        getPlaylistRepository().syncFromServer();
                    },
                    () -> {
                        // Offline or backend down: local library remains fully usable.
                    });
        });
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