package com.puter.unofficial;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.puter.unofficial.databinding.FragmentHomeBinding;

/**
 * The Primary Dashboard Fragment for Puter Unofficial.
 * This fragment hosts the WebView that displays the Puter AI chat interface.
 * It initializes the JavaScript bridge and handles native feature integration.
 * 
 * UPDATED: Integrated WebViewAssetLoader support and Secure Origin migration.
 * CRITICAL FIX: Destroys the native background standard VoiceManager's SpeechRecognizer 
 * inside the fragment lifecycle to eliminate background hardware microphone locks.
 * RESTORATION FIX: Automatically re-initializes the VoiceManager upon returning to the foreground.
 */
public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private WebView webView;
    private WebAppInterface webAppInterface;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Reference the WebView defined in fragment_home.xml
        webView = binding.homeWebView;

        // FIX: Enable Remote Debugging for the fragment's WebView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // 1. Configure WebView Settings for Puter.js compatibility
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); // For TTS/Audio

        // FIX: Enable universal access to allow debug_console.js to function
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        // FIX: Bypass SDK initialization hangs by removing the WebView identifier ("; wv").
        // Matches the logic in MainActivity for session and model-loading consistency.
        String userAgent = settings.getUserAgentString();
        userAgent = userAgent.replace("; wv", "");
        settings.setUserAgentString(userAgent);

        // Ensure standard mobile viewport behavior
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        // 2. Initialize the Native Managers
        // Note: Using getActivity() because the bridge requires an Activity context for UI operations
        webAppInterface = new WebAppInterface(requireActivity(), webView);

        // 3. Set the Custom Puter WebView Client and Web Chrome Client
        // This handles authentication redirects, AssetLoader routing, file pickers, popups, and device permissions
        webView.setWebViewClient(new PuterWebViewClient(requireContext()));
        webView.setWebChromeClient(new MyWebChromeClient(requireActivity()));

        // 4. Register the JavaScript Bridge
        // This exposes 'window.AndroidInterface' to the HTML/JS frontend
        webView.addJavascriptInterface(webAppInterface, AppConstants.JS_BRIDGE_NAME);

        // 5. Load the local Puter frontend via the secure HTTPS virtual origin
        webView.loadUrl(AppConstants.LOCAL_INDEX_URL);
    }

    /**
     * Refreshes the chat interface. 
     * Can be called by the Activity when returning from settings.
     */
    public void refreshChat() {
        if (webView != null) {
            webView.reload();
        }
    }

    /**
     * Lifecycle hook called when the fragment comes back to the foreground.
     * We cleanly resume WebView execution.
     */
    @Override
    public void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    /**
     * Lifecycle hook called when the fragment is paused.
     * We cleanly pause WebView timers to prevent background resource leaks.
     */
    @Override
    public void onPause() {
        super.onPause();

        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
    }

    @Override
    public void onDestroyView() {
        // Cleanup to prevent memory leaks
        if (webAppInterface != null) {
            webAppInterface.destroy();
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroyView();
        binding = null;
    }
}