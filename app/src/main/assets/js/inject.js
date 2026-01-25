/**
 * MassDroid JavaScript Injection Entry Point
 *
 * This file is loaded by MainActivity and orchestrates the loading
 * of all JavaScript modules in the correct order.
 *
 * Load order:
 * 1. ma-websocket.js      - WebSocket manager (no dependencies)
 * 2. mediasession-polyfill.js - MediaSession interception
 * 3. ws-interceptor.js    - WebSocket interception (depends on ma-websocket)
 *
 * @version 1.0.0
 * @author MassDroid
 */

(function() {
    'use strict';

    // Prevent double initialization
    if (window._massdroidInjected) {
        console.log('[MassDroid] Already injected, skipping');
        return;
    }
    window._massdroidInjected = true;

    console.log('[MassDroid] =====================================');
    console.log('[MassDroid] Initializing MassDroid JavaScript...');
    console.log('[MassDroid] =====================================');

    // Note: Individual modules are loaded separately by MainActivity
    // This file just marks that injection has occurred

    // Verify all modules loaded correctly after a short delay
    setTimeout(function() {
        const status = {
            MaWebSocket: !!window.MaWebSocket,
            musicPlayer: !!window.musicPlayer,
            wsInterceptor: !!window._wsInterceptorInstalled,
            mediaSessionPolyfill: !!window._mediaSessionPolyfillInstalled
        };

        console.log('[MassDroid] Module status:', JSON.stringify(status));

        // Notify Android that injection is complete
        if (window.AndroidMediaSession && window.AndroidMediaSession.onInjectionComplete) {
            window.AndroidMediaSession.onInjectionComplete(JSON.stringify(status));
        }
    }, 100);

    // Auto-resume is now handled entirely via triggerPlay() in MainActivity.kt
    // which sets window._massdroidWaitingForReady flag, and ws-interceptor.js
    // watches for queue_updated events to trigger playback. No page reload needed!

})();
