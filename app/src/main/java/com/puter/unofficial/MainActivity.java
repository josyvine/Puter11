package com.puter.unofficial;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech; // Added for native TTS fallback
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import android.util.Log;
import android.view.View;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale; // Added for native TTS Locale

import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.ProgressBar;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

/**
 * MainActivity: The primary host for the Puter Unofficial WebView.
 * Handles permissions, hardware-level WebView configuration, 
 * and communication with the native Voice Agent Activity.
 * 
 * UPDATED: Fixed File Picker logic to ensure Bridge-initiated uploads 
 * are converted to Base64 and sent to the stagedFiles UI.
 * UPDATED: Integrated runtime POST_NOTIFICATIONS check and startForegroundService.
 * CRITICAL LIFECYCLE FIXES: Implemented clean onPause microphone release and onResume STT context recreation.
 * TTS INTEGRATION: Implemented TextToSpeech.OnInitListener to handle native vocalization fallbacks.
 * ENHANCED DIAGNOSTICS: Added explicit millisecond-precise trace logging for permissions, TTS, and core transitions.
 * NATIVE BYPASS REVERTED: Reverted to https://appassets.androidplatform.net/assets/index.html to restore Puter Sign-In.
 */
public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private WebAppInterface webAppInterface;
    private MyWebChromeClient myWebChromeClient; // Custom client for popups/uploads

    // Native Text-to-Speech Fallback Variables
    private TextToSpeech tts;
    private boolean isTtsInitialized = false;

    // Native Browser Control Panel Views
    private LinearLayout browserToolbar;
    private Button btnBrowserExit;
    private EditText inputBrowserAddress;
    private Button btnBrowserBack;
    private Button btnBrowserForward;
    private Button btnBrowserReload;
    private Button btnBrowserScraper; // Added native scraper button view reference
    private FloatingActionButton fabScrape;

    // Background handler for managing native scraping timers and watchdogs
    private final android.os.Handler scrapeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable scrapeTimeoutRunnable;

    private final static int FILE_CHOOSER_RESULT_CODE = 1;
    private final static int PERMISSION_REQUEST_CODE = 100;

    // Logic Guard to prevent the UI Blinking Loop during Auth/Reloads
    private boolean isRefreshing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d("MainActivity", "onCreate: Instantiating Primary Activity View context.");
        ActionReportLogger.logAction("LIFECYCLE", "onCreate: Main Activity initialized.");

        // REQUIREMENT: Force White/Light Theme at the Activity Level
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar);

        // PERFORMANCE FIX: Pre-warm the WebView class loader context off-screen
        // to mitigate Chromium binder setting stalls during main layout inflation.
        try {
            Class.forName("android.webkit.WebView");
        } catch (Exception ignored) {
            Log.w("MainActivity", "WebView class loader pre-warmup bypassed.");
        }

        setContentView(R.layout.activity_main);

        // =========================================================================
        // NATIVE IDENTITY AUTO-CHECK & INITIALIZATION
        // =========================================================================
        android.content.SharedPreferences prefs = getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(AppConstants.KEY_NOSTR_PRIVATE_KEY) || !prefs.contains(AppConstants.KEY_NOSTR_PUBLIC_KEY)) {
            try {
                String[] keys = NostrKeyManager.generateKeyPair();
                prefs.edit()
                     .putString(AppConstants.KEY_NOSTR_PRIVATE_KEY, keys[0])
                     .putString(AppConstants.KEY_NOSTR_PUBLIC_KEY, keys[1])
                     .apply();
                Log.d("MainActivity", "Identity Handshake: Generated new secure Nostr keypair. PubKey: " + keys[1]);
                ActionReportLogger.logAction("IDENTITY_HANDSHAKE", "Generated secure cryptographic Nostr keys.");
            } catch (Exception e) {
                Log.e("MainActivity", "Identity Handshake: Cryptographic key generation failed", e);
                ActionReportLogger.logError("IDENTITY_HANDSHAKE_FAIL", "Key generation failed: " + e.getMessage());
            }
        }

        webView = findViewById(R.id.webView);

        // Bind Native Browser Controls from activity_main.xml
        browserToolbar = findViewById(R.id.browserToolbar);
        btnBrowserExit = findViewById(R.id.btnBrowserExit);
        inputBrowserAddress = findViewById(R.id.inputBrowserAddress);
        btnBrowserBack = findViewById(R.id.btnBrowserBack);
        btnBrowserForward = findViewById(R.id.btnBrowserForward);
        btnBrowserReload = findViewById(R.id.btnBrowserReload);
        btnBrowserScraper = findViewById(R.id.btnBrowserScraper); // Bound native scraper button
        fabScrape = findViewById(R.id.fabScrape);

        // Setup Native Browser Controls click listeners
        btnBrowserExit.setOnClickListener(v -> loadIndexHtml());
        btnBrowserBack.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            }
        });
        btnBrowserForward.setOnClickListener(v -> {
            if (webView != null && webView.canGoForward()) {
                webView.goForward();
            }
        });
        btnBrowserReload.setOnClickListener(v -> {
            if (webView != null) {
                webView.reload();
            }
        });
        btnBrowserScraper.setOnClickListener(v -> {
            if (webView != null) {
                // Redirects directly to the browser.html receiver inbox since scraper.html is removed
                webView.loadUrl(AppConstants.LOCAL_BROWSER_URL);
            }
        });

        // Handle keyboard actions inside address input field
        inputBrowserAddress.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                String url = inputBrowserAddress.getText().toString().trim();
                if (!url.isEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://" + url;
                    }
                    webView.loadUrl(url);
                    // Hide virtual keyboard
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(inputBrowserAddress.getWindowToken(), 0);
                    }
                }
                return true;
            }
            return false;
        });

        // Handle clicking the Native Scraping FAB by implementing native color state timers and redirects
        fabScrape.setOnClickListener(v -> startScrapeSequence());

        // DIAGNOSTICS: Enable Remote Debugging via Chrome DevTools (pc)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // --- WebView Configuration: Deep Hardware Settings ---
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        // CRITICAL FOR AUTH: Allows Puter SDK to open the Sign-In Popup Window
        webSettings.setSupportMultipleWindows(true); 

        // DIAGNOSTICS: Allow scripts to access local content for debug console
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        // FIX: Remove the WebView identifier ("; wv") from the User Agent.
        // This tricks the Puter.js SDK into initializing correctly in an app context.
        String userAgent = webSettings.getUserAgentString();
        userAgent = userAgent.replace("; wv", "");
        webSettings.setUserAgentString(userAgent);

        // FIX FOR PUTER.JS AUTH: Enable and force Third-Party Cookie acceptance.
        // This is necessary to bridge the session between the popup and main window.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        // Standard Mobile Viewport scaling
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);

        // --- Client Assignments ---
        // PuterWebViewClient handles virtual HTTPS routing for session persistence
        webView.setWebViewClient(new PuterWebViewClient(this));

        // MyWebChromeClient handles native file pickers and the Auth Popup Dialog
        myWebChromeClient = new MyWebChromeClient(this);
        webView.setWebChromeClient(myWebChromeClient);

        // --- Native Manager Initialization ---
        webAppInterface = new WebAppInterface(this, webView);

        // --- JavaScript Bridge Registration ---
        // Exposes 'window.AndroidInterface' to the HTML/JS logic
        webView.addJavascriptInterface(webAppInterface, AppConstants.JS_BRIDGE_NAME);

        // Reverted back to App Assets HTTPS origin to satisfy Puter Sign-In requirement
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");

        // Check and Request System Permissions
        checkAndRequestPermissions();

        // Initialize the Native Text-to-Speech Engine
        Log.d("MainActivity", "onCreate: Initializing native TextToSpeech engine.");
        tts = new TextToSpeech(this, this);
    }

    /**
     * Initializes the native TextToSpeech engine and sets the default Locale.
     */
    @Override
    public void onInit(int status) {
        Log.d("MainActivity", "onInit: TTS callback entered with status: " + status);
        ActionReportLogger.logAction("TTS_INIT", "onInit callback entered. Status: " + status);
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("MainActivity", "TTS Init Error: Language is not supported or missing resources. Code: " + result);
                ActionReportLogger.logError("TTS_INIT_FAIL", "Locale not supported or missing resources. Code: " + result);
                isTtsInitialized = false;
            } else {
                isTtsInitialized = true;
                Log.d("MainActivity", "TTS Engine successfully initialized with US Locale.");
                ActionReportLogger.logAction("TTS_INIT_SUCCESS", "TTS successfully synchronized with US Locale.");
            }
        } else {
            Log.e("MainActivity", "TTS Engine initialization failed with status: " + status);
            ActionReportLogger.logError("TTS_INIT_FAILED_STATUS", "Engine initialization failed. Code: " + status);
            isTtsInitialized = false;
        }
    }

    /**
     * Vocalizes the provided text string natively utilizing the Android TTS framework.
     * Satisfies the Tier 1 fallback specification for the Read Aloud requirement.
     */
    public void speak(String text) {
        if (isTtsInitialized && tts != null) {
            Log.d("MainActivity", "Executing native speak vocalization.");
            ActionReportLogger.logAction("TTS_SPEAK", "Native speak payload accepted. Length: " + (text != null ? text.length() : 0));
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, AppConstants.TTS_UTTERANCE_ID);
        } else {
            Log.w("MainActivity", "Native speak aborted: TTS Engine is not initialized or ready.");
            ActionReportLogger.logError("TTS_SPEAK_ABORTED", "Speak aborted. Initialization state: " + isTtsInitialized);
            Toast.makeText(this, "Native Speech Engine is not ready.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Safely halts system-level vocalizations immediately to prevent queue stalls.
     */
    public void stopSpeaking() {
        if (isTtsInitialized && tts != null) {
            Log.d("MainActivity", "Halt vocalization requested natively.");
            ActionReportLogger.logAction("TTS_STOP", "Native stop command executed.");
            tts.stop();
        } else {
            Log.w("MainActivity", "Native stop aborted: TTS Engine is not initialized.");
        }
    }

    /**
     * Executes the native scraping sequence including Amazon redirects, Same-Origin
     * evaluations, and background color state handler timers.
     */
    private void startScrapeSequence() {
        if (webView == null) return;

        String activeUrl = webView.getUrl();
        if (activeUrl == null || activeUrl.startsWith("about:blank") || activeUrl.startsWith("https://appassets.androidplatform.net/assets/index.html")) {
            Toast.makeText(this, "Navigate to a webpage first before scraping.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (activeUrl.contains("amazon.in") || activeUrl.contains("amazon.com")) {
            // E-Commerce Route: Redirect directly to the secure local browser.html receiver inbox
            Log.d("MainActivity", "Native Scraper: Navigating directly to local browser.html for Amazon product.");
            webView.loadUrl(AppConstants.LOCAL_BROWSER_URL);
        } else {
            // Universal Mode: Trigger the scraper and manage color state transitions natively
            scrapeHandler.removeCallbacksAndMessages(null); // Cancel any lingering progress timers

            // 1. Transition instantly to Scraping state (RED)
            fabScrape.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D93025")));

            // 2. Set timeout to transition to intermediate Processing state (ORANGE) after 1.2 seconds
            scrapeHandler.postDelayed(() -> {
                fabScrape.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F2994A")));
            }, 1200);

            // 3. START DIAGNOSTIC WATCHDOG TIMER (8-seconds limit)
            scrapeTimeoutRunnable = () -> {
                fabScrape.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D93025"))); // Revert to RED indicating failure
                Toast.makeText(MainActivity.this, "Scraping failed: Iframe or Script Timeout.", Toast.LENGTH_LONG).show();

                // Smoothly return back to original Blue state after 4 seconds
                scrapeHandler.postDelayed(() -> {
                    fabScrape.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#1A73E8")));
                }, 4000);
            };
            scrapeHandler.postDelayed(scrapeTimeoutRunnable, 8000);

            // Evaluate standard postMessage to trigger the injected script inside the current same-origin main window context
            webView.evaluateJavascript("window.postMessage('scrape', '*');", null);
        }
    }

    /**
     * Public success callback invoked by WebAppInterface upon successful parsing inside addScrapedProduct.
     */
    public void onScrapeSuccess(String scrapedId) {
        runOnUiThread(() -> {
            // Clear active progress and watchdog timers immediately
            scrapeHandler.removeCallbacksAndMessages(null);

            // Transition to Finished state (GREEN) on success callback
            fabScrape.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#27AE60")));

            // Trigger native Toast message feedback
            Toast.makeText(MainActivity.this, "JSON sent successfully to Puter Unofficial", Toast.LENGTH_LONG).show();

            // Smoothly return back to original Blue state after 4 seconds
            scrapeHandler.postDelayed(() -> {
                fabScrape.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#1A73E8")));
            }, 4000);
        });
    }

    /**
     * Toggles visibility of the Native browser control toolbar and the Scraping FAB.
     * Triggered automatically by the WebViewClient during page transitions.
     */
    public void handleUrlChange(String url) {
        runOnUiThread(() -> {
            if (url == null) return;

            // Hide native controls if loading ANY local web assets or the main index page
            if (url.startsWith("https://appassets.androidplatform.net/assets/index.html") || url.contains("browser.html") || url.contains("bot.html")) {
                browserToolbar.setVisibility(View.GONE);
                fabScrape.setVisibility(View.GONE);
            } else {
                // If browsing an external page, show native browser controls
                browserToolbar.setVisibility(View.VISIBLE);
                fabScrape.setVisibility(View.VISIBLE);

                // Dynamically update the address field text when not active
                if (!inputBrowserAddress.isFocused()) {
                    inputBrowserAddress.setText(url);
                }
            }
        });
    }

    /**
     * Navigates back to the main local index URL.
     */
    private void loadIndexHtml() {
        if (webView != null) {
            webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        }
    }

    /**
     * Requirement #3: Injects spoken text into the index.html logic.
     */
    private void injectSpeechToWebView(String text) {
        String safeText = text.replace("'", "\\'");
        webView.post(() -> webView.evaluateJavascript(
                "if(window.onSpeechResult) { window.onSpeechResult('" + safeText + "'); }",
                null)
        );
    }

    /**
     * Launches the QueryWatcherService safely using ContextCompat.
     */
    private void startWatcherService() {
        try {
            Intent serviceIntent = new Intent(this, QueryWatcherService.class);
            ContextCompat.startForegroundService(this, serviceIntent);
            Log.d("MainActivity", "QueryWatcherService background monitor started successfully.");
        } catch (Exception e) {
            Log.e("MainActivity", "Fatal failure starting QueryWatcherService background execution: ", e);
        }
    }

    /**
     * Validates and requests all necessary hardware permissions.
     */
    private void checkAndRequestPermissions() {
        List<String> listPermissionsNeeded = new ArrayList<>();
        listPermissionsNeeded.add(Manifest.permission.INTERNET);
        listPermissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        listPermissionsNeeded.add(Manifest.permission.CAMERA);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listPermissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES);
            // REQUIREMENT: Check and Request system notifications permissions on Android 13+
            listPermissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            listPermissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            listPermissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        List<String> remainingPermissions = new ArrayList<>();
        for (String permission : listPermissionsNeeded) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                remainingPermissions.add(permission);
            }
        }

        if (!remainingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, remainingPermissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            // All permission constraints already satisfied; launch the query watcher service immediately
            startWatcherService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d("MainActivity", "onRequestPermissionsResult: Received callback for request code: " + requestCode);
        ActionReportLogger.logAction("PERMISSIONS", "onRequestPermissionsResult callback initiated. Code: " + requestCode);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            boolean notificationPermissionGranted = true;

            for (int i = 0; i < permissions.length; i++) {
                boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                Log.d("MainActivity", "Permission: " + permissions[i] + " Status: " + (granted ? "GRANTED" : "DENIED"));
                ActionReportLogger.logAction("PERMISSIONS_DETAIL", "Permission: " + permissions[i] + " Status: " + (granted ? "GRANTED" : "DENIED"));

                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    if (Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])) {
                        notificationPermissionGranted = false;
                    }
                }
            }

            if (!allGranted) {
                Log.w("MainActivity", "Warning: One or more requested runtime permissions were denied by the user.");
                ActionReportLogger.logHtmlGlitch("PERMISSIONS_DENIED", "One or more requested permissions were denied.");
                Toast.makeText(this, "Voice and Image features require permissions.", Toast.LENGTH_SHORT).show();
            } else {
                Log.d("MainActivity", "All requested permissions successfully granted.");
                ActionReportLogger.logAction("PERMISSIONS_GRANTED", "All hardware permissions successfully verified.");
            }

            // Start foreground watcher service safely once notification permissions are resolved or on older devices
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionGranted) {
                startWatcherService();
            }
        }
    }

    /**
     * UPDATED: Handles both standard WebChromeClient uploads and Bridge-initiated uploads.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        // 1. Logic for Bridge-initiated File Upload (Feature Enhancement #1)
        if (requestCode == FILE_CHOOSER_RESULT_CODE && resultCode == RESULT_OK && data != null) {
            Uri fileUri = data.getData();
            if (fileUri != null) {
                Log.d("MainActivity", "File selected via Bridge: " + fileUri.toString());
                
                // Convert to Base64 Data URI
                String base64Data = FileUtils.fileToDataUri(this, fileUri);
                
                if (base64Data != null) {
                    // Inject the Base64 string directly into the JS stagedFiles array
                    webView.post(() -> {
                        webView.evaluateJavascript(
                            "if(window.onImageResult){ window.onImageResult('" + base64Data + "'); }", 
                            null
                        );
                    });
                }
            }
        }

        // 2. Original Requirement #3: Delegate standard file picker result back to ChromeClient
        if (myWebChromeClient != null) {
            myWebChromeClient.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * Requirement #5: Force-reloads the WebView UI.
     * Used after sign-out to clear session state.
     */
    public void reloadWebView() {
        runOnUiThread(() -> {
            if (webView != null && !isRefreshing) {
                isRefreshing = true;

                // Requirement #4: Persistence Fix. Ensure cookies are saved before reload.
                CookieManager.getInstance().flush();

                webView.reload();

                // Release reload guard after 3 seconds to prevent UI flicker
                webView.postDelayed(() -> isRefreshing = false, 3000);
            }
        });
    }

    /**
     * Overridden to intercept back button navigation dynamically.
     * Prevents the app from closing when browsing an external page.
     */
    @Override
    public void onBackPressed() {
        if (webView != null) {
            String currentUrl = webView.getUrl();
            if (currentUrl != null) {
                // If currently inside the active native browser session
                if (browserToolbar.getVisibility() == View.VISIBLE) {
                    if (webView.canGoBack()) {
                        webView.goBack(); // Navigate web history natively
                    } else {
                        loadIndexHtml(); // If no web history remains, gracefully load home index
                    }
                    return;
                }
                // If viewing other external webpages inside the top-level viewport
                else if (!currentUrl.startsWith("https://appassets.androidplatform.net/")) {
                    if (webView.canGoBack()) {
                        webView.goBack();
                        return;
                    }
                } 
                // If inside local sub-panels (like browser.html receiver)
                else if (currentUrl.contains("browser.html")) {
                    webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
                    return;
                }
                // NEW ROUTE: Swipe-to-Exit or Back-to-Exit for bot.html (Traders Zone)
                else if (currentUrl.contains("bot.html")) {
                    webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
                    return;
                }
            }
        }
        // Default fallback if we are on index.html
        super.onBackPressed();
    }

    /**
     * Lifecycle hook called when the Activity is paused.
     * We preserve super lifecycle states cleanly without managing standard voice loops.
     */
    @Override
    protected void onPause() {
        super.onPause();
        Log.d("MainActivity", "onPause: Activity entered background.");
        ActionReportLogger.logAction("LIFECYCLE", "onPause: Activity entered background.");
    }

    /**
     * Lifecycle hook called when the Activity returns to focus.
     * We preserve super lifecycle states cleanly without managing standard voice loops.
     */
    @Override
    protected void onResume() {
        super.onResume();
        Log.d("MainActivity", "onResume: Activity returned to foreground.");
        ActionReportLogger.logAction("LIFECYCLE", "onResume: Activity returned to foreground.");
    }

    @Override
    protected void onDestroy() {
        Log.d("MainActivity", "onDestroy: Releasing native TextToSpeech and cleanup.");
        ActionReportLogger.logAction("LIFECYCLE", "onDestroy: Terminating activity.");

        // Cleanup TextToSpeech safely to avoid memory leak conditions
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            Log.d("MainActivity", "Native TTS Engine successfully terminated.");
            ActionReportLogger.logAction("TTS_SHUTDOWN", "TextToSpeech engine cleanly released.");
        }

        // Cleanup Native Resources
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        if (webAppInterface != null) {
            webAppInterface.destroy();
        }
        scrapeHandler.removeCallbacksAndMessages(null); // Purge any remaining scraper handshakes
        super.onDestroy();
    }

    /**
     * Displays a full-screen, native contact and feedback dialog [1].
     * Validates input fields and issues an asynchronous HTTP POST payload containing
     * the captured details directly to the Formspree endpoint using OkHttp [1].
     */
    public void openContactDialog() {
        runOnUiThread(() -> {
            Dialog dialog = new Dialog(MainActivity.this, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(R.layout.dialog_contact);

            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.setGravity(Gravity.CENTER);
            }

            EditText etName = dialog.findViewById(R.id.contactNameInput);
            EditText etEmail = dialog.findViewById(R.id.contactEmailInput);
            EditText etFeedback = dialog.findViewById(R.id.contactFeedbackInput);
            Button btnSend = dialog.findViewById(R.id.contactSendBtn);
            ImageButton btnClose = dialog.findViewById(R.id.contactCloseBtn);
            ProgressBar progressBar = dialog.findViewById(R.id.contactProgressBar);

            btnClose.setOnClickListener(v -> dialog.dismiss());

            btnSend.setOnClickListener(v -> {
                String name = etName.getText().toString().trim();
                String email = etEmail.getText().toString().trim();
                String feedback = etFeedback.getText().toString().trim();

                if (name.isEmpty() || email.isEmpty() || feedback.isEmpty()) {
                    Toast.makeText(MainActivity.this, "All input fields must be filled.", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    Toast.makeText(MainActivity.this, "A valid email address is required.", Toast.LENGTH_SHORT).show();
                    return;
                }

                progressBar.setVisibility(View.VISIBLE);
                btnSend.setEnabled(false);

                OkHttpClient client = new OkHttpClient();
                try {
                    JSONObject json = new JSONObject();
                    json.put("name", name);
                    json.put("email", email);
                    json.put("feedback", feedback);

                    RequestBody body = RequestBody.create(json.toString(), MediaType.get("application/json; charset=utf-8"));
                    Request request = new Request.Builder()
                            .url("https://formspree.io/f/xyzenlao")
                            .post(body)
                            .build();

                    client.newCall(request).enqueue(new Callback() {
                        @Override
                        public void onFailure(@NonNull Call call, @NonNull IOException e) {
                            runOnUiThread(() -> {
                                progressBar.setVisibility(View.GONE);
                                btnSend.setEnabled(true);
                                Toast.makeText(MainActivity.this, "Network failure: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                ActionReportLogger.logError("CONTACT_API_FAILURE", e.getMessage());
                            });
                        }

                        @Override
                        public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                            final String bodyResponse = response.body() != null ? response.body().string() : "";
                            runOnUiThread(() -> {
                                progressBar.setVisibility(View.GONE);
                                btnSend.setEnabled(true);
                                if (response.isSuccessful()) {
                                    Toast.makeText(MainActivity.this, "Message sent successfully!", Toast.LENGTH_LONG).show();
                                    ActionReportLogger.logAction("CONTACT_API_SUCCESS", "Feedback received by Formspree");
                                    dialog.dismiss();
                                } else {
                                    Toast.makeText(MainActivity.this, "API rejected submission: HTTP " + response.code(), Toast.LENGTH_LONG).show();
                                    ActionReportLogger.logError("CONTACT_API_REJECTION", "HTTP " + response.code() + ": " + bodyResponse);
                                }
                            });
                        }
                    });

                } catch (Exception err) {
                    progressBar.setVisibility(View.GONE);
                    btnSend.setEnabled(true);
                    Toast.makeText(MainActivity.this, "Payload exception: " + err.getMessage(), Toast.LENGTH_SHORT).show();
                    ActionReportLogger.logError("CONTACT_PAYLOAD_EXCEPTION", err.getMessage());
                }
            });

            dialog.show();
        });
    }
}