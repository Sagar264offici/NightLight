package com.nightlight.app.data.api;

import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;
import android.util.Log;

import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.CredentialManager;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Google Sign-In via Credential Manager.
 *
 * Returns a Google ID token (aud = GOOGLE_WEB_CLIENT_ID, iss =
 * accounts.google.com). The app exchanges it at /auth/firebase/exchange,
 * which routes Google-issuer tokens to the Google verifier - identity is
 * Google's, the session is NightLight's.
 *
 * The serverClientId is the OAuth *Web* client ID from Firebase (public).
 * Firebase must have the app's package + release SHA-1 registered or Google
 * returns DEVELOPER_ERROR.
 */
public final class GoogleSignInHelper {

    private static final String TAG = "NightLightAuth";
    /** Injected at app startup from BuildConfig.GOOGLE_WEB_CLIENT_ID. */
    public static volatile String serverClientId;

    private final CredentialManager manager;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public GoogleSignInHelper(Context context) {
        this.manager = CredentialManager.create(context.getApplicationContext());
    }

    public interface Callback {
        /** idToken carries the Google ID token; error carries a friendly message (null = user cancelled). */
        void onResult(String idToken, String error);
    }

    public void signIn(Activity activity, Callback cb) {
        if (serverClientId == null || serverClientId.isEmpty()) {
            cb.onResult(null, "Google sign-in is not configured in this build yet.");
            return;
        }
        GetGoogleIdOption option = new GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(false)
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();
        manager.getCredentialAsync(activity, request, null, executor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        try {
                            if (result.getCredential() instanceof CustomCredential
                                    && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(
                                            result.getCredential().getType())) {
                                GoogleIdTokenCredential cred = GoogleIdTokenCredential.createFrom(
                                        result.getCredential().getData());
                                cb.onResult(cred.getIdToken(), null);
                                return;
                            }
                            cb.onResult(null, "Unexpected sign-in credential. Try again.");
                        } catch (Exception e) {
                            Log.w(TAG, "Google credential parse failed");
                            cb.onResult(null, "Could not read the Google sign-in result. Try again.");
                        }
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        if (e instanceof GetCredentialCancellationException) {
                            cb.onResult(null, null); // user closed the sheet
                            return;
                        }
                        String name = e.getClass().getSimpleName();
                        String msg = "Google sign-in failed. Try again.";
                        if (name.contains("NoCredential")) {
                            msg = "No Google account is available on this device.";
                        } else if (name.contains("Development") || name.contains("Developer")
                                || String.valueOf(e.getMessage()).contains("DEVELOPER")) {
                            msg = "Google sign-in is not linked to this app yet (SHA-1 registration).";
                        }
                        Log.w(TAG, "Google sign-in error: " + name);
                        cb.onResult(null, msg);
                    }
                });
    }

    /** Clears the Credential Manager state after logout. */
    public void signOut(Context context) {
        try {
            manager.clearCredentialStateAsync(new ClearCredentialStateRequest(), null,
                    Runnable::run, new CredentialManagerCallback<Void, androidx.credentials.exceptions.ClearCredentialException>() {
                        @Override
                        public void onResult(Void result) {
                        }

                        @Override
                        public void onError(androidx.credentials.exceptions.ClearCredentialException e) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }
}
