package com.chamika.dashtune

import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.chamika.dashtune.NetworkSyncScheduler.Companion.LAST_SYNC_ATTEMPT_PREF
import com.chamika.dashtune.NetworkSyncScheduler.Companion.LAST_SYNC_PREF
import com.chamika.dashtune.NetworkSyncScheduler.Companion.SYNC_RETRY_BACKOFF_MS
import com.chamika.dashtune.NetworkSyncScheduler.Companion.SYNC_SETTLE_DELAY_MS
import com.chamika.dashtune.NetworkSyncScheduler.Companion.SyncDecision
import com.chamika.dashtune.NetworkSyncScheduler.Companion.UNVALIDATED_FALLBACK_MS
import com.chamika.dashtune.NetworkSyncScheduler.Companion.syncDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * When a change in connectivity starts a library sync. In the car, a sync started the moment a
 * network appeared timed out every time, and each flap mid-drive started another one.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkSyncSchedulerTest {

    private val hour = 60 * 60 * 1000L
    private val now = 1_800_000_000_000L

    private lateinit var prefs: SharedPreferences
    private lateinit var scope: TestScope
    private lateinit var scheduler: NetworkSyncScheduler
    private val log = mutableListOf<String>()
    private var syncs = 0
    private var synced = 0
    private var libraryReady = true
    private var syncResult: CompletableDeferred<Boolean>? = null

    private val wifi = Any()
    private val cellular = Any()

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("sync-test", 0)
        prefs.edit().clear().commit()
        scope = TestScope()
        scheduler = NetworkSyncScheduler(
            scope = scope,
            prefs = prefs,
            isSignedIn = { true },
            isLibraryReady = { libraryReady },
            sync = {
                syncs++
                syncResult?.await() ?: true
            },
            onSynced = { synced++ },
            log = { log += it },
            clock = { now + scope.testScheduler.currentTime },
        )
    }

    // --- The decision ---

    @Test
    fun `a library never synced is due`() {
        assertEquals(SyncDecision.DUE, syncDecision(now, 0L, 0L))
    }

    @Test
    fun `a sync that succeeded within six hours is not repeated`() {
        assertEquals(SyncDecision.RAN_RECENTLY, syncDecision(now, now - 5 * hour, now - 5 * hour))
    }

    @Test
    fun `a failed sync is not retried on every network flap`() {
        assertEquals(SyncDecision.FAILED_RECENTLY, syncDecision(now, now - 30 * hour, now - 10 * 60 * 1000L))
    }

    @Test
    fun `a failed sync is retried once the backoff has passed`() {
        assertEquals(SyncDecision.DUE, syncDecision(now, now - 30 * hour, now - SYNC_RETRY_BACKOFF_MS - 1))
    }

    @Test
    fun `timestamps from the future count as long ago, not as recent`() {
        // A car's clock can jump backwards; that must not suppress syncing for hours.
        assertEquals(SyncDecision.DUE, syncDecision(now, now + hour, now + hour))
    }

    // --- The wiring ---

    @Test
    fun `a validated network syncs once, after it settles`() {
        scheduler.onAvailable(wifi)
        scheduler.onValidated(wifi)

        scope.advanceTimeBy(SYNC_SETTLE_DELAY_MS - 1)
        assertEquals("nothing before the settle delay", 0, syncs)
        scope.advanceTimeBy(2)

        assertEquals(1, syncs)
        assertEquals(1, synced)
        assertTrue(prefs.getLong(LAST_SYNC_PREF, 0L) > 0)
    }

    @Test
    fun `repeated capability updates on one network start one sync`() {
        repeat(5) { scheduler.onValidated(wifi) }
        scope.advanceTimeBy(SYNC_SETTLE_DELAY_MS + 1)
        repeat(5) { scheduler.onValidated(wifi) }
        scope.advanceTimeBy(SYNC_SETTLE_DELAY_MS + 1)

        assertEquals(1, syncs)
    }

    @Test
    fun `a failed sync is not retried when the network flaps`() {
        syncResult = CompletableDeferred(false)
        scheduler.onValidated(wifi)
        scope.advanceTimeBy(SYNC_SETTLE_DELAY_MS + 1)
        assertEquals(1, syncs)
        assertTrue(prefs.getLong(LAST_SYNC_ATTEMPT_PREF, 0L) > 0)

        scheduler.onLost(wifi)
        scheduler.onAvailable(cellular)
        scheduler.onValidated(cellular)
        scope.advanceTimeBy(SYNC_SETTLE_DELAY_MS + 1)

        assertEquals(1, syncs)
        assertTrue(log.any { it.contains("failed recently") })
    }

    @Test
    fun `losing the network during the settle delay cancels the sync`() {
        scheduler.onValidated(wifi)
        scope.advanceTimeBy(SYNC_SETTLE_DELAY_MS / 2)
        scheduler.onLost(wifi)
        scope.advanceTimeBy(SYNC_SETTLE_DELAY_MS)

        assertEquals(0, syncs)
        assertEquals("no attempt recorded, so no backoff", 0L, prefs.getLong(LAST_SYNC_ATTEMPT_PREF, 0L))
    }

    @Test
    fun `losing the network cancels a running sync`() {
        syncResult = CompletableDeferred()
        scheduler.onValidated(wifi)
        scope.advanceTimeBy(SYNC_SETTLE_DELAY_MS + 1)
        assertEquals(1, syncs)

        scheduler.onLost(wifi)
        syncResult!!.complete(true)
        scope.runCurrent()

        assertEquals("a cancelled sync reports nothing", 0, synced)
    }

    @Test
    fun `a sync before anything has browsed waits for the next update`() {
        libraryReady = false
        scheduler.onValidated(wifi)
        scope.advanceTimeBy(SYNC_SETTLE_DELAY_MS + 1)
        assertEquals(0, syncs)
        assertTrue(log.any { it.contains("library not initialised") })

        libraryReady = true
        scheduler.onValidated(wifi)
        scope.advanceTimeBy(SYNC_SETTLE_DELAY_MS + 1)

        assertEquals(1, syncs)
    }

    @Test
    fun `a network that is never validated still gets a sync`() {
        // A travel router with no internet in front of a LAN-only server never validates.
        scheduler.onAvailable(wifi)
        scope.advanceTimeBy(UNVALIDATED_FALLBACK_MS + SYNC_SETTLE_DELAY_MS + 1)

        assertEquals(1, syncs)
    }

    @Test
    fun `validation before the fallback means one sync, not two`() {
        scheduler.onAvailable(wifi)
        scope.advanceTimeBy(1_000)
        scheduler.onValidated(wifi)
        scope.advanceTimeBy(UNVALIDATED_FALLBACK_MS + SYNC_SETTLE_DELAY_MS + 1)

        assertEquals(1, syncs)
    }

    @Test
    fun `a network lost before the fallback is not synced`() {
        scheduler.onAvailable(wifi)
        scope.advanceTimeBy(UNVALIDATED_FALLBACK_MS / 2)
        scheduler.onLost(wifi)
        scope.advanceTimeBy(UNVALIDATED_FALLBACK_MS + SYNC_SETTLE_DELAY_MS)

        assertEquals(0, syncs)
    }
}
