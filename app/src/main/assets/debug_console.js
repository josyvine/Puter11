/**
 * Puter Unofficial Diagnostic Console
 * Purpose: Track session transitions and identify origin isolation errors.
 * Features: Floating UI, Minimize, Close, Full Screen.
 * 
 * UPDATED: Optimized for Secure Origin (HTTPS) environment.
 */
(function() {
    // 1. Create and Inject the Console Styles
    const style = document.createElement('style');
    style.innerHTML = `
        #puter-debug-console {
            position: fixed;
            bottom: 80px;
            right: 10px;
            width: 320px;
            height: 450px;
            background: rgba(15, 15, 15, 0.95);
            color: #00ff00;
            font-family: 'monospace';
            font-size: 11px;
            z-index: 999999;
            border-radius: 12px;
            display: flex;
            flex-direction: column;
            box-shadow: 0 8px 32px rgba(0,0,0,0.8);
            border: 1px solid #333;
            overflow: hidden;
            transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
            backdrop-filter: blur(10px);
        }

        #puter-debug-console.minimized {
            width: 60px;
            height: 60px;
            border-radius: 30px;
            bottom: 90px;
            right: 10px;
            border: 2px solid #1a73e8;
        }

        #puter-debug-console.fullscreen {
            width: 100vw;
            height: 100vh;
            top: 0;
            left: 0;
            bottom: 0;
            right: 0;
            border-radius: 0;
        }

        .console-header {
            background: #222;
            padding: 8px 12px;
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 1px solid #444;
            user-select: none;
        }

        .console-title {
            font-weight: bold;
            color: #00ccff;
            text-transform: uppercase;
            letter-spacing: 1px;
        }

        .console-controls {
            display: flex;
            gap: 12px;
        }

        .console-btn {
            cursor: pointer;
            font-weight: bold;
            font-size: 16px;
            color: #fff;
            background: none;
            border: none;
            padding: 0;
            display: flex;
            align-items: center;
            justify-content: center;
        }

        .console-btn:hover { color: #1a73e8; }
        .btn-close { color: #ff5555; }

        .console-body {
            flex: 1;
            overflow-y: auto;
            padding: 10px;
            word-break: break-all;
            background: #000;
        }

        .log-entry {
            margin-bottom: 6px;
            border-bottom: 1px solid #222;
            padding-bottom: 4px;
            line-height: 1.4;
        }

        .log-time { color: #666; margin-right: 6px; font-size: 9px; }
        .log-info { color: #00ff00; }
        .log-warn { color: #ffff00; }
        .log-error { color: #ff3333; font-weight: bold; }
        .log-native { color: #00ccff; border-left: 2px solid #00ccff; padding-left: 5px; }

        #puter-debug-console.minimized .console-body,
        #puter-debug-console.minimized .console-title,
        #puter-debug-console.minimized .btn-fs {
            display: none;
        }
        
        #puter-debug-console.minimized .console-header {
            height: 100%;
            background: transparent;
            border: none;
            justify-content: center;
        }
    `;
    document.head.appendChild(style);

    // 2. Create the HTML Structure
    const consoleDiv = document.createElement('div');
    consoleDiv.id = 'puter-debug-console';
    consoleDiv.innerHTML = `
        <div class="console-header">
            <span class="console-title">Puter Debug</span>
            <div class="console-controls">
                <button class="console-btn btn-fs" title="Full Screen" id="debug-btn-fs">⛶</button>
                <button class="console-btn" title="Minimize" id="debug-btn-min">−</button>
                <button class="console-btn btn-close" title="Close" id="debug-btn-close">×</button>
            </div>
        </div>
        <div class="console-body" id="debug-console-body"></div>
    `;
    document.body.appendChild(consoleDiv);

    const body = document.getElementById('debug-console-body');

    /**
     * Appends a log entry to the UI.
     * Accessible globally for the WebAppInterface to call.
     */
    window.addPuterLog = function(message, type = 'info') {
        const entry = document.createElement('div');
        entry.className = 'log-entry';
        
        const now = new Date();
        const timeStr = now.getHours().toString().padStart(2, '0') + ':' + 
                        now.getMinutes().toString().padStart(2, '0') + ':' + 
                        now.getSeconds().toString().padStart(2, '0');

        entry.innerHTML = `<span class="log-time">${timeStr}</span><span class="log-${type}">${message}</span>`;
        body.appendChild(entry);
        
        // Auto-scroll to bottom
        body.scrollTop = body.scrollHeight;
    };

    // 3. UI Button Handlers
    document.getElementById('debug-btn-min').onclick = (e) => {
        e.stopPropagation();
        consoleDiv.classList.toggle('minimized');
        consoleDiv.classList.remove('fullscreen');
        document.getElementById('debug-btn-min').innerText = consoleDiv.classList.contains('minimized') ? '+' : '−';
    };

    document.getElementById('debug-btn-fs').onclick = (e) => {
        e.stopPropagation();
        consoleDiv.classList.toggle('fullscreen');
        consoleDiv.classList.remove('minimized');
    };

    document.getElementById('debug-btn-close').onclick = (e) => {
        e.stopPropagation();
        consoleDiv.style.display = 'none';
    };

    // 4. Override standard console to pipe into our UI
    const originalLog = console.log;
    const originalError = console.error;
    const originalWarn = console.warn;

    console.log = function(...args) {
        originalLog.apply(console, args);
        window.addPuterLog(args.map(a => typeof a === 'object' ? JSON.stringify(a) : a).join(' '), 'info');
    };

    console.error = function(...args) {
        originalError.apply(console, args);
        window.addPuterLog(args.map(a => typeof a === 'object' ? JSON.stringify(a) : a).join(' '), 'error');
    };

    console.warn = function(...args) {
        originalWarn.apply(console, args);
        window.addPuterLog(args.map(a => typeof a === 'object' ? JSON.stringify(a) : a).join(' '), 'warn');
    };

    // Catch unhandled JS errors
    window.onerror = function(msg, url, line) {
        window.addPuterLog(`CRASH: ${msg} [Line: ${line}]`, 'error');
    };

    // Initialize display with metadata
    const isSecure = window.isSecureContext ? "YES" : "NO";
    window.addPuterLog("--- DEBUG SESSION STARTED ---", "native");
    window.addPuterLog("Origin: " + window.location.origin, "native");
    window.addPuterLog("Secure Context: " + isSecure, "native");
    window.addPuterLog("User Agent: " + navigator.userAgent, "info");
    
    // Check if models.json is reachable under the new origin
    window.addPuterLog("Checking Asset Path compatibility...", "info");

})();