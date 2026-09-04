package com.nightlight.app.data.repo;

import android.content.Context;

import com.nightlight.app.data.api.ApiClient;
import com.nightlight.app.data.api.NightLightApi;
import com.nightlight.app.data.api.dto.ApiResponse;
import com.nightlight.app.data.api.dto.Requests;
import com.nightlight.app.data.api.dto.UserDtos;
import com.nightlight.app.util.AppExecutors;
import com.nightlight.app.util.TokenStore;

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