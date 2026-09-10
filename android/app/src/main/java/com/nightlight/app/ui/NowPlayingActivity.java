package com.nightlight.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.player.PlaybackSnapshot;
import com.nightlight.app.ui.adapters.QueueAdapter;
import com.nightlight.app.ui.fragments.NowPlayingViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class NowPlayingActivity extends AppCompatActivity {

    private NowPlayingViewModel viewModel;

    private ImageView artwork;
    private ImageView backdrop;
    private TextView title;
    private TextView artist;
    private SeekBar seekBar;
    private TextView positionText;
    private TextView durationText;
    private ImageButton playPause;
    private ImageButton shuffle;
    private ImageButton repeat;
    private ImageButton likeIcon;
    private View artworkStage;
    private View artworkRing;
    private View disc;
    private ImageView vinyl;
    private View chatButton;

    private boolean userDraggingSeek;
    private boolean artworkSized;
    private int artworkPx = 900;
    private String loadedArtworkId;
    private com.nightlight.app.util.AmbientAnimator ambient;
    private String ambientKey;
    /** Vinyl rotation: one revolution per 12s, paused with playback. */
    private android.animation.ObjectAnimator vinylAnimator;
    private static final long VINYL_REVOLUTION_MS = 12_000L;
    /** Artwork label diameter as a fraction of the vinyl diameter. */
    private static final float LABEL_FRACTION = 0.38f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_now_playing);

        com.nightlight.app.util.InsetsUtil.applySystemBars(findViewById(R.id.now_playing_root));

        viewModel = new androidx.lifecycle.ViewModelProvider(this).get(NowPlayingViewModel.class);

        artwork = findViewById(R.id.np_artwork);
        backdrop = findViewById(R.id.np_backdrop);
        artworkStage = findViewById(R.id.np_artwork_stage);
        artworkRing = findViewById(R.id.np_artwork_ring);
        disc = findViewById(R.id.np_disc);
        vinyl = findViewById(R.id.np_vinyl);
        artworkStage.post(this::ensureArtworkSize);
        title = findViewById(R.id.np_title);
        artist = findViewById(R.id.np_artist);
        seekBar = findViewById(R.id.np_seek);
        positionText = findViewById(R.id.np_position);
        durationText = findViewById(R.id.np_duration);
        playPause = findViewById(R.id.np_play_pause);
        shuffle = findViewById(R.id.np_shuffle);
        repeat = findViewById(R.id.np_repeat);
        likeIcon = findViewById(R.id.np_like_icon);
        ImageButton queueIcon = findViewById(R.id.np_queue_icon);
        View lyricsBox = findViewById(R.id.np_lyrics);

        // Lyrics icon uses the state-list tint from XML (cream normal, gold
        // pressed/active) — do NOT override it in code or the gold states break.

        findViewById(R.id.np_back).setOnClickListener(v -> finish());
        lyricsBox.setOnClickListener(v ->
                startActivity(new android.content.Intent(this, LyricsActivity.class)));
        findViewById(R.id.np_share).setOnClickListener(v -> shareListenSession());
        playPause.setOnClickListener(v -> {
            // Optimistic flip: the manager also publishes instantly, but the
            // icon changes on this tap even before the snapshot round-trips.
            PlaybackSnapshot cur = viewModel.getSnapshot().getValue();
            if (cur != null) {
                playPause.setImageResource(cur.isPlaying ? R.drawable.ic_play : R.drawable.ic_pause);
            }
            viewModel.togglePlayPause();
            playPause.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80)
                    .withEndAction(() -> playPause.animate().scaleX(1f).scaleY(1f).setDuration(140).start())
                    .start();
        });
        findViewById(R.id.np_next).setOnClickListener(v -> viewModel.next());
        findViewById(R.id.np_previous).setOnClickListener(v -> viewModel.previous());
        shuffle.setOnClickListener(v -> {
            String mode = viewModel.toggleShuffle();
            String label = "smart".equals(mode) ? getString(R.string.shuffle_smart_label)
                    : "normal".equals(mode) ? getString(R.string.shuffle_normal_label)
                    : getString(R.string.shuffle_off_label);
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show();
            renderShuffleMode(mode);
        });
        repeat.setOnClickListener(v -> {
            int mode = viewModel.cycleRepeat();
            ((NightLightApp) getApplication()).getLibraryRepository().setRepeatPref(mode);
            renderRepeat(mode);
        });
        findViewById(R.id.np_like).setOnClickListener(v -> {
            PlaybackSnapshot s = viewModel.getSnapshot().getValue();
            if (s != null && s.current != null) {
                viewModel.toggleLike(s.current);
            }
        });
        findViewById(R.id.np_queue).setOnClickListener(v -> showQueueSheet());
        chatButton = findViewById(R.id.np_chat);
        chatButton.setOnClickListener(v -> showChatSheet());
        // Show chat button only when a Listen Together session is active.
        updateChatButtonVisibility();

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    PlaybackSnapshot snap = viewModel.getSnapshot().getValue();
                    long duration = snap != null ? snap.duration : 0L;
                    long pos = duration * progress / 1000L;
                    positionText.setText(Track.formatDuration(pos));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userDraggingSeek = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userDraggingSeek = false;
                PlaybackSnapshot snap = viewModel.getSnapshot().getValue();
                long duration = snap != null ? snap.duration : 0L;
                viewModel.seekTo(duration * bar.getProgress() / 1000L);
            }
        });
    }

    private final androidx.lifecycle.Observer<PlaybackSnapshot> snapshotObserver = this::render;
    private final androidx.lifecycle.Observer<Set<String>> likesObserver = this::renderLikes;

    @Override
    protected void onStart() {
        super.onStart();
        viewModel.onStart();
        viewModel.getSnapshot().observe(this, snapshotObserver);
        viewModel.getLikedIds().observe(this, likesObserver);
        // Shuffle state always re-syncs from the persisted preference when the
        // screen becomes visible — no stale icon from a previous session.
        renderShuffleMode(com.nightlight.app.util.ShufflePrefs.mode(this));
        startAmbient();
    }

    @Override
    protected void onStop() {
        super.onStop();
        viewModel.onStop();
        viewModel.getSnapshot().removeObserver(snapshotObserver);
        viewModel.getLikedIds().removeObserver(likesObserver);
        stopAmbient();
        stopVinylRotation();
    }

    /**
     * Starts the ambient motion system. Rebuilt only when playing-state
     * actually changes (render() runs every tick).
     */
    private void startAmbient() {
        PlaybackSnapshot s = viewModel.getSnapshot().getValue();
        boolean playing = s != null && s.isPlaying;
        String key = "ambient:" + playing;
        if (key.equals(ambientKey) && ambient != null) {
            return;
        }
        ambientKey = key;
        stopAmbient();
        ambient = com.nightlight.app.util.AmbientAnimator.forNowPlaying(artwork, backdrop, artworkRing);
        ambient.start();
    }

    private void stopAmbient() {
        if (ambient != null) {
            ambient.stop();
            ambient = null;
        }
    }

    private void updateChatButtonVisibility() {
        boolean active = com.nightlight.app.player.ListenTogether.get().isActive();
        chatButton.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    private void render(PlaybackSnapshot s) {
        if (s == null || s.current == null) {
            finish();
            return;
        }
        Track track = s.current;
        title.setText(track.name);
        artist.setText(track.artists.isEmpty() ? "Unknown artist" : track.artists);

        ensureArtworkSize();
        applyPowerMode(s.isPlaying);
        if (track.imageUrl != null && !track.imageUrl.isEmpty()) {
            if (!track.id.equals(loadedArtworkId)) {
                loadedArtworkId = track.id;
                // Track transition: disk + backdrop fade in, metadata
                // follows — no instant swap, no layout jump. The vinyl
                // restarts upright with the new center label.
                disc.setAlpha(0f);
                backdrop.setAlpha(0f);
                title.setAlpha(0f);
                artist.setAlpha(0f);
                title.animate().alpha(1f).setDuration(340).setStartDelay(70).start();
                artist.animate().alpha(1f).setDuration(340).setStartDelay(130).start();
                com.nightlight.app.util.AmbientAnimator.enter(artworkStage);
                startVinylRotation();
            }
            Glide.with(this)
                    .load(track.imageUrl)
                    .placeholder(R.drawable.bg_artwork_placeholder_round)
                    .error(R.drawable.bg_artwork_placeholder_round)
                    .override(artworkPx, artworkPx)
                    .circleCrop()
                    .into(artwork);
            Glide.with(this)
                    .load(track.imageUrl)
                    .override(720, 720)
                    .centerCrop()
                    .into(backdrop);
            artwork.animate().alpha(1f).setDuration(400).start();
            backdrop.animate().alpha(1f).setDuration(900).start();
            if (disc.getAlpha() < 1f) disc.animate().alpha(1f).setDuration(400).start();
        }
        updateVinylAnimation(s.isPlaying);

        playPause.setImageResource(s.isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);

        long duration = s.duration > 0 ? s.duration : track.durationMs;
        if (!userDraggingSeek) {
            seekBar.setMax(1000);
            seekBar.setProgress(duration > 0 ? (int) (s.position * 1000 / duration) : 0);
            positionText.setText(Track.formatDuration(s.position));
        }
        durationText.setText(Track.formatDuration(duration));

        renderRepeat(s.repeatMode);
        renderShuffleMode(com.nightlight.app.util.ShufflePrefs.mode(this));
        renderLikes(viewModel.getLikedIds().getValue());
    }

    /**
     * Applies the single balanced playback profile. Artwork decode size,
     * backdrop alpha, and ambient motion are fixed to balanced defaults
     * (900px decode, 0.52 alpha, motion enabled when playing).
     */
    private void applyPowerMode(boolean playing) {
        int px = 900;
        if (px != artworkPx) {
            artworkPx = px;
            artworkSized = false;
            ensureArtworkSize();
        }
        backdrop.setAlpha(0.52f);
        if (playing) {
            startAmbient();
        } else {
            stopAmbient();
        }
    }

    /**
     * Shuffle states: OFF neutral, NORMAL gold, SMART gold + smart glyph +
     * label badge. Reads the persisted preference; the player contract is
     * applyShuffleMode() → publish() → this render, so UI and Media3 agree.
     */
    private void renderShuffleMode(String mode) {
        boolean smart = "smart".equals(mode);
        boolean normal = "normal".equals(mode);
        shuffle.setImageResource(smart ? R.drawable.ic_shuffle_smart : R.drawable.ic_shuffle);
        shuffle.setColorFilter(ContextCompat.getColor(this,
                (smart || normal) ? R.color.nightlight_gold : R.color.nightlight_cream_dim));
        shuffle.setContentDescription(smart ? getString(R.string.shuffle_smart_label)
                : normal ? getString(R.string.shuffle_normal_label)
                : getString(R.string.shuffle_off_label));
        TextView label = findViewById(R.id.np_shuffle_label);
        if (label != null) {
            label.setText(smart ? "SMART" : normal ? "SHUFFLE" : "OFF");
            label.setTextColor(ContextCompat.getColor(this,
                    (smart || normal) ? R.color.nightlight_gold : R.color.nightlight_cream_dim));
        }
    }

    /** Vinyl disk in the middle: full disc + circular artwork label, capped
     * so the transport controls stay comfortably on screen. */
    private void ensureArtworkSize() {
        if (artworkSized || artworkStage == null || artworkStage.getWidth() <= 0) {
            return;
        }
        int stageSide = Math.min(artworkStage.getWidth(), artworkStage.getHeight());
        if (stageSide <= 0) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int maxPx = Math.round(300f * density);
        int capByStage = Math.round(stageSide * 0.72f);
        int side = Math.min(stageSide, Math.min(maxPx, capByStage));
        if (side <= 0) {
            return;
        }
        artworkSized = true;
        int ringPad = Math.round(6f * density);
        android.view.ViewGroup.LayoutParams ringLp = artworkRing.getLayoutParams();
        ringLp.width = side + ringPad;
        ringLp.height = side + ringPad;
        artworkRing.setLayoutParams(ringLp);
        android.view.ViewGroup.LayoutParams discLp = disc.getLayoutParams();
        discLp.width = side;
        discLp.height = side;
        disc.setLayoutParams(discLp);
        android.view.ViewGroup.LayoutParams vinylLp = vinyl.getLayoutParams();
        vinylLp.width = side;
        vinylLp.height = side;
        vinyl.setLayoutParams(vinylLp);
        int label = Math.round(side * LABEL_FRACTION);
        android.view.ViewGroup.LayoutParams lp = artwork.getLayoutParams();
        lp.width = label;
        lp.height = label;
        artwork.setLayoutParams(lp);
        // Pin the rotation pivot to the disk center explicitly. If the spin
        // animator ever starts before layout completes, the default pivot
        // resolves to the top-left corner and the disk swings off-center
        // (reads as "shifted left"). Explicit pivots make centering
        // timing-independent.
        disc.setPivotX(side / 2f);
        disc.setPivotY(side / 2f);
    }

    /**
     * Vinyl rotation (reference: DayNight-Music): continuous spin while
     * playing, frozen the moment playback pauses. Restarted on track change
     * so the new artwork label starts upright.
     */
    private void startVinylRotation() {
        if (disc == null) return;
        if (disc.getWidth() <= 0) {
            // Not laid out yet: size first (pins pivots too). If layout still
            // hasn't happened, updateVinylAnimation() retries on the next
            // render tick — never spin around a degenerate pivot.
            artworkSized = false;
            ensureArtworkSize();
            if (disc.getWidth() <= 0) return;
        }
        disc.setPivotX(disc.getWidth() / 2f);
        disc.setPivotY(disc.getHeight() / 2f);
        stopVinylRotation();
        vinylAnimator = android.animation.ObjectAnimator.ofFloat(disc, "rotation", 0f, 360f);
        vinylAnimator.setDuration(VINYL_REVOLUTION_MS);
        vinylAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        vinylAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        PlaybackSnapshot s = viewModel.getSnapshot().getValue();
        boolean playing = s != null && s.isPlaying;
        vinylAnimator.start();
        if (!playing && vinylAnimator.isRunning()) {
            vinylAnimator.pause();
        }
    }

    private void updateVinylAnimation(boolean isPlaying) {
        if (vinylAnimator == null) {
            if (isPlaying) startVinylRotation();
            return;
        }
        if (isPlaying) {
            if (vinylAnimator.isPaused()) {
                vinylAnimator.resume();
            } else if (!vinylAnimator.isRunning()) {
                vinylAnimator.start();
            }
        } else if (vinylAnimator.isRunning()) {
            vinylAnimator.pause();
        }
    }

    private void stopVinylRotation() {
        if (vinylAnimator != null) {
            vinylAnimator.cancel();
            vinylAnimator = null;
        }
    }

    private void renderRepeat(int mode) {
        repeat.setImageResource(mode == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repeat.setColorFilter(ContextCompat.getColor(this,
                mode == 0 ? R.color.nightlight_cream_dim : R.color.nightlight_gold));
    }

    private void renderLikes(Set<String> likedIds) {
        PlaybackSnapshot s = viewModel.getSnapshot().getValue();
        if (s == null || s.current == null || likedIds == null) {
            return;
        }
        boolean liked = likedIds.contains(s.current.id);
        likeIcon.setImageResource(liked ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        likeIcon.setColorFilter(ContextCompat.getColor(this,
                liked ? R.color.nightlight_gold : R.color.nightlight_cream_dim));
    }

    private boolean sharingInProgress;

    private void shareListenSession() {
        if (sharingInProgress) return;
        if (com.nightlight.app.player.ListenTogether.get().isActive()) {
            com.nightlight.app.player.ListenTogether.shareCode(this,
                    com.nightlight.app.player.ListenTogether.get().activeCode());
            return;
        }
        sharingInProgress = true;
        androidx.appcompat.app.AlertDialog progress = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(getString(R.string.listen_starting))
                .setCancelable(false)
                .create();
        progress.show();
        // Safety timeout: never leave the UI stuck on a hung network call.
        android.os.Handler timeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable timeout = () -> {
            if (sharingInProgress) {
                sharingInProgress = false;
                try { progress.dismiss(); } catch (Exception ignored) {}
                Toast.makeText(NowPlayingActivity.this,
                        "Couldn't start the session — check your connection", Toast.LENGTH_SHORT).show();
            }
        };
        timeoutHandler.postDelayed(timeout, 15000);
        com.nightlight.app.player.ListenTogether.get().startHosting(this,
                new com.nightlight.app.player.ListenTogether.CodeCallback() {
                    @Override
                    public void onCode(String code) {
                        sharingInProgress = false;
                        timeoutHandler.removeCallbacks(timeout);
                        try { progress.dismiss(); } catch (Exception ignored) {}
                        if (isFinishing() || isDestroyed()) return;
                        com.nightlight.app.player.ListenTogether.shareCode(NowPlayingActivity.this, code);
                        updateChatButtonVisibility();
                    }

                    @Override
                    public void onError(String message) {
                        sharingInProgress = false;
                        timeoutHandler.removeCallbacks(timeout);
                        try { progress.dismiss(); } catch (Exception ignored) {}
                        if (isFinishing() || isDestroyed()) return;
                        Toast.makeText(NowPlayingActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void showQueueSheet() {
        PlaybackManager playback = PlaybackManager.get(this);
        List<Track> queue = playback.getQueueTracks();
        PlaybackSnapshot s = viewModel.getSnapshot().getValue();
        int currentIndex = s != null ? s.currentIndex : 0;

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_add_to_playlist, null);
        TextView header = view.findViewById(R.id.add_to_playlist_new);
        header.setText(R.string.action_queue);
        header.setOnClickListener(v -> {
            playback.clearQueue();
            sheet.dismiss();
        });
        view.findViewById(R.id.add_to_playlist_list).setBackgroundColor(android.graphics.Color.TRANSPARENT);

        QueueAdapter adapter = new QueueAdapter(new QueueAdapter.Callbacks() {
            @Override
            public void onItemClick(int index) {
                playback.seekToIndex(index);
                sheet.dismiss();
            }

            @Override
            public void onRemoveClick(int index) {
                playback.removeQueueItem(index);
            }
        });
        RecyclerView list = view.findViewById(R.id.add_to_playlist_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        adapter.submit(queue, currentIndex);

        sheet.setContentView(view);
        sheet.show();
    }

    /**
     * Compact chat bottom sheet for Listen Together sessions.
     * Shows message list, text input, send button.
     * Opening chat does NOT stop playback.
     */
    private void showChatSheet() {
        com.nightlight.app.player.ListenTogether lt = com.nightlight.app.player.ListenTogether.get();
        if (!lt.isActive()) {
            Toast.makeText(this, "No active session", Toast.LENGTH_SHORT).show();
            return;
        }

        BottomSheetDialog sheet = new BottomSheetDialog(this);

        // Build chat UI programmatically (no new XML layout needed).
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16f * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(android.graphics.Color.parseColor("#E60A0C1A"));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView headerText = new TextView(this);
        headerText.setText("Listening Together");
        headerText.setTextColor(getColor(R.color.nightlight_cream));
        headerText.setTextSize(16f);
        headerText.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(headerText, headerLp);

        TextView sessionCode = new TextView(this);
        sessionCode.setText(lt.activeCode());
        sessionCode.setTextColor(getColor(R.color.nightlight_gold));
        sessionCode.setTextSize(12f);
        sessionCode.setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD));
        header.addView(sessionCode);

        root.addView(header);

        // Message list
        RecyclerView messageList = new RecyclerView(this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        messageList.setLayoutManager(lm);
        messageList.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.topMargin = Math.round(8f * getResources().getDisplayMetrics().density);
        listLp.bottomMargin = Math.round(8f * getResources().getDisplayMetrics().density);
        root.addView(messageList, listLp);

        // Simple message adapter with incremental updates (no full refresh lag).
        List<com.nightlight.app.data.api.dto.SessionsDtos.ChatMessage> messages =
                new ArrayList<>(lt.getChatMessages());
        String myDeviceId = com.nightlight.app.util.TokenStore.getDeviceId();
        RecyclerView.Adapter<?> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public int getItemCount() { return messages.size(); }
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
                TextView tv = new TextView(NowPlayingActivity.this);
                tv.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(tv) {};
            }
            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                TextView tv = (TextView) holder.itemView;
                com.nightlight.app.data.api.dto.SessionsDtos.ChatMessage msg = messages.get(position);
                boolean isMe = msg.deviceId != null && msg.deviceId.equals(myDeviceId);
                String displayName = (msg.name != null && !msg.name.trim().isEmpty())
                        ? msg.name.trim() : "Listener";
                tv.setText(displayName + (isMe ? " (you)" : "") + ": " + (msg.text != null ? msg.text : ""));
                tv.setTextColor(getColor(isMe ? R.color.nightlight_gold : R.color.nightlight_cream));
                tv.setTextSize(14f);
                int dp4 = Math.round(4f * getResources().getDisplayMetrics().density);
                int dp8 = Math.round(8f * getResources().getDisplayMetrics().density);
                tv.setPadding(dp8, dp4, dp8, dp4);
            }
        };
        messageList.setAdapter(adapter);
        messageList.setItemAnimator(null);

        // Scroll to bottom on new messages — incremental insert, instant jump.
        lt.setChatListener(msgs -> {
            int prev = messages.size();
            java.util.Set<String> have = new java.util.HashSet<>();
            for (com.nightlight.app.data.api.dto.SessionsDtos.ChatMessage m : messages) {
                if (m.id != null) have.add(m.id);
            }
            List<com.nightlight.app.data.api.dto.SessionsDtos.ChatMessage> fresh = new ArrayList<>();
            for (com.nightlight.app.data.api.dto.SessionsDtos.ChatMessage m : msgs) {
                if (m.id == null || have.add(m.id)) fresh.add(m);
            }
            if (fresh.isEmpty() && msgs.size() == prev) return;
            if (fresh.isEmpty()) {
                // Full resync (e.g. capped history): replace without animation.
                messages.clear();
                messages.addAll(msgs);
                adapter.notifyDataSetChanged();
            } else {
                for (com.nightlight.app.data.api.dto.SessionsDtos.ChatMessage m : fresh) messages.add(m);
                adapter.notifyItemRangeInserted(prev, fresh.size());
            }
            if (!messages.isEmpty()) {
                messageList.scrollToPosition(messages.size() - 1);
            }
        });

        // Input row
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams inputRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputRow.setLayoutParams(inputRowLp);

        EditText input = new EditText(this);
        input.setHint("Type a message…");
        input.setSingleLine(true);
        input.setTextColor(getColor(R.color.nightlight_cream));
        input.setHintTextColor(getColor(R.color.nightlight_cream_dim));
        input.setTextSize(14f);
        android.graphics.drawable.GradientDrawable inputBg = new android.graphics.drawable.GradientDrawable();
        inputBg.setColor(android.graphics.Color.parseColor("#1A121B3D"));
        inputBg.setCornerRadius(Math.round(20f * getResources().getDisplayMetrics().density));
        inputBg.setStroke(Math.round(1f * getResources().getDisplayMetrics().density),
                android.graphics.Color.parseColor("#33FFFFFF"));
        input.setBackground(inputBg);
        int inputPad = Math.round(12f * getResources().getDisplayMetrics().density);
        input.setPadding(inputPad, inputPad, inputPad, inputPad);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inputLp.setMarginEnd(Math.round(8f * getResources().getDisplayMetrics().density));
        inputRow.addView(input, inputLp);

        TextView sendBtn = new TextView(this);
        sendBtn.setText("Send");
        sendBtn.setTextColor(getColor(R.color.nightlight_cream));
        sendBtn.setTextSize(14f);
        sendBtn.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        sendBtn.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable sendBg = new android.graphics.drawable.GradientDrawable();
        sendBg.setColor(getColor(R.color.nightlight_blue));
        sendBg.setCornerRadius(Math.round(20f * getResources().getDisplayMetrics().density));
        sendBtn.setBackground(sendBg);
        int sendPadH = Math.round(16f * getResources().getDisplayMetrics().density);
        int sendPadV = Math.round(10f * getResources().getDisplayMetrics().density);
        sendBtn.setPadding(sendPadH, sendPadV, sendPadH, sendPadV);
        sendBtn.setOnClickListener(v -> {
            String msg = input.getText().toString().trim();
            if (msg.isEmpty()) return;
            lt.sendChatMessage(this, msg, null);
            input.setText("");
        });
        inputRow.addView(sendBtn);

        root.addView(inputRow);

        sheet.setContentView(root);
        sheet.setOnDismissListener(d -> {
            lt.setChatListener(null);
        });
        sheet.show();

        // Scroll to current position
        if (!messages.isEmpty()) {
            messageList.scrollToPosition(messages.size() - 1);
        }
    }
}
