package com.nightlight.app.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.api.dto.LyricsDtos;
import com.nightlight.app.data.repo.MusicRepository;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.player.PlaybackSnapshot;
import com.nightlight.app.util.InsetsUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Spotify-style synchronized lyrics. Lines highlight in time with playback:
 * timed lyrics use their own timestamps; untimed ones are distributed evenly
 * across the track duration.
 */
public final class LyricsActivity extends Activity implements PlaybackManager.Listener {

    private static final int GOLD = 0xFFF5C869;
    private static final int CREAM = 0xFFF5EBDD;
    private static final int DIM = 0x99F5EBDD;

    private RecyclerView list;
    private TextView state;
    private TextView songLabel;

    private LyricsAdapter adapter = new LyricsAdapter();
    private final List<LyricsDtos.LineDto> lines = new ArrayList<>();
    private boolean timed;
    private long durationMs;
    private int activeLine = -1;
    private String lyricsForId;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lyrics);
        InsetsUtil.applySystemBars(findViewById(R.id.lyrics_root));

        list = findViewById(R.id.lyrics_list);
        state = findViewById(R.id.lyrics_state);
        songLabel = findViewById(R.id.lyrics_song);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.lyrics_back).setOnClickListener(v -> finish());
    }

    @Override
    protected void onStart() {
        super.onStart();
        PlaybackManager.get(this).addListener(this);
        refresh();
    }

    @Override
    protected void onStop() {
        PlaybackManager.get(this).removeListener(this);
        super.onStop();
    }

    @Override
    public void onPlaybackChanged(PlaybackSnapshot snapshot) {
        refresh();
    }

    private void refresh() {
        PlaybackSnapshot snapshot = PlaybackManager.get(this).getSnapshot();
        Track track = snapshot.current;
        if (track == null) {
            return;
        }
        songLabel.setText(track.name + " — " + track.artists);
        durationMs = snapshot.duration;

        if (!track.id.equals(lyricsForId)) {
            lyricsForId = track.id;
            lines.clear();
            timed = false;
            activeLine = -1;
            adapter.notifyDataSetChanged();
            loadLyrics(track);
        }
        updateActiveLine();
    }

    private PlaybackSnapshot snapshot() {
        return PlaybackManager.get(this).getSnapshot();
    }

    private void loadLyrics(Track track) {
        loading = true;
        showState(getString(R.string.lyrics_loading));
        NightLightApp app = (NightLightApp) getApplication();
        app.getMusicRepository().fetchLyrics(track, new MusicRepository.LyricsCallback() {
            @Override
            public void onSuccess(LyricsDtos.LyricsDto lyrics) {
                loading = false;
                if (!track.id.equals(lyricsForId)) {
                    return; // the song changed while we were fetching
                }
                if (!lyrics.available) {
                    showState(getString(R.string.lyrics_none));
                    return;
                }
                if (lyrics.instrumental) {
                    showState(getString(R.string.lyrics_instrumental));
                    return;
                }
                timed = lyrics.timed;
                lines.clear();
                if (lyrics.lines != null) {
                    lines.addAll(lyrics.lines);
                }
                if (lines.isEmpty()) {
                    showState(getString(R.string.lyrics_none));
                    return;
                }
                hideState();
                adapter.notifyDataSetChanged();
                updateActiveLine();
            }

            @Override
            public void onFailure(Throwable error) {
                loading = false;
                if (track.id.equals(lyricsForId)) {
                    showState(getString(R.string.lyrics_none));
                }
            }
        });
    }

    private void showState(String message) {
        state.setText(message);
        state.setVisibility(View.VISIBLE);
        list.setVisibility(View.INVISIBLE);
    }

    private void hideState() {
        state.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
    }

    private void updateActiveLine() {
        if (lines.isEmpty()) {
            return;
        }
        long pos = snapshot().position;
        int index;
        if (timed) {
            index = -1;
            for (int i = 0; i < lines.size(); i++) {
                Integer t = lines.get(i).timeMs;
                if (t == null || t > pos) {
                    break;
                }
                index = i;
            }
        } else if (durationMs > 0) {
            index = (int) Math.min(lines.size() - 1, (pos * lines.size()) / durationMs);
        } else {
            return;
        }
        index = Math.max(0, index);
        if (index != activeLine) {
            int old = activeLine;
            activeLine = index;
            adapter.notifyItemChanged(old);
            adapter.notifyItemChanged(activeLine);
            if (activeLine >= 0) {
                list.smoothScrollToPosition(activeLine);
            }
        }
    }

    private final class LyricsAdapter extends RecyclerView.Adapter<LyricsAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(LyricsActivity.this);
            tv.setTextSize(21);
            tv.setLineSpacing(6f, 1.05f);
            tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            tv.setPadding(48, 18, 48, 18);
            return new Holder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            LyricsDtos.LineDto line = lines.get(position);
            holder.text.setText(line.text != null ? line.text : "");
            boolean active = position == activeLine;
            holder.text.setTextColor(active ? GOLD : DIM);
            holder.text.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
            holder.text.setTextSize(active ? 24 : 21);
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView text;

            Holder(@NonNull TextView item) {
                super(item);
                text = item;
            }
        }
    }
}
