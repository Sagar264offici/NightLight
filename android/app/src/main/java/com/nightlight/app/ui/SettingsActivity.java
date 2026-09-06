package com.nightlight.app.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.nightlight.app.BuildConfig;
import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.repo.AuthRepository;
import com.nightlight.app.util.AccountPrefs;
import com.nightlight.app.util.TokenStore;

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
        wireAccount();

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

    /** Account section: signed-in email + sign out (clears session + onboarding). */
    private void wireAccount() {
        final ViewGroup content = findViewById(R.id.settings_content);
        if (content == null) {
            return;
        }
        int dp = Math.round(getResources().getDisplayMetrics().density);

        TextView header = new TextView(this);
        header.setText(R.string.settings_account);
        header.setTextColor(getColor(R.color.nightlight_gold));
        header.setTextSize(13f);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.topMargin = Math.round(26f * dp);
        header.setLayoutParams(headerLp);
        content.addView(header);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E60B1128"));
        bg.setCornerRadius(Math.round(16f * dp));
        bg.setStroke(Math.round(1f * dp), Color.parseColor("#26FFFFFF"));
        panel.setBackground(bg);
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.topMargin = Math.round(10f * dp);
        panel.setLayoutParams(panelLp);
        int pad = Math.round(16f * dp);
        panel.setPadding(pad, Math.round(6f * dp), pad, Math.round(6f * dp));

        TextView email = new TextView(this);
        email.setTextSize(15f);
        email.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        email.setGravity(Gravity.START);
        email.setPadding(0, Math.round(10f * dp), 0, Math.round(2f * dp));
        String accountEmail = AccountPrefs.email(this);
        if (TokenStore.hasToken() && accountEmail != null) {
            email.setText(accountEmail);
            email.setTextColor(getColor(R.color.nightlight_cream));
        } else {
            email.setText(R.string.settings_signed_out);
            email.setTextColor(getColor(R.color.nightlight_cream_dim));
        }
        panel.addView(email);

        TextView signOut = new TextView(this);
        signOut.setText(R.string.settings_sign_out);
        signOut.setTextSize(14f);
        signOut.setTextColor(getColor(R.color.nightlight_error));
        signOut.setGravity(Gravity.START);
        signOut.setPadding(0, Math.round(6f * dp), 0, Math.round(10f * dp));
        signOut.setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.settings_sign_out)
                    .setMessage(R.string.settings_sign_out_account_confirm)
                    .setPositiveButton(R.string.settings_sign_out, (d, w) -> doSignOut())
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        });
        if (!TokenStore.hasToken()) {
            signOut.setVisibility(View.GONE);
        }
        panel.addView(signOut);
        content.addView(panel);
    }

    private void doSignOut() {
        AuthRepository auth = ((NightLightApp) getApplication()).getAuthRepository();
        auth.logout();
        Intent login = new Intent(this, LoginActivity.class);
        login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(login);
        finish();
    }
}