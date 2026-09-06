package com.nightlight.app.player;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single source of truth for playback state. UI never reads ExoPlayer
 * directly: it observes {@link PlaybackSnapshot} delivered through
 * {@link Listener#onPlaybackChanged}. Everything here runs on the main thread.
 *
 * Smart Shuffle uses an authoritative curated queue: Media3 shuffle is
 * disabled so our order is respected, and a generation counter protects
 * against stale radio responses contaminating the active queue.
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

    /**
     * Monotonically increasing counter. Every time Smart Shuffle is activated
     * or the user manually changes tracks, this is bumped so in-flight radio
     * responses for older seeds are discarded instead of mutating the queue.
     */
    private final AtomicInteger smartGeneration = new AtomicInteger(0);

    /** Seed track id of the most recent radio request (for validation). */
    private String lastRadioSeedId;

    /** Media id of the last item a transition was accepted for (double-advance guard). */
    private String lastTransitionId;

    /**
     * This session's recent tracks (feeds the Smart Shuffle engine).
     * Bounded at {@link #MAX_SESSION_RECENT}.
     */
    private final List<Track> sessionRecent = new ArrayList<>();
    private static final int MAX_SESSION_RECENT = 24;

    /**
     * Artists the listener skipped this session (weak negative signal).
     * Bounded at {@link #MAX_SESSION_SKIPS}.
     */
    private final Set<String> sessionSkips = new HashSet<>();
    private static final int MAX_SESSION_SKIPS = 24;

    private long currentTrackStartedAt;

    /** Debounce window for the next() action (one tap = one track). */
    private static final long NEXT_DEBOUNCE_MS = 350;
    private long lastNextAt;

    /**
     * User NEXT taps that have not yet been reflected by an item transition.
     * Media3 can coalesce back-to-back relative seeks when a previous skip is
     * still buffering, which used to silently drop rapid taps; the backlog is
     * drained one transition at a time so every tap still lands.
     */
    private int pendingNext;

    /** Retry watchdog for a skip that never manifested as a transition. */
    private static final long NEXT_PUSH_RETRY_MS = 1200;
    private long nextPushGen;
    private final Runnable nextPushWatchdog = new Runnable() {
        @Override
        public void run() {
            pushNext();
        }
    };

    /**
     * When remaining upcoming tracks fall below this threshold and Smart
     * Shuffle is active, a proactive radio top-up is triggered so playback
     * never stutters from an empty queue.
     */
    private static final int PROACTIVE_TOPUP_THRESHOLD = 6;

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
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                // Race hardening: when the item actually changes (manual next,
                // auto-advance, seekToIndex), bump the generation so in-flight
                // radio responses for the previous seed can never mutate the
                // queue around the new track — even while still buffering.
                String newId = mediaItem != null ? mediaItem.mediaId : null;
                if (newId != null && !newId.equals(lastTransitionId)) {
                    lastTransitionId = newId;
                    smartGeneration.incrementAndGet();
                }
                // Consume one pending NEXT: an item transition just occurred, so
                // one user skip has manifested. Drain the rest shortly after,
                // spacing the seeks so Media3 applies every one of them.
                if (pendingNext > 0) {
                    pendingNext--;
                    nextPushGen++;
                    main.removeCallbacks(nextPushWatchdog);
                    if (pendingNext > 0) {
                        main.postDelayed(nextPushWatchdog, 300);
                    }
                }
                recordedTrackId = null;
                handleTrackStart();
                checkProactiveTopUp();
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
        // Deduplicate by stable track ID before insertion — never add the same
        // track twice to the queue.
        Set<String> existingIds = existingQueueIds();
        List<MediaItem> items = new ArrayList<>();
        for (Track track : tracks) {
            if (track.streamUrl != null && !existingIds.contains(track.id)) {
                existingIds.add(track.id);
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
        // One tap = exactly one track (spec: a single NEXT must never skip
        // A → C). The guard window also swallows a duplicated transport event,
        // while staying short enough to feel instant.
        long now = System.currentTimeMillis();
        if (now - lastNextAt < NEXT_DEBOUNCE_MS) {
            return;
        }
        lastNextAt = now;
        // Skipping a track within its first 30s is a weak negative signal for
        // the current artist — Smart Shuffle lowers their ranking this session.
        if (controller.isPlaying()
                && System.currentTimeMillis() - currentTrackStartedAt < 30_000) {
            Track t = currentTrack();
            if (t != null && t.artists != null && !t.artists.isEmpty()) {
                sessionSkips.add(t.artists);
                if (sessionSkips.size() > MAX_SESSION_SKIPS) {
                    sessionSkips.clear();
                }
            }
        }
        pendingNext++;
        pushNext();
    }

    /**
     * Advances the queue for each pending NEXT. The debounce in {@link #next()}
     * still guarantees one tap == one track; this loop only makes sure a skip is
     * never lost when Media3 coalesces a seek issued while a previous skip is
     * still buffering/transitioning.
     */
    private void pushNext() {
        if (controller == null || pendingNext <= 0) {
            return;
        }
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem();
        } else if (controller.getRepeatMode() != Player.REPEAT_MODE_OFF) {
            controller.seekToDefaultPosition(0);
        } else {
            // Nothing after the current item and repeat is off: clear the
            // backlog so the retry watchdog does not spin forever.
            pendingNext = 0;
            return;
        }
        // Watchdog: if the seek was coalesced away while another transition was
        // in flight, re-push until an item actually changes.
        nextPushGen++;
        main.removeCallbacks(nextPushWatchdog);
        main.postDelayed(nextPushWatchdog, NEXT_PUSH_RETRY_MS);
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
    }

    /**
     * Applies a stored shuffle preference (used on startup / settings change).
     *
     * Key Smart Shuffle contract:
     * 1. Media3 shuffle is disabled — our curated order is authoritative.
     * 2. The queue is immediately re-ordered using SmartShuffleEngine so the
     *    toggle is visually instant.
     * 3. A radio top-up is fetched in the background and appended when ready.
     * 4. A generation counter is bumped so stale responses are discarded.
     */
    public void applyShuffleMode(String mode) {
        if (controller == null) {
            return;
        }
        if (ShufflePrefs.NORMAL.equals(mode)) {
            controller.setShuffleModeEnabled(true);
            // Leaving Smart mode: bump generation so any in-flight radio
            // responses for the old smart queue are discarded.
            smartGeneration.incrementAndGet();
        } else {
            controller.setShuffleModeEnabled(false);
            if (ShufflePrefs.SMART.equals(mode)) {
                // Entering Smart mode: bump generation to invalidate old requests.
                int gen = smartGeneration.incrementAndGet();
                // Immediately curate the existing queue so the toggle is visible.
                curateSmartQueue(gen);
                // Fetch radio candidates in the background.
                Track seed = currentTrack();
                if (seed != null && controller.getMediaItemCount() > 0) {
                    fetchRadio(seed, 24, true, gen);
                }
            }
        }
        publish(true);
    }

    /**
     * Immediately reorders the current Media3 queue using SmartShuffleEngine
     * instead of a dumb random shuffle. The currently playing track stays at
     * index 0 (preserving playback), and all other tracks are scored and
     * re-ordered with context, mood, diversity, and skip penalties.
     *
     * This makes the Smart Shuffle toggle visually instant — the user sees
     * the queue change immediately.
     */
    private void curateSmartQueue(int generation) {
        if (controller == null || controller.getMediaItemCount() < 2) {
            return;
        }
        long position = controller.getCurrentPosition();
        boolean playing = controller.isPlaying();

        // Resolve the seed from the CURRENT item first, then collect the rest of
        // the queue. The seed is removed by IDENTITY (object or stable id) —
        // never by a re-derived index, which null-tag items would shift onto the
        // wrong song.
        Track currentTagged = currentTrack();
        MediaItem currentItem = controller.getCurrentMediaItem();
        Track seed = currentTagged;
        if (seed == null && currentItem != null) {
            seed = trackFromMetadata(currentItem.mediaMetadata, currentItem.mediaId);
        }
        if (seed == null) {
            return;
        }
        List<Track> allTracks = new ArrayList<>();
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            MediaItem item = controller.getMediaItemAt(i);
            Track t = item.localConfiguration != null ? (Track) item.localConfiguration.tag : null;
            if (t == null) {
                t = trackFromMetadata(item.mediaMetadata, item.mediaId);
            }
            if (t == null) {
                continue;
            }
            if (t == currentTagged || t.id.equals(seed.id)) {
                continue; // seed itself is never re-queued
            }
            allTracks.add(t);
        }
        if (allTracks.isEmpty()) {
            return;
        }

        // Run remaining tracks through SmartShuffleEngine for intelligent ordering.
        NightLightApp nightLight = (NightLightApp) app.getApplicationContext();
        SmartShuffleEngine engine = new SmartShuffleEngine();
        LibraryRepository library = nightLight.getLibraryRepository();
        List<Track> curated = engine.generateQueue(
                seed, allTracks, new ArrayList<>(sessionRecent),
                com.nightlight.app.util.AccountPrefs.effectiveMood(app.getApplicationContext()),
                ShufflePrefs.discoveryRatio(app.getApplicationContext()),
                new HashSet<>(sessionSkips),
                library.likedIds(),
                seed.id);

        // Verify generation hasn't changed (user may have toggled away rapidly).
        if (generation != smartGeneration.get()) {
            return;
        }

        // Rebuild the queue: seed first, then curated order.
        List<MediaItem> rebuilt = new ArrayList<>();
        rebuilt.add(toMediaItem(seed));
        for (Track t : curated) {
            rebuilt.add(toMediaItem(t));
        }
        controller.setMediaItems(rebuilt, 0, position);
        if (playing) {
            controller.play();
        }
        Log.i(TAG, "smart queue curated " + allTracks.size() + " tracks into " + curated.size());
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
     * Only radios in Smart mode; Off mode stops at queue end; Normal uses
     * Media3's own shuffle/loop.
     */
    private void radioContinue() {
        if (controller == null || radioBusy) {
            return;
        }
        // Off mode: no radio, no auto-continue.
        if (ShufflePrefs.isOff(app.getApplicationContext())) {
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
        int gen = smartGeneration.get();
        // Give the UI a moment to surface the ended state, then continue.
        main.postDelayed(() -> fetchRadio(seed, 30, false, gen), 400);
    }

    /**
     * Proactively tops up the queue when remaining upcoming tracks are running
     * low. This prevents the player from stuttering or showing an empty queue
     * while waiting for the next radio batch.
     */
    private void checkProactiveTopUp() {
        if (controller == null || radioBusy) {
            return;
        }
        if (!ShufflePrefs.isSmart(app.getApplicationContext())) {
            return;
        }
        if (com.nightlight.app.util.PowerModes.isLow(app.getApplicationContext())) {
            return;
        }
        if (controller.getRepeatMode() != Player.REPEAT_MODE_OFF) {
            return;
        }
        int remaining = controller.getMediaItemCount() - controller.getCurrentMediaItemIndex() - 1;
        if (remaining > PROACTIVE_TOPUP_THRESHOLD) {
            return;
        }
        Track seed = currentTrack();
        if (seed == null) {
            return;
        }
        int gen = smartGeneration.get();
        Log.i(TAG, "smart proactive top-up: " + remaining + " tracks remaining");
        fetchRadio(seed, 24, true, gen);
    }

    /**
     * Fetches a related-song batch. append=true adds it behind the current
     * queue (shuffle mixing); append=false replaces the exhausted queue and
     * starts playing (auto-continue). Transient failures (e.g. a cold-start
     * timeout against the server) are retried once after a short delay so a
     * flaky first request doesn't leave shuffle/auto-next dead.
     *
     * Every request carries a generation token. If the user toggles modes or
     * changes tracks while the request is in-flight, the generation will have
     * changed and the stale response is silently discarded.
     */
    private void fetchRadio(Track seed, int limit, boolean append, int generation) {
        fetchRadio(seed, limit, append, generation, 0);
    }

    private void fetchRadio(Track seed, int limit, boolean append, int generation, int attempt) {
        if (radioBusy) {
            return;
        }
        NightLightApp nightLight = (NightLightApp) app.getApplicationContext();
        radioBusy = true;
        lastRadioSeedId = seed.id;
        // append=true requests may be served from the short-lived cache;
        // auto-continue (append=false) must always hit the network.
        nightLight.getMusicRepository().fetchRadio(seed, limit, append, new MusicRepository.SearchCallback() {
            @Override
            public void onSuccess(List<Track> tracks, int total, int page) {
                // Race protection: discard unless BOTH the generation token and
                // the seed are still current. A stale response for song A must
                // never mutate the queue now built around song B.
                String requestSeed = lastRadioSeedId;
                if (generation != smartGeneration.get()
                        || requestSeed == null || !requestSeed.equals(seed.id)) {
                    radioBusy = false;
                    Log.i(TAG, "radio stale (gen " + generation + " != " + smartGeneration.get()
                            + " or seed " + requestSeed + " != " + seed.id + "), discarding");
                    return;
                }
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
                    LibraryRepository library = nightLight.getLibraryRepository();
                    curated = engine.generateQueue(
                            seed, tracks, new ArrayList<>(sessionRecent),
                            com.nightlight.app.util.AccountPrefs.effectiveMood(app.getApplicationContext()),
                            ShufflePrefs.discoveryRatio(app.getApplicationContext()),
                            new HashSet<>(sessionSkips),
                            library.likedIds(),
                            controller.getCurrentMediaItem() != null
                                    ? (controller.getCurrentMediaItem().localConfiguration != null
                                    && controller.getCurrentMediaItem().localConfiguration.tag instanceof Track)
                                    ? ((Track) controller.getCurrentMediaItem().localConfiguration.tag).id
                                    : controller.getCurrentMediaItem().mediaId
                                    : null);
                    Log.i(TAG, "smart shuffle curated " + tracks.size() + " -> " + curated.size());
                }
                if (append) {
                    addToQueue(curated);
                    Log.i(TAG, "smart queue: seed=" + seed.id + " candidates=" + tracks.size()
                            + " curated=" + curated.size() + " queueSize=" + controller.getMediaItemCount()
                            + " next=" + nextArtists(curated, 3));
                } else {
                    Log.i(TAG, "radio continue: seed=" + seed.id + " curated=" + curated.size()
                            + " next=" + nextArtists(curated, 3));
                    if (controller.getPlaybackState() == Player.STATE_ENDED) {
                        playTracks(curated, 0);
                    }
                }
            }

            @Override
            public void onFailure(Throwable error) {
                radioBusy = false;
                if (attempt == 0) {
                    Log.w(TAG, "radio fetch failed (" + error + "); retrying once");
                    main.postDelayed(() -> fetchRadio(seed, limit, append, generation, 1), 3000);
                } else {
                    Log.w(TAG, "radio fetch failed after retry: " + error);
                }
            }
        });
    }

    /**
     * Concise, secret-free curation record: the first artists the queue will
     * actually play. Never logs URLs, tokens, or credentials.
     */
    private static String nextArtists(List<Track> tracks, int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tracks.size() && i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String a = tracks.get(i).artists;
            sb.append(a != null && a.length() > 24 ? a.substring(0, 24) : a);
        }
        return sb.append(']').toString();
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

    /**
     * Collects all track IDs currently in the Media3 queue. Used for
     * deduplication before inserting new tracks.
     */
    private Set<String> existingQueueIds() {
        Set<String> ids = new HashSet<>();
        if (controller == null) {
            return ids;
        }
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            MediaItem item = controller.getMediaItemAt(i);
            ids.add(item.mediaId);
        }
        return ids;
    }

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
            // The seed changed: bump the generation so any in-flight radio
            // response for the previous track is discarded instead of mutating
            // the queue built around the new one.
            smartGeneration.incrementAndGet();
            sessionRecent.add(track);
            if (sessionRecent.size() > MAX_SESSION_RECENT) {
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
