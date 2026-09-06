package com.nightlight.app.ui.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.api.dto.WeatherDtos;
import com.nightlight.app.domain.model.Playlist;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.smartshuffle.ContextEngine;
import com.nightlight.app.ui.MainActivity;
import com.nightlight.app.ui.PlaylistActivity;
import com.nightlight.app.ui.SettingsActivity;
import com.nightlight.app.ui.adapters.PlaylistCardAdapter;
import com.nightlight.app.ui.adapters.RecentCardAdapter;
import com.nightlight.app.ui.common.TrackPlayer;
import com.nightlight.app.util.MoodPrefs;

import java.util.ArrayList;
import java.util.List;

public final class HomeFragment extends Fragment {

    private HomeViewModel viewModel;
    private RecentCardAdapter recentAdapter;
    private RecentCardAdapter forYouAdapter;
    private RecentCardAdapter trendingAdapter;
    private PlaylistCardAdapter playlistAdapter;
    private TextView likedCount;
    private View recentEmpty;
    private View playlistsEmpty;
    private View forYouEmpty;
    private View trendingEmpty;
    private LinearLayout moodRow;
    private TextView forYouTitle;
    private TextView forYouSubtitle;
    private TextView trendingTitle;
    private boolean moodsRendered;

    private final Observer<List<Track>> recentObserver = tracks -> {
        List<Track> display = tracks != null && tracks.size() > 8 ? tracks.subList(0, 8) : tracks;
        recentAdapter.submitList(display);
        recentEmpty.setVisibility(display == null || display.isEmpty() ? View.VISIBLE : View.GONE);
    };

    private final Observer<List<Track>> likesObserver = tracks ->
            likedCount.setText(tracks == null ? "" : String.valueOf(tracks.size()));

    private final Observer<List<Playlist>> playlistsObserver = playlists -> {
        playlistAdapter.submitList(playlists);
        playlistsEmpty.setVisibility(playlists == null || playlists.isEmpty() ? View.VISIBLE : View.GONE);
    };

    private final Observer<List<Track>> forYouObserver = tracks -> {
        forYouAdapter.submitList(tracks);
        forYouEmpty.setVisibility(tracks == null || tracks.isEmpty() ? View.VISIBLE : View.GONE);
    };

    private final Observer<List<Track>> trendingObserver = tracks -> {
        trendingAdapter.submitList(tracks);
        trendingEmpty.setVisibility(tracks == null || tracks.isEmpty() ? View.VISIBLE : View.GONE);
    };

    private final Observer<String> chartTitleObserver = title -> {
        if (title != null && !title.isEmpty()) {
            trendingTitle.setText(getString(R.string.section_trending_chart, title));
        } else {
            trendingTitle.setText(R.string.section_trending);
        }
    };

    private final Observer<String> titleObserver = title -> {
        if (title != null && !title.isEmpty()) {
            forYouTitle.setText(title);
        }
    };

    private final Observer<String> subtitleObserver = subtitle -> {
        if (subtitle != null && !subtitle.isEmpty()) {
            forYouSubtitle.setText(subtitle);
            forYouSubtitle.setVisibility(View.VISIBLE);
        } else {
            forYouSubtitle.setVisibility(View.GONE);
        }
    };

    private final Observer<WeatherDtos.WeatherDto> weatherObserver = w -> { };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        NightLightApp app = (NightLightApp) requireActivity().getApplication();
        viewModel = new androidx.lifecycle.ViewModelProvider(this)
                .get(HomeViewModel.class);

        TextView greeting = view.findViewById(R.id.home_greeting);
        greeting.setText(viewModel.greeting());

        recentEmpty = view.findViewById(R.id.home_recent_empty);
        playlistsEmpty = view.findViewById(R.id.home_playlists_empty);
        forYouEmpty = view.findViewById(R.id.home_for_you_empty);
        trendingEmpty = view.findViewById(R.id.home_trending_empty);
        likedCount = view.findViewById(R.id.home_liked_count);
        moodRow = view.findViewById(R.id.home_mood_row);
        forYouTitle = view.findViewById(R.id.home_for_you_title);
        forYouSubtitle = view.findViewById(R.id.home_for_you_subtitle);
        trendingTitle = view.findViewById(R.id.home_trending_title);

        RecyclerView recentList = view.findViewById(R.id.home_recent);
        recentList.setLayoutManager(new LinearLayoutManager(requireContext(),
                LinearLayoutManager.HORIZONTAL, false));
        recentAdapter = new RecentCardAdapter(track -> {
            // Same-named duplicates (different recordings of "Believer") should
            // not follow each other; keep the queue varied.
            java.util.List<Track> queue = TrackPlayer.dedupeVariants(recentAdapter.getCurrentList());
            TrackPlayer.play(requireContext(), queue, indexOf(queue, track));
        });
        recentList.setAdapter(recentAdapter);

        RecyclerView forYouList = view.findViewById(R.id.home_for_you);
        forYouList.setLayoutManager(new LinearLayoutManager(requireContext(),
                LinearLayoutManager.HORIZONTAL, false));
        forYouAdapter = new RecentCardAdapter(track ->
                TrackPlayer.play(requireContext(), singleList(track), 0));
        forYouList.setAdapter(forYouAdapter);

        RecyclerView trendingList = view.findViewById(R.id.home_trending);
        trendingList.setLayoutManager(new LinearLayoutManager(requireContext(),
                LinearLayoutManager.HORIZONTAL, false));
        trendingAdapter = new RecentCardAdapter(track ->
                TrackPlayer.play(requireContext(), singleList(track), 0));
        trendingList.setAdapter(trendingAdapter);

        RecyclerView playlistList = view.findViewById(R.id.home_playlists);
        playlistList.setLayoutManager(new LinearLayoutManager(requireContext(),
                LinearLayoutManager.HORIZONTAL, false));
        playlistAdapter = new PlaylistCardAdapter(playlist ->
                startActivity(PlaylistActivity.intent(requireContext(), playlist.id, playlist.name)));
        playlistList.setAdapter(playlistAdapter);

        view.findViewById(R.id.home_search_bar).setOnClickListener(v ->
                ((MainActivity) requireActivity()).switchToSearch());
        view.findViewById(R.id.home_liked_row).setOnClickListener(v ->
                ((MainActivity) requireActivity()).switchToLibrary());
        view.findViewById(R.id.home_settings).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), SettingsActivity.class)));
    }

    private static List<Track> singleList(Track track) {
        List<Track> list = new ArrayList<>();
        list.add(track);
        return list;
    }

    private void renderMoodChips() {
        if (moodsRendered || moodRow == null) {
            return;
        }
        moodsRendered = true;
        String[][] chips = ContextEngine.moodChips();
        String active = MoodPrefs.active(requireContext());
        for (String[] chip : chips) {
            final String moodKey = chip[2];
            TextView chipView = new TextView(requireContext());
            chipView.setText(chip[1]);
            chipView.setTextSize(13f);
            chipView.setTextColor(requireContext().getColor(R.color.nightlight_cream));
            chipView.setBackgroundResource(moodKey.equals(active)
                    ? R.drawable.bg_mood_chip_selected : R.drawable.bg_mood_chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Math.round(10f * getResources().getDisplayMetrics().density));
            chipView.setLayoutParams(lp);
            int padH = Math.round(16f * getResources().getDisplayMetrics().density);
            int padV = Math.round(8f * getResources().getDisplayMetrics().density);
            chipView.setPadding(padH, padV, padH, padV);
            chipView.setOnClickListener(v -> {
                // Mood chip press animation (spec 34): quick press-in, then the
                // selection state rebuilds with the new chip highlighted.
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90)
                        .withEndAction(() -> {
                            boolean wasActive = moodKey.equals(MoodPrefs.active(requireContext()));
                            if (wasActive) {
                                viewModel.clearMood();
                            } else {
                                viewModel.selectMood(moodKey);
                            }
                            moodRow.removeAllViews();
                            moodsRendered = false;
                            renderMoodChips();
                        }).start();
            });
            chipView.setAlpha(0f);
            chipView.animate().alpha(1f).setDuration(160).start();
            moodRow.addView(chipView);
        }
    }

    private static int indexOf(List<Track> tracks, Track track) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id.equals(track.id)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public void onStart() {
        super.onStart();
        viewModel.getRecent().observe(this, recentObserver);
        viewModel.getLikes().observe(this, likesObserver);
        viewModel.getPlaylists().observe(this, playlistsObserver);
        viewModel.getForYouTracks().observe(this, forYouObserver);
        viewModel.getTrendingSongs().observe(this, trendingObserver);
        viewModel.getChartTitle().observe(this, chartTitleObserver);
        viewModel.getForYouTitle().observe(this, titleObserver);
        viewModel.getForYouSubtitle().observe(this, subtitleObserver);
        viewModel.getWeather().observe(this, weatherObserver);
        renderMoodChips();
        viewModel.refreshContext();
    }

    @Override
    public void onStop() {
        super.onStop();
        viewModel.getRecent().removeObserver(recentObserver);
        viewModel.getLikes().removeObserver(likesObserver);
        viewModel.getPlaylists().removeObserver(playlistsObserver);
        viewModel.getForYouTracks().removeObserver(forYouObserver);
        viewModel.getTrendingSongs().removeObserver(trendingObserver);
        viewModel.getChartTitle().removeObserver(chartTitleObserver);
        viewModel.getForYouTitle().removeObserver(titleObserver);
        viewModel.getForYouSubtitle().removeObserver(subtitleObserver);
        viewModel.getWeather().removeObserver(weatherObserver);
    }
}