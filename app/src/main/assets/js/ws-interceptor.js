/**
 * WebSocket Interceptor
 *
 * Intercepts all WebSocket connections to:
 * 1. Route MA API socket to MaWebSocket manager
 * 2. Handle SendSpin events (connection, stream start/end)
 * 3. Log messages for debugging/protocol discovery
 *
 * Depends on: ma-websocket.js (must be loaded first)
 *
 * @version 1.0.0
 * @author MassDroid
 */

(function() {
    'use strict';

    // Prevent double initialization
    if (window._wsInterceptorInstalled) {
        console.log('[WSInterceptor] Already installed, skipping');
        return;
    }
    window._wsInterceptorInstalled = true;

    console.log('[WSInterceptor] Installing WebSocket interceptor...');

    // Store original WebSocket constructor
    const OriginalWebSocket = window.WebSocket;

    // ============================================
    // LOGGING HELPER
    // ============================================

    function logToAndroid(source, type, data) {
        if (window.AndroidMediaSession && window.AndroidMediaSession.logWsMessage) {
            try {
                const payload = typeof data === 'string' ? data : JSON.stringify(data);
                window.AndroidMediaSession.logWsMessage(source, type, payload);
            } catch (e) { /* ignore */ }
        }
    }

    // ============================================
    // CONNECTION CLASSIFIER
    // ============================================

    function classifyConnection(url) {
        const urlStr = url.toString().toLowerCase();

        if (urlStr.includes('/sendspin')) {
            return { type: 'SENDSPIN', isMainApi: false, isSendspin: true };
        }

        if (urlStr.includes('/ws') || urlStr.includes('api')) {
            return { type: 'API', isMainApi: true, isSendspin: false };
        }

        return { type: 'OTHER', isMainApi: false, isSendspin: false };
    }

    // ============================================
    // WEBSOCKET INTERCEPTOR
    // ============================================

    window.WebSocket = function(url, protocols) {
        // Create original WebSocket
        const ws = protocols
            ? new OriginalWebSocket(url, protocols)
            : new OriginalWebSocket(url);

        const urlStr = url.toString();
        const conn = classifyConnection(urlStr);

        console.log('[WSInterceptor] New connection:', conn.type, urlStr.substring(0, 60));

        // Log connection to Android
        if (window.AndroidMediaSession && window.AndroidMediaSession.logWsConnection) {
            window.AndroidMediaSession.logWsConnection(urlStr, conn.type);
        }

        // ============================================
        // MAIN API WEBSOCKET
        // ============================================

        if (conn.isMainApi) {
            // Register with MaWebSocket manager
            if (window.MaWebSocket) {
                window.MaWebSocket.setSocket(ws, urlStr);
            } else {
                console.warn('[WSInterceptor] MaWebSocket not available!');
            }
        }

        // ============================================
        // SENDSPIN WEBSOCKET
        // ============================================

        if (conn.isSendspin) {
            ws.addEventListener('message', function(event) {
                try {
                    if (typeof event.data !== 'string') return;

                    const msg = JSON.parse(event.data);

                    // server/hello = control connection ready
                    if (msg.type === 'server/hello') {
                        console.log('[WSInterceptor] SendSpin connected');
                        if (window.AndroidMediaSession && window.AndroidMediaSession.onSendspinConnected) {
                            window.AndroidMediaSession.onSendspinConnected();
                        }
                    }

                    // stream/start = audio streaming started
                    if (msg.type === 'stream/start') {
                        console.log('[WSInterceptor] SendSpin stream started');
                        if (window.AndroidMediaSession && window.AndroidMediaSession.onSendspinStreamStart) {
                            window.AndroidMediaSession.onSendspinStreamStart();
                        }
                    }

                    // Log message (skip noisy time sync)
                    if (msg.type !== 'server/time') {
                        logToAndroid('SENDSPIN', msg.type || 'unknown', msg);
                    }

                } catch (e) { /* binary data */ }
            });

            ws.addEventListener('close', function(event) {
                console.log('[WSInterceptor] SendSpin disconnected:', event.code);
                if (window.AndroidMediaSession && window.AndroidMediaSession.logWsDisconnection) {
                    window.AndroidMediaSession.logWsDisconnection('SENDSPIN', event.code, event.reason || '');
                }
                if (window.AndroidMediaSession && window.AndroidMediaSession.onSendspinDisconnected) {
                    window.AndroidMediaSession.onSendspinDisconnected();
                }
            });
        }

        // ============================================
        // DEBUG LOGGING (ALL CONNECTIONS)
        // ============================================

        ws.addEventListener('message', function(event) {
            try {
                if (typeof event.data !== 'string') return;

                const msg = JSON.parse(event.data);
                const msgType = msg.type || msg.event || msg.message_type || 'unknown';

                // Skip noisy messages
                if (msgType === 'server/time') return;

                // Track selected player from queue_updated events (active: true)
                if (conn.isMainApi && msg.event === 'queue_updated' && msg.data) {
                    const queue = msg.data;
                    if (queue.active === true && queue.queue_id) {
                        if (window.MaWebSocket) {
                            window.MaWebSocket.setSelectedPlayer(queue.queue_id, queue.display_name);
                        }
                    }
                }

                // Also track currently playing player from player_updated events
                if (conn.isMainApi && msg.event === 'player_updated' && msg.data) {
                    const player = msg.data;
                    if (player.playback_state === 'playing' && player.player_id) {
                        if (window.MaWebSocket) {
                            window.MaWebSocket.setCurrentlyPlaying(player.player_id);
                        }
                    }
                }

                // DISCOVERY: Log interesting events that might indicate player selection
                if (conn.isMainApi) {
                    const interestingKeywords = ['player', 'active', 'select', 'switch', 'config', 'preference', 'queue'];
                    const isInteresting = interestingKeywords.some(kw =>
                        msgType.toLowerCase().includes(kw) ||
                        JSON.stringify(msg).toLowerCase().includes('active')
                    );

                    if (isInteresting) {
                        console.log('[WS-DISCOVERY]', msgType, JSON.stringify(msg).substring(0, 200));
                    }
                }

                // Log to Android for protocol discovery
                logToAndroid(conn.type, msgType, msg);

            } catch (e) { /* binary data or parse error */ }
        });

        ws.addEventListener('close', function(event) {
            console.log('[WSInterceptor]', conn.type, 'closed:', event.code, event.reason || '');
        });

        ws.addEventListener('error', function(event) {
            console.error('[WSInterceptor]', conn.type, 'error');
        });

        return ws;
    };

    // Preserve WebSocket constants
    window.WebSocket.CONNECTING = OriginalWebSocket.CONNECTING;
    window.WebSocket.OPEN = OriginalWebSocket.OPEN;
    window.WebSocket.CLOSING = OriginalWebSocket.CLOSING;
    window.WebSocket.CLOSED = OriginalWebSocket.CLOSED;

    console.log('[WSInterceptor] WebSocket interceptor installed');

})();
