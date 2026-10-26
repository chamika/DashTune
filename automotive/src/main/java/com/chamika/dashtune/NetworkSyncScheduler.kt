package com.chamika.dashtune

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decides when a change in connectivity starts a library sync. Every method must be called on
 * the thread [scope] runs on (the service's main thread); network callbacks post here.
 *
 * Sync waits for a network the system has validated, then lets it settle. Started the moment
 * a network appeared, every sync in the car timed out — and since a failure never advanced the
 * timestamp, each flap mid-drive started another full sync while music played. A network that
 * is never validated (a travel router with no internet in front of a LAN-only server, or a
 * blocked connectivity check) still gets a try after [UNVALIDATED_FALLBACK_MS].
 *
 * @param key identifies a network; [android.net.Network] in the service.
 */
internal class NetworkSyncScheduler(
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val isSignedIn: () -> Boolean,
    private val isLibraryReady: () -> Boolean,
    private val sync: suspend () -> Boolean,
    private val onSynced: () -> Unit,
    private val log: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        const val LAST_SYNC_PREF = "last_sync_timestamp"
        const val LAST_SYNC_ATTEMPT_PREF = "last_sync_attempt_timestamp"

        const val SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /** How long to leave a failed sync before trying again. */
        const val SYNC_RETRY_BACKOFF_MS = 30 * 60 * 1000L

        /** A validated network can still be settling; the first requests on it used to time out. */
        const val SYNC_SETTLE_DELAY_MS = 10_000L

        /** How long a network may stay unvalidated before sync is tried on it anyway. */
        const val UNVALIDATED_FALLBACK_MS = 60_000L

        enum class SyncDecision { DUE, RAN_RECENTLY, FAILED_RECENTLY }

        /** A clock that went backwards (a car's RTC) counts as long ago, not as recent. */
        private fun within(now: Long, then: Long, windowMs: Long): Boolean =
            then > 0 && now - then in 0..windowMs

        fun syncDecision(now: Long, lastSuccess: Long, lastAttempt: Long): SyncDecision = when {
            within(now, lastSuccess, SYNC_INTERVAL_MS) -> SyncDecision.RAN_RECENTLY
            within(now, lastAttempt, SYNC_RETRY_BACKOFF_MS) -> SyncDecision.FAILED_RECENTLY
            else -> SyncDecision.DUE
        }
    }

    /** The network a sync was last considered for, so its capability updates don't re-trigger it. */
    private var syncNetwork: Any? = null
    private var syncJob: Job? = null

    private var fallbackNetwork: Any? = null
    private var fallbackJob: Job? = null

    fun onAvailable(network: Any) {
        fallbackJob?.cancel()
        fallbackNetwork = network
        fallbackJob = scope.launch {
            delay(UNVALIDATED_FALLBACK_MS)
            log("Network not validated after ${UNVALIDATED_FALLBACK_MS / 1000}s: considering sync anyway")
            considerSync(network, "unvalidated")
        }
    }

    fun onValidated(network: Any) {
        if (network == fallbackNetwork) fallbackJob?.cancel()
        considerSync(network, "validated")
    }

    fun onLost(network: Any) {
        if (network == fallbackNetwork) {
            fallbackJob?.cancel()
            fallbackNetwork = null
        }
        if (network == syncNetwork) {
            syncNetwork = null
            syncJob?.cancel()
        }
    }

    fun release() {
        fallbackJob?.cancel()
        syncJob?.cancel()
    }

    /** Starts at most one sync per network, after it has settled. */
    private fun considerSync(network: Any, how: String) {
        if (network == syncNetwork || syncJob?.isActive == true) return
        if (!isSignedIn()) return
        syncNetwork = network

        val decision = syncDecision(
            clock(),
            prefs.getLong(LAST_SYNC_PREF, 0L),
            prefs.getLong(LAST_SYNC_ATTEMPT_PREF, 0L)
        )
        when (decision) {
            SyncDecision.RAN_RECENTLY -> {
                log("Network $how: sync skipped (ran recently)")
                return
            }
            SyncDecision.FAILED_RECENTLY -> {
                log("Network $how: sync skipped (failed recently)")
                return
            }
            SyncDecision.DUE -> log("Network $how: sync triggered")
        }

        syncJob = scope.launch {
            delay(SYNC_SETTLE_DELAY_MS)
            if (!isLibraryReady()) {
                // Nothing has browsed yet. Forget this network so its next update tries again.
                log("Sync skipped: library not initialised yet")
                syncNetwork = null
                return@launch
            }
            prefs.edit { putLong(LAST_SYNC_ATTEMPT_PREF, clock()) }
            if (sync()) {
                prefs.edit { putLong(LAST_SYNC_PREF, clock()) }
                onSynced()
            }
        }
    }
}
