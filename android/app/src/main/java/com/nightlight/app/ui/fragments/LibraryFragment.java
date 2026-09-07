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

    /** Import from Spotify, Apple Music, or YouTube with explicit source selection. */
    private void showImportDialog() {
        android.widget.LinearLayout box = new android.widget.LinearLayout(requireContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        TextView sourceLabel = new TextView(requireContext());
        sourceLabel.setText("SOURCE");
        sourceLabel.setTextColor(com.google.android.material.color.MaterialColors.getColor(
                sourceLabel, com.google.android.material.R.attr.colorOnSurfaceVariant));
        sourceLabel.setTextSize(11f);
        sourceLabel.setLetterSpacing(0.14f);
        box.addView(sourceLabel);

        android.widget.LinearLayout sources = new android.widget.LinearLayout(requireContext());
        sources.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        sources.setPadding(0, dp(6), 0, dp(12));

        String[] names = {"Spotify", "Apple Music", "YouTube"};
        String[] keys = {"spotify", "apple", "youtube"};
        android.widget.Button[] buttons = new android.widget.Button[3];
        final String[] selected = {"spotify"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            android.widget.Button b = new android.widget.Button(requireContext());
            b.setAllCaps(false);
            b.setText(names[i]);
            b.setTextSize(12f);
            b.setMinHeight(0);
            b.setMinWidth(0);
            b.setPadding(dp(8), 0, dp(8), 0);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    0, dp(42), 1f);
            if (i > 0) lp.setMarginStart(dp(6));
            b.setLayoutParams(lp);
            buttons[i] = b;
            b.setOnClickListener(v -> {
                selected[0] = keys[idx];
                for (int j = 0; j < buttons.length; j++) {
                    buttons[j].setTypeface(android.graphics.Typeface.DEFAULT,
                            j == idx ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                    buttons[j].setAlpha(j == idx ? 1f : 0.62f);
                }
                inputHint(box, idx);
            });
            sources.addView(b);
        }
        box.addView(sources);

        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setTextColor(0xFFF5EBDD);
        input.setHintTextColor(0xFF8D8495);
        input.setHint("Paste playlist link");
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.addView(input);
        buttons[0].performClick();

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.import_title)
                .setMessage("Choose a service, then paste its playlist link. Public playlists work best.")
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.import_positive, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = input.getText() == null ? "" : input.getText().toString().trim();
            if (url.isEmpty()) {
                input.setError("Paste a playlist link");
                return;
            }
            if (!matchesSource(url, selected[0])) {
                input.setError("That link is not a " + selected[0] + " playlist");
                return;
            }
            dialog.dismiss();
            doImport(url);
        }));
        dialog.show();
    }

    private void inputHint(android.view.View box, int index) {
        if (box instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) box;
            android.view.View last = group.getChildAt(group.getChildCount() - 1);
            if (last instanceof EditText) {
                String[] hints = {"https://open.spotify.com/playlist/…", "https://music.apple.com/.../playlist/…", "https://www.youtube.com/playlist?list=…"};
                ((EditText) last).setHint(hints[index]);
            }
        }
    }

    private boolean matchesSource(String raw, String source) {
        try {
            String host = new java.net.URI(raw).getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.US).replaceFirst("^www\\.", "").replaceFirst("^music\\.", "");
            if ("spotify".equals(source)) return host.contains("spotify.com");
            if ("apple".equals(source)) return host.contains("apple.com");
            return host.contains("youtube.com") || host.contains("youtu.be");
        } catch (Exception e) {
            return false;
        }
    }

    private void doImport(String url) {
        final ProgressDialog progress = new ProgressDialog(requireContext());
        progress.setMessage(getString(R.string.import_progress));
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        playlists.importFromUrl(url, 200, new PlaylistRepository.ImportCallback() {
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
                playlists.createLocalWithTracks(name, tracks, success ->
                        Toast.makeText(requireContext(),
                                getString(R.string.import_done, tracks.size(), tracks.size() + unmatched.size()),
                                Toast.LENGTH_LONG).show());
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