package com.puter.unofficial;

/**
 * Centralized constants for the Puter Unofficial application.
 * Manages configuration keys, URLs, and shared preference identifiers 
 * to ensure consistency across Java and JavaScript components.
 */
public class AppConstants {

    // --- SHARED PREFERENCES ---
    public static final String PREF_NAME = "PuterPrefs";
    public static final String KEY_IS_LOGGED_IN = "is_logged_in";
    public static final String KEY_CUSTOM_INSTRUCTION = "puter_custom_instruction";
    
    // NEW: Key for local chat history persistence (Enhancement #4)
    public static final String KEY_CHAT_HISTORY = "puter_chat_history";
    
    // REQUIREMENT #3: Keys for Multi-Session Management
    // Tracks the current active session ID and the list/map of all saved sessions.
    public static final String KEY_SESSIONS_LIST = "puter_sessions_list";
    public static final String KEY_ACTIVE_SESSION_ID = "puter_active_session_id";
    
    // NEW: Key for toggling between local models.json and Puter Live API (Requirement #7)
    public static final String KEY_USE_LIVE_MODELS = "use_live_models";

    // NEW: Key for tracking scraped files index
    public static final String KEY_SCRAPED_PRODUCTS_INDEX = "puter_scraped_index";

    // NEW: Key for managing bulk scraper queues
    public static final String KEY_SCRAPER_QUEUE = "puter_scraper_queue";

    // NEW: Key for toggling Auto Mode vs Manual Mode (Instruction One, Two, and Six)
    public static final String KEY_AUTO_MODE = "puter_auto_mode";
    public static final String KEY_MODE_AUTO = "puter_auto_mode"; // Defensive compile-time alias

    // --- NOSTR IDENTITY & RELAY PREFERENCES ---
    public static final String KEY_NOSTR_PRIVATE_KEY = "nostr_private_key_hex";
    public static final String KEY_NOSTR_PUBLIC_KEY = "nostr_public_key_hex";
    public static final String KEY_NOSTR_RELAY_URL = "puter_nostr_relay_url";
    public static final String KEY_EXTENSION_PUBLIC_ID = "puter_extension_public_id";

    // --- NEW: GEMINI INTEGRATION & STATE STORAGE PREFERENCES ---
    public static final String KEY_ACTIVE_PROVIDER = "puter_active_provider";
    public static final String KEY_GEMINI_API_KEY = "gemini_user_api_key";
    public static final String KEY_GEMINI_STREAMING = "gemini_streaming_enabled";
    public static final String KEY_GEMINI_GROUNDING = "gemini_grounding_type";

    // --- PUTER URLs ---
    // The main entry point for Puter.com authentication via browser redirect.
    public static final String PUTER_LOGIN_URL = "https://puter.com/login";
    
    // The local asset path for the app frontend.
    // UPDATED: Now uses the virtual HTTPS origin required for Puter.js session persistence.
    public static final String LOCAL_INDEX_URL = "https://appassets.androidplatform.net/assets/index.html";
    
    // NEW: Local asset path for the manual browser frontend.
    // RESTORED: Points back to the local assets portal view to enable your custom browser layout
    // inside the app WebView.
    public static final String LOCAL_BROWSER_URL = "https://appassets.androidplatform.net/assets/browser.html";

    // NEW: Local asset path for the Traders Zone trading bot dashboard.
    public static final String LOCAL_BOT_URL = "https://appassets.androidplatform.net/assets/bot.html";

    // Markers for detecting successful SDK authentication redirects
    public static final String AUTH_SUCCESS_MARKER = "signed_in=true";
    public static final String AUTH_TOKEN_PARAM = "token=";

    // --- ASSETS ---
    public static final String MODELS_JSON_FILE = "models.json";
    public static final String GEMINI_MODELS_JSON_FILE = "gemini_models.json";

    // --- PERMISSION REQUEST CODES ---
    public static final int REQUEST_CODE_PERMISSIONS = 100;
    public static final int FILE_CHOOSER_RESULT_CODE = 1;

    // --- BRIDGE NAMES ---
    // This MUST match the name used in JavaScript: window.AndroidInterface
    public static final String JS_BRIDGE_NAME = "AndroidInterface";

    // --- TTS CONFIGURATION ---
    public static final String TTS_UTTERANCE_ID = "PuterTTS_ID";

    // --- LOG TAGS ---
    public static final String TAG_AUTH = "PuterAuth";
    public static final String TAG_VOICE = "PuterVoice";
    public static final String TAG_WEBVIEW = "PuterWebView";

    // --- WATCHER SERVICE CONFIGURATION ---
    public static final int WATCHER_NOTIFICATION_ID = 1002;
    public static final String WATCHER_CHANNEL_ID = "puter_watcher_channel";
    public static final String WATCHER_CHANNEL_NAME = "Puter Query Watcher";

    /**
     * Private constructor to prevent instantiation of a constant utility class.
     */
    private AppConstants() {
        // No-op
    }
}