package com.nightlight.app.ui.common;

import android.content.Context;
import android.content.Intent;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.data.repo.MusicRepository;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.ui.NowPlayingActivity;
import com.nightlight.app.util.AppExecutors;
import com.nightlight.app.util.ErrorMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plays a list of tracks, resolving expired stream URLs first when the list
 * came from local library snapshots. Always opens the Now Playing screen.
 */
public final class TrackPlayer {

    private TrackPlayer() {
    }

    /**
     * Collapses near-duplicate variants of the same song (remix / acoustic /
     * slowed / live...) so shuffle and auto-next deliver variety instead of
     * cycling versions of one track. The dedupe key combines the canonical
     * title with the artist: different artists' recordings of the same title
     * are distinct songs and must survive (e.g. a search can return many
     * "Imagine" covers by different artists — collapsing them to one track
     * leaves an empty-feeling queue). Playlist queues are never touched.
     */
    public static List<Track> dedupeVariants(List<Track> tracks) {
        if (tracks == null || tracks.size() < 2) {
            return tracks;
        }
        List<Track> out = new ArrayList<>(tracks.size());
        Set<String> keys = new HashSet<>();
        for (Track t : tracks) {
            String canon = canonicalTitle(t.name);
            String artist = t.artists != null ? t.artists.toLowerCase().trim() : "";
            String key = canon.isEmpty() ? t.id : canon + "|" + artist;
            if (keys.add(key)) {
                out.add(t);
            }
        }
        return out;
    }

    private static String canonicalTitle(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase()
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\[[^\\]]*\\]", " ")
                .replaceAll("[-–—:;._+/,]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static void play(Context context, List<Track> tracks, int startIndex) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        boolean allResolved = true;
        for (Track t : tracks) {
            if (t.streamUrl == null) {
                allResolved = false;
                break;
            }
        }

        if (allResolved) {
            PlaybackManager.get(context).playTracks(tracks, Math.max(0, Math.min(startIndex, tracks.size() - 1)));
            openNowPlaying(context);
            return;
        }

        NightLightApp app = (NightLightApp) context.getApplicationContext();
        app.getMusicRepository().resolveTracks(tracks, new MusicRepository.TracksCallback() {
            @Override
            public void onSuccess(List<Track> resolved) {
                PlaybackManager.get(context).playTracks(resolved, Math.max(0, Math.min(startIndex, resolved.size() - 1)));
                openNowPlaying(context);
            }

            @Override
            public void onFailure(Throwable error) {
                AppExecutors.onMain(() -> {
                    android.widget.Toast.makeText(context,
                            ErrorMapper.toUserMessage(context, error), android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    public static void openNowPlaying(Context context) {
        Intent intent = new Intent(context, NowPlayingActivity.class);
        // Called from the application context (e.g. listen-together join) needs
        // a task to launch into; Activity callers must not get one.
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }
}