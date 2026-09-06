package com.nightlight.app.data.repo;

import android.content.Context;

import com.google.gson.Gson;

import com.nightlight.app.data.api.ApiClient;
import com.nightlight.app.data.api.NightLightApi;
import com.nightlight.app.data.api.dto.ApiResponse;
import com.nightlight.app.data.api.dto.OtpDtos;
import com.nightlight.app.data.api.dto.Requests;
import com.nightlight.app.data.api.dto.UserDtos;
import com.nightlight.app.util.AccountPrefs;
import com.nightlight.app.util.AppExecutors;
import com.nightlight.app.util.ErrorMapper;
import com.nightlight.app.util.TokenStore;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Registers the device with the backend exactly once and stores the bearer
 * token securely. Music features work without auth; user data requires it.
 */
public final class AuthRepository {

    private final Context app;
    private final NightLightApi api;
    private volatile boolean registering;

    public AuthRepository(Context context) {
        this.app = context.getApplicationContext();
        this.api = ApiClient.nightLightApi(app);
    }

    public boolean isAuthenticated() {
        return TokenStore.hasToken();
    }

    /** True when a signed-in user has completed language + category onboarding. */
    public boolean isOnboarded() {
        return AccountPrefs.isOnboarded(app);
    }

    /**
     * Requests a 6-digit email verification code. On success, the code is
     * delivered by the backend (Resend in production; dev channel locally); it
     * is NEVER surfaced through this app. {@code onError} receives a friendly
     * message or null on success.
     */
    public void requestOtp(String email,
                           final Callback2 onSuccess,
                           final Callback1<String> onError) {
        api.requestOtp(new OtpDtos.RequestOtpRequest(email))
                .enqueue(new Callback<ApiResponse<OtpDtos.RequestOtpResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<OtpDtos.RequestOtpResponse>> call,
                                           Response<ApiResponse<OtpDtos.RequestOtpResponse>> response) {
                        ApiResponse<OtpDtos.RequestOtpResponse> body = response.body();
                        if (response.isSuccessful() && body != null && body.success) {
                            onSuccess.run();
                        } else {
                            onError.run(friendlyError(response, body));
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<OtpDtos.RequestOtpResponse>> call, Throwable t) {
                        onError.run(ErrorMapper.toUserMessage(app, t));
                    }
                });
    }

    /**
     * Verifies the code. On success the returned session token is stored and
     * the email is persisted with the account. The user record is created
     * server-side on first verification.
     */
    public void verifyOtp(String email, String otp,
                          final Callback2 onSuccess,
                          final Callback1<String> onError) {
        api.verifyOtp(new OtpDtos.VerifyOtpRequest(email, otp))
                .enqueue(new Callback<ApiResponse<OtpDtos.VerifyOtpResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<OtpDtos.VerifyOtpResponse>> call,
                                           Response<ApiResponse<OtpDtos.VerifyOtpResponse>> response) {
                        ApiResponse<OtpDtos.VerifyOtpResponse> body = response.body();
                        if (response.isSuccessful() && body != null && body.success
                                && body.data != null && body.data.token != null) {
                            TokenStore.setToken(body.data.token);
                            AccountPrefs.setEmail(app, email);
                            onSuccess.run();
                        } else {
                            onError.run(friendlyError(response, body));
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<OtpDtos.VerifyOtpResponse>> call, Throwable t) {
                        onError.run(ErrorMapper.toUserMessage(app, t));
                    }
                });
    }

    /** Best-effort server-side sync of onboarding selections (never blocks UI). */
    public void savePreferences(List<String> languages, List<String> categories) {
        if (!TokenStore.hasToken()) {
            return;
        }
        api.savePreferences(new OtpDtos.SavePreferencesRequest(languages, categories))
                .enqueue(new Callback<ApiResponse<Object>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<Object>> call, Response<ApiResponse<Object>> response) {
                        // Best-effort: local onboarding is authoritative for the UI.
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<Object>> call, Throwable t) {
                        // Best-effort: ignore.
                    }
                });
    }

    /** Clears the local session and account state after revoking the token server-side. */
    public void logout() {
        final String token = TokenStore.peekToken();
        if (TokenStore.hasToken()) {
            // Fire-and-forget revoke; local state is cleared regardless.
            api.logout().enqueue(new retrofit2.Callback<ApiResponse<Object>>() {
                @Override
                public void onResponse(retrofit2.Call<ApiResponse<Object>> call,
                                       retrofit2.Response<ApiResponse<Object>> response) {
                }

                @Override
                public void onFailure(retrofit2.Call<ApiResponse<Object>> call, Throwable t) {
                }
            });
        }
        TokenStore.clearToken();
        AccountPrefs.clear(app);
    }

    // ---- Email + password auth ----

    /**
     * Creates a password account. The backend emails a verification code and
     * returns NO session; the mailbox is proven via {@link #verifyOtp}.
     */
    public void registerPassword(String email, String password,
                                 final Callback2 onSuccess,
                                 final Callback1<String> onError) {
        api.registerPassword(new OtpDtos.PasswordRegisterRequest(email, password))
                .enqueue(new Callback<ApiResponse<OtpDtos.PasswordRegisterResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<OtpDtos.PasswordRegisterResponse>> call,
                                           Response<ApiResponse<OtpDtos.PasswordRegisterResponse>> response) {
                        ApiResponse<OtpDtos.PasswordRegisterResponse> body = response.body();
                        if (response.isSuccessful() && body != null && body.success) {
                            AccountPrefs.setEmail(app, email);
                            onSuccess.run();
                        } else {
                            onError.run(friendlyError(response, body));
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<OtpDtos.PasswordRegisterResponse>> call, Throwable t) {
                        onError.run(ErrorMapper.toUserMessage(app, t));
                    }
                });
    }

    /** Logs in with email + password and stores the returned session token. */
    public void loginPassword(String email, String password,
                              final Callback2 onSuccess,
                              final Callback1<String> onError) {
        api.login(new OtpDtos.LoginRequest(email, password))
                .enqueue(new Callback<ApiResponse<OtpDtos.LoginResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<OtpDtos.LoginResponse>> call,
                                           Response<ApiResponse<OtpDtos.LoginResponse>> response) {
                        ApiResponse<OtpDtos.LoginResponse> body = response.body();
                        if (response.isSuccessful() && body != null && body.success
                                && body.data != null && body.data.token != null) {
                            TokenStore.setToken(body.data.token);
                            AccountPrefs.setEmail(app, email);
                            onSuccess.run();
                        } else {
                            onError.run(friendlyError(response, body));
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<OtpDtos.LoginResponse>> call, Throwable t) {
                        onError.run(ErrorMapper.toUserMessage(app, t));
                    }
                });
    }

    /** Begins password recovery; a reset code is emailed server-side only. */
    public void forgotPassword(String email,
                               final Callback2 onSuccess,
                               final Callback1<String> onError) {
        api.forgotPassword(new OtpDtos.ForgotPasswordRequest(email))
                .enqueue(new Callback<ApiResponse<OtpDtos.RequestOtpResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<OtpDtos.RequestOtpResponse>> call,
                                           Response<ApiResponse<OtpDtos.RequestOtpResponse>> response) {
                        ApiResponse<OtpDtos.RequestOtpResponse> body = response.body();
                        if (response.isSuccessful() && body != null && body.success) {
                            onSuccess.run();
                        } else {
                            onError.run(friendlyError(response, body));
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<OtpDtos.RequestOtpResponse>> call, Throwable t) {
                        onError.run(ErrorMapper.toUserMessage(app, t));
                    }
                });
    }

    /** Exchanges the emailed reset code for a one-time reset token. */
    public void verifyResetOtp(String email, String otp,
                               final Callback1<String> onSuccess,
                               final Callback1<String> onError) {
        api.resetVerify(new OtpDtos.ResetVerifyRequest(email, otp))
                .enqueue(new Callback<ApiResponse<OtpDtos.ResetVerifyResponse>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<OtpDtos.ResetVerifyResponse>> call,
                                           Response<ApiResponse<OtpDtos.ResetVerifyResponse>> response) {
                        ApiResponse<OtpDtos.ResetVerifyResponse> body = response.body();
                        if (response.isSuccessful() && body != null && body.success
                                && body.data != null && body.data.resetToken != null) {
                            onSuccess.run(body.data.resetToken);
                        } else {
                            onError.run(friendlyError(response, body));
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<OtpDtos.ResetVerifyResponse>> call, Throwable t) {
                        onError.run(ErrorMapper.toUserMessage(app, t));
                    }
                });
    }

    /** Completes the reset with the one-time token; returns the user to Login. */
    public void resetPassword(String email, String resetToken, String newPassword,
                              final Callback2 onSuccess,
                              final Callback1<String> onError) {
        api.resetPassword(new OtpDtos.ResetPasswordRequest(email, resetToken, newPassword))
                .enqueue(new Callback<ApiResponse<Object>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<Object>> call,
                                           Response<ApiResponse<Object>> response) {
                        ApiResponse<Object> body = response.body();
                        if (response.isSuccessful() && body != null && body.success) {
                            onSuccess.run();
                        } else {
                            onError.run(friendlyError(response, body));
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<Object>> call, Throwable t) {
                        onError.run(ErrorMapper.toUserMessage(app, t));
                    }
                });
    }

    // ---- Guest mode ----

    /** Enters the app without any server identity. Playlists stay local-only. */
    public void continueAsGuest() {
        AccountPrefs.setGuest(app, true);
    }

    public boolean isGuest() {
        return AccountPrefs.isGuest(app);
    }

    /** Called after a successful signup that converts a guest account. */
    public void markConvertedFromGuest() {
        AccountPrefs.clearGuest(app);
    }

    private String friendlyError(Response<?> response, ApiResponse<?> body) {
        ApiResponse<?> parsed = body;
        if (parsed == null && response.errorBody() != null) {
            try {
                parsed = new Gson().fromJson(response.errorBody().string(), ApiResponse.class);
            } catch (Exception ignored) {
                // Fall through to the code-based mapping.
            }
        }
        if (parsed != null && parsed.code != null) {
            String code = parsed.code;
            if ("OTP_INVALID".equals(code)) {
                return app.getString(com.nightlight.app.R.string.otp_error_invalid);
            }
            if ("OTP_EXPIRED".equals(code)) {
                return app.getString(com.nightlight.app.R.string.otp_error_expired);
            }
            if ("OTP_TOO_MANY_ATTEMPTS".equals(code)) {
                return app.getString(com.nightlight.app.R.string.otp_error_too_many);
            }
            if ("RESEND_COOLDOWN".equals(code)) {
                return app.getString(com.nightlight.app.R.string.otp_error_cooldown);
            }
            if ("EMAIL_SERVICE_NOT_CONFIGURED".equals(code)) {
                return app.getString(com.nightlight.app.R.string.otp_error_not_configured);
            }
            if ("INVALID_EMAIL".equals(code)) {
                return app.getString(com.nightlight.app.R.string.otp_error_email);
            }
        }
        return ErrorMapper.forApiResponse(app, parsed);
    }

    /** Success callback (no payload). */
    public interface Callback2 {
        void run();
    }

    /** Single-argument callback (a friendly message). */
    public interface Callback1<T> {
        void run(T value);
    }

    /**
     * Ensures a token exists. Never blocks the caller: the result arrives on
     * the main thread. The deviceId is stable per install.
     */
    public void ensureAuthenticated(final Runnable onReady, final Runnable onFailure) {
        if (TokenStore.hasToken()) {
            onReady.run();
            return;
        }
        if (registering) {
            // A registration is already in flight; retry shortly.
            AppExecutors.onMain(() -> AppExecutors.get().main().execute(() -> ensureAuthenticated(onReady, onFailure)));
            return;
        }

        registering = true;
        String deviceId = TokenStore.getDeviceId();
        Requests.RegisterRequest body = new Requests.RegisterRequest(deviceId, "1.0.0");

        api.register(body).enqueue(new Callback<ApiResponse<UserDtos.AuthDataDto>>() {
            @Override
            public void onResponse(Call<ApiResponse<UserDtos.AuthDataDto>> call,
                                   Response<ApiResponse<UserDtos.AuthDataDto>> response) {
                registering = false;
                ApiResponse<UserDtos.AuthDataDto> body = response.body();
                if (response.isSuccessful() && body != null && body.success && body.data != null
                        && body.data.token != null) {
                    TokenStore.setToken(body.data.token);
                    onReady.run();
                } else {
                    onFailure.run();
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<UserDtos.AuthDataDto>> call, Throwable t) {
                registering = false;
                onFailure.run();
            }
        });
    }
}