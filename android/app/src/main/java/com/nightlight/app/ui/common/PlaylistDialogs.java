package com.nightlight.app.ui.common;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import com.nightlight.app.ui.LoginActivity;
import com.nightlight.app.ui.adapters.PlaylistAdapter;
import com.nightlight.app.util.AccountPrefs;

import java.util.List;

/** AlertDialog / bottom-sheet builders shared by all screens. */
public final class PlaylistDialogs {

    private PlaylistDialogs() {
    }

    private static final int SOURCE_SPOTIFY = 0;
    private static final int SOURCE_APPLE = 1;
    private static final int SOURCE_YOUTUBE = 2;

    public interface NameCallback {
        void onName(String name);
    }

    /**
     * Import playlist dialog with service-source selection.
     * Shows three source buttons, a URL input, staged progress, and
     * matched/unmatched results.
     */
    public static void showImportDialog(Activity activity) {
        if (AccountPrefs.isGuest(activity)) {
            showGuestAccountPrompt(activity, () -> {});
            return;
        }

        final int[] selectedSource = {SOURCE_SPOTIFY};

        android.widget.LinearLayout root = new android.widget.LinearLayout(activity);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(20f * activity.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, 0);

        // Source selection buttons
        android.widget.TextView sourceLabel = new android.widget.TextView(activity);
        sourceLabel.setText("Choose a service");
        sourceLabel.setTextColor(activity.getColor(R.color.nightlight_cream_dim));
        sourceLabel.setTextSize(13f);
        sourceLabel.setPadding(0, 0, 0, Math.round(8f * activity.getResources().getDisplayMetrics().density));
        root.addView(sourceLabel);

        android.widget.LinearLayout sourceRow = new android.widget.LinearLayout(activity);
        sourceRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.Button btnSpotify = sourceButton(activity, "Spotify", true);
        android.widget.Button btnApple = sourceButton(activity, "Apple Music", false);
        android.widget.Button btnYouTube = sourceButton(activity, "YouTube", false);
        sourceRow.addView(btnSpotify, matchWrap(1f));
        sourceRow.addView(btnApple, matchWrap(1f));
        sourceRow.addView(btnYouTube, matchWrap(1f));
        root.addView(sourceRow);

        // URL input
        android.widget.EditText urlInput = new android.widget.EditText(activity);
        urlInput.setHint(R.string.import_url_hint);
        urlInput.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setSingleLine(true);
        urlInput.setTextColor(activity.getColor(R.color.nightlight_cream));
        urlInput.setHintTextColor(activity.getColor(R.color.nightlight_cream_dim));
        urlInput.setTextSize(14f);
        android.graphics.drawable.GradientDrawable urlBg = new android.graphics.drawable.GradientDrawable();
        urlBg.setColor(android.graphics.Color.parseColor("#1A121B3D"));
        urlBg.setCornerRadius(Math.round(12f * activity.getResources().getDisplayMetrics().density));
        urlBg.setStroke(Math.round(1f * activity.getResources().getDisplayMetrics().density),
                android.graphics.Color.parseColor("#33FFFFFF"));
        urlInput.setBackground(urlBg);
        int urlPad = Math.round(14f * activity.getResources().getDisplayMetrics().density);
        urlInput.setPadding(urlPad, urlPad, urlPad, urlPad);
        android.widget.LinearLayout.LayoutParams urlLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        urlLp.topMargin = Math.round(14f * activity.getResources().getDisplayMetrics().density);
        root.addView(urlInput, urlLp);

        // Helper text
        android.widget.TextView helper = new android.widget.TextView(activity);
        helper.setText("Paste a public playlist link.");
        helper.setTextColor(activity.getColor(R.color.nightlight_cream_dim));
        helper.setTextSize(11f);
        helper.setPadding(0, Math.round(6f * activity.getResources().getDisplayMetrics().density), 0, 0);
        root.addView(helper);

        // Progress text (hidden initially)
        android.widget.TextView progressText = new android.widget.TextView(activity);
        progressText.setTextColor(activity.getColor(R.color.nightlight_gold));
        progressText.setTextSize(13f);
        progressText.setVisibility(View.GONE);
        progressText.setPadding(0, Math.round(10f * activity.getResources().getDisplayMetrics().density), 0, 0);
        root.addView(progressText);

        // Result text (hidden initially)
        android.widget.TextView resultText = new android.widget.TextView(activity);
        resultText.setTextColor(activity.getColor(R.color.nightlight_cream));
        resultText.setTextSize(13f);
        resultText.setVisibility(View.GONE);
        resultText.setPadding(0, Math.round(8f * activity.getResources().getDisplayMetrics().density), 0, 0);
        root.addView(resultText);

        // Wire source buttons
        Runnable updateHints = () -> {
            switch (selectedSource[0]) {
                case SOURCE_SPOTIFY:
                    urlInput.setHint("https://open.spotify.com/playlist/…");
                    break;
                case SOURCE_APPLE:
                    urlInput.setHint("https://music.apple.com/…");
                    break;
                case SOURCE_YOUTUBE:
                    urlInput.setHint("https://youtube.com/playlist?list=…");
                    break;
            }
        };

        btnSpotify.setOnClickListener(v -> {
            selectedSource[0] = SOURCE_SPOTIFY;
            setSelected(btnSpotify, btnApple, btnYouTube);
            updateHints.run();
        });
        btnApple.setOnClickListener(v -> {
            selectedSource[0] = SOURCE_APPLE;
            setSelected(btnApple, btnSpotify, btnYouTube);
            updateHints.run();
        });
        btnYouTube.setOnClickListener(v -> {
            selectedSource[0] = SOURCE_YOUTUBE;
            setSelected(btnYouTube, btnSpotify, btnApple);
            updateHints.run();
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.import_title)
                .setView(root)
                .setPositiveButton(R.string.import_positive, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();

        dialog.show();

        // Override positive button to prevent auto-dismiss.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                urlInput.setError("Enter a playlist URL");
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                urlInput.setError("Enter a valid URL");
                return;
            }
            // Validate source matches URL.
            String lower = url.toLowerCase();
            boolean looksRight = switch (selectedSource[0]) {
                case SOURCE_SPOTIFY -> lower.contains("spotify.com");
                case SOURCE_APPLE -> lower.contains("apple.com");
                case SOURCE_YOUTUBE -> lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("music.youtube.com");
                default -> false;
            };
            if (!looksRight) {
                urlInput.setError("This doesn't look like a " + sourceName(selectedSource[0]) + " link");
                return;
            }

            // Start import.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            urlInput.setEnabled(false);
            progressText.setVisibility(View.VISIBLE);
            progressText.setText("Reading playlist…");
            resultText.setVisibility(View.GONE);

            PlaylistRepository repo = ((NightLightApp) activity.getApplication()).getPlaylistRepository();
            repo.importFromUrl(url, 200, new PlaylistRepository.ImportCallback() {
                @Override
                public void onSuccess(String playlistName, List<Track> tracks, List<String> unmatched) {
                    activity.runOnUiThread(() -> {
                        progressText.setText("Saving playlist…");
                        progressText.postDelayed(() -> {
                            progressText.setVisibility(View.GONE);
                            resultText.setVisibility(View.VISIBLE);
                            String msg = "Imported " + tracks.size() + " songs";
                            if (unmatched != null && !unmatched.isEmpty()) {
                                msg += "\n" + unmatched.size() + " songs could not be matched";
                            }
                            resultText.setText(msg);
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                        }, 300);
                    });
                }

                @Override
                public void onFailure(Throwable error) {
                    activity.runOnUiThread(() -> {
                        progressText.setVisibility(View.GONE);
                        resultText.setVisibility(View.VISIBLE);
                        resultText.setText("Import failed — check the URL and try again");
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                        urlInput.setEnabled(true);
                    });
                }
            });
        });
    }

    private static android.widget.Button sourceButton(Activity activity, String label, boolean selected) {
        android.widget.Button btn = new android.widget.Button(activity);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(13f);
        btn.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        updateSourceBtnStyle(btn, selected, activity);
        return btn;
    }

    private static void updateSourceBtnStyle(android.widget.Button btn, boolean selected, Context ctx) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Math.round(10f * ctx.getResources().getDisplayMetrics().density));
        if (selected) {
            bg.setColor(ctx.getColor(R.color.nightlight_blue));
            btn.setTextColor(ctx.getColor(R.color.nightlight_cream));
        } else {
            bg.setColor(android.graphics.Color.parseColor("#1A121B3D"));
            bg.setStroke(Math.round(1f * ctx.getResources().getDisplayMetrics().density),
                    android.graphics.Color.parseColor("#33FFFFFF"));
            btn.setTextColor(ctx.getColor(R.color.nightlight_cream_dim));
        }
        btn.setBackground(bg);
    }

    private static void setSelected(android.widget.Button active,
            android.widget.Button... others) {
        Context ctx = active.getContext();
        updateSourceBtnStyle(active, true, ctx);
        for (android.widget.Button b : others) {
            updateSourceBtnStyle(b, false, ctx);
        }
    }

    private static String sourceName(int source) {
        return switch (source) {
            case SOURCE_SPOTIFY -> "Spotify";
            case SOURCE_APPLE -> "Apple Music";
            case SOURCE_YOUTUBE -> "YouTube";
            default -> "this service";
        };
    }

    private static android.widget.LinearLayout.LayoutParams matchWrap(float weight) {
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        return lp;
    }

    public interface GuestConversionListener {
        /** Called after the guest chooses to continue to signup (Create Account). */
        void onGoCreateAccount();
    }

    /**
     * Premium account-required prompt for guests. The listener lets the
     * caller pass pending local state (e.g. the just-created playlist name)
     * into the signup flow; on success OnboardingActivity offers to save it.
     */
    public static void showGuestAccountPrompt(Activity activity, GuestConversionListener onConvert) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.guest_account_prompt_title)
                .setMessage(R.string.guest_account_prompt_message)
                .setPositiveButton(R.string.login_link_create, (d, w) -> {
                    AccountPrefs.setPendingGuestConversion(activity, true);
                    activity.startActivity(new Intent(activity, LoginActivity.class)
                            .putExtra(LoginActivity.EXTRA_MODE, LoginActivity.MODE_CREATE));
                    onConvert.onGoCreateAccount();
                })
                .setNeutralButton(R.string.login_login_button, (d, w) -> {
                    AccountPrefs.setPendingGuestConversion(activity, true);
                    activity.startActivity(new Intent(activity, LoginActivity.class)
                            .putExtra(LoginActivity.EXTRA_MODE, LoginActivity.MODE_LOGIN));
                    onConvert.onGoCreateAccount();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
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
        if (AccountPrefs.isGuest(activity)) {
            showGuestAccountPrompt(activity, () -> {
            });
            return;
        }
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