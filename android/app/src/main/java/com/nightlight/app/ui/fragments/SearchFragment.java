package com.nightlight.app.ui.fragments;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.repo.LibraryRepository;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.ui.adapters.HistoryAdapter;
import com.nightlight.app.ui.adapters.TrackAdapter;
import com.nightlight.app.ui.common.PlaylistDialogs;
import com.nightlight.app.ui.common.TrackPlayer;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SearchFragment extends Fragment {

    private SearchViewModel viewModel;
    private NightLightApp app;
    private LibraryRepository library;

    private EditText input;
    private RecyclerView resultsList;
    private RecyclerView recentList;
    private View stateView;
    private ProgressBar stateProgress;
    private ImageView stateIcon;
    private TextView stateText;
    private MaterialButton stateRetry;

    private TrackAdapter resultsAdapter;
    private HistoryAdapter historyAdapter;

    private LiveData<List<Track>> likesLiveData;
    private final Observer<List<Track>> likesObserver = this::onLikesChanged;
    private final Observer<SearchViewModel.UiState> stateObserver = this::render;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        app = (NightLightApp) requireActivity().getApplication();
        library = app.getLibraryRepository();
        viewModel = new androidx.lifecycle.ViewModelProvider(this).get(SearchViewModel.class);

        input = view.findViewById(R.id.search_input);
        resultsList = view.findViewById(R.id.search_results);
        recentList = view.findViewById(R.id.search_recent);
        stateView = view.findViewById(R.id.search_state);
        stateProgress = view.findViewById(R.id.search_state_progress);
        stateIcon = view.findViewById(R.id.search_state_icon);
        stateText = view.findViewById(R.id.search_state_text);
        stateRetry = view.findViewById(R.id.search_state_retry);

        resultsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        resultsAdapter = new TrackAdapter(new TrackAdapter.Callbacks() {
            @Override
            public void onTrackClick(Track track) {
                // Collapse remix/acoustic/slowed variants so shuffle and
                // auto-next play varied songs, then queue the clean list.
                List<Track> queue = TrackPlayer.dedupeVariants(resultsAdapter.getCurrentList());
                TrackPlayer.play(requireContext(), queue, indexOf(queue, track));
                hideKeyboard();
            }

            @Override
            public void onLikeClick(Track track) {
                library.toggleLike(track, !library.isLiked(track.id), null);
            }

            @Override
            public void onMoreClick(Track track) {
                showTrackSheet(track);
            }
        });
        resultsList.setAdapter(resultsAdapter);
        resultsList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!recyclerView.canScrollVertically(1)) {
                    viewModel.loadMore();
                }
            }
        });

        recentList.setLayoutManager(new LinearLayoutManager(requireContext()));
        historyAdapter = new HistoryAdapter(new HistoryAdapter.Callbacks() {
            @Override
            public void onQueryClick(String query) {
                input.setText(query);
                input.setSelection(query.length());
            }

            @Override
            public void onQueryDelete(String query) {
                library.deleteSearchHistoryQuery(query);
            }
        });
        recentList.setAdapter(historyAdapter);

        stateRetry.setOnClickListener(v -> viewModel.retry());

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                viewModel.onQueryChanged(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    private void showTrackSheet(Track track) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(track.name)
                .setItems(new CharSequence[]{
                        requireContext().getString(R.string.action_add_to_playlist),
                        "Play next",
                        "Add to queue"
                }, (dialog, which) -> {
                    if (which == 0) {
                        PlaylistDialogs.showAddToPlaylistSheet(requireActivity(), track);
                    } else if (which == 1) {
                        PlaybackManager.get(requireContext()).playNext(track);
                    } else {
                        PlaybackManager.get(requireContext())
                                .addToQueue(Collections.singletonList(track));
                    }
                })
                .show();
    }

    private void render(SearchViewModel.UiState state) {
        if (state == null) {
            return;
        }
        switch (state.status) {
            case IDLE:
                resultsList.setVisibility(View.GONE);
                recentList.setVisibility(View.VISIBLE);
                stateView.setVisibility(View.GONE);
                historyAdapter.submit(state.recentQueries);
                break;
            case LOADING:
                resultsList.setVisibility(View.GONE);
                recentList.setVisibility(View.GONE);
                stateView.setVisibility(View.VISIBLE);
                stateProgress.setVisibility(View.VISIBLE);
                stateIcon.setVisibility(View.GONE);
                stateRetry.setVisibility(View.GONE);
                stateText.setText(R.string.search_state_loading);
                break;
            case SUCCESS:
                resultsList.setVisibility(View.VISIBLE);
                recentList.setVisibility(View.GONE);
                stateView.setVisibility(View.GONE);
                resultsAdapter.submitList(state.tracks);
                break;
            case EMPTY:
                resultsList.setVisibility(View.GONE);
                recentList.setVisibility(View.GONE);
                stateView.setVisibility(View.VISIBLE);
                stateProgress.setVisibility(View.GONE);
                stateIcon.setVisibility(View.VISIBLE);
                stateIcon.setImageResource(R.drawable.ic_music_note);
                stateRetry.setVisibility(View.GONE);
                String query = input.getText() != null ? input.getText().toString().trim() : "";
                stateText.setText(getString(R.string.empty_results, query));
                break;
            case OFFLINE:
                showErrorState(R.string.search_state_offline, R.drawable.ic_offline);
                break;
            case RATE_LIMITED:
                showErrorState(R.string.search_state_rate_limited, R.drawable.ic_error_outline);
                break;
            case ERROR:
            default:
                showErrorState(R.string.search_state_error, R.drawable.ic_error_outline);
                break;
        }
    }

    private void showErrorState(int textRes, int iconRes) {
        resultsList.setVisibility(View.GONE);
        recentList.setVisibility(View.GONE);
        stateView.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(View.GONE);
        stateIcon.setVisibility(View.VISIBLE);
        stateIcon.setImageResource(iconRes);
        stateRetry.setVisibility(View.VISIBLE);
        stateText.setText(textRes);
    }

    private void onLikesChanged(List<Track> likes) {
        Set<String> ids = new HashSet<>();
        for (Track t : likes) {
            ids.add(t.id);
        }
        resultsAdapter.setLikedIds(ids);
    }

    private static int indexOf(List<Track> tracks, Track track) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id.equals(track.id)) {
                return i;
            }
        }
        return 0;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null && input != null) {
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        input.clearFocus();
    }

    @Override
    public void onStart() {
        super.onStart();
        likesLiveData = library.observeLikes();
        likesLiveData.observe(this, likesObserver);
        viewModel.getState().observe(this, stateObserver);
    }

    @Override
    public void onStop() {
        super.onStop();
        viewModel.getState().removeObserver(stateObserver);
        if (likesLiveData != null) {
            likesLiveData.removeObserver(likesObserver);
        }
    }
}