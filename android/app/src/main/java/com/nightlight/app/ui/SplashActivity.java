package com.nightlight.app.ui;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.nightlight.app.R;

/** Very short splash; remote work never blocks it (see NightLightApp). */
public final class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_MS = 900;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View logo = findViewById(R.id.splash_logo);
        View tagline = findViewById(R.id.splash_tagline);
        ObjectAnimator logoAnim = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f);
        logoAnim.setDuration(450);
        logoAnim.start();
        ObjectAnimator taglineAnim = ObjectAnimator.ofFloat(tagline, View.ALPHA, 0f, 1f);
        taglineAnim.setDuration(450);
        taglineAnim.setStartDelay(180);
        taglineAnim.start();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }, SPLASH_MS);
    }
}