/**
 * @file SplashActivity.java
 * @summary Launch splash screen displaying Google AdMob ad with 5s countdown + skip control and parallel HDMI auto-boot detection.
 * @description Waits for MobileAds initialization before requesting the MEDIUM_RECTANGLE ad inside measured bounds so ads render reliably on cold start.
 * @testing Compiled and lint-checked via Gradle assembleRelease (v3.2).
 */
package com.project.autoswitch.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;

/**
 * Splash Screen Activity with Google AdMob.
 * - Displays ad for up to 10 seconds (minimum 5 seconds non-skippable).
 * - After 5 seconds: "SKIP AD [OK]" button appears; remote OK / Back skips immediately.
 * - At 10 seconds: Auto-dismisses.
 * - Offline / Failure Fallback: If ad fails or takes > 3.5s to load, transitions immediately.
 * - Parallel Hardware Detection: Detects armed HDMI input in background while ad is showing.
 */
public class SplashActivity extends AppCompatActivity {
    private static final String TAG = "SplashActivity";

    // Google AdMob Official Test Banner ID (Replace with production Ad Unit ID before release)
    private static final String TEST_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111";

    private FrameLayout adContainer;
    private TextView adCountdown;
    private Button btnSkipAd;
    private AdView adView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable adTickerRunnable;
    private Runnable adLoadTimeoutRunnable;
    private Runnable hdmiPollRunnable;

    private int adSecondsRemaining = 10;
    private boolean canSkip = false;
    private boolean hasProceeded = false;
    private boolean isFromBoot = false;
    private boolean returnToCaller = false;

    // HDMI background detection
    private boolean isHardwareReady = false;
    private String targetHdmi = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        adContainer = findViewById(R.id.ad_container);
        adCountdown = findViewById(R.id.ad_countdown);
        btnSkipAd = findViewById(R.id.btn_skip_ad);

        isFromBoot = getIntent().getBooleanExtra("from_boot", false);
        returnToCaller = getIntent().getBooleanExtra("return_to_caller", false);

        btnSkipAd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (canSkip) {
                    proceedToDestination();
                }
            }
        });

        // 1. If boot launch, begin HDMI detection in parallel so it's ready when ad finishes
        if (isFromBoot) {
            prepareHdmiDetection();
        }

        // 2. Initialize Google Mobile Ads and load the ad
        initAndLoadAdMob();
    }

    private void initAndLoadAdMob() {
        adContainer.setVisibility(View.INVISIBLE);
        adContainer.setAlpha(0f);

        adView = new AdView(this);
        adView.setAdUnitId(TEST_AD_UNIT_ID);
        adView.setAdSize(AdSize.MEDIUM_RECTANGLE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        );
        adContainer.addView(adView, params);

        adView.setAdListener(new AdListener() {
            @Override
            public void onAdLoaded() {
                Log.d(TAG, "AdMob Ad successfully loaded.");
                cancelAdLoadTimeout();
                startAdDisplayCycle();
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                Log.w(TAG, "AdMob failed to load: " + loadAdError.getMessage() + ". Proceeding directly.");
                cancelAdLoadTimeout();
                proceedToDestination();
            }
        });

        // Safety Timeout: 7.0s so cold WebView + MobileAds startup on a fresh install never skips the ad
        adLoadTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                Log.w(TAG, "Ad load timed out. Proceeding directly.");
                proceedToDestination();
            }
        };
        handler.postDelayed(adLoadTimeoutRunnable, 7000);

        final boolean[] requested = new boolean[]{false};
        MobileAds.initialize(this, initializationStatus -> {
            Log.d(TAG, "MobileAds initialized.");
            handler.post(() -> {
                if (!requested[0] && !isFinishing() && !isDestroyed() && !hasProceeded) {
                    requested[0] = true;
                    adView.loadAd(new AdRequest.Builder().build());
                }
            });
        });
        // Fallback kick if MobileAds callback is delayed > 1.2s
        handler.postDelayed(() -> {
            if (!requested[0] && !isFinishing() && !isDestroyed() && !hasProceeded) {
                requested[0] = true;
                adView.loadAd(new AdRequest.Builder().build());
            }
        }, 1200);
    }

    private void startAdDisplayCycle() {
        if (isFinishing() || isDestroyed() || hasProceeded) return;

        adContainer.setVisibility(View.VISIBLE);
        adContainer.setAlpha(1f);
        adCountdown.setVisibility(View.VISIBLE);
        adCountdown.setText("Ad: 5s");

        adSecondsRemaining = 10;
        canSkip = false;

        adTickerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed() || hasProceeded) return;

                adSecondsRemaining--;

                if (adSecondsRemaining > 5) {
                    // Seconds 1-4: Non-skippable countdown to skip
                    int secondsToSkip = adSecondsRemaining - 5;
                    adCountdown.setText("Skip in " + secondsToSkip + "s");
                    btnSkipAd.setVisibility(View.GONE);
                    canSkip = false;
                    handler.postDelayed(this, 1000);
                } else if (adSecondsRemaining > 0) {
                    // Seconds 5-9: Skippable period
                    adCountdown.setText("Ad: " + adSecondsRemaining + "s");
                    if (btnSkipAd.getVisibility() != View.VISIBLE) {
                        btnSkipAd.setVisibility(View.VISIBLE);
                        btnSkipAd.requestFocus();
                    }
                    canSkip = true;
                    handler.postDelayed(this, 1000);
                } else {
                    // At 10 seconds: Auto dismiss
                    proceedToDestination();
                }
            }
        };

        handler.postDelayed(adTickerRunnable, 1000);
    }

    private void prepareHdmiDetection() {
        SharedPreferences prefs = getSharedPreferences("ProjectPrefs", Context.MODE_PRIVATE);
        boolean isConfigured = prefs.getBoolean("is_configured", false);
        targetHdmi = prefs.getString("target_hdmi", "");

        if (!isConfigured || targetHdmi.isEmpty() || "OFF".equals(targetHdmi)) {
            Log.d(TAG, "Autoswitch not armed. Will finish to launcher on exit.");
            return;
        }

        final TvInputManager manager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (manager == null) return;

        hdmiPollRunnable = new Runnable() {
            private int attempts = 0;
            @Override
            public void run() {
                if (isFinishing() || isDestroyed() || isHardwareReady) return;
                attempts++;
                try {
                    TvInputInfo info = manager.getTvInputInfo(targetHdmi);
                    if (info != null) {
                        Log.d(TAG, "Hardware pre-detected and ready for switch.");
                        isHardwareReady = true;
                        return;
                    }
                } catch (Exception ignored) {}

                if (attempts < 80) {
                    handler.postDelayed(this, 100);
                }
            }
        };
        handler.post(hdmiPollRunnable);
    }

    private synchronized void proceedToDestination() {
        if (hasProceeded) return;
        hasProceeded = true;

        cancelAllTimers();

        if (isFromBoot) {
            // Boot Launch: switch to HDMI if configured
            if (!targetHdmi.isEmpty() && !"OFF".equals(targetHdmi)) {
                try {
                    Uri uri = TvContract.buildChannelUriForPassthroughInput(targetHdmi);
                    Intent switchIntent = new Intent(Intent.ACTION_VIEW, uri);
                    switchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(switchIntent);
                } catch (Exception e) {
                    Log.e(TAG, "HDMI switch failed: " + e.getMessage());
                }
            }
            finish();
        } else if (returnToCaller) {
            // Returning to existing screen after app reload ad
            finish();
        } else {
            // Manual Launch: Route to Main Hub
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (canSkip && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            proceedToDestination();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void cancelAdLoadTimeout() {
        if (adLoadTimeoutRunnable != null) {
            handler.removeCallbacks(adLoadTimeoutRunnable);
            adLoadTimeoutRunnable = null;
        }
    }

    private void cancelAllTimers() {
        cancelAdLoadTimeout();
        if (adTickerRunnable != null) {
            handler.removeCallbacks(adTickerRunnable);
            adTickerRunnable = null;
        }
        if (hdmiPollRunnable != null) {
            handler.removeCallbacks(hdmiPollRunnable);
            hdmiPollRunnable = null;
        }
    }

    @Override
    protected void onPause() {
        if (adView != null) adView.pause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adView != null) adView.resume();
    }

    @Override
    protected void onDestroy() {
        cancelAllTimers();
        if (adView != null) {
            adView.destroy();
            adView = null;
        }
        super.onDestroy();
    }
}
