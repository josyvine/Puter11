package com.puter.unofficial;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TOTAL SURVEILLANCE ENGINE: ActionReportLogger
 * Role: Silently records every interaction, WebView glitch, and runtime error to .txt files.
 * Location: Saves to a public, non-hidden folder named "puter report" inside public Documents.
 * 
 * Features:
 * - Asynchronous Background Writing: Logging never blocks the Main UI thread or WebView frames.
 * - Millisecond Precision: Tracks exact timing of user actions and system responses.
 * - Daily Rotation: Generates a new .txt file every day for easy monitoring.
 * - Thread Auditing: Prints active thread name and ID with every log entry to spot deadlocks.
 */
public class ActionReportLogger {

    private static final String TAG = "ActionReportLogger";
    private static final String FOLDER_NAME = "puter report";
    
    // Background executor to handle file I/O asynchronously
    private static final ExecutorService logExecutor = Executors.newSingleThreadExecutor();
    
    private static File reportDirectory;
    private static final SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private static final SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    /**
     * Initializes the reporting directory.
     * Called on Application startup (PuterApplication).
     */
    public static void init(Context context) {
        // Targets the public "Documents" directory to ensure the folder is NOT hidden
        File publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        reportDirectory = new File(publicDocs, FOLDER_NAME);

        if (!reportDirectory.exists()) {
            boolean created = reportDirectory.mkdirs();
            if (created) {
                Log.i(TAG, "Surveillance Directory Created: " + reportDirectory.getAbsolutePath());
            } else {
                // Fallback to internal app storage files directory if public documents creation fails
                reportDirectory = new File(context.getExternalFilesDir(null), FOLDER_NAME);
                reportDirectory.mkdirs();
                Log.w(TAG, "Fallback Directory Created: " + reportDirectory.getAbsolutePath());
            }
        }
    }

    /**
     * Logs a standard user action, tab switch, or browsing transition.
     */
    public static void logAction(String category, String message) {
        writeToReport("ACTION", category, message);
    }

    /**
     * Logs a performance issue, rendering delay, or load latency.
     */
    public static void logPerformance(String category, String message) {
        writeToReport("PERFORMANCE", category, message);
    }

    /**
     * Logs a silent error caught in a try-catch block.
     */
    public static void logError(String category, String errorDetails) {
        writeToReport("SILENT_ERROR", category, errorDetails);
    }

    /**
     * Logs a detected UI Hang or Thread Freeze.
     */
    public static void logHang(String category, String hangDetails) {
        writeToReport("UI_HANG", category, hangDetails);
    }

    /**
     * Logs a glitch detected within the WebView HTML/JS environment.
     * Use this for: Disappearing elements, empty page containers, or scraping timeouts.
     */
    public static void logHtmlGlitch(String component, String glitchDetails) {
        writeToReport("HTML_GLITCH", component, glitchDetails);
    }

    /**
     * Logs a violation of application logic.
     * Use this for: Invalid states, configuration errors, or data corruption.
     */
    public static void logLogicViolation(String rule, String violationDetails) {
        writeToReport("LOGIC_VIOLATION", rule, violationDetails);
    }

    /**
     * Logs the execution time taken for a Javascript-to-Native bridge handshake.
     */
    public static void logBridgeLatency(String bridgeMethod, long durationMs) {
        writeToReport("BRIDGE_LATENCY", bridgeMethod, "Execution took " + durationMs + "ms");
    }

    /**
     * Logs when a user click or automated process results in a failed intent or dead zone.
     */
    public static void logUxBlockage(String componentId, String reason) {
        writeToReport("UX_BLOCKAGE", componentId, reason);
    }

    /**
     * Core I/O Logic: Queues the string to be written to the daily .txt file.
     * Captures current Thread info dynamically to identify the caller.
     */
    private static void writeToReport(final String level, final String category, final String message) {
        final long currentTime = System.currentTimeMillis();
        final String threadName = Thread.currentThread().getName();
        final long threadId = Thread.currentThread().getId();
        
        logExecutor.execute(() -> {
            if (reportDirectory == null) return;

            String dateStr = fileDateFormat.format(new Date(currentTime));
            String timeStr = timestampFormat.format(new Date(currentTime));
            
            // Daily rotated log naming convention: report_2026-05-21.txt
            File reportFile = new File(reportDirectory, "report_" + dateStr + ".txt");
            
            // Format: [2026-05-21 14:05:01.123] [T:JavaBridge:15] [ACTION] [UI_NAV] User switched tab: Browser
            String logEntry = String.format("[%s] [T:%s:%d] [%s] [%s] %s\n", 
                    timeStr, threadName, threadId, level, category, message);

            FileWriter writer = null;
            try {
                // 'true' ensures we append data to the file without overwriting previous entries
                writer = new FileWriter(reportFile, true);
                writer.write(logEntry);
                writer.flush();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write surveillance report: " + e.getMessage());
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException ignored) {}
                }
            }
        });
    }
}