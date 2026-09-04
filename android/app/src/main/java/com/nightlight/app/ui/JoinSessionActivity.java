package com.nightlight.app.ui;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.nightlight.app.R;
import com.nightlight.app.player.ListenTogether;
import com.nightlight.app.util.InsetsUtil;

/**
 * Handles nightlight://listen/CODE deep links: joins the shared session so the
 * device plays the same song at the same time, then finishes.
 */
public final class JoinSessionActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_join_session);
        InsetsUtil.applySystemBars(findViewById(R.id.join_root));

        Uri uri = getIntent().getData();
        String code = null;
        if (uri != null) {
            String last = uri.getLastPathSegment();
            if (last != null && last.length() >= 4 && last.length() <= 8) {
                code = last.toUpperCase();
            }
        }
        String share = getIntent().getStringExtra("join_code");
        if (code == null && share != null) {
            code = share.trim().toUpperCase();
        }
        if (code == null) {
            Toast.makeText(this, R.string.listen_join_hint, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final String target = code;
        ListenTogether.get().join(this, target, new ListenTogether.CodeCallback() {
            @Override
            public void onCode(String joined) {
                Toast.makeText(JoinSessionActivity.this,
                        getString(R.string.listen_joined, joined), Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(JoinSessionActivity.this, message, Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }
}
