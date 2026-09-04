package com.nightlight.app.ui.fragments;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.data.repo.LibraryRepository;
import com.nightlight.app.data.repo.PlaylistRepository;
import com.nightlight.app.domain.model.Playlist;
import com.nightlight.app.domain.model.Track;

import java.util.List;

public class LibraryViewModel extends AndroidViewModel {

    private final LibraryRepository library;
    private final PlaylistRepository playlists;

    public LibraryViewModel(@NonNull Application application) {
        super(application);
        NightLightApp app = (NightLightApp) application;
        this.library = app.getLibraryRepository();
        this.playlists = app.getPlaylistRepository();
    }

    public LiveData<List<Track>> getLikes() {
        return library.observeLikes();
    }

    public LiveData<List<Playlist>> getPlaylists() {
        return playlists.observePlaylists();
    }

    public void toggleLike(Track track) {
        library.toggleLike(track, !library.isLiked(track.id), null);
    }

    public void createPlaylist(String name) {
        playlists.createPlaylist(name, ok -> {
        });
    }

    public void deletePlaylist(Playlist playlist) {
        playlists.deletePlaylist(playlist.id, ok -> {
        });
    }

    public void renamePlaylist(Playlist playlist, String name) {
        playlists.renamePlaylist(playlist.id, name);
    }
}