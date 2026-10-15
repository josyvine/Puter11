package com.puter.unofficial;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Manages the persistent authentication state for Puter Unofficial.
 * This class ensures that once a user signs in via the browser, the app 
 * remembers that state across restarts using SharedPreferences.
 * 
 * CORE ANALYSIS UPDATE: 
 * Following the identification of the "OAuth Interruption" bug, this class 
 * has been transitioned to a "Mirror State" architecture. It no longer 
 * dictates the session state; instead, it synchronizes with the Puter.js 
 * SDK to ensure the Native Android UI and Splash Screen are consistent 
 * with the real Web session.
 */
public class AuthManager {

    private static final String PREF_NAME = "PuterPrefs";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_AUTH_TOKEN = "puter_auth_token"; // Key for the extracted SDK token
    private static AuthManager instance;
    private final SharedPreferences prefs;

    /**
     * Private constructor for Singleton pattern.
     * Accesses the shared preference file dedicated to Puter settings.
     */
    private AuthManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Gets the global instance of the AuthManager.
     * Uses synchronized block to ensure thread safety.
     * 
     * @param context The application context.
     * @return The singleton instance of AuthManager.
     */
    public static synchronized AuthManager getInstance(Context context) {
        if (instance == null) {
            instance = new AuthManager(context);
        }
        return instance;
    }

    /**
     * Saves the current authentication status.
     * CORE FIX: This is now only called by the JavaScript SDK via the 
     * onAuthStatusChanged bridge. This ensures Native state never 
     * gets ahead of the REAL Puter session state.
     * 
     * @param status true if the user is authenticated, false otherwise.
     */
    public void setLoggedIn(boolean status) {
        prefs.edit().putBoolean(KEY_IS_LOGGED_IN, status).apply();
    }

    /**
     * Checks if the user is currently considered logged in.
     * Used by SplashActivity and Fragments to determine the initial UI layout.
     * 
     * @return true if status is saved as logged in.
     */
    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    /**
     * Persists the authentication token string extracted from the login popup.
     * Used as a secondary backup to the browser's native cookie storage.
     * 
     * @param token The session token string.
     */
    public void setAuthToken(String token) {
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply();
    }

    /**
     * Retrieves the saved authentication token.
     * 
     * @return The saved token string or null if not available.
     */
    public String getAuthToken() {
        return prefs.getString(KEY_AUTH_TOKEN, null);
    }

    /**
     * Clears the authentication state.
     * Triggered when the user selects "Sign Out" from the HTML dropdown menu.
     * Ensures both the mirror flag and the backup token are removed.
     */
    public void logout() {
        prefs.edit()
             .putBoolean(KEY_IS_LOGGED_IN, false)
             .remove(KEY_AUTH_TOKEN) // Ensure token is cleared on logout
             .apply();
    }

    /**
     * Helper to verify if a specific URL is a Puter authentication success callback.
     * This logic is used by the WebViewClient and ChromeClient to detect when 
     * the OAuth flow has reached its completion phase.
     * 
     * UPDATED: Aligned with the deep analysis to capture all redirect success markers.
     * 
     * @param url The URL being intercepted in the WebView.
     * @return true if the URL indicates a successful authentication.
     */
    public boolean isAuthCallback(String url) {
        if (url == null) return false;

        /* 
         * Puter typically redirects back to the main domain or a custom 
         * callback URL after login. We check for all known success markers.
         */
        return (url.contains(AppConstants.AUTH_TOKEN_PARAM) || 
                url.contains(AppConstants.AUTH_SUCCESS_MARKER) ||
                url.contains("auth_success") ||
                url.contains("signed_in=true"));
    }
}