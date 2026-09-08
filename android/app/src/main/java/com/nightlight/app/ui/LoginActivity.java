package com.nightlight.app.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.nightlight.app.NightLightApp;
import com.nightlight.app.R;
import com.nightlight.app.data.repo.AuthRepository;
import com.nightlight.app.util.AccountPrefs;

import java.util.regex.Pattern;

/**
 * NightLight sign-in screen — matches reference design:
 *   Dark background → Lock icon → Sign In → Fields → Remember me + Forgot → Button → Sign Up
 */
public final class LoginActivity extends AppCompatActivity {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

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

    // Brand
    private ImageView lockIcon;
    private TextView heading;

    // Fields
    private EditText emailInput;
    private EditText otpInput;
    private EditText passwordInput;
    private EditText confirmInput;

    // Remember me + forgot
    private CheckBox rememberMe;
    private TextView forgotLink;
    private TextView guestLink;

    // Primary CTA
    private TextView loginButton;
    private ProgressBar spinner;

    // Secondary
    private TextView signUpLink;
    private TextView createLink;
    private TextView loginLink;
    private android.widget.CheckBox passwordToggle;
    private android.widget.CheckBox confirmToggle;

    // Google
    private android.widget.Button googleButton;
    private com.nightlight.app.data.api.GoogleSignInHelper googleHelper;

    // Resend
    private TextView resend;
    private TextView errorText;

    private int mode = MODE_ENTRY;
    private String email;
    private String resetToken;
    private boolean awaitingEmailVerification;
    private boolean busy;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auth = ((NightLightApp) getApplication()).getAuthRepository();
        int requested = getIntent().getIntExtra(EXTRA_MODE, MODE_ENTRY);
        mode = (requested >= MODE_ENTRY && requested <= MODE_RESET_NEW) ? requested : MODE_ENTRY;
        if (mode == MODE_CREATE || mode == MODE_LOGIN) {
            AccountPrefs.setPendingGuestConversion(this, true);
        }
        buildUi();
    }

    private void buildUi() {
        // Root: full-screen dark background
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(darkBackground());

        int hPad = dp(32);

        // --- NightLight logo ---
        lockIcon = new ImageView(this);
        lockIcon.setImageResource(R.drawable.nightlight_logo);
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(64), dp(64));
        lockLp.gravity = Gravity.CENTER_HORIZONTAL;
        lockLp.topMargin = dp(60);
        lockIcon.setLayoutParams(lockLp);
        lockIcon.setAlpha(0f);
        lockIcon.animate().alpha(1f).setDuration(500).start();
        root.addView(lockIcon);

        // --- "Sign In" heading ---
        heading = new TextView(this);
        heading.setText("Sign In");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(28f);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        heading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.topMargin = dp(12);
        headLp.bottomMargin = dp(16);
        heading.setLayoutParams(headLp);
        root.addView(heading);

        // --- Tagline ---
        TextView tagline = new TextView(this);
        tagline.setText(R.string.login_tagline);
        tagline.setTextColor(getColor(R.color.nightlight_cream_dim));
        tagline.setTextSize(14f);
        tagline.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tagLp.bottomMargin = dp(24);
        tagline.setLayoutParams(tagLp);
        root.addView(tagline);

        // --- Email / Username field ---
        emailInput = iconInput(R.string.login_email_hint, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                R.drawable.ic_person_outline);
        LinearLayout.LayoutParams emailLp = (LinearLayout.LayoutParams) emailInput.getLayoutParams();
        emailLp.leftMargin = hPad;
        emailLp.rightMargin = hPad;
        root.addView(emailInput);

        // --- OTP field (hidden by default) ---
        otpInput = plainInput(R.string.login_otp_hint, InputType.TYPE_CLASS_NUMBER);
        otpInput.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        otpInput.setVisibility(View.GONE);
        LinearLayout.LayoutParams otpLp = (LinearLayout.LayoutParams) otpInput.getLayoutParams();
        otpLp.leftMargin = hPad;
        otpLp.rightMargin = hPad;
        root.addView(otpInput);

        // --- Password field ---
        passwordInput = iconInput(R.string.login_password_hint, InputType.TYPE_TEXT_VARIATION_PASSWORD,
                R.drawable.ic_lock_outline);
        passwordInput.setVisibility(View.GONE);
        LinearLayout.LayoutParams passLp = (LinearLayout.LayoutParams) passwordInput.getLayoutParams();
        passLp.leftMargin = hPad;
        passLp.rightMargin = hPad;
        root.addView(passwordInput);
        passwordToggle = addShowHide(passwordInput);

        // --- Confirm field (hidden by default) ---
        confirmInput = iconInput(R.string.login_confirm_hint, InputType.TYPE_TEXT_VARIATION_PASSWORD,
                R.drawable.ic_lock_outline);
        confirmInput.setVisibility(View.GONE);
        LinearLayout.LayoutParams confirmLp = (LinearLayout.LayoutParams) confirmInput.getLayoutParams();
        confirmLp.leftMargin = hPad;
        confirmLp.rightMargin = hPad;
        root.addView(confirmInput);
        confirmToggle = addShowHide(confirmInput);

        // --- Remember me + Forgot Password row ---
        LinearLayout optionsRow = new LinearLayout(this);
        optionsRow.setOrientation(LinearLayout.HORIZONTAL);
        optionsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams optLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        optLp.leftMargin = hPad - dp(8);
        optLp.rightMargin = hPad;
        optLp.topMargin = dp(8);
        optionsRow.setLayoutParams(optLp);

        rememberMe = new CheckBox(this);
        rememberMe.setText("Remember me");
        rememberMe.setTextColor(getColor(R.color.nightlight_cream_dim));
        rememberMe.setTextSize(13f);
        rememberMe.setButtonDrawable(R.drawable.ic_person_outline); // placeholder
        rememberMe.setPadding(0, 0, dp(4), 0);
        rememberMe.setVisibility(View.GONE); // hidden in entry mode
        optionsRow.addView(rememberMe);

        forgotLink = new TextView(this);
        forgotLink.setText("Forgot Password?");
        forgotLink.setTextColor(getColor(R.color.nightlight_gold));
        forgotLink.setTextSize(13f);
        forgotLink.setGravity(Gravity.END);
        LinearLayout.LayoutParams forgotLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        forgotLink.setLayoutParams(forgotLp);
        forgotLink.setOnClickListener(v -> setMode(MODE_FORGOT));
        optionsRow.addView(forgotLink);

        root.addView(optionsRow);

        // --- Error text ---
        errorText = new TextView(this);
        errorText.setTextColor(getColor(R.color.nightlight_error));
        errorText.setTextSize(13f);
        errorText.setGravity(Gravity.CENTER);
        errorText.setVisibility(View.GONE);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errLp.leftMargin = hPad;
        errLp.rightMargin = hPad;
        errLp.topMargin = dp(8);
        errorText.setLayoutParams(errLp);
        root.addView(errorText);

        // --- LOGIN button ---
        loginButton = new TextView(this);
        loginButton.setText("LOGIN");
        loginButton.setGravity(Gravity.CENTER);
        loginButton.setTextColor(Color.WHITE);
        loginButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        loginButton.setTextSize(16f);
        loginButton.setBackground(loginButtonBg());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        btnLp.leftMargin = hPad;
        btnLp.rightMargin = hPad;
        btnLp.topMargin = dp(16);
        loginButton.setLayoutParams(btnLp);
        loginButton.setOnClickListener(v -> onPrimary());
        root.addView(loginButton);

        // Spinner overlay on button
        spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        int spinSize = dp(22);
        LinearLayout.LayoutParams spinLp = new LinearLayout.LayoutParams(spinSize, spinSize);
        spinLp.gravity = Gravity.CENTER;
        spinner.setLayoutParams(spinLp);

        // --- Resend link (OTP modes) ---
        resend = new TextView(this);
        resend.setText(R.string.login_resend);
        resend.setTextColor(getColor(R.color.nightlight_gold));
        resend.setTextSize(13f);
        resend.setGravity(Gravity.CENTER);
        resend.setPadding(0, dp(8), 0, 0);
        resend.setVisibility(View.GONE);
        resend.setOnClickListener(v -> {
            if (mode == MODE_OTP && awaitingEmailVerification) {
                setBusy(true);
                auth.resendVerificationEmail(() -> {
                    setBusy(false);
                    android.widget.Toast.makeText(this, R.string.login_verify_wait_resent,
                            android.widget.Toast.LENGTH_SHORT).show();
                    startResendCountdown();
                }, message -> {
                    setBusy(false);
                    showError(message);
                    startResendCountdown();
                });
            } else if (mode == MODE_OTP) {
                requestOtp();
            } else if (mode == MODE_RESET_OTP) {
                startForgotPassword();
            }
        });
        root.addView(resend);

        // --- "Don't have an account? Sign Up" ---
        signUpLink = new TextView(this);
        signUpLink.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams signLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        signLp.topMargin = dp(24);
        signLp.bottomMargin = dp(16);
        signUpLink.setLayoutParams(signLp);
        root.addView(signUpLink);

        // --- Create Account link (hidden in entry) ---
        createLink = new TextView(this);
        createLink.setTextColor(getColor(R.color.nightlight_gold));
        createLink.setTextSize(13f);
        createLink.setGravity(Gravity.CENTER);
        createLink.setVisibility(View.GONE);
        root.addView(createLink);

        // --- Login link (hidden in entry) ---
        loginLink = new TextView(this);
        loginLink.setTextColor(getColor(R.color.nightlight_gold));
        loginLink.setTextSize(13f);
        loginLink.setGravity(Gravity.CENTER);
        loginLink.setVisibility(View.GONE);
        root.addView(loginLink);

        // --- Google button ---
        googleButton = new android.widget.Button(this);
        googleButton.setAllCaps(false);
        googleButton.setTextSize(14f);
        googleButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        googleButton.setTextColor(getColor(R.color.nightlight_cream));
        googleButton.setBackground(outlinedButtonBg());
        googleButton.setText(R.string.login_google);
        googleButton.setVisibility(View.GONE);
        googleButton.setOnClickListener(v -> startGoogleSignIn());
        LinearLayout.LayoutParams googleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        googleLp.leftMargin = hPad;
        googleLp.rightMargin = hPad;
        googleLp.topMargin = dp(12);
        googleButton.setLayoutParams(googleLp);
        root.addView(googleButton);
        googleHelper = new com.nightlight.app.data.api.GoogleSignInHelper(this);

        // --- Guest link (tertiary) ---
        guestLink = new TextView(this);
        guestLink.setText(R.string.login_link_guest);
        guestLink.setTextColor(getColor(R.color.nightlight_cream_dim));
        guestLink.setTextSize(13f);
        guestLink.setGravity(Gravity.CENTER);
        guestLink.setVisibility(View.GONE);
        guestLink.setPadding(0, dp(8), 0, dp(4));
        guestLink.setOnClickListener(v -> enterAsGuest());
        LinearLayout.LayoutParams guestLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        guestLp.leftMargin = hPad;
        guestLp.rightMargin = hPad;
        guestLink.setLayoutParams(guestLp);
        root.addView(guestLink);

        setContentView(root);
        renderStep();
    }

    // =========================================================================
    // Component Builders
    // =========================================================================

    private EditText iconInput(int hintRes, int type, int iconRes) {
        EditText field = new EditText(this);
        field.setHint(getString(hintRes));
        field.setInputType(InputType.TYPE_CLASS_TEXT | type);
        field.setSingleLine(true);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(getColor(R.color.nightlight_cream_dim));
        field.setTextSize(15f);
        field.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        // Transparent background with thin border
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1AFFFFFF"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        field.setBackground(bg);
        field.setPadding(dp(16), dp(14), dp(16), dp(14));
        // Left icon
        field.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
        field.setCompoundDrawablePadding(dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        field.setLayoutParams(lp);
        return field;
    }

    private EditText plainInput(int hintRes, int type) {
        EditText field = new EditText(this);
        field.setHint(getString(hintRes));
        field.setInputType(InputType.TYPE_CLASS_TEXT | type);
        field.setSingleLine(true);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(getColor(R.color.nightlight_cream_dim));
        field.setTextSize(15f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1AFFFFFF"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        field.setBackground(bg);
        field.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        field.setLayoutParams(lp);
        return field;
    }

    private android.widget.CheckBox addShowHide(EditText field) {
        android.widget.CheckBox toggle = new android.widget.CheckBox(this);
        toggle.setText(R.string.login_show_password);
        toggle.setTextSize(11f);
        toggle.setTextColor(getColor(R.color.nightlight_cream_dim));
        toggle.setPadding(0, 0, 0, dp(6));
        toggle.setOnCheckedChangeListener((b, checked) ->
                field.setInputType(InputType.TYPE_CLASS_TEXT
                        | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_TEXT_VARIATION_PASSWORD)));
        ViewGroup parent = (ViewGroup) field.getParent();
        parent.addView(toggle, parent.indexOfChild(field) + 1);
        return toggle;
    }

    private GradientDrawable darkBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#1A0A18"), Color.parseColor("#0A0A18")});
    }

    private GradientDrawable loginButtonBg() {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{getColor(R.color.nightlight_gold), Color.parseColor("#C9942E")});
        bg.setCornerRadius(dp(14));
        return bg;
    }

    private GradientDrawable outlinedButtonBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1AFFFFFF"));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        return bg;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // =========================================================================
    // Step Rendering
    // =========================================================================

    private void renderStep() {
        boolean entry = mode == MODE_ENTRY;
        boolean otp = mode == MODE_OTP;
        boolean create = mode == MODE_CREATE;
        boolean login = mode == MODE_LOGIN;
        boolean forgot = mode == MODE_FORGOT;
        boolean resetOtp = mode == MODE_RESET_OTP;
        boolean resetNew = mode == MODE_RESET_NEW;
        boolean verifyWait = otp && awaitingEmailVerification;

        // Logo: always visible
        lockIcon.setVisibility(View.VISIBLE);

        // Heading
        if (entry) {
            heading.setText("Sign In");
        } else if (otp) {
            heading.setText(verifyWait ? "Check Email" : "Enter Code");
        } else if (create) {
            heading.setText("Create Account");
        } else if (login) {
            heading.setText("Welcome Back");
        } else if (forgot) {
            heading.setText("Reset Password");
        } else if (resetOtp) {
            heading.setText("Check Email");
        } else if (resetNew) {
            heading.setText("New Password");
        }

        // Fields
        emailInput.setVisibility(entry || forgot || create || login ? View.VISIBLE : View.GONE);
        otpInput.setVisibility((otp && !verifyWait) || resetOtp ? View.VISIBLE : View.GONE);
        passwordInput.setVisibility(create || login || resetNew ? View.VISIBLE : View.GONE);
        confirmInput.setVisibility(create || resetNew ? View.VISIBLE : View.GONE);
        passwordToggle.setVisibility(passwordInput.getVisibility());
        confirmToggle.setVisibility(confirmInput.getVisibility());

        // Options row
        forgotLink.setVisibility(entry || login ? View.VISIBLE : View.GONE);
        rememberMe.setVisibility(View.GONE); // keep hidden for now

        // Resend
        resend.setVisibility(otp || resetOtp ? View.VISIBLE : View.GONE);

        // Login button text
        if (entry) loginButton.setText("LOGIN");
        else if (otp) loginButton.setText(verifyWait ? "CONTINUE" : "VERIFY");
        else if (create) loginButton.setText("CREATE ACCOUNT");
        else if (login) loginButton.setText("LOGIN");
        else if (forgot) loginButton.setText("SEND RESET LINK");
        else if (resetOtp) loginButton.setText("VERIFY");
        else if (resetNew) loginButton.setText("SAVE PASSWORD");

        // Bottom links
        if (entry) {
            signUpLink.setText("Don't have an account? Sign Up");
            signUpLink.setOnClickListener(v -> setMode(MODE_CREATE));
        } else if (create) {
            signUpLink.setText("Already have an account? Sign In");
            signUpLink.setOnClickListener(v -> setMode(MODE_LOGIN));
        } else if (login) {
            signUpLink.setText("Don't have an account? Sign Up");
            signUpLink.setOnClickListener(v -> setMode(MODE_CREATE));
        } else {
            signUpLink.setVisibility(View.GONE);
        }

        // Guest link (tertiary, quiet)
        guestLink.setVisibility(entry ? View.VISIBLE : View.GONE);

        // Google (entry only)
        googleButton.setVisibility(entry ? View.VISIBLE : View.GONE);

        // Create / Login links (hidden in entry, shown as alternatives)
        createLink.setVisibility(View.GONE);
        loginLink.setVisibility(View.GONE);

        // Subtle entrance
        root.setAlpha(0.9f);
        root.animate().alpha(1f).setDuration(150).start();
        setBusy(false);
    }

    // =========================================================================
    // Auth Logic (unchanged)
    // =========================================================================

    private void setMode(int newMode) {
        mode = newMode;
        hideError();
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
        if (busy) return;
        hideError();
        switch (mode) {
            case MODE_ENTRY: setMode(MODE_CREATE); break;
            case MODE_OTP:
                if (awaitingEmailVerification) completeEmailVerification();
                else verifyCode(textOf(otpInput), false);
                break;
            case MODE_CREATE: onCreateAccount(); break;
            case MODE_LOGIN: onLogin(); break;
            case MODE_FORGOT: startForgotPassword(); break;
            case MODE_RESET_OTP: verifyCode(textOf(otpInput), true); break;
            case MODE_RESET_NEW: onSetNewPassword(); break;
        }
    }

    private void onCreateAccount() {
        email = textOf(emailInput);
        String password = textOf(passwordInput);
        String confirm = textOf(confirmInput);
        if (!EMAIL_PATTERN.matcher(email).matches()) { showError(getString(R.string.otp_error_email)); return; }
        if (password.length() < 8) { showError(getString(R.string.login_error_password_weak)); return; }
        if (!password.equals(confirm)) { showError(getString(R.string.login_error_password_mismatch)); return; }
        setBusy(true);
        auth.registerPassword(email, password, () -> {
            setBusy(false);
            awaitingEmailVerification = true;
            setMode(MODE_OTP);
            startResendCountdown();
        }, message -> { setBusy(false); showError(message); });
    }

    private void startGoogleSignIn() {
        if (busy) return;
        hideError();
        setBusy(true);
        googleHelper.signIn(this, (idToken, error) -> {
            if (error != null) { setBusy(false); showError(error); return; }
            if (idToken == null) { setBusy(false); return; }
            auth.loginWithGoogle(idToken, this::onAuthenticated, message -> {
                setBusy(false); showError(message);
            });
        });
    }

    private void completeEmailVerification() {
        setBusy(true);
        auth.completeRegistration(this::onAuthenticated, message -> { setBusy(false); showError(message); });
    }

    private void requestOtp() {
        setBusy(true);
        auth.requestOtp(email, () -> { setBusy(false); setMode(MODE_OTP); startResendCountdown(); },
                message -> { setBusy(false); showError(message); });
    }

    private void onLogin() {
        email = textOf(emailInput);
        String password = textOf(passwordInput);
        if (email.isEmpty()) { showError(getString(R.string.otp_error_email)); return; }
        if (password.isEmpty()) { showError(getString(R.string.login_error_password_empty)); return; }
        setBusy(true);
        auth.loginPassword(email, password, this::onAuthenticated, message -> { setBusy(false); showError(message); });
    }

    private void startForgotPassword() {
        email = textOf(emailInput);
        if (!EMAIL_PATTERN.matcher(email).matches()) { showError(getString(R.string.otp_error_email)); return; }
        setBusy(true);
        auth.forgotPassword(email, () -> {
            setBusy(false);
            android.widget.Toast.makeText(this, getString(R.string.login_reset_email_sent, email),
                    android.widget.Toast.LENGTH_LONG).show();
            setMode(MODE_LOGIN);
        }, message -> { setBusy(false); showError(message); });
    }

    private void verifyCode(String code, boolean forReset) {
        if (code.length() != 6) { showError(getString(R.string.otp_error_invalid)); return; }
        setBusy(true);
        if (forReset) {
            auth.verifyResetOtp(email, code, resetToken -> {
                this.resetToken = resetToken;
                setBusy(false);
                setMode(MODE_RESET_NEW);
            }, message -> { setBusy(false); showError(message); otpInput.setText(""); otpInput.requestFocus(); });
        } else {
            auth.verifyOtp(email, code, this::onAuthenticated, message -> {
                setBusy(false); showError(message); otpInput.setText(""); otpInput.requestFocus();
            });
        }
    }

    private void onSetNewPassword() {
        String password = textOf(passwordInput);
        String confirm = textOf(confirmInput);
        if (password.length() < 8) { showError(getString(R.string.login_error_password_weak)); return; }
        if (!password.equals(confirm)) { showError(getString(R.string.login_error_password_mismatch)); return; }
        setBusy(true);
        auth.resetPassword(email, resetToken, password, () -> {
            setBusy(false);
            setMode(MODE_LOGIN);
            android.widget.Toast.makeText(this, R.string.login_reset_success, android.widget.Toast.LENGTH_LONG).show();
        }, message -> { setBusy(false); showError(message); });
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

    // =========================================================================
    // Helpers
    // =========================================================================

    private String textOf(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private void setBusy(boolean value) {
        busy = value;
        loginButton.setEnabled(!value);
        loginButton.setAlpha(value ? 0.6f : 1f);
        emailInput.setEnabled(!value);
        otpInput.setEnabled(!value);
        passwordInput.setEnabled(!value);
        confirmInput.setEnabled(!value);
        spinner.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        if (message == null || message.isEmpty()) return;
        errorText.setText(message);
        errorText.setVisibility(View.VISIBLE);
        errorText.setAlpha(0f);
        errorText.animate().alpha(1f).setDuration(150).start();
    }

    private void hideError() {
        errorText.setVisibility(View.GONE);
    }

    private void startResendCountdown() {
        resend.setEnabled(false);
        resend.setTextColor(getColor(R.color.nightlight_muted));
        final int[] remaining = {60};
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (remaining[0] <= 0) {
                    resend.setEnabled(true);
                    resend.setTextColor(getColor(R.color.nightlight_gold));
                    resend.setText(awaitingEmailVerification ? R.string.login_resend_email : R.string.login_resend);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
