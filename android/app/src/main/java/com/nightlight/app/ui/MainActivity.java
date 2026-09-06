package com.nightlight.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.domain.model.Track;
import com.nightlight.app.player.PlaybackManager;
import com.nightlight.app.player.PlaybackSnapshot;
import com.nightlight.app.ui.fragments.HomeFragment;
import com.nightlight.app.ui.fragments.LibraryFragment;
import com.nightlight.app.ui.fragments.SearchFragment;
import com.nightlight.app.util.NetworkMonitor;

import java.util.Set;

/**
 * Application shell: hosts the three primary fragments, the persistent mini
 * player and the offline banner. Playback lives in the media service; this
 * activity merely observes it.
 */
public final class MainActivity extends AppCompatActivity {

    private HomeFragment homeFragment;
    private SearchFragment searchFragment;
    private LibraryFragment libraryFragment;
    private Fragment current;

    private View miniPlayer;
    private ImageView miniArtwork;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageButton miniPlayPause;
    private TextView offlineBanner;

    private final PlaybackManager.Listener playbackListener = this::onPlaybackChanged;
    private final Observer<Boolean> onlineObserver = this::onConnectivityChanged;
    private LiveData<Boolean> onlineLiveData;

    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        BottomNavigationView nav = findViewById(R.id.bottom_nav);

        // Fragment content must start below the status bar (edge-to-edge).
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.fragment_container), (v, insets) -> {
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0);
            return insets;
        });

        miniPlayer = findViewById(R.id.mini_player);
        miniArtwork = findViewById(R.id.mini_artwork);
        miniTitle = findViewById(R.id.mini_title);
        miniArtist = findViewById(R.id.mini_artist);
        miniPlayPause = findViewById(R.id.mini_play_pause);
        offlineBanner = findViewById(R.id.offline_banner);

        // Navigation bar inset for the bottom nav (edge-to-edge).
        ViewCompat.setOnApplyWindowInsetsListener(nav, (v, insets) -> {
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom);
            return insets;
        });

        // Mini player: whole row opens Now Playing; play/pause toggles.
        miniPlayer.setOnClickListener(v -> startActivity(new Intent(this, NowPlayingActivity.class)));
        miniPlayPause.setOnClickListener(v -> PlaybackManager.get(this).togglePlayPause());

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                });
        maybeRequestNotificationPermission();

        setupFragments(nav);
        setupOfflineBanner();
    }

    private void setupFragments(BottomNavigationView nav) {
        homeFragment = new HomeFragment();
        searchFragment = new SearchFragment();
        libraryFragment = new LibraryFragment();

        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, libraryFragment, "library")
                .hide(libraryFragment)
                .add(R.id.fragment_container, searchFragment, "search")
                .hide(searchFragment)
                .add(R.id.fragment_container, homeFragment, "home")
                .commit();
        current = homeFragment;
        nav.setSelectedItemId(R.id.nav_home);

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                show(homeFragment);
            } else if (id == R.id.nav_search) {
                show(searchFragment);
            } else if (id == R.id.nav_library) {
                show(libraryFragment);
            }
            return true;
        });
    }

    private void show(Fragment fragment) {
        if (fragment == current) {
            return;
        }
        getSupportFragmentManager().beginTransaction()
                .hide(current)
                .show(fragment)
                .commit();
        current = fragment;
    }

    private void setupOfflineBanner() {
        onlineLiveData = NetworkMonitor.get(this).online();
        onlineLiveData.observe(this, onlineObserver);
    }

    private void onConnectivityChanged(Boolean online) {
        offlineBanner.setVisibility(Boolean.TRUE.equals(online) ? View.GONE : View.VISIBLE);
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /** Public entry for fragments to switch tabs. */
    public void switchToSearch() {
        ((BottomNavigationView) findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_search);
    }

    public void switchToLibrary() {
        ((BottomNavigationView) findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_library);
    }

    private String miniLoadedId;

    private void onPlaybackChanged(PlaybackSnapshot snapshot) {
        boolean visible = snapshot.hasQueue && snapshot.current != null;
        miniPlayer.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            return;
        }
        Track track = snapshot.current;
        boolean trackChanged = !track.id.equals(miniLoadedId);
        miniLoadedId = track.id;
        miniTitle.setText(track.name);
        miniArtist.setText(track.artists.isEmpty() ? "Unknown artist" : track.artists);
        miniPlayPause.setImageResource(snapshot.isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        if (track.imageUrl != null && !track.imageUrl.isEmpty()) {
            Glide.with(this)
                    .load(track.imageUrl)
                    .placeholder(R.drawable.bg_artwork_placeholder)
                    .error(R.drawable.bg_artwork_placeholder)
                    .override(120, 120)
                    .centerCrop()
                    .into(miniArtwork);
        }
        if (trackChanged) {
            // Track-change crossfade (spec 32): artwork + text fade in instead
            // of an instant swap.
            miniArtwork.setAlpha(0f);
            miniArtwork.animate().alpha(1f).setDuration(260).start();
            miniTitle.setAlpha(0.3f);
            miniTitle.animate().alpha(1f).setDuration(300).start();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        PlaybackManager.get(this).addListener(playbackListener);
    }

    @Override
    protected void onStop() {
        super.onStop();
        PlaybackManager.get(this).removeListener(playbackListener);
    }

    @Override
    protected void onDestroy() {
        if (onlineLiveData != null) {
            onlineLiveData.removeObserver(onlineObserver);
        }
        super.onDestroy();
    }
}