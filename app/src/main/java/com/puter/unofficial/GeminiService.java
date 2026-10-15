package com.puter.unofficial;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Native Network Service for Google Gemini APIs.
 * Handles background REST and streaming execution utilizing OkHttp and thread executors.
 * Pipes streaming chunk payloads directly back to the active WebView on the main UI thread.
 */
public class GeminiService {

    private static final String TAG = "GeminiService";
    
    private final Context context;
    private final WebView webView;
    private final OkHttpClient client;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    /**
     * Constructor for GeminiService.
     * Initializes the client with specific read/write timeouts optimal for streaming.
     */
    public GeminiService(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executorService = Executors.newSingleThreadExecutor();
        
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS) // Extended read timeout for stable streaming of large codeblocks
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Executes the Gemini completion or streaming request on a background thread.
     * 
     * @param modelId     The target Google model ID (e.g., "gemini-2.5-flash")
     * @param payloadJson The compiled JSON request parameters passed from JavaScript
     * @param isStream    True to route over streaming connections, false for standard completions
     * @param msgId       Unique message transaction identifier for targeted WebView callbacks
     */
    public void executeCall(final String modelId, final String payloadJson, final boolean isStream, final String msgId) {
        executorService.execute(() -> {
            try {
                // Retrieve API Key securely from local SharedPreferences
                SharedPreferences prefs = context.getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);
                String apiKey = prefs.getString(AppConstants.KEY_GEMINI_API_KEY, "");

                if (apiKey.isEmpty()) {
                    // Fallback to initial default key if not configured in the three-dot setting dropdown
                    apiKey = "AIzaSyBbwPg-mPKe4FNtfI8j07REFFjV6C-xVMw";
                }

                // Resolve target URL endpoints dynamically based on connection style
                String baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + modelId;
                String action = isStream ? ":streamGenerateContent" : ":generateContent";
                String targetUrl = baseUrl + action + "?key=" + apiKey;

                Log.d(TAG, "Initiating Gemini network request. URL: " + baseUrl + action + " | MsgId: " + msgId);

                MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
                RequestBody body = RequestBody.create(payloadJson, mediaType);

                Request request = new Request.Builder()
                        .url(targetUrl)
                        .post(body)
                        .build();

                Response response = client.newCall(request).execute();

                if (!response.isSuccessful()) {
                    String errorText = response.body() != null ? response.body().string() : "Empty response body";
                    Log.e(TAG, "Gemini API Execution Failure: HTTP " + response.code() + " -> " + errorText);
                    deliverError(msgId, "HTTP " + response.code() + ": " + errorText);
                    return;
                }

                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    deliverError(msgId, "Response body was empty.");
                    return;
                }

                if (isStream) {
                    // Handle stream connections by reading characters dynamically from the stream reader
                    parseStreamResponse(responseBody, msgId);
                } else {
                    // Deliver complete content immediately for standard single REST calls
                    String responseData = responseBody.string();
                    deliverComplete(msgId, responseData);
                }

            } catch (IOException e) {
                Log.e(TAG, "Network exception occurred during Gemini connection: " + e.getMessage(), e);
                deliverError(msgId, "Connection failed: " + e.getMessage());
            }
        });
    }

    /**
     * Parses the response body stream cleanly using UTF-8 character buffers to prevent
     * multi-byte character boundary corruption, and pipes raw chunks back to JavaScript.
     */
    private void parseStreamResponse(ResponseBody responseBody, String msgId) {
        InputStream inputStream = responseBody.byteStream();
        Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        char[] buffer = new char[2048];
        int charsRead;
        try {
            while ((charsRead = reader.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, charsRead);
                deliverStreamChunk(msgId, chunk);
            }
            deliverStreamComplete(msgId);
        } catch (IOException e) {
            Log.e(TAG, "Stream reading interrupted: " + e.getMessage());
            deliverError(msgId, "Streaming interrupted: " + e.getMessage());
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {}
            try {
                inputStream.close();
            } catch (IOException ignored) {}
        }
    }

    // --- MAIN THREAD WEBVIEW CALLBACK DISPATCHERS ---

    private void deliverStreamChunk(final String msgId, final String chunk) {
        mainHandler.post(() -> {
            String quotedMsgId = safeQuote(msgId);
            String quotedChunk = safeQuote(chunk);
            String js = "if(window.onGeminiStreamChunk){ window.onGeminiStreamChunk(" + quotedMsgId + ", " + quotedChunk + "); }";
            webView.evaluateJavascript(js, null);
        });
    }

    private void deliverStreamComplete(final String msgId) {
        mainHandler.post(() -> {
            String quotedMsgId = safeQuote(msgId);
            String js = "if(window.onGeminiStreamComplete){ window.onGeminiStreamComplete(" + quotedMsgId + "); }";
            webView.evaluateJavascript(js, null);
        });
    }

    private void deliverComplete(final String msgId, final String responseData) {
        mainHandler.post(() -> {
            String quotedMsgId = safeQuote(msgId);
            String quotedResponse = safeQuote(responseData);
            String js = "if(window.onGeminiComplete){ window.onGeminiComplete(" + quotedMsgId + ", " + quotedResponse + "); }";
            webView.evaluateJavascript(js, null);
        });
    }

    private void deliverError(final String msgId, final String errorMessage) {
        mainHandler.post(() -> {
            String quotedMsgId = safeQuote(msgId);
            String quotedError = safeQuote(errorMessage);
            String js = "if(window.onGeminiError){ window.onGeminiError(" + quotedMsgId + ", " + quotedError + "); }";
            webView.evaluateJavascript(js, null);
        });
    }

    /**
     * Helper to safely format JavaScript string parameters using standard JSON quoting rules.
     */
    private String safeQuote(String value) {
        if (value == null) {
            return "\"\"";
        }
        return JSONObject.quote(value);
    }

    /**
     * Disposes the asynchronous background thread executors safely on destruction.
     */
    public void shutdown() {
        if (!executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }
}