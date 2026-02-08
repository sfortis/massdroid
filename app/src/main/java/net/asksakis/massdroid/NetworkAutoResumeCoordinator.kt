package net.asksakis.massdroid

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.widget.Toast

/**
 * Coordinates network loss/reconnect auto-resume flows for SendSpin playback.
 * Keeps state and retry logic out of MainActivity to reduce coupling.
 */
class NetworkAutoResumeCoordinator(
    private val handler: Handler,
    private val host: Host
) {
    interface Host {
        fun evaluateJavascript(script: String, callback: (String) -> Unit)
        fun showToast(message: String, duration: Int = Toast.LENGTH_SHORT)
        fun reloadWebViewForAutoResumeRetry(retryCount: Int, maxRetries: Int)
        fun forceCloseSocketsForReconnect()
        fun getCurrentTrackTitle(): String
        fun getPhonePlayerId(): String?
        fun isCurrentlyPlaying(): Boolean
        fun getCurrentPositionMs(): Long
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val AUTO_RESUME_FALLBACK_DELAY_MS = 5000L
        private const val AUTO_RESUME_CONNECTION_WAIT_ATTEMPTS = 45
        private const val AUTO_RESUME_RECOVERY_WINDOW_MS = 120000L
        private const val MAX_AUTO_RESUME_RETRIES = 5
        private const val SOFT_STOP_PLAY_RETRY_LIMIT = 2
        private const val AUTO_RESUME_TIMEOUT_MS = 5000L
        private const val SENDSPIN_DISCONNECT_RETRY_DELAY_MS = 2000L
        private const val RECENT_PLAYING_GRACE_MS = 10000L
        private const val PLAYING_CONFIRM_FALLBACK_MS = 8000L
    }

    private data class ActiveFlowState(
        val id: String,
        var waitingForStreamStart: Boolean = true,
        var commandInFlight: Boolean = false,
        var retryCount: Int = 0,
        var streamStartSeen: Boolean = false,
        var pendingPlayingConfirmAtMs: Long = 0L,
        var pendingPlayingConfirmPositionMs: Long = -1L
    )

    private data class PlayerCommandTarget(
        val escapedPhonePlayerId: String?,
        val hasPhonePlayer: Boolean,
        val logTarget: String
    )

    private var autoResumeFlowCounter = 0L
    private var activeFlow: ActiveFlowState? = null
    private var pendingFallbackAutoResumeRunnable: Runnable? = null
    private var autoResumeRecoveryDeadlineMs: Long = 0L
    private var autoResumeTimeoutRunnable: Runnable? = null
    private var lastPlayingConfirmedAtMs: Long = 0L

    private var wasPlayingBeforeNetworkLossFlag = false
    private var savedPositionMs: Long = 0
    private var savedDurationMs: Long = 0
    private var savedTrackTitle: String = ""

    val wasPlayingBeforeNetworkLoss: Boolean
        get() = wasPlayingBeforeNetworkLossFlag

    val waitingForStreamStart: Boolean
        get() = activeFlow?.waitingForStreamStart == true

    val activeFlowId: String?
        get() = activeFlow?.id

    fun onPlaybackStateUpdate(isPlaying: Boolean) {
        if (isPlaying) {
            lastPlayingConfirmedAtMs = SystemClock.elapsedRealtime()
        } else if (activeFlow?.waitingForStreamStart == true) {
            // A paused/none transition during recovery invalidates any tentative playing confirmation.
            resetPendingPlayingConfirmation()
        }
    }

    fun isRecoveryWindowActive(): Boolean {
        if (!wasPlayingBeforeNetworkLossFlag) return false
        if (autoResumeRecoveryDeadlineMs <= 0L) return false
        return SystemClock.elapsedRealtime() < autoResumeRecoveryDeadlineMs
    }

    fun onNetworkLost(
        isCurrentlyPlaying: Boolean,
        autoResumeEnabled: Boolean,
        currentPositionMs: Long,
        currentDurationMs: Long,
        currentTrackTitle: String
    ) {
        val recentlyPlaying = wasRecentlyPlaying()
        val keepExistingRecoveryState = autoResumeEnabled && isRecoveryWindowActive()
        resetActiveFlowState()

        if ((isCurrentlyPlaying || recentlyPlaying) && autoResumeEnabled) {
            wasPlayingBeforeNetworkLossFlag = true
            autoResumeRecoveryDeadlineMs = SystemClock.elapsedRealtime() + AUTO_RESUME_RECOVERY_WINDOW_MS
            savedPositionMs = currentPositionMs
            savedDurationMs = currentDurationMs
            savedTrackTitle = currentTrackTitle
            Log.i(
                TAG,
                "Saved state for auto-resume: position=${savedPositionMs}ms, duration=${savedDurationMs}ms, track=$savedTrackTitle"
            )
            if (!isCurrentlyPlaying && recentlyPlaying) {
                Log.i(TAG, "Auto-resume armed via recent-playing grace window")
            }
        } else if (keepExistingRecoveryState) {
            // Network can flap (lost/available/lost) while playback state already flipped to paused.
            // Keep prior recovery snapshot so auto-resume can still complete after connectivity stabilizes.
            Log.i(TAG, "Keeping existing auto-resume recovery snapshot across repeated network loss")
        } else {
            clearRecoveryState()
        }

    }

    fun onNetworkAvailable(autoResumeEnabled: Boolean) {
        if (autoResumeEnabled && isRecoveryWindowActive()) {
            Log.i(TAG, "Scheduling fallback auto-resume check in ${AUTO_RESUME_FALLBACK_DELAY_MS / 1000}s...")
            scheduleFallbackAutoResume("network_restore_fallback", AUTO_RESUME_FALLBACK_DELAY_MS)
        }
    }

    fun startOrContinueFromStabilized() {
        startOrContinueAutoResumeFlow(
            reason = "sendspin_stabilized",
            waitForSendspinReady = false
        )
    }

    fun onStreamStart(): String? {
        activeFlow?.streamStartSeen = true
        return markAutoResumeSuccess()
    }

    fun onPlaybackConfirmedPlaying(positionMs: Long): String? {
        val flow = activeFlow ?: return null
        if (!flow.waitingForStreamStart) return null
        val flowId = flow.id
        val now = SystemClock.elapsedRealtime()

        if (flow.pendingPlayingConfirmAtMs <= 0L) {
            flow.pendingPlayingConfirmAtMs = now
            flow.pendingPlayingConfirmPositionMs = positionMs
            Log.i(
                TAG,
                "[NET_RETRY][$flowId] playback reported playing at ${positionMs}ms, waiting for position progress"
            )
            return null
        }

        val baseline = flow.pendingPlayingConfirmPositionMs
        val progressed = positionMs >= baseline + 500L
        if (!progressed) {
            val elapsed = now - flow.pendingPlayingConfirmAtMs
            Log.i(
                TAG,
                "[NET_RETRY][$flowId] playing not yet progressed (pos=${positionMs}ms baseline=${baseline}ms elapsed=${elapsed}ms)"
            )
            return null
        }

        Log.i(
            TAG,
            "[NET_RETRY][$flowId] playback position progressed (${baseline}ms -> ${positionMs}ms)"
        )

        if (flow.streamStartSeen) {
            return markAutoResumeSuccess()
        }

        val elapsedSinceFirstPlaying = now - flow.pendingPlayingConfirmAtMs
        if (elapsedSinceFirstPlaying >= PLAYING_CONFIRM_FALLBACK_MS) {
            Log.w(
                TAG,
                "[NET_RETRY][$flowId] no stream/start seen for ${elapsedSinceFirstPlaying}ms, confirming via fallback"
            )
            return markAutoResumeSuccess()
        }

        Log.i(
            TAG,
            "[NET_RETRY][$flowId] playback progressed but waiting for stream/start (${elapsedSinceFirstPlaying}ms elapsed)"
        )
        return null
    }

    private fun markAutoResumeSuccess(): String? {
        val flow = activeFlow ?: return null
        if (!flow.waitingForStreamStart) return null

        val flowId = flow.id
        flow.waitingForStreamStart = false
        resetPendingPlayingConfirmation()
        clearRecoveryState()
        flow.retryCount = 0
        flow.commandInFlight = false
        activeFlow = null
        clearAutoResumeTimeout()
        cancelPendingFallbackAutoResume()
        host.showToast("Playback resumed")
        return flowId
    }

    fun onPlayFailed() {
        activeFlow?.waitingForStreamStart = false
        resetPendingPlayingConfirmation()
        clearRecoveryState()
        activeFlow?.retryCount = 0
        activeFlow?.commandInFlight = false
        activeFlow = null
        clearAutoResumeTimeout()
        host.showToast("Could not auto-resume")
    }

    /**
     * Handles abrupt SendSpin socket disconnects during the recovery window.
     * Returns true when a fast retry was armed.
     */
    fun onSendspinDisconnected(): Boolean {
        if (!isRecoveryWindowActive()) return false

        val flowId = activeFlow?.id
        if (flowId != null) {
            Log.w(TAG, "[NET_RETRY][$flowId] sendspin disconnected during recovery, re-arming retry")
        } else {
            Log.w(TAG, "[NET_RETRY] sendspin disconnected during recovery window, re-arming retry")
        }

        clearFlowIfMatches(flowId ?: "")
        clearAutoResumeTimeout()
        resetPendingPlayingConfirmation()
        scheduleFallbackAutoResume("sendspin_disconnected", delayMs = SENDSPIN_DISCONNECT_RETRY_DELAY_MS)
        return true
    }

    fun clearRecoveryState() {
        wasPlayingBeforeNetworkLossFlag = false
        autoResumeRecoveryDeadlineMs = 0L
    }

    fun onBluetoothDisconnect() {
        clearRecoveryState()
    }

    fun onDestroy() {
        resetActiveFlowState()
        clearRecoveryState()
    }

    private fun triggerFallbackAutoResume(reason: String) {
        startOrContinueAutoResumeFlow(reason, waitForSendspinReady = true)
    }

    private fun startOrContinueAutoResumeFlow(reason: String, waitForSendspinReady: Boolean) {
        if (!isRecoveryWindowActive()) {
            Log.i(TAG, "[NET_RETRY] skip start reason=$reason (recovery window inactive)")
            clearRecoveryState()
            return
        }

        val existingFlow = activeFlow
        val existingFlowId = existingFlow?.id
        if (existingFlow?.waitingForStreamStart == true && existingFlowId != null) {
            if (waitForSendspinReady) {
                Log.i(TAG, "[NET_RETRY][$existingFlowId] already in progress, skip fallback reason=$reason")
                return
            }

            Log.i(TAG, "[NET_RETRY][$existingFlowId] continuing flow from stabilized event reason=$reason")
            performAutoResumeStopPlay(existingFlowId)
            return
        }

        val prefix = if (waitForSendspinReady) "fallback" else "stabilized"
        val newFlow = beginNewFlow(prefix)
        val flowId = newFlow.id
        newFlow.retryCount = 0
        newFlow.commandInFlight = false
        newFlow.waitingForStreamStart = true
        resetPendingPlayingConfirmation()
        clearAutoResumeTimeout()
        cancelPendingFallbackAutoResume()

        Log.i(
            TAG,
            "[NET_RETRY][$flowId] start reason=$reason waitForSendspinReady=$waitForSendspinReady"
        )

        val message = if (waitForSendspinReady) "Waiting for connection..." else "Auto-resuming..."
        host.showToast(message)

        if (waitForSendspinReady) {
            waitForSendspinAndResume(flowId, 0)
        } else {
            performAutoResumeStopPlay(flowId)
        }
    }

    private fun waitForSendspinAndResume(flowId: String, attempts: Int) {
        val flow = activeFlow
        if (flow?.id != flowId) {
            Log.d(TAG, "[NET_RETRY][$flowId] stale flow, aborting at attempt=$attempts")
            return
        }

        if (!flow.waitingForStreamStart) {
            Log.d(TAG, "[NET_RETRY][$flowId] waitingForStreamStart=false, aborting")
            return
        }

        if (flow.commandInFlight) {
            Log.d(TAG, "[NET_RETRY][$flowId] stop/play already in progress, stop connection polling")
            return
        }

        host.evaluateJavascript(
            """
            (function() {
                const ssConnected = window.isSendspinConnected ? window.isSendspinConnected() : false;
                const ssStabilized = window.isSendspinStabilized ? window.isSendspinStabilized() : false;
                const maConnected = window.MaWebSocket && window.MaWebSocket.isConnected();
                return 'ssConnected=' + ssConnected + ';ssStabilized=' + ssStabilized + ';maConnected=' + maConnected;
            })();
            """.trimIndent()
        ) { result ->
            val currentFlow = activeFlow
            if (currentFlow?.id != flowId || !currentFlow.waitingForStreamStart) {
                Log.d(TAG, "[NET_RETRY][$flowId] stale callback for check#$attempts, ignoring")
                return@evaluateJavascript
            }

            if (currentFlow.commandInFlight) {
                Log.d(TAG, "[NET_RETRY][$flowId] callback check#$attempts ignored (commands in flight)")
                return@evaluateJavascript
            }

            val status = parseConnectionStatus(result)
            Log.i(TAG, "[NET_RETRY][$flowId] check#$attempts status=${status.raw}")

            val ssConnected = status.ssConnected
            val ssStabilized = status.ssStabilized
            val maConnected = status.maConnected

            when {
                ssConnected && maConnected -> {
                    if (!ssStabilized && attempts < 5) {
                        Log.i(TAG, "[NET_RETRY][$flowId] connected but not stabilized yet (attempt=$attempts), waiting")
                        handler.postDelayed({
                            waitForSendspinAndResume(flowId, attempts + 1)
                        }, 1000)
                        return@evaluateJavascript
                    }

                    val reason = if (ssStabilized) "stabilized" else "connected_fallback"
                    Log.i(TAG, "[NET_RETRY][$flowId] SendSpin ready ($reason), waiting 3s for safety")
                    host.showToast("Connection ready, resuming...")
                    handler.postDelayed({
                        val delayedFlow = activeFlow
                        if (delayedFlow?.id == flowId && delayedFlow.waitingForStreamStart) {
                            Log.i(TAG, "[NET_RETRY][$flowId] stability delay complete, proceeding")
                            delayedFlow.retryCount = 0
                            performAutoResumeStopPlay(flowId)
                        }
                    }, 3000)
                }

                attempts >= AUTO_RESUME_CONNECTION_WAIT_ATTEMPTS -> {
                    if (maConnected) {
                        Log.w(
                            TAG,
                            "[NET_RETRY][$flowId] SendSpin not connected after ${AUTO_RESUME_CONNECTION_WAIT_ATTEMPTS}s, trying anyway"
                        )
                        host.showToast("Auto-resuming...")
                        performAutoResumeStopPlay(flowId)
                    } else {
                        Log.w(TAG, "[NET_RETRY][$flowId] timeout - no connection (will retry if window still active)")
                        clearFlowIfMatches(flowId)

                        if (isRecoveryWindowActive()) {
                            host.showToast("Still reconnecting...")
                            scheduleFallbackAutoResume("retry_after_no_connection")
                        } else {
                            clearRecoveryState()
                            host.showToast("Could not auto-resume - no connection")
                        }
                    }
                }

                else -> {
                    handler.postDelayed({
                        waitForSendspinAndResume(flowId, attempts + 1)
                    }, 1000)
                }
            }
        }
    }

    private data class ConnectionStatus(
        val ssConnected: Boolean,
        val ssStabilized: Boolean,
        val maConnected: Boolean,
        val raw: String
    )

    private fun parseConnectionStatus(result: String): ConnectionStatus {
        val raw = decodeJsString(result)
        return ConnectionStatus(
            ssConnected = raw.contains("ssConnected=true"),
            ssStabilized = raw.contains("ssStabilized=true"),
            maConnected = raw.contains("maConnected=true"),
            raw = raw
        )
    }

    private fun decodeJsString(result: String): String {
        var value = result.trim()
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }
        return value.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun performAutoResumeStopPlay(flowId: String) {
        val flow = activeFlow
        if (flow?.id != flowId) {
            Log.d(TAG, "[NET_RETRY][$flowId] stale before stop/play, aborting")
            return
        }

        if (flow.commandInFlight) {
            Log.d(TAG, "[NET_RETRY][$flowId] command sequence already in flight, skipping duplicate trigger")
            return
        }

        flow.commandInFlight = true
        clearAutoResumeTimeout()
        resetPendingPlayingConfirmation()
        val target = resolvePlayerCommandTarget()
        val targetPrelude = buildPlayerTargetPrelude(target)

        Log.i(TAG, "[NET_RETRY][$flowId] sending stop command (target=${target.logTarget})")
        host.evaluateJavascript(
            """
            (function() {
                if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                    $targetPrelude
                    console.log('[FallbackResume] Sending stop command');
                    if (phonePlayerId) {
                        window.MaWebSocket.stop(phonePlayerId);
                    } else {
                        window.MaWebSocket.stop();
                    }
                    return 'stop_sent';
                }
                return 'not_connected';
            })();
            """.trimIndent()
        ) { stopResult ->
            Log.i(TAG, "[NET_RETRY][$flowId] stop result=$stopResult")

            handler.postDelayed({
                if (activeFlow?.id != flowId) {
                    Log.d(TAG, "[NET_RETRY][$flowId] stale before play, aborting")
                    return@postDelayed
                }
                Log.i(TAG, "[NET_RETRY][$flowId] sending play command (target=${target.logTarget})")
                host.evaluateJavascript(
                    """
                    (function() {
                        if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                            $targetPrelude
                            console.log('[FallbackResume] Sending play command');
                            if (phonePlayerId) {
                                window.MaWebSocket.play(phonePlayerId);
                            } else {
                                window.MaWebSocket.play();
                            }
                            return 'play_sent';
                        }
                        return 'not_connected';
                    })();
                    """.trimIndent()
                ) { playResult ->
                    Log.i(TAG, "[NET_RETRY][$flowId] play result=$playResult")
                    maybeSeekSavedPosition(flowId)
                }
            }, 500)
        }

        autoResumeTimeoutRunnable = Runnable {
            autoResumeTimeoutRunnable = null
            val timeoutFlow = activeFlow
            if (timeoutFlow?.id == flowId && timeoutFlow.waitingForStreamStart) {
                timeoutFlow.commandInFlight = false
                timeoutFlow.retryCount++
                Log.w(
                    TAG,
                    "[NET_RETRY][$flowId] timeout - no stream/start (attempt ${timeoutFlow.retryCount}/$MAX_AUTO_RESUME_RETRIES)"
                )

                when {
                    timeoutFlow.retryCount <= SOFT_STOP_PLAY_RETRY_LIMIT -> {
                        val retryDelayMs = computeRetryBackoffMs(timeoutFlow.retryCount)
                        Log.i(
                            TAG,
                            "[NET_RETRY][$flowId] soft retry without reload in ${retryDelayMs}ms (attempt ${timeoutFlow.retryCount}/$MAX_AUTO_RESUME_RETRIES)"
                        )
                        host.showToast("Retrying playback...")
                        handler.postDelayed({
                            val currentFlow = activeFlow
                            if (currentFlow?.id != flowId || !currentFlow.waitingForStreamStart) {
                                Log.d(TAG, "[NET_RETRY][$flowId] stale before soft retry, aborting")
                                return@postDelayed
                            }
                            if (currentFlow.commandInFlight) {
                                Log.d(TAG, "[NET_RETRY][$flowId] soft retry skipped - command still in flight")
                                return@postDelayed
                            }
                            Log.i(TAG, "[NET_RETRY][$flowId] soft retry executing stop/play")
                            performAutoResumeStopPlay(flowId)
                        }, retryDelayMs)
                    }

                    timeoutFlow.retryCount == SOFT_STOP_PLAY_RETRY_LIMIT + 1 -> {
                        Log.w(TAG, "[NET_RETRY][$flowId] escalating to controlled socket reset before reload")
                        host.showToast("Reconnecting audio...")
                        host.forceCloseSocketsForReconnect()
                        clearFlowIfMatches(flowId)
                        clearAutoResumeTimeout()
                        resetPendingPlayingConfirmation()
                        scheduleFallbackAutoResume("forced_socket_reset", delayMs = 1500L)
                    }

                    timeoutFlow.retryCount < MAX_AUTO_RESUME_RETRIES -> {
                        Log.i(TAG, "[NET_RETRY][$flowId] reloading WebView for retry")
                        host.reloadWebViewForAutoResumeRetry(timeoutFlow.retryCount, MAX_AUTO_RESUME_RETRIES)
                    }

                    else -> {
                        Log.w(TAG, "[NET_RETRY][$flowId] all retries failed, giving up")
                        host.showToast("Could not resume playback", Toast.LENGTH_LONG)
                        timeoutFlow.waitingForStreamStart = false
                        clearRecoveryState()
                        timeoutFlow.retryCount = 0
                        activeFlow = null
                    }
                }
            }
        }
        handler.postDelayed(autoResumeTimeoutRunnable!!, AUTO_RESUME_TIMEOUT_MS)
    }

    private fun maybeSeekSavedPosition(flowId: String) {
        val savedPosSec = savedPositionMs / 1000
        if (savedPosSec <= 5 || savedTrackTitle.isEmpty()) return

        handler.postDelayed(seekDelay@{
            if (activeFlow?.id != flowId) {
                Log.d(TAG, "[NET_RETRY][$flowId] stale before seek, aborting")
                return@seekDelay
            }

            val currentTrackTitle = host.getCurrentTrackTitle()
            if (currentTrackTitle == savedTrackTitle) {
                Log.i(TAG, "[NET_RETRY][$flowId] track matched, seeking to ${savedPosSec}s")
                host.evaluateJavascript(
                    """
                    (function() {
                        if (window.MaWebSocket && window.MaWebSocket.isConnected()) {
                            console.log('[FallbackResume] Seeking to $savedPosSec seconds');
                            window.MaWebSocket.seek($savedPosSec);
                            return 'seek_sent';
                        }
                        return 'not_connected';
                    })();
                    """.trimIndent()
                ) { seekResult ->
                    Log.i(TAG, "[NET_RETRY][$flowId] seek result=$seekResult")
                }
            } else {
                Log.i(TAG, "[NET_RETRY][$flowId] track changed, skip seek (was=$savedTrackTitle now=$currentTrackTitle)")
            }
        }, 1000)
    }

    private fun scheduleFallbackAutoResume(reason: String, delayMs: Long = AUTO_RESUME_FALLBACK_DELAY_MS) {
        cancelPendingFallbackAutoResume()

        val runnable = Runnable {
            pendingFallbackAutoResumeRunnable = null

            if (!isRecoveryWindowActive()) {
                Log.d(TAG, "[NET_RETRY] fallback skipped reason=$reason (window expired)")
                clearRecoveryState()
                return@Runnable
            }

            if (activeFlow?.waitingForStreamStart == true) {
                Log.d(TAG, "[NET_RETRY] fallback skipped reason=$reason (flow already running)")
                return@Runnable
            }

            val currentPositionMs = host.getCurrentPositionMs()
            if (
                reason != "sendspin_disconnected" &&
                reason != "forced_socket_reset" &&
                host.isCurrentlyPlaying() &&
                currentPositionMs >= savedPositionMs + 500L
            ) {
                Log.i(
                    TAG,
                    "[NET_RETRY] fallback skipped reason=$reason (playback advanced ${savedPositionMs}ms -> ${currentPositionMs}ms)"
                )
                clearRecoveryState()
                return@Runnable
            }

            Log.i(TAG, "[NET_RETRY] fallback trigger reason=$reason")
            triggerFallbackAutoResume(reason)
        }

        pendingFallbackAutoResumeRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun clearAutoResumeTimeout() {
        autoResumeTimeoutRunnable?.let { handler.removeCallbacks(it) }
        autoResumeTimeoutRunnable = null
    }

    private fun cancelPendingFallbackAutoResume() {
        pendingFallbackAutoResumeRunnable?.let { handler.removeCallbacks(it) }
        pendingFallbackAutoResumeRunnable = null
    }

    private fun resetActiveFlowState() {
        activeFlow?.let { flow ->
            flow.waitingForStreamStart = false
            flow.commandInFlight = false
            flow.retryCount = 0
        }
        activeFlow = null
        resetPendingPlayingConfirmation()
        clearAutoResumeTimeout()
        cancelPendingFallbackAutoResume()
    }

    private fun beginNewFlow(prefix: String): ActiveFlowState {
        autoResumeFlowCounter++
        return ActiveFlowState(id = "NET-$prefix-$autoResumeFlowCounter").also { flow ->
            activeFlow = flow
        }
    }

    private fun clearFlowIfMatches(flowId: String) {
        val current = activeFlow ?: return
        if (current.id != flowId) return
        current.waitingForStreamStart = false
        current.commandInFlight = false
        activeFlow = null
    }

    private fun resolvePlayerCommandTarget(): PlayerCommandTarget {
        val escaped = host.getPhonePlayerId()
            ?.replace("\\", "\\\\")
            ?.replace("'", "\\'")
        val hasPhonePlayer = !escaped.isNullOrEmpty()
        return PlayerCommandTarget(
            escapedPhonePlayerId = escaped,
            hasPhonePlayer = hasPhonePlayer,
            logTarget = escaped ?: "active_player"
        )
    }

    private fun buildPlayerTargetPrelude(target: PlayerCommandTarget): String {
        if (!target.hasPhonePlayer) {
            return "const phonePlayerId = null;"
        }
        val escapedId = target.escapedPhonePlayerId!!
        return """
            const phonePlayerId = '$escapedId';
            if (phonePlayerId && window.MaWebSocket.setSelectedPlayerAndSyncUI) {
                window.MaWebSocket.setSelectedPlayerAndSyncUI(phonePlayerId, 'This Device');
            } else if (phonePlayerId) {
                window.MaWebSocket.setSelectedPlayer(phonePlayerId, 'This Device');
            }
        """.trimIndent()
    }

    private fun wasRecentlyPlaying(): Boolean {
        if (lastPlayingConfirmedAtMs <= 0L) return false
        val delta = SystemClock.elapsedRealtime() - lastPlayingConfirmedAtMs
        return delta in 0..RECENT_PLAYING_GRACE_MS
    }

    private fun resetPendingPlayingConfirmation() {
        activeFlow?.pendingPlayingConfirmAtMs = 0L
        activeFlow?.pendingPlayingConfirmPositionMs = -1L
    }

    private fun computeRetryBackoffMs(retryCount: Int): Long {
        return when (retryCount) {
            1 -> 1000L
            2 -> 2000L
            3 -> 4000L
            4 -> 8000L
            else -> 12000L
        }
    }
}
