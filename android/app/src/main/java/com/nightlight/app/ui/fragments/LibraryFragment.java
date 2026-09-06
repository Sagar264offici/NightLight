package com.nightlight.app.ui.fragments;

import android.app.ProgressDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.repo.LibraryRepository;
import com.nightlight.app.data.repo.PlaylistRepository;
import com.nightlight.app.domain.model.Playlist;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.ui.PlaylistActivity;
import com.nightlight.app.ui.adapters.PlaylistAdapter;
import com.nightlight.app.ui.adapters.TrackAdapter;
import com.nightlight.app.ui.common.PlaylistDialogs;
import com.nightlight.app.ui.common.TrackPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LibraryFragment extends Fragment {

    private NightLightApp app;
    private LibraryRepository library;
    private PlaylistRepository playlists;

    private TrackAdapter likesAdapter;
    private PlaylistAdapter playlistAdapter;
    private TextView likedCount;
    private View likesEmpty;
    private View playlistsEmpty;

    private LiveData<List<Track>> likesLiveData;
    private LiveData<List<Playlist>> playlistsLiveData;

    private final Observer<List<Track>> likesObserver = tracks -> {
        likedCount.setText(tracks == null ? "" : String.valueOf(tracks.size()));
        likesEmpty.setVisibility(tracks == null || tracks.isEmpty() ? View.VISIBLE : View.GONE);
        Set<String> ids = new HashSet<>();
        if (tracks != null) {
            for (Track t : tracks) {
                ids.add(t.id);
            }
        }
        likesAdapter.setLikedIds(ids);
        likesAdapter.submitList(tracks);
    };

    private final Observer<List<Playlist>> playlistsObserver = items -> {
        playlistsEmpty.setVisibility(items == null || items.isEmpty() ? View.VISIBLE : View.GONE);
        playlistAdapter.submitList(items);
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        app = (NightLightApp) requireActivity().getApplication();
        library = app.getLibraryRepository();
        playlists = app.getPlaylistRepository();

        likedCount = view.findViewById(R.id.library_liked_count);
        likesEmpty = view.findViewById(R.id.library_likes_empty);
        playlistsEmpty = view.findViewById(R.id.library_playlists_empty);

        RecyclerView likesList = view.findViewById(R.id.library_likes);
        likesList.setLayoutManager(new LinearLayoutManager(requireContext()));
        likesList.setNestedScrollingEnabled(false);
        likesAdapter = new TrackAdapter(new TrackAdapter.Callbacks() {
            @Override
            public void onTrackClick(Track track) {
                TrackPlayer.play(requireContext(), likesAdapter.getCurrentList(),
                        indexOf(likesAdapter.getCurrentList(), track));
            }

            @Override
            public void onLikeClick(Track track) {
                library.toggleLike(track, false, null);
            }

            @Override
            public void onMoreClick(Track track) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(track.name)
                        .setItems(new CharSequence[]{
                                requireContext().getString(R.string.action_add_to_playlist),
                                "Play next"
                        }, (dialog, which) -> {
                            if (which == 0) {
                                PlaylistDialogs.showAddToPlaylistSheet(requireActivity(), track);
                            } else {
                                com.nightlight.app.player.PlaybackManager.get(requireContext()).playNext(track);
                            }
                        })
                        .show();
            }
        });
        likesList.setAdapter(likesAdapter);

        RecyclerView playlistList = view.findViewById(R.id.library_playlists);
        playlistList.setLayoutManager(new LinearLayoutManager(requireContext()));
        playlistList.setNestedScrollingEnabled(false);
        playlistAdapter = new PlaylistAdapter(new PlaylistAdapter.Callbacks() {
            @Override
            public void onPlaylistClick(Playlist playlist) {
                startActivity(PlaylistActivity.intent(requireContext(), playlist.id, playlist.name));
            }

            @Override
            public void onMoreClick(Playlist playlist) {
                showPlaylistMenu(playlist);
            }
        });
        playlistList.setAdapter(playlistAdapter);

        view.findViewById(R.id.library_new_playlist).setOnClickListener(v ->
                PlaylistDialogs.showCreateDialog(requireContext(),
                        getString(R.string.playlist_create_title), name ->
                                playlists.createPlaylist(name, ok -> {
                                })));

        view.findViewById(R.id.library_import).setOnClickListener(v -> {
            if (com.nightlight.app.util.AccountPrefs.isGuest(requireContext())) {
                PlaylistDialogs.showGuestAccountPrompt(requireActivity(), () -> {
                });
                return;
            }
            showImportDialog();
        });

        // Explicit guest labeling: local playlists are temporary, saving to
        // the account requires sign-in (spec: never a silent failure).
        // NOTE: the fragment root is a ScrollView which can host only ONE
        // direct child, so the banner is added INSIDE the content column.
        if (com.nightlight.app.util.AccountPrefs.isGuest(requireContext())) {
            TextView guestBanner = new TextView(requireContext());
            guestBanner.setText(R.string.guest_banner);
            guestBanner.setTextSize(12f);
            guestBanner.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.nightlight_cream_dim));
            guestBanner.setPadding(0, dp(4), 0, dp(4));
            android.view.ViewGroup contentColumn =
                    (android.view.ViewGroup) view.findViewById(R.id.library_liked_row).getParent();
            if (contentColumn != null) {
                contentColumn.addView(guestBanner, Math.min(1, contentColumn.getChildCount()));
            }
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Paste a Spotify/YouTube playlist URL; matched songs are saved locally. */
    private void showImportDialog() {
        EditText input = new EditText(requireContext());
        input.setHint(R.string.import_url_hint);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setTextColor(0xFFF5EBDD);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.import_title)
                .setMessage(R.string.import_message)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.import_positive, (d, w) -> {
                    String url = input.getText() == null ? "" : input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        doImport(url);
                    }
                })
                .show();
    }

    private void doImport(String url) {
        final ProgressDialog progress = new ProgressDialog(requireContext());
        progress.setMessage(getString(R.string.import_progress));
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        playlists.importFromUrl(url, 60, new PlaylistRepository.ImportCallback() {
            @Override
            public void onSuccess(String playlistName, List<Track> tracks, List<String> unmatched) {
                progress.dismiss();
                if (tracks.isEmpty()) {
                    Toast.makeText(requireContext(),
                            "No songs from that playlist could be matched", Toast.LENGTH_LONG).show();
                    return;
                }
                String name = playlistName == null || playlistName.trim().isEmpty()
                        ? "Imported playlist" : playlistName.trim();
                playlists.createLocalWithTracks(name, tracks, new PlaylistRepository.ActionCallback() {
                    @Override
                    public void onDone(boolean success) {
                        Toast.makeText(requireContext(),
                                getString(R.string.import_done, tracks.size(), tracks.size() + unmatched.size()),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onFailure(Throwable error) {
                progress.dismiss();
                Toast.makeText(requireContext(),
                        com.nightlight.app.util.ErrorMapper.toUserMessage(requireContext(), error),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showPlaylistMenu(Playlist playlist) {
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(playlist.name)
                .setItems(new CharSequence[]{
                        getString(R.string.action_play_playlist),
                        getString(R.string.playlist_rename_title),
                        getString(R.string.action_delete)
                }, (dialog, which) -> {
                    if (which == 0) {
                        playPlaylist(playlist);
                    } else if (which == 1) {
                        PlaylistDialogs.showCreateDialog(requireContext(),
                                getString(R.string.playlist_rename_title),
                                name -> playlists.renamePlaylist(playlist.id, name));
                    } else if (which == 2) {
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setMessage(getString(R.string.playlist_delete_confirm, playlist.name))
                                .setPositiveButton(R.string.action_delete, (d, w) ->
                                        playlists.deletePlaylist(playlist.id, ok -> {
                                        }))
                                .setNegativeButton(R.string.action_cancel, null)
                                .show();
                    }
                })
                .show();
    }

    private void playPlaylist(Playlist playlist) {
        playlists.getTracksAsync(playlist.id, tracks -> {
            if (tracks.isEmpty()) {
                android.widget.Toast.makeText(requireContext(),
                        R.string.empty_playlists, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            TrackPlayer.play(requireContext(), tracks, 0);
        });
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
        likesLiveData = library.observeLikes();
        likesLiveData.observe(this, likesObserver);
        playlistsLiveData = playlists.observePlaylists();
        playlistsLiveData.observe(this, playlistsObserver);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (likesLiveData != null) {
            likesLiveData.removeObserver(likesObserver);
        }
        if (playlistsLiveData != null) {
            playlistsLiveData.removeObserver(playlistsObserver);
        }
    }
}