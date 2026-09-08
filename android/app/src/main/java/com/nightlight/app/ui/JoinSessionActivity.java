package com.nightlight.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.nightlight.app.R;
import com.nightlight.app.player.ListenTogether;
import com.nightlight.app.util.InsetsUtil;

import java.util.List;

/**
 * Handles deep links into a Listen Together session:
 * <ul>
 *   <li>{@code nightlight://listen/ABC123}</li>
 *   <li>{@code https://nightlight.app/l/ABC123}</li>
 *   <li>Intent extra {@code join_code} (internal sharing)</li>
 * </ul>
 *
 * Robustness: handles cold launch, warm resume, repeated links, browser and
 * messaging-app launch.  Code is normalised (trim, uppercase) and validated
 * against the session-code alphabet before joining.
 */
public final class JoinSessionActivity extends AppCompatActivity {

    /** Valid session-code alphabet (matches the backend ALPHABET constant). */
    private static final String VALID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_join_session);
        InsetsUtil.applySystemBars(findViewById(R.id.join_root));

        handleIntent(getIntent());
    }

    /**
     * When the activity is already alive and a new deep link arrives (e.g. the
     * user taps a second link while this activity is on-screen), Android calls
     * onNewIntent instead of onCreate.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String code = extractCode(intent);
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

    /**
     * Extract a valid session code from the intent, trying all supported
     * sources in priority order:
     * <ol>
     *   <li>URI data ({@code nightlight://listen/CODE} or {@code https://nightlight.app/l/CODE})</li>
     *   <li>{@code join_code} string extra (internal share from Settings)</li>
     * </ol>
     */
    private String extractCode(Intent intent) {
        // 1. Deep link URI.
        Uri uri = intent.getData();
        if (uri != null) {
            String code = parseCodeFromUri(uri);
            if (code != null) return code;
        }

        // 2. Internal extra.
        String extra = intent.getStringExtra("join_code");
        if (extra != null) {
            return normaliseCode(extra);
        }

        return null;
    }

    /**
     * Parse a session code from a URI.  Handles:
     * <ul>
     *   <li>{@code nightlight://listen/ABC123} — path segment after "listen"</li>
     *   <li>{@code https://nightlight.app/l/ABC123} — last path segment after "/l/"</li>
     * </ul>
     */
    private String parseCodeFromUri(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.isEmpty()) return null;

        String host = uri.getHost();
        String scheme = uri.getScheme();

        // nightlight://listen/ABC123 → segments = ["listen", "ABC123"]
        if ("nightlight".equals(scheme) && segments.size() >= 2
                && "listen".equalsIgnoreCase(segments.get(0))) {
            return normaliseCode(segments.get(segments.size() - 1));
        }

        // https://nightlight.app/l/ABC123 → segments = ["l", "ABC123"]
        if ("https".equals(scheme) && host != null && host.contains("nightlight.app")
                && segments.size() >= 2 && "l".equals(segments.get(0))) {
            return normaliseCode(segments.get(segments.size() - 1));
        }

        // Fallback: take the last path segment if it looks like a code.
        String last = uri.getLastPathSegment();
        if (last != null) {
            return normaliseCode(last);
        }

        return null;
    }

    /**
     * Trim, uppercase, validate and return a session code, or null if invalid.
     */
    private String normaliseCode(String raw) {
        if (raw == null) return null;
        String code = raw.trim().toUpperCase();
        if (code.length() < 4 || code.length() > 8) return null;
        for (int i = 0; i < code.length(); i++) {
            if (VALID_CHARS.indexOf(code.charAt(i)) == -1) return null;
        }
        return code;
    }
}
