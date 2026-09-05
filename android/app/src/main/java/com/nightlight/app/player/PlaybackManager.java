package com.nightlight.app.player;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.data.repo.LibraryRepository;
import com.nightlight.app.data.repo.MusicRepository;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.SmartShuffleEngine;
import com.nightlight.app.util.MoodPrefs;
import com.nightlight.app.util.ShufflePrefs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single source of truth for playback state. UI never reads ExoPlayer
 * directly: it observes {@link PlaybackSnapshot} delivered through
 * {@link Listener#onPlaybackChanged}. Everything here runs on the main thread.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackManager {

    public interface Listener {
        @MainThread
        void onPlaybackChanged(PlaybackSnapshot snapshot);
    }

    /** Called when a new track actually starts playing (for recently-played). */
    public interface TrackStartedListener {
        @MainThread
        void onTrackStarted(Track track);
    }

    private static volatile PlaybackManager instance;

    private final Context app;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaController controller;
    private volatile PlaybackSnapshot snapshot = PlaybackSnapshot.EMPTY;
    private TrackStartedListener trackStartedListener;
    private String recordedTrackId;
    private boolean tickerRunning;

    /** Guards against stacking radio requests (auto-next / shuffle top-up). */
    private boolean radioBusy;

    /** This session's recent tracks (feeds the Smart Shuffle engine). */
    private final List<Track> sessionRecent = new ArrayList<>();
    /** Artists the listener skipped this session (weak negative signal). */
    private final Set<String> sessionSkips = new HashSet<>();
    private long currentTrackStartedAt;
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            publish(false);
            if (snapshot.isPlaying && tickerRunning) {
                main.postDelayed(this, 500);
            }
        }
    };

    public static PlaybackManager get(Context context) {
        if (instance == null) {
            synchronized (PlaybackManager.class) {
                if (instance == null) {
                    instance = new PlaybackManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private static final String TAG = "NightLight";

    private PlaybackManager(Context context) {
        this.app = context;
        connect();
    }

    private void connect() {
        SessionToken token = new SessionToken(app, new ComponentName(app, PlaybackService.class));
        MediaController.Builder builder = new MediaController.Builder(app, token);
        ListenableFuture<MediaController> future = builder.buildAsync();
        future.addListener(() -> {
            try {
                MediaController c = future.get();
                if (c == null) {
                    main.postDelayed(PlaybackManager.this::connect, 1500);
                    return;
                }
                onConnected(c);
            } catch (Exception e) {
                main.postDelayed(PlaybackManager.this::connect, 1500);
            }
        }, main::post);
    }

    private void onConnected(MediaController c) {
        if (controller != null) {
            controller.release();
        }
        controller = c;
        Player.Listener playerListener = new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                handleTrackStart();
                publish(true);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                handleTrackStart();
                if (playbackState == Player.STATE_ENDED) {
                    radioContinue();
                }
                publish(true);
            }

            @Override
            public void onMediaItemTransition(@androidx.annotation.Nullable MediaItem mediaItem, int reason) {
                recordedTrackId = null;
                handleTrackStart();
                publish(true);
            }

            @Override
            public void onRepeatModeChanged(int repeatMode) {
                Log.i(TAG, "repeat mode -> " + repeatMode);
                publish(true);
            }

            @Override
            public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                Log.i(TAG, "shuffle -> " + shuffleModeEnabled);
                publish(true);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                publish(true);
            }
        };
        controller.addListener(playerListener);
        // Restore the persisted shuffle preference (Smart by default).
        applyShuffleMode(ShufflePrefs.mode(app));
        publish(true);
    }

    // ---- Registration ----

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onPlaybackChanged(snapshot);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setTrackStartedListener(TrackStartedListener listener) {
        this.trackStartedListener = listener;
    }

    public PlaybackSnapshot getSnapshot() {
        return snapshot;
    }

    public boolean isConnected() {
        return controller != null;
    }

    // ---- Actions ----

    /** Plays a resolved list of tracks (must have stream URLs). */
    public void playTracks(List<Track> tracks, int startIndex) {
        if (controller == null || tracks == null || tracks.isEmpty()) {
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        for (Track track : tracks) {
            if (track.streamUrl == null) {
                continue;
            }
            items.add(toMediaItem(track));
        }
        if (items.isEmpty()) {
            return;
        }
        int start = Math.max(0, Math.min(startIndex, items.size() - 1));
        controller.setMediaItems(items, start, 0L);
        controller.prepare();
        controller.play();
    }

    /** Appends tracks to the end of the current queue. */
    public void addToQueue(List<Track> tracks) {
        if (controller == null || tracks == null || tracks.isEmpty()) {
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        for (Track track : tracks) {
            if (track.streamUrl != null) {
                items.add(toMediaItem(track));
            }
        }
        if (!items.isEmpty()) {
            controller.addMediaItems(items);
        }
    }

    /** Inserts a track to play right after the current one. */
    public void playNext(Track track) {
        if (controller == null || track.streamUrl == null) {
            return;
        }
        int index = controller.getCurrentMediaItemIndex() + 1;
        controller.addMediaItem(index, toMediaItem(track));
    }

    public void togglePlayPause() {
        if (controller == null) {
            return;
        }
        if (controller.isPlaying()) {
            controller.pause();
        } else {
            if (controller.getCurrentMediaItem() != null) {
                controller.play();
            }
        }
    }

    public void pause() {
        if (controller != null) {
            controller.pause();
        }
    }

    public void next() {
        if (controller == null) {
            return;
        }
        // Skipping a track within its first 30s is a weak negative signal for
        // the current artist — Smart Shuffle lowers their ranking this session.
        if (controller.isPlaying()
                && System.currentTimeMillis() - currentTrackStartedAt < 30_000) {
            Track t = currentTrack();
            if (t != null && t.artists != null && !t.artists.isEmpty()) {
                sessionSkips.add(t.artists);
                if (sessionSkips.size() > 24) {
                    sessionSkips.clear();
                }
            }
        }
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem();
        } else if (controller.getRepeatMode() != Player.REPEAT_MODE_OFF) {
            controller.seekToDefaultPosition(0);
        }
    }

    public void previous() {
        if (controller == null) {
            return;
        }
        if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem();
        } else if (controller.getCurrentPosition() > 3_000) {
            controller.seekTo(0);
        }
    }

    public void seekTo(long positionMs) {
        if (controller != null) {
            controller.seekTo(positionMs);
        }
    }

    /** Jumps to a queue index (used by the queue sheet). */
    public void seekToIndex(int index) {
        if (controller != null) {
            controller.seekTo(index, 0L);
        }
    }

    /** Cycles repeat: OFF -> ALL -> ONE -> OFF. */
    public int cycleRepeat() {
        if (controller == null) {
            return 0;
        }
        int next = (controller.getRepeatMode() + 1) % 3;
        controller.setRepeatMode(next);
        publish(true);
        return next;
    }

    public void setRepeat(int mode) {
        if (controller != null) {
            controller.setRepeatMode(mode);
        }
    }

    /**
     * Cycles the shuffle preference OFF -> SMART -> NORMAL -> OFF and applies
     * it to the controller. Smart = engine-curated contextual queue (Media3
     * shuffle stays off so our order is respected); Normal = plain random;
     * Off = sequential, no top-ups.
     *
     * @return the newly active mode ({@code smart} | {@code normal} | {@code off})
     */
    public String toggleShuffle() {
        String mode = ShufflePrefs.cycle(app);
        applyShuffleMode(mode);
        return mode;
    }    /** Applies a stored shuffle preference (used on startup / settings change). */
    public void applyShuffleMode(String mode) {
        if (controller == null) {
            return;
        }
        if (ShufflePrefs.NORMAL.equals(mode)) {
            controller.setShuffleModeEnabled(true);
        } else {
            controller.setShuffleModeEnabled(false);
            if (ShufflePrefs.SMART.equals(mode)) {
                // Respond instantly: reshuffle the tracks already in the queue
                // so the toggle visibly reorders music right away. The radio
                // top-up is fetched in the background and mixed in when ready.
                shuffleCurrentQueue();
                if (!com.nightlight.app.util.PowerModes.isLow(app.getApplicationContext())) {
                    Track seed = currentTrack();
                    if (seed != null && controller.getMediaItemCount() > 0) {
                        fetchRadio(seed, 24, true);
                    }
                }
            }
        }

        publish(true);
    }

    /**
     * Randomly reorders the queued tracks without interrupting playback: the
     * whole queue is rebuilt in shuffled order and the playing position is
     * restored, so audio never skips or restarts.
     */
    private void shuffleCurrentQueue() {
        if (controller == null || controller.getMediaItemCount() < 2) {
            return;
        }
        int current = controller.getCurrentMediaItemIndex();
        long position = controller.getCurrentPosition();
        boolean playing = controller.isPlaying();
        List<androidx.media3.common.MediaItem> items = new ArrayList<>();
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            items.add(controller.getMediaItemAt(i));
        }
        List<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            order.add(i);
        }
        java.util.Collections.shuffle(order);
        List<androidx.media3.common.MediaItem> shuffled = new ArrayList<>();
        int newCurrent = 0;
        for (int i = 0; i < order.size(); i++) {
            shuffled.add(items.get(order.get(i)));
            if (order.get(i) == current) {
                newCurrent = i;
            }
        }
        controller.setMediaItems(shuffled, newCurrent, position);
        if (playing) {
            controller.play();
        }
    }

    // ---- Radio ----

    private Track currentTrack() {
        if (controller == null) {
            return null;
        }
        MediaItem current = controller.getCurrentMediaItem();
        if (current == null) {
            return null;
        }
        if (current.localConfiguration != null && current.localConfiguration.tag instanceof Track) {
            return (Track) current.localConfiguration.tag;
        }
        return trackFromMetadata(current.mediaMetadata, current.mediaId);
    }

    /**
     * When the queue runs out (repeat off) the player keeps going with a fresh
     * batch of related songs seeded by the track that just finished.
     */
    private void radioContinue() {
        if (controller == null || radioBusy) {
            return;
        }
        if (com.nightlight.app.util.PowerModes.isLow(app.getApplicationContext())) {
            return; // Low power: stop at queue end instead of fetching more.
        }
        if (controller.getRepeatMode() != Player.REPEAT_MODE_OFF) {
            return; // repeat handles looping on its own
        }
        Track seed = currentTrack();
        if (seed == null) {
            return;
        }
        // Give the UI a moment to surface the ended state, then continue.
        main.postDelayed(() -> fetchRadio(seed, 30, false), 400);
    }

    /**
     * Fetches a related-song batch. append=true adds it behind the current
     * queue (shuffle mixing); append=false replaces the exhausted queue and
     * starts playing (auto-continue).
     */
    private void fetchRadio(Track seed, int limit, boolean append) {
        if (radioBusy) {
            return;
        }
        NightLightApp nightLight = (NightLightApp) app.getApplicationContext();
        radioBusy = true;
        nightLight.getMusicRepository().fetchRadio(seed, limit, new MusicRepository.SearchCallback() {
            @Override
            public void onSuccess(List<Track> tracks, int total, int page) {
                radioBusy = false;
                if (tracks.isEmpty() || controller == null) {
                    return;
                }
                List<Track> curated = tracks;
                if (ShufflePrefs.isSmart(app.getApplicationContext())) {
                    // Smart Shuffle: re-order + re-weight the batch around the
                    // current context (mood, recent plays, skips, likes) using
                    // weighted random selection with artist/album diversity.
                    SmartShuffleEngine engine = new SmartShuffleEngine();
                    LibraryRepository library = ((NightLightApp) app.getApplicationContext())
                            .getLibraryRepository();
                    curated = engine.generateQueue(
                            seed, tracks, new ArrayList<>(sessionRecent),
                            MoodPrefs.active(app.getApplicationContext()),
                            ShufflePrefs.discoveryRatio(app.getApplicationContext()),
                            new HashSet<>(sessionSkips),
                            library.likedIds());
                    Log.i(TAG, "smart shuffle curated " + tracks.size() + " -> " + curated.size());
                }
                if (append) {
                    Log.i(TAG, "radio top-up -> " + curated.size() + " related tracks");
                    addToQueue(curated);
                } else {
                    Log.i(TAG, "radio continue -> " + curated.size() + " related tracks after queue end");
                    if (controller.getPlaybackState() == Player.STATE_ENDED) {
                        playTracks(curated, 0);
                    }
                }
            }

            @Override
            public void onFailure(Throwable error) {
                radioBusy = false;
            }
        });
    }

    public void clearQueue() {
        if (controller == null) {
            return;
        }
        controller.clearMediaItems();
        publish(true);
    }

    /** Current queue as tracks, in playback order. */
    public List<Track> getQueueTracks() {
        List<Track> tracks = new ArrayList<>();
        if (controller == null) {
            return tracks;
        }
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            MediaItem item = controller.getMediaItemAt(i);
            Track t = item.localConfiguration != null ? (Track) item.localConfiguration.tag : null;
            if (t == null) {
                t = trackFromMetadata(item.mediaMetadata, item.mediaId);
            }
            if (t != null) {
                tracks.add(t);
            }
        }
        return tracks;
    }

    public void removeQueueItem(int index) {
        if (controller == null) {
            return;
        }
        controller.removeMediaItem(index);
        publish(true);
    }

    // ---- Internals ----

    private MediaItem toMediaItem(Track track) {
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(track.name)
                .setArtist(track.artists)
                .setAlbumTitle(track.album)
                .setArtworkUri(track.imageUrl == null || track.imageUrl.isEmpty()
                        ? null : Uri.parse(track.imageUrl))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build();

        return new MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(track.streamUrl)
                .setTag(track)
                .setMediaMetadata(metadata)
                .build();
    }

    private void handleTrackStart() {
        if (controller == null || !controller.isPlaying()) {
            return;
        }
        MediaItem current = controller.getCurrentMediaItem();
        if (current == null) {
            return;
        }
        Track track = current.localConfiguration != null ? (Track) current.localConfiguration.tag : null;
        if (track == null) {
            track = trackFromMetadata(current.mediaMetadata, current.mediaId);
        }
        if (track != null && !track.id.equals(recordedTrackId)) {
            recordedTrackId = track.id;
            currentTrackStartedAt = System.currentTimeMillis();
            sessionRecent.add(track);
            if (sessionRecent.size() > 24) {
                sessionRecent.remove(0);
            }
            if (trackStartedListener != null) {
                trackStartedListener.onTrackStarted(track);
            }
        }
    }

    /** This session's recently played tracks (for Smart Shuffle context). */
    public List<Track> getSessionRecent() {
        return new ArrayList<>(sessionRecent);
    }

    private Track trackFromMetadata(MediaMetadata metadata, String id) {
        if (metadata == null || id == null) {
            return null;
        }
        return new Track(id,
                metadata.title != null ? metadata.title.toString() : "Unknown",
                metadata.artist != null ? metadata.artist.toString() : "",
                metadata.albumTitle != null ? metadata.albumTitle.toString() : "",
                metadata.artworkUri != null ? metadata.artworkUri.toString() : "",
                null, 0L, "");
    }

    private void publish(boolean force) {
        if (controller == null) {
            return;
        }
        MediaItem current = controller.getCurrentMediaItem();
        Track track = null;
        if (current != null) {
            track = current.localConfiguration != null ? (Track) current.localConfiguration.tag : null;
            if (track == null) {
                track = trackFromMetadata(current.mediaMetadata, current.mediaId);
            }
        }

        int state = controller.getPlaybackState();
        boolean buffering = state == Player.STATE_BUFFERING;
        boolean ended = state == Player.STATE_ENDED;
        long position = ended ? 0 : controller.getCurrentPosition();
        long duration = controller.getDuration() != C.TIME_UNSET ? controller.getDuration()
                : (track != null ? track.durationMs : 0L);

        String error = null;
        if (controller.getPlayerError() != null) {
            PlaybackException e = controller.getPlayerError();
            error = e.getErrorCodeName() != null ? e.getErrorCodeName() : e.getMessage();
        }

        int currentIndex = controller.getCurrentMediaItemIndex();
        PlaybackSnapshot next = new PlaybackSnapshot(
                track,
                controller.isPlaying(),
                buffering,
                position,
                duration,
                controller.getMediaItemCount() > 0,
                controller.getRepeatMode(),
                controller.getShuffleModeEnabled(),
                error,
                controller.getMediaItemCount(),
                currentIndex,
                true);

        boolean changed = force
                || !next.equalsContent(snapshot)
                || Math.abs(next.position - snapshot.position) > 300
                || next.duration != snapshot.duration;
        snapshot = next;

        if (changed && !listeners.isEmpty()) {
            for (Listener l : listeners) {
                l.onPlaybackChanged(next);
            }
        }

        if (next.isPlaying && !tickerRunning) {
            tickerRunning = true;
            main.removeCallbacks(ticker);
            main.postDelayed(ticker, 500);
        } else if (!next.isPlaying && tickerRunning) {
            tickerRunning = false;
            main.removeCallbacks(ticker);
        }
    }
}