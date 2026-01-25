/**
 * Player Selection Observer
 *
 * Detects when user selects a different player in MA UI by watching
 * for clicks on player cards. Uses localStorage to persist selection
 * across reconnects.
 *
 * IMPORTANT: Only USER CLICKS change the selected player, not automatic
 * DOM updates (which happen when other players become active).
 */

(function() {
    'use strict';

    if (window._playerObserverInstalled) return;
    window._playerObserverInstalled = true;

    const STORAGE_KEY_ID = 'massdroid_selected_player_id';
    const STORAGE_KEY_NAME = 'massdroid_selected_player_name';

    // Track if we're handling a user click
    let userClickPending = false;

    function getPlayerName(card) {
        const el = card.querySelector('.v-list-item-title div');
        return el ? el.textContent.trim() : 'Unknown';
    }

    /**
     * Save user's explicit selection to localStorage
     */
    function saveSelection(playerId, playerName) {
        try {
            localStorage.setItem(STORAGE_KEY_ID, playerId);
            localStorage.setItem(STORAGE_KEY_NAME, playerName);
            console.log('[PlayerObserver] Saved selection:', playerName);
        } catch (e) {
            console.warn('[PlayerObserver] Failed to save selection:', e);
        }
    }

    /**
     * Restore selection from localStorage
     */
    function restoreSelection() {
        try {
            const savedId = localStorage.getItem(STORAGE_KEY_ID);
            const savedName = localStorage.getItem(STORAGE_KEY_NAME);
            if (savedId && window.MaWebSocket) {
                console.log('[PlayerObserver] Restoring saved selection:', savedName);
                window.MaWebSocket.setSelectedPlayer(savedId, savedName || 'Unknown');
                return true;
            }
        } catch (e) {
            console.warn('[PlayerObserver] Failed to restore selection:', e);
        }
        return false;
    }

    /**
     * Handle selection change - only if triggered by user click
     */
    function handleSelection() {
        const card = document.querySelector('.panel-item-selected');
        if (!card || !card.id) return;

        const playerId = card.id;
        const playerName = getPlayerName(card);

        // Only process if this came from a user click
        if (userClickPending) {
            userClickPending = false;

            console.log('[PlayerObserver] User selected:', playerName, playerId);

            // Save to localStorage for persistence
            saveSelection(playerId, playerName);

            // Update MaWebSocket
            if (window.MaWebSocket) {
                window.MaWebSocket.setSelectedPlayer(playerId, playerName);
            }
        }
        // Ignore automatic DOM updates (from PWA showing active player)
    }

    /**
     * Click listener - marks that a user click is pending
     */
    document.addEventListener('click', function(e) {
        const playerCard = e.target.closest('.panel-item');
        if (playerCard) {
            userClickPending = true;
            // Give DOM time to update the selected class
            setTimeout(handleSelection, 100);
        }
    }, true);

    /**
     * Initialize: restore saved selection or wait for user to select
     */
    function start() {
        console.log('[PlayerObserver] Starting...');

        // Try to restore saved selection
        // Small delay to ensure MaWebSocket is ready
        setTimeout(function() {
            if (!restoreSelection()) {
                console.log('[PlayerObserver] No saved selection, waiting for user to select a player');
            }
        }, 1000);

        console.log('[PlayerObserver] Watching for player clicks');
    }

    // Start when DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        setTimeout(start, 500);
    }

})();
