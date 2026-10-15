package com.puter.unofficial;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Entry point of the Puter Unofficial application.
 * Displays the splash branding and determines navigation logic 
 * based on the user's persistent sign-in status.
 */
public class SplashActivity extends AppCompatActivity {

    // Duration for the splash screen display (2.5 seconds)
    private static final int SPLASH_SCREEN_DELAY = 2500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        /* 
         * Set the layout for Splash Activity. 
         * Ensure activity_splash.xml exists in res/layout with puter.png logo.
         */
        setContentView(R.layout.activity_splash);

        // Delay navigation to provide a smooth branding experience
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                checkAuthenticationAndNavigate();
            }
        }, SPLASH_SCREEN_DELAY);
    }

    /**
     * Checks SharedPreferences to see if the user is already authenticated.
     * Logic:
     * - If logged in: Jump directly to MainActivity.
     * - If not logged in: Still go to MainActivity (The WebView inside handles the Sign-In UI).
     */
    private void checkAuthenticationAndNavigate() {
        SharedPreferences prefs = getSharedPreferences("PuterPrefs", MODE_PRIVATE);
        boolean isLoggedIn = prefs.getBoolean("is_logged_in", false);

        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        
        /* 
         * We pass the login status as an extra so MainActivity 
         * can decide whether to show certain UI elements immediately.
         */
        intent.putExtra("ALREADY_LOGGED_IN", isLoggedIn);
        
        startActivity(intent);
        
        // Finalize SplashActivity so the user cannot navigate back to it
        finish();
    }
}