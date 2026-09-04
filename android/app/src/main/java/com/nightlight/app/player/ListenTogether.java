package com.nightlight.app.player;

import android.content.Context;
import android.util.Log;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.data.api.ApiClient;
import com.nightlight.app.data.api.NightLightApi;
import com.nightlight.app.data.api.dto.ApiResponse;
import com.nightlight.app.data.api.dto.SessionsDtos;
import com.nightlight.app.data.api.dto.UserDtos;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.ui.common.TrackPlayer;
import com.nightlight.app.util.AppExecutors;
import com.nightlight.app.util.TokenStore;

import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * "Listen together": keeps two or more devices on the same song at the same
 * time. The host shares a 6-letter code; joiners poll the session and align
 * their playback (track switch, seek, play/pause). The host publishes its
 * playback state on the same cadence.
 */
public final class ListenTogether {

    private static final String TAG = "ListenTogether";
    private static final long POLL_MS = 2500;
    private static final long DRIFT_TOLERANCE_MS = 2500;

    private static final ListenTogether INSTANCE = new ListenTogether();

    public interface CodeCallback {
        void onCode(String code);

        void onError(String message);
    }

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "listen-together");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<String> activeCode = new AtomicReference<>(null);
    private final AtomicBoolean hosting = new AtomicBoolean(false);
    private final AtomicLong lastPublished = new AtomicLong(0);

    private Context appContext;

    private ListenTogether() {
    }

    public static ListenTogether get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return activeCode.get() != null;
    }

    public String activeCode() {
        return activeCode.get();
    }

    // ---- Hosting ----

    public void startHosting(Context context, CodeCallback callback) {
        appContext = context.getApplicationContext();
        final PlaybackSnapshot snap = PlaybackManager.get(context).getSnapshot();
        final Track track = snap != null ? snap.current : null;
        if (track == null) {
            callback.onError("Nothing is playing to share");
            return;
        }
        executor.execute(() -> {
            SessionsDtos.CreateRequest req = new SessionsDtos.CreateRequest();
            req.deviceId = TokenStore.getDeviceId();
            req.name = "Ash";
            req.track = toSnapshot(track);
            try {
                ApiResponse<SessionsDtos.SessionDto> body =
                        ApiClient.nightLightApi(appContext).createSession(req).execute().body();
                if (body == null || !body.success || body.data == null) {
                    AppExecutors.onMain(() -> callback.onError("Couldn't start the session"));
                    return;
                }
                final String code = body.data.code;
                activeCode.set(code);
                hosting.set(true);
                lastPublished.set(0);
                scheduleHostPublish();
                Log.w(TAG, "hosting session code=" + code);
                AppExecutors.onMain(() -> callback.onCode(code));
            } catch (Exception e) {
                Log.w(TAG, "create failed", e);
                AppExecutors.onMain(() -> callback.onError("Couldn't start the session"));
            }
        });
    }

    /** Launches the system share sheet with the join link. */
    public static void shareCode(Context context, String code) {
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_SUBJECT,
                context.getString(com.nightlight.app.R.string.listen_share_title));
        send.putExtra(android.content.Intent.EXTRA_TEXT,
                context.getString(com.nightlight.app.R.string.listen_share_body, code, code));
        context.startActivity(android.content.Intent.createChooser(send,
                context.getString(com.nightlight.app.R.string.listen_share_title)));
    }

    public void stop() {
        hosting.set(false);
        activeCode.set(null);
    }

    private void scheduleHostPublish() {
        executor.scheduleAtFixedRate(() -> publishState(), POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void publishState() {
        final String code = activeCode.get();
        if (code == null || !hosting.get() || appContext == null) {
            return;
        }
        final PlaybackSnapshot snap = PlaybackManager.get(appContext).getSnapshot();
        if (snap == null || snap.current == null) {
            return;
        }
        SessionsDtos.UpdateStateRequest req = new SessionsDtos.UpdateStateRequest();
        req.deviceId = TokenStore.getDeviceId();
        req.track = toSnapshot(snap.current);
        req.positionMs = snap.position;
        req.playing = snap.isPlaying;
        try {
            ApiClient.nightLightApi(appContext).updateSessionState(code, req).execute();
        } catch (Exception e) {
            // Next tick retries; sessions tolerate gaps.
        }
    }

    // ---- Joining ----

    public void join(Context context, String code, CodeCallback callback) {
        appContext = context.getApplicationContext();
        executor.execute(() -> {
            SessionsDtos.JoinRequest req = new SessionsDtos.JoinRequest();
            req.code = code.trim().toUpperCase();
            req.deviceId = TokenStore.getDeviceId();
            req.name = "Friend";
            try {
                ApiResponse<SessionsDtos.SessionDto> body =
                        ApiClient.nightLightApi(appContext).joinSession(req).execute().body();
                if (body == null || !body.success || body.data == null || body.data.state == null) {
                    AppExecutors.onMain(() -> callback.onError("Session not found — check the code"));
                    return;
                }
                final SessionsDtos.SessionDto session = body.data;
                activeCode.set(session.code);
                hosting.set(false);
                scheduleFollowerPoll();
                Log.w(TAG, "joined session code=" + session.code);
                // Playback calls must run on the MediaController's thread (main).
                AppExecutors.onMain(() -> {
                    playRemote(session.state);
                    callback.onCode(session.code);
                });
            } catch (Exception e) {
                Log.w(TAG, "join failed", e);
                AppExecutors.onMain(() -> callback.onError("Couldn't join — check your connection"));
            }
        });
    }

    private void scheduleFollowerPoll() {
        executor.scheduleAtFixedRate(this::follow, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void follow() {
        final String code = activeCode.get();
        if (code == null || hosting.get() || appContext == null) {
            return;
        }
        try {
            ApiResponse<SessionsDtos.SessionDto> body =
                    ApiClient.nightLightApi(appContext).getSession(code).execute().body();
            if (body == null || !body.success || body.data == null) {
                return;
            }
            final SessionsDtos.SessionState remote = body.data.state;
            AppExecutors.onMain(() -> playRemote(remote));
        } catch (Exception e) {
            // Transient network gap — retry next tick.
        }
    }

    /** Aligns local playback with the remote session state (main thread). */
    private void playRemote(SessionsDtos.SessionState remote) {
        if (remote == null || remote.track == null || appContext == null) {
            return;
        }
        PlaybackSnapshot local = PlaybackManager.get(appContext).getSnapshot();
        Track localTrack = local != null ? local.current : null;
        boolean sameTrack = localTrack != null && localTrack.id.equals(remote.track.id);

        if (!sameTrack) {
            UserDtos.TrackSnapshotDto dto = new UserDtos.TrackSnapshotDto();
            dto.id = remote.track.id;
            dto.name = remote.track.name;
            dto.artists = remote.track.artists;
            dto.album = remote.track.album;
            dto.imageUrl = remote.track.imageUrl;
            dto.duration = (int) remote.track.duration;
            dto.year = remote.track.year;
            Track snapshot = Track.fromSnapshot(dto);
            TrackPlayer.play(appContext, Collections.singletonList(snapshot), 0);
            // First seek comes in on the next tick once the track is loaded.
            return;
        }

        if (local == null) {
            return;
        }
        long remoteAge = Math.max(0, System.currentTimeMillis() - remote.updatedAt);
        long expected = remote.positionMs + (remote.playing ? remoteAge : 0);

        Log.d(TAG, "follow remote=" + remote.playing + " exp=" + expected + " local=" + local.isPlaying
                + " pos=" + local.position + " drift=" + (local.position - expected));
        if (remote.playing && !local.isPlaying) {
            if (local.isBuffering || (local.position < 200 && expected < 1500)) {
                // Already requested play — wait for buffering to finish.
                return;
            }
            Log.d(TAG, "follow action=resume");
            PlaybackManager.get(appContext).togglePlayPause();
            return;
        }
        if (!remote.playing && local.isPlaying) {
            Log.d(TAG, "follow action=pause");
            PlaybackManager.get(appContext).togglePlayPause();
            return;
        }
        if (remote.playing && Math.abs(local.position - expected) > DRIFT_TOLERANCE_MS) {
            Log.d(TAG, "follow action=seek to " + Math.max(0, expected - 400));
            PlaybackManager.get(appContext).seekTo(Math.max(0, expected - 400));
        }
    }

    private static SessionsDtos.TrackSnapshot toSnapshot(Track track) {
        SessionsDtos.TrackSnapshot s = new SessionsDtos.TrackSnapshot();
        s.id = track.id;
        s.name = track.name;
        s.artists = track.artists;
        s.album = track.album;
        s.imageUrl = track.imageUrl;
        s.duration = track.durationMs;
        s.year = track.year;
        return s;
    }
}
