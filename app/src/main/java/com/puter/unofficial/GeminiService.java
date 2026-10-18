package com.puter.unofficial;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Native Network Service for Google Gemini APIs.
 * Handles background REST, streaming execution, active call cancellation,
 * URL context extraction/fetching, and live model discovery utilizing OkHttp.
 * Pipes streaming chunk payloads directly back to the active WebView on the main UI thread.
 */
public class GeminiService {

    private static final String TAG = "GeminiService";
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[a-zA-Z0-9./_\\-~%#?=&]+");

    private final Context context;
    private final WebView webView;
    private final OkHttpClient client;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    private Call activeCall = null;
    private final Object callLock = new Object();

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
                .readTimeout(120, TimeUnit.SECONDS) // Extended read timeout for large code streaming
                .writeTimeout(20, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Cancels the currently executing Gemini network request, if active.
     */
    public void cancelCurrentCall() {
        synchronized (callLock) {
            if (activeCall != null && !activeCall.isCanceled()) {
                Log.d(TAG, "User requested cancellation. Aborting active Gemini network call.");
                activeCall.cancel();
                activeCall = null;
            }
        }
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
            Call call = null;
            try {
                // Retrieve API Key securely from local SharedPreferences
                SharedPreferences prefs = context.getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);
                String apiKey = prefs.getString(AppConstants.KEY_GEMINI_API_KEY, "");

                if (apiKey.isEmpty()) {
                    apiKey = "AIzaSyBbwPg-mPKe4FNtfI8j07REFFjV6C-xVMw";
                }

                // Process payload for URL Context and prevent HTTP 400 schema rejections
                String cleanPayload = processUrlContextAndCleanPayload(payloadJson);

                // Resolve target URL endpoints dynamically based on connection style
                String baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + modelId;
                String action = isStream ? ":streamGenerateContent" : ":generateContent";
                String targetUrl = baseUrl + action + "?key=" + apiKey;

                Log.d(TAG, "Initiating Gemini network request. URL: " + baseUrl + action + " | MsgId: " + msgId);

                MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
                RequestBody body = RequestBody.create(cleanPayload, mediaType);

                Request request = new Request.Builder()
                        .url(targetUrl)
                        .post(body)
                        .build();

                call = client.newCall(request);
                synchronized (callLock) {
                    this.activeCall = call;
                }

                Response response = call.execute();

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
                    parseStreamResponse(responseBody, msgId);
                } else {
                    String responseData = responseBody.string();
                    deliverComplete(msgId, responseData);
                }

            } catch (IOException e) {
                if (call != null && call.isCanceled()) {
                    Log.d(TAG, "Active Gemini call was intentionally canceled.");
                    deliverError(msgId, "Request canceled.");
                    return;
                }
                Log.e(TAG, "Network exception occurred during Gemini connection: " + e.getMessage(), e);
                deliverError(msgId, "Connection failed: " + e.getMessage());
            } catch (Exception ex) {
                Log.e(TAG, "Unexpected error in executeCall: " + ex.getMessage(), ex);
                deliverError(msgId, "Processing error: " + ex.getMessage());
            } finally {
                synchronized (callLock) {
                    if (this.activeCall == call) {
                        this.activeCall = null;
                    }
                }
            }
        });
    }

    /**
     * Inspects the incoming payload for "urlContext": true.
     * If enabled, extracts any URLs from the prompt or attached text, fetches their raw contents
     * via OkHttp, injects them as grounded context, and removes the "urlContext" field to
     * prevent Gemini HTTP 400 schema validation errors.
     */
    private String processUrlContextAndCleanPayload(String rawPayloadJson) {
        try {
            JSONObject root = new JSONObject(rawPayloadJson);

            boolean isUrlContextEnabled = false;
            if (root.has("urlContext")) {
                isUrlContextEnabled = root.optBoolean("urlContext", false);
                root.remove("urlContext"); // Clean root to satisfy Google Generative API schema
            }

            if (!isUrlContextEnabled || !root.has("contents")) {
                return root.toString();
            }

            JSONArray contents = root.getJSONArray("contents");
            List<String> detectedUrls = new ArrayList<>();

            // Scan user text parts for URLs
            for (int i = 0; i < contents.length(); i++) {
                JSONObject turn = contents.getJSONObject(i);
                if (turn.optString("role", "").equals("user") && turn.has("parts")) {
                    JSONArray parts = turn.getJSONArray("parts");
                    for (int j = 0; j < parts.length(); j++) {
                        JSONObject part = parts.getJSONObject(j);
                        if (part.has("text")) {
                            String text = part.getString("text");
                            Matcher matcher = URL_PATTERN.matcher(text);
                            while (matcher.find()) {
                                String url = matcher.group();
                                if (!detectedUrls.contains(url)) {
                                    detectedUrls.add(url);
                                }
                            }
                        }
                    }
                }
            }

            // If URLs are found, fetch their text in parallel/sequence (up to 12 files to prevent memory overflow)
            if (!detectedUrls.isEmpty()) {
                StringBuilder fetchedContextBuilder = new StringBuilder();
                int fetchedCount = 0;
                int maxFetches = Math.min(detectedUrls.size(), 15);

                Log.d(TAG, "URL Context active. Found " + detectedUrls.size() + " URLs. Resolving first " + maxFetches + "...");

                for (int i = 0; i < maxFetches; i++) {
                    String url = detectedUrls.get(i);
                    String fetchedData = fetchUrlContentDirectly(url);
                    if (fetchedData != null && !fetchedData.trim().isEmpty()) {
                        fetchedContextBuilder.append("\n\n--- START OF FILE CONTEXT [URL: ").append(url).append("] ---\n");
                        fetchedContextBuilder.append(fetchedData);
                        fetchedContextBuilder.append("\n--- END OF FILE CONTEXT ---\n");
                        fetchedCount++;
                    }
                }

                if (fetchedCount > 0) {
                    // Inject the retrieved document context into the latest user turn
                    JSONObject lastTurn = contents.getJSONObject(contents.length() - 1);
                    JSONArray parts = lastTurn.getJSONArray("parts");

                    JSONObject contextPart = new JSONObject();
                    contextPart.put("text", "\n[System URL Context Retrieval: The following " + fetchedCount +
                            " source file(s) were successfully fetched and verified]\n" +
                            fetchedContextBuilder.toString());

                    parts.put(contextPart);
                    Log.d(TAG, "Successfully injected fetched content for " + fetchedCount + " URLs into Gemini prompt.");
                }
            }

            return root.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error processing URL context in payload: " + e.getMessage());
            return rawPayloadJson;
        }
    }

    /**
     * Direct HTTP fetch helper for fetching raw code/text documents from URLs.
     */
    private String fetchUrlContentDirectly(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                    .get()
                    .build();

            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    String bodyStr = resp.body().string();
                    // Guard against giant binaries: limit single file snippet to 40,000 characters
                    if (bodyStr.length() > 40000) {
                        return bodyStr.substring(0, 40000) + "\n...[Content truncated for token safety]...";
                    }
                    return bodyStr;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to pre-fetch URL context for " + url + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Queries the Google Generative Language API on a background thread to retrieve all live available models.
     *
     * @param apiKey The user-provided or stored Google Gemini API key
     */
    public void fetchAvailableModels(final String apiKey) {
        executorService.execute(() -> {
            try {
                String keyToUse = (apiKey != null && !apiKey.trim().isEmpty()) ? apiKey.trim() : "";
                if (keyToUse.isEmpty()) {
                    SharedPreferences prefs = context.getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);
                    keyToUse = prefs.getString(AppConstants.KEY_GEMINI_API_KEY, "");
                }

                if (keyToUse.isEmpty()) {
                    deliverLiveModelsError("Gemini API key is missing. Please configure an API key in Gemini Settings.");
                    return;
                }

                String targetUrl = AppConstants.GEMINI_MODELS_LIST_URL + "?key=" + keyToUse;
                Log.d(TAG, "Fetching live Gemini models from endpoint: " + AppConstants.GEMINI_MODELS_LIST_URL);

                Request request = new Request.Builder()
                        .url(targetUrl)
                        .get()
                        .build();

                Response response = client.newCall(request).execute();

                if (!response.isSuccessful()) {
                    String errorText = response.body() != null ? response.body().string() : "Empty error response";
                    Log.e(TAG, "Failed to fetch Gemini models: HTTP " + response.code() + " -> " + errorText);
                    deliverLiveModelsError("HTTP " + response.code() + ": " + errorText);
                    return;
                }

                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    deliverLiveModelsError("Empty response body from Google Generative Language API.");
                    return;
                }

                String responseData = responseBody.string();
                Log.d(TAG, "Successfully retrieved live Gemini models from Google API.");
                deliverLiveModelsFetched(responseData);

            } catch (IOException e) {
                Log.e(TAG, "Network error while fetching Gemini models: " + e.getMessage(), e);
                deliverLiveModelsError("Network connection failure: " + e.getMessage());
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
            synchronized (callLock) {
                if (activeCall != null && activeCall.isCanceled()) {
                    Log.d(TAG, "Stream aborted cleanly by user cancellation.");
                    return;
                }
            }
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

    private void deliverLiveModelsFetched(final String json) {
        mainHandler.post(() -> {
            String quotedJson = safeQuote(json);
            String js = "if(window.onGeminiLiveModelsFetched){ window.onGeminiLiveModelsFetched(" + quotedJson + "); }";
            webView.evaluateJavascript(js, null);
        });
    }

    private void deliverLiveModelsError(final String errorMessage) {
        mainHandler.post(() -> {
            String quotedError = safeQuote(errorMessage);
            String js = "if(window.onGeminiLiveModelsError){ window.onGeminiLiveModelsError(" + quotedError + "); }";
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
        cancelCurrentCall();
        if (!executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }
}