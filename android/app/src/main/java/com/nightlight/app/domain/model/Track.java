package com.nightlight.app.domain.model;

import com.nightlight.app.data.api.dto.SongDtos;
import com.nightlight.app.data.api.dto.UserDtos;
import com.nightlight.app.data.db.entity.LikedTrackEntity;
import com.nightlight.app.data.db.entity.RecentTrackEntity;

import java.util.List;
import java.util.Locale;

/**
 * Immutable track used across the app. `streamUrl` is present only when fresh
 * song details have been fetched; library snapshots must be resolved before
 * playback.
 */
public final class Track {

    public final String id;
    public final String name;
    public final String artists;
    public final String album;
    public final String imageUrl;
    public final String streamUrl;
    public final long durationMs;
    public final String year;

    public Track(String id, String name, String artists, String album, String imageUrl,
                 String streamUrl, long durationMs, String year) {
        this.id = id;
        this.name = name;
        this.artists = artists;
        this.album = album;
        this.imageUrl = imageUrl;
        this.streamUrl = streamUrl;
        this.durationMs = durationMs;
        this.year = year;
    }

    public Track withStreamUrl(String url) {
        return new Track(id, name, artists, album, imageUrl, url, durationMs, year);
    }

    public static Track fromSong(SongDtos.SongDto song) {
        String artists = joinArtists(song.artists != null ? song.artists.primary : null);
        long durationMs = song.duration != null ? song.duration * 1000L : 0L;
        return new Track(
                song.id,
                song.name != null ? song.name : "Unknown track",
                artists,
                song.album != null && song.album.name != null ? song.album.name : "",
                bestImage(song.image),
                bestDownloadUrl(song.downloadUrl),
                durationMs,
                song.year != null ? song.year : "");
    }

    public static Track fromSnapshot(UserDtos.TrackSnapshotDto s) {
        return new Track(
                s.id,
                s.name != null ? s.name : "Unknown track",
                s.artists != null ? s.artists : "",
                s.album != null ? s.album : "",
                s.imageUrl != null ? s.imageUrl : "",
                null,
                s.duration != null ? s.duration : 0L,
                s.year != null ? s.year : "");
    }

    public UserDtos.TrackSnapshotDto toSnapshot() {
        UserDtos.TrackSnapshotDto dto = new UserDtos.TrackSnapshotDto();
        dto.id = id;
        dto.name = name;
        dto.artists = artists;
        dto.album = album;
        dto.imageUrl = imageUrl;
        dto.duration = (int) durationMs;
        dto.year = year;
        return dto;
    }

    public static Track fromEntity(LikedTrackEntity e) {
        return new Track(e.trackId, e.name, e.artists, e.album, e.imageUrl, e.streamUrl, e.duration, e.year);
    }

    public static Track fromEntity(RecentTrackEntity e) {
        return new Track(e.trackId, e.name, e.artists, e.album, e.imageUrl, e.streamUrl, e.duration, e.year);
    }

    private static String joinArtists(List<SongDtos.ArtistDto> artists) {
        if (artists == null || artists.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SongDtos.ArtistDto a : artists) {
            if (a.name == null || a.name.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(a.name);
        }
        return sb.toString();
    }

    /** Prefer 500x500 artwork; fall back to the first available size. */
    public static String bestImage(List<SongDtos.DownloadLinkDto> images) {
        if (images == null || images.isEmpty()) {
            return "";
        }
        for (SongDtos.DownloadLinkDto d : images) {
            if (d.quality != null && d.quality.contains("500")) {
                return d.url;
            }
        }
        for (SongDtos.DownloadLinkDto d : images) {
            if (d.quality != null && d.quality.contains("150")) {
                return d.url;
            }
        }
        return images.get(0).url;
    }

    /** Pick the highest-quality streamable URL available. */
    public static String bestDownloadUrl(List<SongDtos.DownloadLinkDto> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        String[] preferred = {"320", "160", "96", "48"};
        for (String quality : preferred) {
            for (SongDtos.DownloadLinkDto d : urls) {
                if (d.quality != null && d.quality.startsWith(quality)) {
                    return d.url;
                }
            }
        }
        return urls.get(0).url;
    }

    public static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}