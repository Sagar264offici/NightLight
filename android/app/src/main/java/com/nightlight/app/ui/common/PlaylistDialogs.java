package com.nightlight.app.ui.common;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.repo.PlaylistRepository;
import com.nightlight.app.domain.model.Playlist;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.ui.adapters.PlaylistAdapter;

import java.util.List;

/** AlertDialog / bottom-sheet builders shared by all screens. */
public final class PlaylistDialogs {

    private PlaylistDialogs() {
    }

    public interface NameCallback {
        void onName(String name);
    }

    public static void showCreateDialog(Context context, String title, NameCallback callback) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_playlist_name, null);
        EditText input = view.findViewById(R.id.playlist_name_input);
        input.requestFocus();

        new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(view)
                .setPositiveButton(R.string.action_create, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        callback.onName(name);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * Bottom sheet listing playlists plus "New playlist". The track is added
     * locally-first and mirrored to the server by the repository.
     */
    public static void showAddToPlaylistSheet(Activity activity, Track track) {
        PlaylistRepository repo = ((NightLightApp) activity.getApplication()).getPlaylistRepository();

        BottomSheetDialog sheet = new BottomSheetDialog(activity);
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_add_to_playlist, null);
        sheet.setContentView(view);

        RecyclerView list = view.findViewById(R.id.add_to_playlist_list);
        list.setLayoutManager(new LinearLayoutManager(activity));

        PlaylistAdapter adapter = new PlaylistAdapter(new PlaylistAdapter.Callbacks() {
            @Override
            public void onPlaylistClick(Playlist playlist) {
                sheet.dismiss();
                repo.addTrack(playlist.id, track, ok -> {
                });
            }

            @Override
            public void onMoreClick(Playlist playlist) {
            }
        });
        list.setAdapter(adapter);

        view.findViewById(R.id.add_to_playlist_new).setOnClickListener(v -> {
            sheet.dismiss();
            showCreateDialog(activity, activity.getString(R.string.playlist_create_title), name -> {
                repo.createPlaylistWithId(name, (playlistId, ok) -> {
                    if (ok) {
                        repo.addTrack(playlistId, track, ignored -> {
                        });
                    }
                });
            });
        });

        androidx.lifecycle.LiveData<List<Playlist>> live = repo.observePlaylists();
        androidx.lifecycle.Observer<List<Playlist>> observer = playlists -> adapter.submitList(playlists);
        live.observeForever(observer);
        sheet.setOnDismissListener(dialog -> live.removeObserver(observer));
        sheet.show();
    }
}