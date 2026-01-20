/**
 * MediaSession Polyfill & Interceptor
 *
 * Intercepts navigator.mediaSession API calls to:
 * 1. Forward metadata to Android (title, artist, album, artwork)
 * 2. Forward playback state changes to Android
 * 3. Capture action handlers for local player control
 *
 * @version 1.0.0
 * @author MassDroid
 */

(function() {
    'use strict';

    // Prevent double initialization
    if (window._mediaSessionPolyfillInstalled) {
        console.log('[MediaSession] Polyfill already installed, skipping');
        return;
    }
    window._mediaSessionPolyfillInstalled = true;

    console.log('[MediaSession] Installing polyfill...');

    // ============================================
    // HELPERS
    // ============================================

    // Debounce helper to prevent notification flickering
    function debounce(func, wait) {
        let timeout;
        return function executedFunction(...args) {
            const later = () => {
                clearTimeout(timeout);
                func(...args);
            };
            clearTimeout(timeout);
            timeout = setTimeout(later, wait);
        };
    }

    // Store current position state
    window._mediaPositionState = {
        duration: 0,
        position: 0,
        playbackRate: 1
    };

    /**
     * Check if this player (phone/SendSpin) is the selected player.
     * If another player is selected, we should NOT forward our updates.
     */
    function isThisPlayerSelected() {
        if (!window.MaWebSocket || !window.MaWebSocket._selectedPlayerId) {
            // No selection yet, allow updates
            return true;
        }

        const selectedId = window.MaWebSocket._selectedPlayerId;
        const phoneId = localStorage.getItem('sendspin_webplayer_id');

        // Allow if phone is selected OR if we can't determine phone ID
        return !phoneId || selectedId === phoneId;
    }

    // ============================================
    // MEDIAMETADATA CLASS
    // ============================================

    if (typeof window.MediaMetadata === 'undefined') {
        console.log('[MediaSession] Creating MediaMetadata class');
        window.MediaMetadata = class MediaMetadata {
            constructor(metadata = {}) {
                this.title = metadata.title || '';
                this.artist = metadata.artist || '';
                this.album = metadata.album || '';
                this.artwork = metadata.artwork || [];
            }
        };
    }

    // ============================================
    // MEDIASESSION POLYFILL
    // ============================================

    if (typeof navigator.mediaSession === 'undefined') {
        console.log('[MediaSession] Creating mediaSession polyfill');
        navigator.mediaSession = {
            metadata: null,
            playbackState: 'none',
            setActionHandler: function(action, handler) {},
            setPositionState: function(state) {}
        };
    }

    // ============================================
    // ARTWORK FETCHER
    // ============================================

    function fetchArtworkBase64(artworkUrl) {
        if (!artworkUrl || !window.AndroidMediaSession) return;

        fetch(artworkUrl)
            .then(response => {
                if (!response.ok) throw new Error('HTTP ' + response.status);
                return response.blob();
            })
            .then(blob => {
                const reader = new FileReader();
                reader.onloadend = () => {
                    const base64 = reader.result.split(',')[1];
                    if (base64 && window.AndroidMediaSession.setArtworkBase64) {
                        window.AndroidMediaSession.setArtworkBase64(base64);
                    }
                };
                reader.readAsDataURL(blob);
            })
            .catch(err => {
                console.warn('[MediaSession] Artwork fetch failed:', err.message);
            });
    }

    // ============================================
    // METADATA INTERCEPTION
    // ============================================

    const originalMetadataDescriptor = Object.getOwnPropertyDescriptor(navigator.mediaSession, 'metadata') || {};
    const originalMetadataGetter = originalMetadataDescriptor.get || function() { return this._metadata; };
    const originalMetadataSetter = originalMetadataDescriptor.set || function(value) { this._metadata = value; };

    // Debounced metadata update
    const debouncedMetadataUpdate = debounce((title, artist, album, artwork, duration) => {
        // Only forward if this player (phone) is selected
        if (!isThisPlayerSelected()) {
            console.log('[MediaSession] Skipping metadata - different player selected');
            return;
        }

        if (window.AndroidMediaSession && window.AndroidMediaSession.updateMetadata) {
            window.AndroidMediaSession.updateMetadata(title, artist, album, artwork, duration);

            // Fetch artwork via JavaScript (has cookies/auth)
            if (artwork) {
                fetchArtworkBase64(artwork);
            }
        }
    }, 300);

    Object.defineProperty(navigator.mediaSession, 'metadata', {
        get: function() {
            return originalMetadataGetter.call(this);
        },
        set: function(value) {
            if (value) {
                // Get best quality artwork
                const artwork = value.artwork?.[2]?.src
                    || value.artwork?.[1]?.src
                    || value.artwork?.[0]?.src
                    || '';
                const duration = window._mediaPositionState.duration || 0;

                debouncedMetadataUpdate(
                    value.title || 'Unknown',
                    value.artist || 'Unknown',
                    value.album || '',
                    artwork,
                    Math.round(duration * 1000)
                );
            }
            originalMetadataSetter.call(this, value);
        },
        configurable: true,
        enumerable: true
    });

    // ============================================
    // PLAYBACK STATE INTERCEPTION
    // ============================================

    const originalPlaybackStateDescriptor = Object.getOwnPropertyDescriptor(navigator.mediaSession, 'playbackState') || {};
    const originalPlaybackStateGetter = originalPlaybackStateDescriptor.get || function() { return this._playbackState || 'none'; };
    const originalPlaybackStateSetter = originalPlaybackStateDescriptor.set || function(value) { this._playbackState = value; };

    // Debounced playback state update
    const debouncedPlaybackUpdate = debounce((state, position) => {
        // Only forward if this player (phone) is selected
        if (!isThisPlayerSelected()) {
            console.log('[MediaSession] Skipping playback state - different player selected');
            return;
        }

        if (window.AndroidMediaSession && window.AndroidMediaSession.updatePlaybackState) {
            window.AndroidMediaSession.updatePlaybackState(state, position);
        }
    }, 200);

    Object.defineProperty(navigator.mediaSession, 'playbackState', {
        get: function() {
            return originalPlaybackStateGetter.call(this);
        },
        set: function(value) {
            const position = Math.round((window._mediaPositionState.position || 0) * 1000);
            debouncedPlaybackUpdate(value, position);
            originalPlaybackStateSetter.call(this, value);
        },
        configurable: true,
        enumerable: true
    });

    // ============================================
    // POSITION STATE INTERCEPTION
    // ============================================

    const originalSetPositionState = navigator.mediaSession.setPositionState?.bind(navigator.mediaSession);

    navigator.mediaSession.setPositionState = function(state) {
        if (state) {
            window._mediaPositionState = {
                duration: state.duration || 0,
                position: state.position || 0,
                playbackRate: state.playbackRate || 1
            };

            // Update Android with position info
            if (window.AndroidMediaSession && window.AndroidMediaSession.updatePositionState) {
                const durationMs = Math.round((state.duration || 0) * 1000);
                const positionMs = Math.round((state.position || 0) * 1000);
                window.AndroidMediaSession.updatePositionState(durationMs, positionMs, state.playbackRate || 1);
            }
        }
        if (originalSetPositionState) {
            return originalSetPositionState(state);
        }
    };

    // ============================================
    // ACTION HANDLER INTERCEPTION
    // ============================================

    const originalSetActionHandler = navigator.mediaSession.setActionHandler?.bind(navigator.mediaSession);
    const actionHandlers = {};

    navigator.mediaSession.setActionHandler = function(action, handler) {
        actionHandlers[action] = handler;
        if (originalSetActionHandler) {
            return originalSetActionHandler(action, handler);
        }
    };

    // ============================================
    // LOCAL MUSIC PLAYER INTERFACE
    // ============================================

    // Provides fallback control for local SendSpin player
    window.musicPlayer = {
        play: function() {
            const handler = actionHandlers['play'];
            if (handler) handler();
        },
        pause: function() {
            const handler = actionHandlers['pause'];
            if (handler) handler();
        },
        next: function() {
            const handler = actionHandlers['nexttrack'];
            if (handler) handler();
        },
        previous: function() {
            const handler = actionHandlers['previoustrack'];
            if (handler) handler();
        },
        seekTo: function(positionSec) {
            const handler = actionHandlers['seekto'];
            if (handler) {
                handler({ seekTime: positionSec });
            }
        },
        _getHandlers: function() {
            return Object.keys(actionHandlers);
        }
    };

    console.log('[MediaSession] Polyfill installed successfully');

})();
