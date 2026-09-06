package com.nightlight.app.ui;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.repo.AuthRepository;
import com.nightlight.app.util.AccountPrefs;

import java.util.regex.Pattern;

/**
 * NightLight authentication hub.
 *
 * Entry screen: Continue with Email (OTP) / Continue as Guest as primary
 * options, with Create Account / Login / Forgot Password as secondary links.
 * Password flows talk to the backend's
 * scrypt+OTP-verified auth; the OTP flow is unchanged. Guests never receive
 * a server token, so no server account can be created for them.
 */
public final class LoginActivity extends AppCompatActivity {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    /** Launch extra: open directly on a specific auth mode. */
    public static final String EXTRA_MODE = "extra_mode";

    public static final int MODE_ENTRY = 0;
    public static final int MODE_OTP = 1;
    public static final int MODE_CREATE = 2;
    public static final int MODE_LOGIN = 3;
    public static final int MODE_FORGOT = 4;
    public static final int MODE_RESET_OTP = 5;
    public static final int MODE_RESET_NEW = 6;

    private AuthRepository auth;
    private LinearLayout root;
    private ScrollView scroller;
    private LinearLayout content;

    private TextView stepTitle;
    private TextView stepSubtitle;
    private TextView errorText;
    private EditText emailInput;
    private EditText otpInput;
    private EditText passwordInput;
    private EditText confirmInput;
    private TextView primaryButtonText;
    private TextView resend;
    private android.widget.TextView guestButton;
    private TextView loginLink;
    private TextView forgotLink;
    private TextView switchAuthLink;
    private android.widget.CheckBox passwordToggle;
    private android.widget.CheckBox confirmToggle;
    private ProgressBar spinner;

    private int mode = MODE_ENTRY;
    private String email;
    private String resetToken;
    private boolean busy;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auth = ((NightLightApp) getApplication()).getAuthRepository();
        int requested = getIntent().getIntExtra(EXTRA_MODE, MODE_ENTRY);
        mode = (requested >= MODE_ENTRY && requested <= MODE_RESET_NEW) ? requested : MODE_ENTRY;
        if (mode == MODE_CREATE || mode == MODE_LOGIN) {
            // Coming from the guest conversion prompt: prefill nothing, but
            // keep the pending flag (set by the prompt) for onboarding.
            AccountPrefs.setPendingGuestConversion(this, true);
        }
        buildUi();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ambientBackground());

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(28f * getResources().getDisplayMetrics().density);
        content.setPadding(pad, Math.round(24f * getResources().getDisplayMetrics().density), pad, pad);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.nightlight_logo);
        int logoSize = Math.round(72f * getResources().getDisplayMetrics().density);
        logo.setLayoutParams(new LinearLayout.LayoutParams(logoSize, logoSize));
        logo.setAlpha(0f);
        logo.animate().alpha(1f).setDuration(500).start();
        content.addView(logo);

        TextView welcome = new TextView(this);
        welcome.setText(R.string.login_welcome);
        welcome.setTextColor(getColor(R.color.nightlight_cream));
        welcome.setTextSize(26f);
        welcome.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        welcome.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams welcomeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        welcomeLp.topMargin = Math.round(14f * getResources().getDisplayMetrics().density);
        welcome.setLayoutParams(welcomeLp);
        content.addView(welcome);

        TextView tagline = new TextView(this);
        tagline.setText(R.string.login_tagline);
        tagline.setTextColor(getColor(R.color.nightlight_cream_dim));
        tagline.setTextSize(14f);
        tagline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.topMargin = dp(6);
        tagline.setLayoutParams(tagLp);
        content.addView(tagline);

        stepTitle = new TextView(this);
        stepTitle.setTextColor(getColor(R.color.nightlight_cream));
        stepTitle.setTextSize(22f);
        stepTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        stepTitle.setGravity(Gravity.CENTER);
        stepTitle.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = Math.round(12f * getResources().getDisplayMetrics().density);
        stepTitle.setLayoutParams(titleLp);
        content.addView(stepTitle);

        stepSubtitle = new TextView(this);
        stepSubtitle.setTextColor(getColor(R.color.nightlight_cream_dim));
        stepSubtitle.setTextSize(14f);
        stepSubtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Math.round(8f * getResources().getDisplayMetrics().density);
        subLp.bottomMargin = Math.round(12f * getResources().getDisplayMetrics().density);
        stepSubtitle.setLayoutParams(subLp);
        content.addView(stepSubtitle);

        FrameLayout card = new FrameLayout(this);
        card.setBackground(glassBackground());
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = Math.round(4f * getResources().getDisplayMetrics().density);
        card.setLayoutParams(cardLp);
        int cardPad = Math.round(20f * getResources().getDisplayMetrics().density);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);

        LinearLayout cardContent = new LinearLayout(this);
        cardContent.setOrientation(LinearLayout.VERTICAL);
        card.addView(cardContent);

        emailInput = inputField(R.string.login_email_hint, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        cardContent.addView(emailInput);

        otpInput = inputField(R.string.login_otp_hint, InputType.TYPE_CLASS_NUMBER);
        otpInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        cardContent.addView(otpInput);

        passwordInput = inputField(R.string.login_password_hint, InputType.TYPE_TEXT_VARIATION_PASSWORD);
        cardContent.addView(passwordInput);
        passwordToggle = addShowHide(passwordInput);

        confirmInput = inputField(R.string.login_confirm_hint, InputType.TYPE_TEXT_VARIATION_PASSWORD);
        cardContent.addView(confirmInput);
        confirmToggle = addShowHide(confirmInput);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(52f * getResources().getDisplayMetrics().density));
        rowLp.topMargin = Math.round(14f * getResources().getDisplayMetrics().density);
        buttonRow.setLayoutParams(rowLp);

        TextView primaryButton = new TextView(this);
        primaryButton.setGravity(Gravity.CENTER);
        primaryButton.setBackground(buttonBackground());
        primaryButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        primaryButton.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        primaryButtonText = primaryButton;
        primaryButton.setOnClickListener(v -> onPrimary());
        buttonRow.addView(primaryButton);

        spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        int spinSize = Math.round(22f * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(spinSize, spinSize);
        spinLp.setMarginStart(Math.round(14f * getResources().getDisplayMetrics().density));
        spinner.setLayoutParams(spinLp);
        buttonRow.addView(spinner);

        cardContent.addView(buttonRow);

        resend = new TextView(this);
        resend.setText(R.string.login_resend);
        resend.setTextColor(getColor(R.color.nightlight_gold));
        resend.setTextSize(13f);
        resend.setGravity(Gravity.CENTER);
        resend.setPadding(0, Math.round(12f * getResources().getDisplayMetrics().density), 0, 0);
        resend.setOnClickListener(v -> {
            if (mode == MODE_OTP) {
                requestOtp();
            } else if (mode == MODE_RESET_OTP) {
                startForgotPassword();
            }
        });
        cardContent.addView(resend);

        // Secondary links live inside the card, under the primary action.
        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.VERTICAL);
        links.setGravity(Gravity.CENTER);
        links.setPadding(0, dp(6), 0, 0);

        switchAuthLink = linkTextView();
        links.addView(switchAuthLink);

        // Spec: Login is a direct secondary link on the entry screen (not
        // reachable only through Create Account).
        loginLink = linkTextView();
        links.addView(loginLink);

        forgotLink = linkTextView();
        links.addView(forgotLink);

        cardContent.addView(links);

        content.addView(card);

        // Guest is a PRIMARY entry option: outlined button under the main CTA.
        guestButton = new android.widget.Button(this);
        guestButton.setAllCaps(false);
        guestButton.setTextSize(15f);
        guestButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        guestButton.setTextColor(getColor(R.color.nightlight_cream));
        guestButton.setBackground(outlineButtonBackground());
        guestButton.setOnClickListener(v -> enterAsGuest());
        LinearLayout.LayoutParams guestLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(50f * getResources().getDisplayMetrics().density));
        guestLp.topMargin = Math.round(10f * getResources().getDisplayMetrics().density);
        guestButton.setLayoutParams(guestLp);
        content.addView(guestButton);

        errorText = new TextView(this);
        errorText.setTextColor(getColor(R.color.nightlight_error));
        errorText.setTextSize(13f);
        errorText.setGravity(Gravity.CENTER);
        errorText.setVisibility(View.GONE);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errLp.topMargin = Math.round(14f * getResources().getDisplayMetrics().density);
        errorText.setLayoutParams(errLp);
        content.addView(errorText);

        scroller.addView(content);
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        renderStep();
        emailInput.post(() -> emailInput.requestFocus());
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private android.widget.CheckBox addShowHide(EditText field) {
        android.widget.CheckBox toggle = new android.widget.CheckBox(this);
        toggle.setText(R.string.login_show_password);
        toggle.setTextSize(12f);
        toggle.setTextColor(getColor(R.color.nightlight_cream_dim));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        toggle.setLayoutParams(lp);
        toggle.setOnCheckedChangeListener((b, checked) ->
                field.setInputType(InputType.TYPE_CLASS_TEXT
                        | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_TEXT_VARIATION_PASSWORD)));
        // The toggle must sit right after the field it controls: the parent is
        // cardContent, so find the field's index and insert after it.
        ViewGroup parent = (ViewGroup) field.getParent();
        parent.addView(toggle, parent.indexOfChild(field) + 1);
        return toggle;
    }

    private TextView linkTextView() {
        TextView tv = new TextView(this);
        tv.setTextSize(14f);
        tv.setTextColor(getColor(R.color.nightlight_gold));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(6), 0, dp(6));
        return tv;
    }

    private android.graphics.drawable.Drawable ambientBackground() {
        android.graphics.drawable.GradientDrawable top = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#141B3D"), Color.parseColor("#0A0A18")});
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{top});
    }

    private EditText inputField(int hintRes, int type) {
        EditText field = new EditText(this);
        field.setHint(getString(hintRes));
        field.setInputType(InputType.TYPE_CLASS_TEXT | type);
        field.setSingleLine(true);
        field.setTextColor(getColor(R.color.nightlight_cream));
        field.setHintTextColor(getColor(R.color.nightlight_cream_dim));
        field.setTextSize(15f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1A121B3D"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        field.setBackground(bg);
        field.setPadding(dp(16), dp(13), dp(16), dp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        field.setLayoutParams(lp);
        return field;
    }

    private GradientDrawable glassBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E60B1128"));
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), Color.parseColor("#26FFFFFF"));
        return bg;
    }

    private GradientDrawable outlineButtonBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#26FFFFFF"));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.parseColor("#55FFFFFF"));
        return bg;
    }

    private GradientDrawable buttonBackground() {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{getColor(R.color.nightlight_blue), getColor(R.color.nightlight_blue_glow)});
        bg.setCornerRadius(dp(16));
        return bg;
    }

    private void renderStep() {
        boolean entry = mode == MODE_ENTRY;
        boolean otp = mode == MODE_OTP;
        boolean create = mode == MODE_CREATE;
        boolean login = mode == MODE_LOGIN;
        boolean forgot = mode == MODE_FORGOT;
        boolean resetOtp = mode == MODE_RESET_OTP;
        boolean resetNew = mode == MODE_RESET_NEW;

        emailInput.setVisibility(entry || forgot || create || login ? View.VISIBLE : View.GONE);
        otpInput.setVisibility(otp || resetOtp ? View.VISIBLE : View.GONE);
        passwordInput.setVisibility(create || login || resetNew ? View.VISIBLE : View.GONE);
        confirmInput.setVisibility(create || resetNew ? View.VISIBLE : View.GONE);
        passwordToggle.setVisibility(passwordInput.getVisibility());
        confirmToggle.setVisibility(confirmInput.getVisibility());
        resend.setVisibility(otp || resetOtp ? View.VISIBLE : View.GONE);

        switchAuthLink.setVisibility(entry || login || create ? View.VISIBLE : View.GONE);
        loginLink.setVisibility(entry ? View.VISIBLE : View.GONE);
        forgotLink.setVisibility(entry || login ? View.VISIBLE : View.GONE);
        guestButton.setVisibility(entry ? View.VISIBLE : View.GONE);

        if (entry) {
            stepTitle.setText(R.string.login_title);
            stepSubtitle.setText(R.string.login_subtitle);
            primaryButtonText.setText(R.string.login_continue);
            switchAuthLink.setText(R.string.login_link_create);
            switchAuthLink.setOnClickListener(v -> setMode(MODE_CREATE));
            loginLink.setText(R.string.login_link_login);
            loginLink.setOnClickListener(v -> setMode(MODE_LOGIN));
            forgotLink.setText(R.string.login_link_forgot);
            forgotLink.setOnClickListener(v -> setMode(MODE_FORGOT));
            guestButton.setText(R.string.login_link_guest);
        } else if (otp) {
            stepTitle.setText(R.string.login_otp_title);
            stepSubtitle.setText(getString(R.string.login_otp_subtitle, email == null ? "" : email));
            primaryButtonText.setText(R.string.login_verify);
        } else if (create) {
            stepTitle.setText(R.string.login_create_title);
            stepSubtitle.setText(R.string.login_create_subtitle);
            primaryButtonText.setText(R.string.login_create_button);
            switchAuthLink.setText(R.string.login_link_have_account);
            switchAuthLink.setOnClickListener(v -> setMode(MODE_LOGIN));
            forgotLink.setText(R.string.login_link_forgot);
            forgotLink.setOnClickListener(v -> setMode(MODE_FORGOT));
            confirmInput.post(() -> scroller.smoothScrollTo(0, confirmInput.getBottom()));
        } else if (login) {
            stepTitle.setText(R.string.login_password_title);
            stepSubtitle.setText(R.string.login_password_subtitle);
            primaryButtonText.setText(R.string.login_login_button);
            switchAuthLink.setText(R.string.login_link_create);
            switchAuthLink.setOnClickListener(v -> setMode(MODE_CREATE));
            forgotLink.setText(R.string.login_link_forgot);
            forgotLink.setOnClickListener(v -> setMode(MODE_FORGOT));
        } else if (forgot) {
            stepTitle.setText(R.string.login_forgot_title);
            stepSubtitle.setText(R.string.login_forgot_subtitle);
            primaryButtonText.setText(R.string.login_forgot_button);
        } else if (resetOtp) {
            stepTitle.setText(R.string.login_reset_otp_title);
            stepSubtitle.setText(getString(R.string.login_reset_otp_subtitle, email == null ? "" : email));
            primaryButtonText.setText(R.string.login_verify);
        } else if (resetNew) {
            stepTitle.setText(R.string.login_reset_new_title);
            stepSubtitle.setText(R.string.login_reset_new_subtitle);
            primaryButtonText.setText(R.string.login_reset_new_button);
        }

        // Gentle entrance for the step transition.
        content.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> {
                    content.setAlpha(1f);
                    content.animate().alpha(1f).setDuration(240).start();
                })
                .start();
        setBusy(false);
    }

    private void setMode(int newMode) {
        mode = newMode;
        hideError();
        // Fresh fields on every mode change: stale credentials from a previous
        // step (e.g. the reset form) must never leak into the next one.
        emailInput.setText("");
        otpInput.setText("");
        passwordInput.setText("");
        confirmInput.setText("");
        renderStep();
    }

    private void enterAsGuest() {
        auth.continueAsGuest();
        Intent target = new Intent(this, MainActivity.class);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(target);
        finish();
    }

    private void onPrimary() {
        if (busy) {
            return;
        }
        hideError();
        switch (mode) {
            case MODE_ENTRY:
                email = textOf(emailInput);
                if (!EMAIL_PATTERN.matcher(email).matches()) {
                    showError(getString(R.string.otp_error_email));
                    return;
                }
                requestOtp();
                break;
            case MODE_OTP:
                verifyCode(textOf(otpInput), false);
                break;
            case MODE_CREATE:
                onCreateAccount();
                break;
            case MODE_LOGIN:
                onLogin();
                break;
            case MODE_FORGOT:
                startForgotPassword();
                break;
            case MODE_RESET_OTP:
                verifyCode(textOf(otpInput), true);
                break;
            case MODE_RESET_NEW:
                onSetNewPassword();
                break;
            default:
                break;
        }
    }

    private void onCreateAccount() {
        email = textOf(emailInput);
        String password = textOf(passwordInput);
        String confirm = textOf(confirmInput);
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            showError(getString(R.string.otp_error_email));
            return;
        }
        if (password.length() < 8) {
            showError(getString(R.string.login_error_password_weak));
            return;
        }
        if (!password.equals(confirm)) {
            showError(getString(R.string.login_error_password_mismatch));
            return;
        }
        setBusy(true);
        auth.registerPassword(email, password, () -> {
            setBusy(false);
            // Same OTP step as the email flow: the code proves the mailbox.
            setMode(MODE_OTP);
            startResendCountdown();
        }, message -> {
            setBusy(false);
            showError(message);
        });
    }

    private void requestOtp() {
        setBusy(true);
        auth.requestOtp(email, () -> {
            setBusy(false);
            setMode(MODE_OTP);
            startResendCountdown();
        }, message -> {
            setBusy(false);
            showError(message);
        });
    }

    private void onLogin() {
        email = textOf(emailInput);
        String password = textOf(passwordInput);
        if (email.isEmpty()) {
            showError(getString(R.string.otp_error_email));
            return;
        }
        if (password.isEmpty()) {
            showError(getString(R.string.login_error_password_empty));
            return;
        }
        setBusy(true);
        auth.loginPassword(email, password, this::onAuthenticated, message -> {
            setBusy(false);
            showError(message);
        });
    }

    private void startForgotPassword() {
        if (mode != MODE_FORGOT) {
            email = textOf(emailInput);
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                showError(getString(R.string.otp_error_email));
                return;
            }
        } else {
            email = textOf(emailInput);
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                showError(getString(R.string.otp_error_email));
                return;
            }
        }
        setBusy(true);
        auth.forgotPassword(email, () -> {
            setBusy(false);
            setMode(MODE_RESET_OTP);
            startResendCountdown();
        }, message -> {
            setBusy(false);
            showError(message);
        });
    }

    private void verifyCode(String code, boolean forReset) {
        if (code.length() != 6) {
            showError(getString(R.string.otp_error_invalid));
            return;
        }
        setBusy(true);
        if (forReset) {
            auth.verifyResetOtp(email, code, resetToken -> {
                this.resetToken = resetToken;
                setBusy(false);
                setMode(MODE_RESET_NEW);
            }, message -> {
                setBusy(false);
                showError(message);
                otpInput.setText("");
                otpInput.requestFocus();
            });
        } else {
            auth.verifyOtp(email, code, this::onAuthenticated, message -> {
                setBusy(false);
                showError(message);
                otpInput.setText("");
                otpInput.requestFocus();
            });
        }
    }

    private void onSetNewPassword() {
        String password = textOf(passwordInput);
        String confirm = textOf(confirmInput);
        if (password.length() < 8) {
            showError(getString(R.string.login_error_password_weak));
            return;
        }
        if (!password.equals(confirm)) {
            showError(getString(R.string.login_error_password_mismatch));
            return;
        }
        setBusy(true);
        auth.resetPassword(email, resetToken, password, () -> {
            setBusy(false);
            // Security default: return to Login, do not auto-login.
            setMode(MODE_LOGIN);
            android.widget.Toast.makeText(this, R.string.login_reset_success, android.widget.Toast.LENGTH_LONG).show();
        }, message -> {
            setBusy(false);
            showError(message);
        });
    }

    private void onAuthenticated() {
        setBusy(false);
        AccountPrefs.setEmail(this, email);
        AccountPrefs.clearGuest(this);
        Intent target = auth.isOnboarded()
                ? new Intent(this, MainActivity.class)
                : new Intent(this, OnboardingActivity.class);
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(target);
        finish();
    }

    private String textOf(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private void startResendCountdown() {
        resend.setEnabled(false);
        resend.setTextColor(getColor(R.color.nightlight_muted));
        final int[] remaining = {60};
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (remaining[0] <= 0) {
                    resend.setEnabled(true);
                    resend.setTextColor(getColor(R.color.nightlight_gold));
                    resend.setText(R.string.login_resend);
                } else {
                    resend.setText(getString(R.string.login_resend_in, remaining[0]));
                    remaining[0]--;
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.removeCallbacksAndMessages(null);
        handler.post(tick);
    }

    private void setBusy(boolean value) {
        busy = value;
        primaryButtonText.setAlpha(value ? 0.35f : 1f);
        primaryButtonText.setEnabled(!value);
        emailInput.setEnabled(!value);
        otpInput.setEnabled(!value);
        passwordInput.setEnabled(!value);
        confirmInput.setEnabled(!value);
        spinner.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
        errorText.setAlpha(0f);
        errorText.animate().alpha(1f).setDuration(180).start();
    }

    private void hideError() {
        errorText.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
