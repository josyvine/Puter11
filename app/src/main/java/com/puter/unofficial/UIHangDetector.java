package com.puter.unofficial;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * TOTAL SURVEILLANCE WATCHDOG: UIHangDetector
 * Role: Monitors the Main UI Thread/Looper for freezes, lags, and execution bloat.
 * 
 * Logic:
 * 1. Sends a handshake probe to the Main Looper at regular intervals (500ms).
 * 2. Measures the "Response Latency" of the interface.
 * 3. Records a complete Main Thread StackTrace dump if rendering exceeds thresholds.
 */
public class UIHangDetector {

    private static final String TAG = "UIHangDetector";
    
    // Performance Thresholds (in milliseconds)
    private static final long CHECK_INTERVAL = 500;  // How often to check Main Looper health
    private static final long BLOAT_THRESHOLD = 200; // Time beyond which UI is considered "Laggy"
    private static final long HANG_THRESHOLD = 2000; // Time beyond which UI is considered "Frozen"

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Thread watchdogThread;
    private static volatile boolean isRunning = false;
    
    // Tracking variables for handshake synchronization
    private static long lastTickTime = 0;
    private static final Object syncObject = new Object();
    private static boolean isCallbackRunning = false;

    /**
     * Starts the looper surveillance watchdog.
     * Called on Application startup (PuterApplication).
     */
    public static void startWatchdog() {
        if (isRunning) return;
        isRunning = true;

        watchdogThread = new Thread(() -> {
            ActionReportLogger.logAction("WATCHDOG", "UI Looper Watchdog Thread Started.");
            
            while (isRunning) {
                final long startTime = System.currentTimeMillis();
                isCallbackRunning = true;

                // Post a probe to the Main Thread
                mainHandler.post(() -> {
                    synchronized (syncObject) {
                        isCallbackRunning = false;
                        lastTickTime = System.currentTimeMillis();
                        syncObject.notifyAll();
                    }
                });

                try {
                    // Watchdog waits on background thread for the Main Thread to finish the posted task
                    synchronized (syncObject) {
                        long waitTime = HANG_THRESHOLD;
                        while (isCallbackRunning && waitTime > 0) {
                            long startWait = System.currentTimeMillis();
                            syncObject.wait(waitTime);
                            waitTime -= (System.currentTimeMillis() - startWait);
                        }
                    }

                    long duration = System.currentTimeMillis() - startTime;

                    // EVALUATION LOGIC
                    if (isCallbackRunning) {
                        // CASE 1: MAIN THREAD FREEZE / HARD HANG
                        // The Main Thread is completely blocked and failed to respond within 2 seconds.
                        captureAndLogHang(System.currentTimeMillis() - startTime);
                    } else if (duration > BLOAT_THRESHOLD) {
                        // CASE 2: UI BLOAT / RENDER LAG
                        // The UI responded, but took longer than the acceptable threshold (200ms).
                        ActionReportLogger.logPerformance("UI_BLOAT", 
                            "Significant rendering lag detected. Response time: " + duration + "ms");
                    }

                } catch (InterruptedException e) {
                    ActionReportLogger.logError("WATCHDOG_INTERRUPT", e.getMessage());
                    break;
                }

                // Sleep before the next looper health check
                try {
                    Thread.sleep(CHECK_INTERVAL);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "Puter-UI-Watchdog");

        watchdogThread.setPriority(Thread.MAX_PRIORITY);
        watchdogThread.start();
    }

    /**
     * Captures and dumps the Main Thread's active stack trace to the log file.
     * Tells you exactly what line of Java code is causing the freeze.
     */
    private static void captureAndLogHang(long duration) {
        StringBuilder report = new StringBuilder();
        report.append("CRITICAL PROCESS FREEZE DETECTED.\n");
        report.append("Total Freeze Duration: ").append(duration).append("ms\n");
        report.append("Analysis: Main UI Thread is locked.\n");
        report.append("Suspected Location (Main Thread Stack Trace):\n");

        // Extract the active stack trace of the Main Thread
        StackTraceElement[] stackTrace = Looper.getMainLooper().getThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            report.append("    at ").append(element.toString()).append("\n");
        }

        // Save detailed forensic dump to public log file
        ActionReportLogger.logHang("CRITICAL_FREEZE", report.toString());
        Log.e(TAG, "!!! CRITICAL MAIN THREAD HANG DETECTED !!! Check /Documents/puter report/ directory.");
    }

    /**
     * Terminates the background watchdog thread cleanly.
     */
    public static void stopWatchdog() {
        isRunning = false;
        if (watchdogThread != null) {
            watchdogThread.interrupt();
        }
    }
}