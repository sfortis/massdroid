/**
 * Player Selection Observer
 *
 * Detects when user selects a different player in MA UI by watching
 * for the 'panel-item-selected' class on player cards.
 */

(function() {
    'use strict';

    if (window._playerObserverInstalled) return;
    window._playerObserverInstalled = true;

    let currentSelectedId = null;

    function getPlayerName(card) {
        const el = card.querySelector('.v-list-item-title div');
        return el ? el.textContent.trim() : 'Unknown';
    }

    function checkSelected() {
        const card = document.querySelector('.panel-item-selected');
        if (card && card.id && card.id !== currentSelectedId) {
            currentSelectedId = card.id;
            const name = getPlayerName(card);

            console.log('[PlayerObserver] Selected:', name, card.id);

            // MaWebSocket.setSelectedPlayer handles Android notification
            if (window.MaWebSocket) {
                window.MaWebSocket.setSelectedPlayer(card.id, name);
            }
        }
    }

    // MutationObserver for class changes
    const observer = new MutationObserver(function(mutations) {
        for (const m of mutations) {
            if (m.type === 'attributes' && m.attributeName === 'class') {
                if (m.target.classList?.contains('panel-item-selected')) {
                    checkSelected();
                    return;
                }
            }
        }
    });

    function start() {
        checkSelected();
        observer.observe(document.body, {
            attributes: true,
            attributeFilter: ['class'],
            subtree: true
        });
        console.log('[PlayerObserver] Watching for player selection');
    }

    // Click listener as backup
    document.addEventListener('click', function(e) {
        if (e.target.closest('.panel-item')) {
            setTimeout(checkSelected, 100);
        }
    }, true);

    // Start when DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        setTimeout(start, 500);
    }

})();
