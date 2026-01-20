/**
 * Music Assistant WebSocket Manager
 *
 * Provides a clean API for sending commands to MA server via WebSocket.
 * Works with any player type: Sonos, Chromecast, AirPlay, local SendSpin, etc.
 *
 * Usage:
 *   MaWebSocket.play()           // Play on active player
 *   MaWebSocket.pause()          // Pause active player
 *   MaWebSocket.seek(120)        // Seek to 120 seconds
 *   MaWebSocket.setVolume(50)    // Set volume to 50%
 *
 * @version 1.0.0
 * @author MassDroid
 */

(function() {
    'use strict';

    // Prevent double initialization
    if (window.MaWebSocket && window.MaWebSocket._initialized) {
        console.log('[MaWebSocket] Already initialized, skipping');
        return;
    }

    window.MaWebSocket = {
        _initialized: true,
        _ws: null,
        _url: null,
        _connected: false,
        _messageId: 0,
        _pendingResponses: {},
        _debug: true,  // Set to false for production
        _reconnectAttempts: 0,
        _maxReconnectAttempts: 5,

        // ============================================
        // LOGGING
        // ============================================

        log: function(level, msg, data) {
            if (!this._debug && level === 'debug') return;

            const prefix = '[MaWS]';
            const timestamp = new Date().toISOString().substr(11, 12);

            if (data !== undefined) {
                console[level](prefix, timestamp, msg, data);
            } else {
                console[level](prefix, timestamp, msg);
            }

            // Log to Android via bridge
            this._logToAndroid(level, msg, data);
        },

        _logToAndroid: function(level, msg, data) {
            if (window.AndroidMediaSession && window.AndroidMediaSession.logWsMessage) {
                try {
                    window.AndroidMediaSession.logWsMessage(
                        'MA_WS',
                        level.toUpperCase(),
                        JSON.stringify({ msg: msg, data: data || null })
                    );
                } catch (e) { /* ignore */ }
            }
        },

        // ============================================
        // CONNECTION MANAGEMENT
        // ============================================

        isConnected: function() {
            return this._ws && this._ws.readyState === WebSocket.OPEN;
        },

        getConnectionState: function() {
            if (!this._ws) return 'NO_SOCKET';
            switch (this._ws.readyState) {
                case WebSocket.CONNECTING: return 'CONNECTING';
                case WebSocket.OPEN: return 'OPEN';
                case WebSocket.CLOSING: return 'CLOSING';
                case WebSocket.CLOSED: return 'CLOSED';
                default: return 'UNKNOWN';
            }
        },

        // Called by WebSocket interceptor when MA API socket is created
        setSocket: function(ws, url) {
            this._ws = ws;
            this._url = url;
            this._connected = false;
            this._reconnectAttempts = 0;

            this.log('info', 'Socket registered', { url: url });

            // Setup event handlers
            ws.addEventListener('open', () => {
                this._connected = true;
                this._reconnectAttempts = 0;
                this.log('info', 'Connected to MA API');
                this._notifyAndroid('connected');
            });

            ws.addEventListener('close', (event) => {
                this._connected = false;
                this.log('warn', 'Disconnected from MA API', {
                    code: event.code,
                    reason: event.reason || 'Unknown'
                });
                this._notifyAndroid('disconnected', { code: event.code });
            });

            ws.addEventListener('error', (event) => {
                this.log('error', 'WebSocket error occurred');
            });

            ws.addEventListener('message', (event) => {
                this._handleMessage(event);
            });
        },

        _notifyAndroid: function(event, data) {
            if (window.AndroidMediaSession) {
                if (event === 'disconnected' && window.AndroidMediaSession.logWsDisconnection) {
                    window.AndroidMediaSession.logWsDisconnection('API', data?.code || 0, '');
                }
            }
        },

        // ============================================
        // MESSAGE HANDLING
        // ============================================

        _handleMessage: function(event) {
            try {
                if (typeof event.data !== 'string') return;

                const msg = JSON.parse(event.data);

                // Handle command responses
                if (msg.message_id && this._pendingResponses[msg.message_id]) {
                    const pending = this._pendingResponses[msg.message_id];
                    delete this._pendingResponses[msg.message_id];

                    if (msg.error) {
                        this.log('error', 'Command failed', { id: msg.message_id, error: msg.error });
                        if (pending.reject) pending.reject(msg.error);
                    } else {
                        this.log('debug', 'Command succeeded', { id: msg.message_id });
                        if (pending.resolve) pending.resolve(msg.result);
                    }
                }

                // Handle player_updated events - forward to Android
                if (msg.event === 'player_updated' && msg.data) {
                    this._handlePlayerUpdate(msg.data);
                }

            } catch (e) {
                // Binary data or parse error - ignore
            }
        },

        _handlePlayerUpdate: function(player) {
            // Only process updates for the SELECTED player
            if (!this._selectedPlayerId || player.player_id !== this._selectedPlayerId) {
                return;
            }

            this.log('debug', 'Processing update for selected player:', player.player_id);

            const isPlaying = player.playback_state === 'playing';
            const media = player.current_media;

            // Update playback state
            if (window.AndroidMediaSession && window.AndroidMediaSession.updatePlaybackState) {
                const positionMs = Math.round((media?.elapsed_time || 0) * 1000);
                window.AndroidMediaSession.updatePlaybackState(
                    isPlaying ? 'playing' : 'paused',
                    positionMs
                );
            }

            // Update metadata if we have current_media
            if (media && window.AndroidMediaSession && window.AndroidMediaSession.updateMetadata) {
                const title = media.title || 'Unknown';
                const artist = media.artist || '';
                const album = media.album || '';
                // Try different field names for artwork URL
                const artworkUrl = media.image_url || media.image || media.artwork || '';
                const durationMs = Math.round((media.duration || 0) * 1000);

                this.log('debug', 'Media fields:', Object.keys(media).join(', '));
                this.log('debug', 'Artwork URL field:', artworkUrl ? 'found' : 'empty');

                window.AndroidMediaSession.updateMetadata(title, artist, album, artworkUrl, durationMs);

                // Fetch artwork
                if (artworkUrl) {
                    this._fetchArtwork(artworkUrl);
                }
            }
        },

        _fetchArtwork: function(url) {
            if (!url) return;
            const self = this;

            // Rewrite URL to match configured origin (handles reverse proxy scenarios)
            let fetchUrl = url;
            try {
                const imgUrl = new URL(url);
                const configuredOrigin = window.location.origin;
                if (imgUrl.origin !== configuredOrigin) {
                    fetchUrl = configuredOrigin + imgUrl.pathname + imgUrl.search;
                    self.log('debug', 'Rewrote image URL to configured origin');
                }
            } catch (e) { /* use original URL */ }

            fetch(fetchUrl)
                .then(response => {
                    if (!response.ok) throw new Error('HTTP ' + response.status);
                    return response.blob();
                })
                .then(blob => {
                    const reader = new FileReader();
                    reader.onloadend = () => {
                        const base64 = reader.result.split(',')[1];
                        if (base64 && window.AndroidMediaSession && window.AndroidMediaSession.setArtworkBase64) {
                            window.AndroidMediaSession.setArtworkBase64(base64);
                            self.log('info', 'Artwork sent to Android, size:', base64.length);
                        }
                    };
                    reader.readAsDataURL(blob);
                })
                .catch(err => self.log('warn', 'Artwork fetch failed:', err.message));
        },

        // ============================================
        // PLAYER ID MANAGEMENT
        // ============================================

        // Track currently playing player (updated via player_updated events)
        _currentlyPlayingId: null,
        // Track selected player (from queue_updated with active: true)
        _selectedPlayerId: null,
        _selectedPlayerName: null,

        /**
         * Set the selected player (called when user selects a player in MA UI)
         * This is detected from queue_updated events with active: true
         */
        setSelectedPlayer: function(playerId, playerName) {
            if (playerId !== this._selectedPlayerId) {
                this.log('info', 'Selected player changed:', {
                    from: this._selectedPlayerId,
                    to: playerId,
                    name: playerName
                });
                this._selectedPlayerId = playerId;
                this._selectedPlayerName = playerName;

                // Notify Android of player selection
                if (window.AndroidMediaSession && window.AndroidMediaSession.onPlayerSelected) {
                    window.AndroidMediaSession.onPlayerSelected(playerId, playerName);
                }

                // Fetch current state of newly selected player
                this._refreshSelectedPlayerState();
            }
        },

        /**
         * Fetch and forward the current state of the selected player
         */
        _refreshSelectedPlayerState: function() {
            if (!this._selectedPlayerId || !this.isConnected()) return;

            const self = this;
            this.sendCommand('players/all', {})
                .then(function(players) {
                    if (!Array.isArray(players)) return;

                    const player = players.find(p => p.player_id === self._selectedPlayerId);
                    if (player) {
                        self.log('info', 'Refreshing state for:', player.display_name);
                        self._handlePlayerUpdate(player);
                    }
                })
                .catch(function(err) {
                    self.log('warn', 'Failed to refresh player state:', err);
                });
        },

        /**
         * Set the currently playing player (called from ws-interceptor)
         */
        setCurrentlyPlaying: function(playerId) {
            if (playerId !== this._currentlyPlayingId) {
                this.log('info', 'Currently playing changed:', {
                    from: this._currentlyPlayingId,
                    to: playerId
                });
                this._currentlyPlayingId = playerId;
            }
        },

        /**
         * Get the active player ID
         * Priority: 1) Selected player (from UI), 2) Currently playing, 3) localStorage
         */
        getActivePlayerId: function() {
            // First priority: user's selected player from MA UI
            if (this._selectedPlayerId) {
                this.log('debug', 'Using selected player:', this._selectedPlayerId);
                return this._selectedPlayerId;
            }

            // Second priority: currently playing player
            if (this._currentlyPlayingId) {
                this.log('debug', 'Using currently playing player:', this._currentlyPlayingId);
                return this._currentlyPlayingId;
            }

            // Fallback to localStorage
            const keys = [
                'mass.activePlayerId',
                'activePlayerId',
                'mass.preferences.activePlayerId'
            ];

            for (const key of keys) {
                const val = localStorage.getItem(key);
                if (val) {
                    this.log('debug', 'Using localStorage player:', { key: key, value: val });
                    return val;
                }
            }

            return null;
        },

        // ============================================
        // COMMAND SENDING
        // ============================================

        nextMessageId: function() {
            return 'android_' + Date.now() + '_' + (++this._messageId);
        },

        /**
         * Send a command to the MA server
         * @param {string} command - The command path (e.g., 'players/cmd/play')
         * @param {object} args - Command arguments
         * @returns {Promise} - Resolves with result or rejects with error
         */
        sendCommand: function(command, args) {
            return new Promise((resolve, reject) => {
                if (!this.isConnected()) {
                    this.log('warn', 'Not connected, cannot send:', command);
                    reject('Not connected');
                    return;
                }

                const msgId = this.nextMessageId();
                const message = {
                    message_id: msgId,
                    command: command,
                    args: args || {}
                };

                this.log('info', 'Sending:', { command: command, args: args });

                // Store for response handling
                this._pendingResponses[msgId] = {
                    resolve: resolve,
                    reject: reject,
                    timestamp: Date.now()
                };

                // Cleanup after timeout
                setTimeout(() => {
                    if (this._pendingResponses[msgId]) {
                        delete this._pendingResponses[msgId];
                        // Don't reject on timeout - fire-and-forget is OK
                    }
                }, 10000);

                try {
                    this._ws.send(JSON.stringify(message));
                } catch (e) {
                    delete this._pendingResponses[msgId];
                    this.log('error', 'Send failed:', e.message);
                    reject(e.message);
                }
            });
        },

        /**
         * Send a player command via WebSocket
         * Works for ALL players (Sonos, Chromecast, SendSpin, etc.)
         *
         * @param {string} action - The action (play, pause, next, etc.)
         * @param {string} playerId - Optional player ID (defaults to active player)
         */
        playerCommand: function(action, playerId) {
            const pid = playerId || this.getActivePlayerId();
            if (!pid) {
                this.log('warn', 'No active player for command:', action);
                return Promise.reject('No active player');
            }

            this.log('info', 'Player command via WebSocket:', { action: action, player_id: pid });
            return this.sendCommand('players/cmd/' + action, { player_id: pid });
        },

        // ============================================
        // PLAYER CONTROL SHORTCUTS
        // ============================================

        play: function(playerId) {
            return this.playerCommand('play', playerId);
        },

        pause: function(playerId) {
            return this.playerCommand('pause', playerId);
        },

        playPause: function(playerId) {
            return this.playerCommand('play_pause', playerId);
        },

        stop: function(playerId) {
            return this.playerCommand('stop', playerId);
        },

        next: function(playerId) {
            return this.playerCommand('next', playerId);
        },

        previous: function(playerId) {
            return this.playerCommand('previous', playerId);
        },

        /**
         * Seek to position via WebSocket
         * @param {number} positionSeconds - Position in seconds
         * @param {string} playerId - Optional player ID
         */
        seek: function(positionSeconds, playerId) {
            const pid = playerId || this.getActivePlayerId();
            if (!pid) return Promise.reject('No active player');

            return this.sendCommand('players/cmd/seek', {
                player_id: pid,
                position: positionSeconds
            });
        },

        /**
         * Set volume
         * @param {number} volume - Volume level (0-100)
         * @param {string} playerId - Optional player ID
         */
        setVolume: function(volume, playerId) {
            const pid = playerId || this.getActivePlayerId();
            if (!pid) return Promise.reject('No active player');

            return this.sendCommand('players/cmd/volume_set', {
                player_id: pid,
                volume_level: volume
            });
        },

        /**
         * Power on/off player
         * @param {boolean} powered - Power state
         * @param {string} playerId - Optional player ID
         */
        setPower: function(powered, playerId) {
            const pid = playerId || this.getActivePlayerId();
            if (!pid) return Promise.reject('No active player');

            return this.sendCommand('players/cmd/power', {
                player_id: pid,
                powered: powered
            });
        },

        // ============================================
        // DEBUG / DIAGNOSTICS
        // ============================================

        getStatus: function() {
            return {
                connected: this.isConnected(),
                state: this.getConnectionState(),
                url: this._url,
                activePlayer: this.getActivePlayerId(),
                pendingCommands: Object.keys(this._pendingResponses).length
            };
        },

        dumpStatus: function() {
            const status = this.getStatus();
            this.log('info', 'Status dump:', status);
            return status;
        },

        // ============================================
        // DISCOVERY / DEBUG COMMANDS
        // ============================================

        /**
         * Get all players from MA server
         * Returns a Promise that resolves with player list
         */
        getPlayers: function() {
            return this.sendCommand('players/all', {});
        },

        /**
         * Get all players and store them locally
         */
        fetchPlayers: function() {
            const self = this;
            return this.sendCommand('players/all', {})
                .then(players => {
                    if (Array.isArray(players)) {
                        self._players = {};
                        players.forEach(p => {
                            if (p.player_id) {
                                self._players[p.player_id] = p;
                            }
                        });
                        self.log('info', 'Fetched ' + players.length + ' players');

                        // Find currently playing
                        const playing = players.find(p => p.playback_state === 'playing');
                        if (playing) {
                            self.setCurrentlyPlaying(playing.player_id);
                        }
                    }
                    return players;
                });
        },

        /**
         * List players in console (debug helper)
         */
        listPlayers: function() {
            const self = this;
            this.getPlayers().then(players => {
                console.log('[MaWS] === PLAYERS ===');
                players.forEach(p => {
                    const name = p.display_name || p.name || 'Unknown';
                    const state = p.playback_state || '?';
                    console.log('[MaWS]   [' + state + '] ' + name + ' - ' + p.player_id);
                });
            }).catch(e => console.error('[MaWS] Failed to get players:', e));
        }
    };

    console.log('[MaWebSocket] Module loaded');

})();
