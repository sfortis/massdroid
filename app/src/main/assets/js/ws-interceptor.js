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

    // Track SendSpin connection status
    let _sendspinConnected = false;
    let _sendspinSocket = null;

    // Track stabilization for auto-resume
    let _lastConnectionChange = 0;
    let _connectionChangeCount = 0;
    let _stabilizationTimer = null;
    let _isStabilized = false;
    let _serverPlaybackState = null;  // Track what server thinks (from group/update)

    // Constants
    const STABILIZATION_DELAY_MS = 2500;  // Wait 2.5s after last connect/disconnect

    // Expose function to check SendSpin connection status
    window.isSendspinConnected = function() {
        return _sendspinConnected && _sendspinSocket && _sendspinSocket.readyState === WebSocket.OPEN;
    };

    // Expose function to check if player is stabilized and ready
    window.isSendspinStabilized = function() {
        return _isStabilized && _sendspinConnected;
    };

    // Expose function to get server's believed playback state
    window.getSendspinPlaybackState = function() {
        return _serverPlaybackState;
    };

    // Expose function to force close SendSpin WebSocket (for network change cleanup)
    window.closeSendspinSocket = function() {
        console.log('[SS-DEBUG] Force closing SendSpin socket');
        if (_sendspinSocket) {
            try {
                _sendspinSocket.close(1000, 'Network lost');
            } catch (e) {
                console.log('[SS-DEBUG] Error closing socket:', e);
            }
        }
        _sendspinConnected = false;
        _isStabilized = false;
        _serverPlaybackState = null;
        // Clear any pending stabilization timer
        if (_stabilizationTimer) {
            clearTimeout(_stabilizationTimer);
            _stabilizationTimer = null;
        }
    };

    // Internal function to handle connection state changes
    function onConnectionStateChange(event, details) {
        _lastConnectionChange = Date.now();
        _connectionChangeCount++;
        _isStabilized = false;

        console.log('[SS-DEBUG] Connection state change #' + _connectionChangeCount + ': ' + event);

        // Clear any pending stabilization timer
        if (_stabilizationTimer) {
            clearTimeout(_stabilizationTimer);
            _stabilizationTimer = null;
        }

        // Start new stabilization timer
        _stabilizationTimer = setTimeout(function() {
            const timeSinceChange = Date.now() - _lastConnectionChange;
            if (timeSinceChange >= STABILIZATION_DELAY_MS - 100) {
                _isStabilized = true;
                console.log('[SS-DEBUG] ====== CONNECTION STABILIZED ======');
                console.log('[SS-DEBUG] Total connection changes: ' + _connectionChangeCount);
                console.log('[SS-DEBUG] Server playback_state: ' + _serverPlaybackState);

                // Notify Android that we're stabilized
                if (window.AndroidMediaSession && window.AndroidMediaSession.onSendspinStabilized) {
                    window.AndroidMediaSession.onSendspinStabilized(
                        _sendspinConnected,
                        _serverPlaybackState || 'unknown'
                    );
                }
            }
        }, STABILIZATION_DELAY_MS);
    }

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

            // Also track API WebSocket for stabilization (in case no separate SendSpin WebSocket)
            ws.addEventListener('open', function() {
                console.log('[WSInterceptor] API WebSocket opened, starting stabilization timer');
                onConnectionStateChange('API_OPEN', {});
            });

            ws.addEventListener('close', function(event) {
                console.log('[WSInterceptor] API WebSocket closed:', event.code);
                onConnectionStateChange('API_CLOSE', { code: event.code });
            });
        }

        // ============================================
        // SENDSPIN WEBSOCKET
        // ============================================

        if (conn.isSendspin) {
            // CRITICAL: Reset flag when a NEW SendSpin WebSocket is created
            console.log('[SS-DEBUG] ====== NEW SENDSPIN WEBSOCKET ======');
            console.log('[SS-DEBUG] URL:', urlStr);
            _sendspinConnected = false;
            _sendspinSocket = ws;

            ws.addEventListener('open', function() {
                console.log('[SS-DEBUG] ====== WEBSOCKET OPENED ======');
                _sendspinConnected = true;
                _serverPlaybackState = null;  // Reset until we get group/update
                onConnectionStateChange('OPEN', {});
                if (window.AndroidMediaSession && window.AndroidMediaSession.onSendspinConnected) {
                    window.AndroidMediaSession.onSendspinConnected();
                }
            });

            ws.addEventListener('message', function(event) {
                try {
                    if (typeof event.data !== 'string') return;

                    const msg = JSON.parse(event.data);

                    // Verbose logging for all SendSpin messages
                    if (msg.type === 'server/hello') {
                        console.log('[SS-DEBUG] ====== SERVER HELLO ======');
                        console.log('[SS-DEBUG] Server ready, player_id available');
                    }

                    if (msg.type === 'auth_ok') {
                        console.log('[SS-DEBUG] ====== AUTH OK ======');
                    }

                    if (msg.type === 'group/update') {
                        console.log('[SS-DEBUG] ====== GROUP UPDATE ======');
                        console.log('[SS-DEBUG] playback_state:', msg.payload?.playback_state);
                        // Track server's believed state
                        if (msg.payload?.playback_state) {
                            _serverPlaybackState = msg.payload.playback_state;
                        }
                    }

                    if (msg.type === 'stream/start') {
                        console.log('[SS-DEBUG] ====== STREAM START - READY TO PLAY! ======');
                        console.log('[SS-DEBUG] codec:', msg.payload?.player?.codec);
                        if (window.AndroidMediaSession && window.AndroidMediaSession.onSendspinStreamStart) {
                            window.AndroidMediaSession.onSendspinStreamStart();
                        }
                    }

                    if (msg.type === 'stream/stop') {
                        console.log('[SS-DEBUG] ====== STREAM STOP ======');
                    }

                    // Log all messages except time sync
                    if (msg.type !== 'server/time') {
                        logToAndroid('SENDSPIN', msg.type || 'unknown', msg);
                    }

                } catch (e) { /* binary data */ }
            });

            ws.addEventListener('close', function(event) {
                console.log('[SS-DEBUG] ====== WEBSOCKET CLOSED ======');
                console.log('[SS-DEBUG] code:', event.code, 'reason:', event.reason || 'none');
                _sendspinConnected = false;
                _isStabilized = false;
                onConnectionStateChange('CLOSE', { code: event.code, reason: event.reason });
                if (window.AndroidMediaSession && window.AndroidMediaSession.logWsDisconnection) {
                    window.AndroidMediaSession.logWsDisconnection('SENDSPIN', event.code, event.reason || '');
                }
                if (window.AndroidMediaSession && window.AndroidMediaSession.onSendspinDisconnected) {
                    window.AndroidMediaSession.onSendspinDisconnected();
                }
            });

            ws.addEventListener('error', function(event) {
                console.log('[SS-DEBUG] ====== WEBSOCKET ERROR ======');
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

                // NOTE: Do NOT auto-select player from queue_updated events!
                // Player selection is ONLY done via player-selection-observer.js when
                // user explicitly selects a player in the MA UI. This prevents the
                // notification from jumping to other players when they become active.

                // Track currently playing player from player_updated events (for fallback only)
                if (conn.isMainApi && msg.event === 'player_updated' && msg.data) {
                    const player = msg.data;
                    if (player.playback_state === 'playing' && player.player_id) {
                        if (window.MaWebSocket) {
                            window.MaWebSocket.setCurrentlyPlaying(player.player_id);
                        }
                    }
                }

                // PHONE PLAYER AVAILABLE: Track when phone player becomes available via API WebSocket
                // Since there may not be a separate SendSpin WebSocket, we use API events to track availability
                if (conn.isMainApi && (msg.event === 'player_added' || msg.event === 'player_updated') && msg.data) {
                    const player = msg.data;
                    const phonePlayerId = localStorage.getItem('sendspin_webplayer_id');

                    // Mark as connected when phone player becomes available
                    if (phonePlayerId && player.player_id === phonePlayerId && player.available === true) {
                        console.log('[WSInterceptor] Phone player available via API: ' + player.player_id);
                        if (!_sendspinConnected) {
                            _sendspinConnected = true;
                            console.log('[WSInterceptor] Setting _sendspinConnected = true (from API)');
                        }
                        // Report phone player ID to Android for audio focus guard
                        if (window.AndroidMediaSession && window.AndroidMediaSession.setPhonePlayerId) {
                            window.AndroidMediaSession.setPhonePlayerId(player.player_id);
                        }
                    }

                    // Track when phone player becomes unavailable
                    if (phonePlayerId && player.player_id === phonePlayerId && player.available === false) {
                        console.log('[WSInterceptor] Phone player unavailable via API: ' + player.player_id);
                        if (_sendspinConnected) {
                            _sendspinConnected = false;
                            // Note: Don't call onSendspinDisconnected here - let the WebSocket close handle it
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
