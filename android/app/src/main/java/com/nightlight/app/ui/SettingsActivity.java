package com.nightlight.app.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.nightlight.app.BuildConfig;
import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;

public final class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);
        com.nightlight.app.util.InsetsUtil.applySystemBars(findViewById(R.id.activity_settings_root));

        NightLightApp app = (NightLightApp) getApplication();

        findViewById(R.id.settings_back).setOnClickListener(v -> finish());

        TextView version = findViewById(R.id.settings_version);
        version.setText(getString(R.string.settings_version, BuildConfig.VERSION_NAME));

        wirePowerMode();
        wireShuffleMode();
        wireDiscovery();
        wireListenTogether();
        wireDeveloper();

        findViewById(R.id.settings_clear_cache).setOnClickListener(v -> {
            Glide.get(this).clearMemory();
            new Thread(() -> {
                Glide.get(this).clearDiskCache();
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show());
            }).start();
        });

        findViewById(R.id.settings_clear_search_history).setOnClickListener(v -> {
            app.getLibraryRepository().clearSearchHistory();
            Toast.makeText(this, R.string.settings_history_cleared, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.settings_clear_recent_history).setOnClickListener(v -> {
            app.getLibraryRepository().clearRecent();
            Toast.makeText(this, R.string.settings_history_cleared, Toast.LENGTH_SHORT).show();
        });

        // Test/automation + deep-link hook: com.nightlight.app.SettingsActivity
        // accepts an extra join_code to start listening immediately.
        String joinCode = getIntent().getStringExtra("join_code");
        if (joinCode != null && !joinCode.trim().isEmpty()) {
            joinSession(joinCode.trim());
        }

        findViewById(R.id.settings_licenses).setOnClickListener(v ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.settings_licenses)
                        .setMessage(R.string.licenses_text)
                        .setPositiveButton(R.string.action_close, null)
                        .show());

        findViewById(R.id.settings_privacy).setOnClickListener(v ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.settings_privacy)
                        .setMessage(R.string.settings_privacy_text)
                        .setPositiveButton(R.string.action_close, null)
                        .show());
    }

    private void wireShuffleMode() {
        int[] rows = {R.id.settings_shuffle_smart, R.id.settings_shuffle_normal, R.id.settings_shuffle_off};
        String[] modes = {com.nightlight.app.util.ShufflePrefs.SMART,
                com.nightlight.app.util.ShufflePrefs.NORMAL,
                com.nightlight.app.util.ShufflePrefs.OFF};
        for (int i = 0; i < rows.length; i++) {
            final int index = i;
            findViewById(rows[i]).setOnClickListener(v -> {
                com.nightlight.app.util.ShufflePrefs.setMode(this, modes[index]);
                com.nightlight.app.player.PlaybackManager.get(this).applyShuffleMode(modes[index]);
                renderShuffleMode(modes);
                Toast.makeText(this, getString(R.string.shuffle_mode_saved), Toast.LENGTH_SHORT).show();
            });
        }
        renderShuffleMode(modes);
    }

    private void renderShuffleMode(String[] modes) {
        String current = com.nightlight.app.util.ShufflePrefs.mode(this);
        int[] rows = {R.id.settings_shuffle_smart, R.id.settings_shuffle_normal, R.id.settings_shuffle_off};
        for (int i = 0; i < rows.length; i++) {
            View row = findViewById(rows[i]);
            TextView title = (TextView) ((ViewGroup) row).getChildAt(0);
            boolean active = modes[i].equals(current);
            title.setTextColor(getColor(active
                    ? R.color.nightlight_gold : R.color.nightlight_cream));
            title.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void wireDiscovery() {
        findViewById(R.id.settings_discovery_familiar).setOnClickListener(v -> {
            com.nightlight.app.util.ShufflePrefs.setDiscovery(this,
                    com.nightlight.app.util.ShufflePrefs.FAMILIAR);
            renderDiscovery();
        });
        findViewById(R.id.settings_discovery_balanced).setOnClickListener(v -> {
            com.nightlight.app.util.ShufflePrefs.setDiscovery(this,
                    com.nightlight.app.util.ShufflePrefs.BALANCED);
            renderDiscovery();
        });
        findViewById(R.id.settings_discovery_discovery).setOnClickListener(v -> {
            com.nightlight.app.util.ShufflePrefs.setDiscovery(this,
                    com.nightlight.app.util.ShufflePrefs.DISCOVERY);
            renderDiscovery();
        });
        renderDiscovery();
    }

    private void renderDiscovery() {
        String current = com.nightlight.app.util.ShufflePrefs.discovery(this);
        int[] rows = {R.id.settings_discovery_familiar, R.id.settings_discovery_balanced,
                R.id.settings_discovery_discovery};
        for (int row : rows) {
            TextView label = findViewById(row);
            boolean active = row == R.id.settings_discovery_familiar
                    ? com.nightlight.app.util.ShufflePrefs.FAMILIAR.equals(current)
                    : row == R.id.settings_discovery_balanced
                    ? com.nightlight.app.util.ShufflePrefs.BALANCED.equals(current)
                    : com.nightlight.app.util.ShufflePrefs.DISCOVERY.equals(current);
            label.setTextColor(getColor(active ? R.color.nightlight_gold : R.color.nightlight_cream));
            label.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void wireDeveloper() {
        openLink(R.id.settings_dev_linkedin, "https://www.linkedin.com/in/sagarakanoone/");
        openLink(R.id.settings_dev_github, "https://github.com/Sagar264offici/");
        openLink(R.id.settings_dev_portfolio, "https://sagar-horizon.vercel.app/");
        findViewById(R.id.settings_dev_email).setOnClickListener(v -> {
            android.content.Intent mail = new android.content.Intent(android.content.Intent.ACTION_SENDTO,
                    android.net.Uri.parse("mailto:pathaksagar264@gmail.com"));
            try {
                startActivity(mail);
            } catch (Exception e) {
                Toast.makeText(this, R.string.dev_email, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openLink(int rowId, String url) {
        findViewById(rowId).setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void wirePowerMode() {
        int[] rows = {R.id.settings_power_low, R.id.settings_power_balanced, R.id.settings_power_high};
        String[] modes = {com.nightlight.app.util.PowerModes.LOW,
                com.nightlight.app.util.PowerModes.BALANCED,
                com.nightlight.app.util.PowerModes.HIGH};
        String[] labels = {getString(R.string.power_low),
                getString(R.string.power_balanced),
                getString(R.string.power_high)};

        for (int i = 0; i < rows.length; i++) {
            final int index = i;
            View row = findViewById(rows[i]);
            row.setOnClickListener(v -> {
                com.nightlight.app.util.PowerModes.set(this, modes[index]);
                renderPowerMode(modes, labels);
                Toast.makeText(this,
                        getString(R.string.power_mode_saved, labels[index]),
                        Toast.LENGTH_SHORT).show();
            });
        }
        renderPowerMode(modes, labels);
    }

    private void wireListenTogether() {
        findViewById(R.id.settings_listen_share).setOnClickListener(v -> {
            if (com.nightlight.app.player.ListenTogether.get().isActive()) {
                com.nightlight.app.player.ListenTogether.shareCode(this,
                        com.nightlight.app.player.ListenTogether.get().activeCode());
                return;
            }
            Toast.makeText(this, R.string.listen_starting, Toast.LENGTH_SHORT).show();
            com.nightlight.app.player.ListenTogether.get().startHosting(this,
                    new com.nightlight.app.player.ListenTogether.CodeCallback() {
                        @Override
                        public void onCode(String code) {
                            com.nightlight.app.player.ListenTogether.shareCode(SettingsActivity.this, code);
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_SHORT).show();
                        }
                    });
        });

        findViewById(R.id.settings_listen_join).setOnClickListener(v -> {
            final android.widget.EditText input = new android.widget.EditText(this);
            input.setHint(R.string.listen_join_hint);
            input.setSingleLine(true);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.listen_join)
                    .setView(input)
                    .setPositiveButton(R.string.action_join, (d, w) -> {
                        String code = input.getText() == null ? "" : input.getText().toString().trim();
                        if (!code.isEmpty()) {
                            joinSession(code);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private void joinSession(String code) {
        Toast.makeText(this, R.string.listen_starting, Toast.LENGTH_SHORT).show();
        com.nightlight.app.player.ListenTogether.get().join(this, code,
                new com.nightlight.app.player.ListenTogether.CodeCallback() {
                    @Override
                    public void onCode(String joined) {
                        Toast.makeText(SettingsActivity.this,
                                getString(R.string.listen_joined, joined), Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void renderPowerMode(String[] modes, String[] labels) {
        String current = com.nightlight.app.util.PowerModes.get(this);
        int[] rows = {R.id.settings_power_low, R.id.settings_power_balanced, R.id.settings_power_high};
        for (int i = 0; i < modes.length; i++) {
            View row = findViewById(rows[i]);
            TextView title = (TextView) ((ViewGroup) row).getChildAt(0);
            boolean active = modes[i].equals(current);
            title.setTextColor(getColor(active
                    ? R.color.nightlight_gold : R.color.nightlight_cream));
            title.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }
}