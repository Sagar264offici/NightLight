package com.nightlight.app.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.SeekBar;
import android.widget.TextView;

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

import java.util.List;
import java.util.Set;

public final class NowPlayingActivity extends AppCompatActivity {

    private NowPlayingViewModel viewModel;

    private ImageView artwork;
    private TextView title;
    private TextView artist;
    private SeekBar seekBar;
    private TextView positionText;
    private TextView durationText;
    private ImageButton playPause;
    private ImageButton shuffle;
    private ImageButton repeat;
    private ImageButton like;
    private View artworkStage;
    private View artworkRing;

    private boolean userDraggingSeek;
    private boolean artworkSized;
    private int artworkPx = 900;
    private android.animation.ObjectAnimator ringAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_now_playing);

        com.nightlight.app.util.InsetsUtil.applySystemBars(findViewById(R.id.now_playing_root));

        NightLightApp app = (NightLightApp) getApplication();
        viewModel = new androidx.lifecycle.ViewModelProvider(this).get(NowPlayingViewModel.class);

        artwork = findViewById(R.id.np_artwork);
        artworkStage = findViewById(R.id.np_artwork_stage);
        artworkRing = findViewById(R.id.np_artwork_ring);
        artworkStage.post(this::ensureArtworkSize);
        title = findViewById(R.id.np_title);
        artist = findViewById(R.id.np_artist);
        seekBar = findViewById(R.id.np_seek);
        positionText = findViewById(R.id.np_position);
        durationText = findViewById(R.id.np_duration);
        playPause = findViewById(R.id.np_play_pause);
        shuffle = findViewById(R.id.np_shuffle);
        repeat = findViewById(R.id.np_repeat);
        like = findViewById(R.id.np_like);

        findViewById(R.id.np_back).setOnClickListener(v -> finish());
        findViewById(R.id.np_lyrics).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, LyricsActivity.class)));
        findViewById(R.id.np_share).setOnClickListener(v -> shareListenSession());
        playPause.setOnClickListener(v -> viewModel.togglePlayPause());
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
            app.getLibraryRepository().setRepeatPref(mode);
            renderRepeat(mode);
        });
        like.setOnClickListener(v -> {
            PlaybackSnapshot s = viewModel.getSnapshot().getValue();
            if (s != null && s.current != null) {
                viewModel.toggleLike(s.current);
            }
        });
        findViewById(R.id.np_queue).setOnClickListener(v -> showQueueSheet());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    long duration = viewModel.getSnapshot().getValue() != null
                            ? viewModel.getSnapshot().getValue().duration : 0L;
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
                long duration = viewModel.getSnapshot().getValue() != null
                        ? viewModel.getSnapshot().getValue().duration : 0L;
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
    }

    @Override
    protected void onStop() {
        super.onStop();
        viewModel.onStop();
        viewModel.getSnapshot().removeObserver(snapshotObserver);
        viewModel.getLikedIds().removeObserver(likesObserver);
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
            int corner = Math.round(22f * getResources().getDisplayMetrics().density);
            Glide.with(this)
                    .load(track.imageUrl)
                    .placeholder(R.drawable.bg_artwork_placeholder_round)
                    .error(R.drawable.bg_artwork_placeholder_round)
                    .override(artworkPx, artworkPx)
                    .transform(new com.bumptech.glide.load.resource.bitmap.RoundedCorners(corner))
                    .into(artwork);
        }

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
     * Shuffle button states: OFF dim, NORMAL gold (plain random), SMART gold
     * with the smart-shuffle glyph — Smart is the default and stays on top.
     */
    private void renderShuffleMode(String mode) {
        boolean smart = "smart".equals(mode);
        boolean normal = "normal".equals(mode);
        shuffle.setImageResource(smart ? R.drawable.ic_shuffle_smart : R.drawable.ic_shuffle);
        shuffle.setColorFilter(ContextCompat.getColor(this,
                (smart || normal) ? R.color.nightlight_gold : R.color.nightlight_cream_dim));
    }

    /**
     * Tunes visuals to the power mode: Low uses small artwork and no motion,
     * Balanced the default look, High bigger artwork plus a slow breathing
     * glow on the gold ring while music plays.
     */
    private void applyPowerMode(boolean playing) {
        String mode = com.nightlight.app.util.PowerModes.get(this);
        int px = "high".equals(mode) ? 1400 : "low".equals(mode) ? 480 : 900;
        if (px != artworkPx) {
            artworkPx = px;
            artworkSized = false;
            ensureArtworkSize();
        }
        boolean wantGlow = playing && "high".equals(mode);
        if (wantGlow == (ringAnimator != null && ringAnimator.isStarted())) {
            return;
        }
        if (ringAnimator != null) {
            ringAnimator.cancel();
            ringAnimator = null;
        }
        if (wantGlow) {
            ringAnimator = android.animation.ObjectAnimator.ofFloat(artworkRing, "alpha", 0.35f, 1f);
            ringAnimator.setDuration(1800);
            ringAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            ringAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            ringAnimator.start();
        } else {
            artworkRing.setAlpha(1f);
        }
    }

    /** Gives the album art a large square footprint inside its stage. */
    private void ensureArtworkSize() {
        if (artworkSized || artworkStage == null || artworkStage.getWidth() <= 0) {
            return;
        }
        int side = Math.min(artworkStage.getWidth(), artworkStage.getHeight());
        if (side <= 0) {
            return;
        }
        artworkSized = true;
        int ringPad = Math.round(5f * getResources().getDisplayMetrics().density);
        android.view.ViewGroup.LayoutParams ringLp = artworkRing.getLayoutParams();
        ringLp.width = side + ringPad;
        ringLp.height = side + ringPad;
        artworkRing.setLayoutParams(ringLp);
        android.view.ViewGroup.LayoutParams lp = artwork.getLayoutParams();
        lp.width = side;
        lp.height = side;
        artwork.setLayoutParams(lp);
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
        like.setImageResource(liked ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        like.setColorFilter(ContextCompat.getColor(this,
                liked ? R.color.nightlight_gold : R.color.nightlight_gold_dim));
    }

    private void shareListenSession() {
        if (com.nightlight.app.player.ListenTogether.get().isActive()) {
            // Already hosting/joined: re-share the current code.
            com.nightlight.app.player.ListenTogether.shareCode(this,
                    com.nightlight.app.player.ListenTogether.get().activeCode());
            return;
        }
        Toast.makeText(this, R.string.listen_starting, Toast.LENGTH_SHORT).show();
        com.nightlight.app.player.ListenTogether.get().startHosting(this,
                new com.nightlight.app.player.ListenTogether.CodeCallback() {
                    @Override
                    public void onCode(String code) {
                        com.nightlight.app.player.ListenTogether.shareCode(NowPlayingActivity.this, code);
                    }

                    @Override
                    public void onError(String message) {
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
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_to_playlist, null);
        TextView header = view.findViewById(R.id.add_to_playlist_new);
        header.setText(R.string.action_queue);
        header.setOnClickListener(v -> {
            playback.clearQueue();
            sheet.dismiss();
        });
        view.findViewById(R.id.add_to_playlist_list).setBackgroundColor(Color.TRANSPARENT);

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
}