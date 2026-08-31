package com.chamika.dashtune.support

import android.accounts.AccountManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.chamika.dashtune.DashTuneMusicService
import com.chamika.dashtune.DashTuneSessionCallback.Companion.LOGIN_COMMAND
import com.chamika.dashtune.auth.JellyfinAccountManager
import com.chamika.dashtune.data.db.DashTuneDatabase
import com.chamika.dashtune.fake.DashTuneFixture
import com.chamika.dashtune.fake.FakeJellyfinServer
import com.chamika.dashtune.settings.SettingsActivity
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import java.util.concurrent.TimeUnit

/**
 * Boots a [FakeJellyfinServer], points the running [DashTuneMusicService] at it, and hands
 * the test a connected [MediaBrowser] — the same interface an AAOS head unit uses.
 *
 * The service is a process singleton that outlives any one test class, so this re-points it
 * per test through the production `LOGIN_COMMAND` path rather than trying to restart it.
 */
class DashTuneE2eRule(
    private val categories: Set<String> = setOf("latest", "favourites", "books", "playlists"),
    /**
     * Bring one of the app's own activities to the foreground before the test runs.
     *
     * Required by any test that asserts audio actually plays. Android 15's audio focus
     * hardening (`AS.HardeningEnforcer`) denies focus to an app whose only running component
     * is a bound service — Media3 then forces `playWhenReady` to false and nothing plays. On a
     * head unit the media host supplies that foreground context; under headless instrumentation
     * the test has to. Browse-only tests leave this off, since it costs an activity launch.
     */
    private val foregroundForPlayback: Boolean = false,
) : ExternalResource() {

    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    lateinit var server: FakeJellyfinServer
        private set

    lateinit var browser: MediaBrowser
        private set

    private var foregroundActivity: ActivityScenario<SettingsActivity>? = null

    val fixture: DashTuneFixture get() = server.fixture

    /**
     * A second connection to the app's browse cache, opened on the same database file.
     *
     * Clearing it between tests is not optional: `MediaRepository.getChildren` serves Room
     * rows whenever any exist, so a previous test's items would be returned instead of the
     * current fixture's. The suite deliberately avoids `@HiltAndroidTest` — that swaps
     * `DashTuneApplication` for `HiltTestApplication` and would stop this being end-to-end —
     * and a test-declared `@EntryPoint` cannot be installed into the app APK's already
     * generated component, so a direct connection is the way in. Room's DAO methods query
     * SQLite on each call rather than caching rows, so the service sees these deletes.
     */
    private val database: DashTuneDatabase by lazy {
        Room.databaseBuilder(context, DashTuneDatabase::class.java, "dashtune_db").build()
    }

    override fun before() {
        server = FakeJellyfinServer().also { it.start() }

        seedPreferences()
        if (foregroundForPlayback) {
            // ActivityScenario blocks until RESUMED, so focus is grantable by the time the
            // test calls play().
            foregroundActivity = ActivityScenario.launch(SettingsActivity::class.java)
        }
        JellyfinAccountManager(AccountManager.get(context))
            .storeAccount(server.baseUrl, TEST_USER, TEST_TOKEN)

        browser = awaitOnMain {
            MediaBrowser.Builder(
                context,
                SessionToken(context, ComponentName(context, DashTuneMusicService::class.java))
            ).buildAsync()
        }

        // Clear before the login command, so the tree cache invalidation that command
        // triggers is the last thing to happen and nothing can repopulate stale rows after.
        runBlocking { database.mediaCacheDao().deleteAll() }
        applyLogin()
        server.clearRequests()
    }

    override fun after() {
        runCatching {
            onMain {
                browser.stop()
                browser.clearMediaItems()
                browser.release()
            }
        }
        runCatching { foregroundActivity?.close() }
        runCatching { runBlocking { database.mediaCacheDao().deleteAll() } }
        runCatching { server.close() }
        runCatching { JellyfinAccountManager(AccountManager.get(context)).logout() }
    }

    /** Re-runs the production login path, re-reading the account and invalidating caches. */
    fun applyLogin() {
        onMain { browser.sendCustomCommand(SessionCommand(LOGIN_COMMAND, Bundle()), Bundle()) }
            .get(30, TimeUnit.SECONDS)
    }

    /** Drops the Room browse cache mid-test, forcing the next browse back to the network. */
    fun clearBrowseCache() = runBlocking { database.mediaCacheDao().deleteAll() }

    private fun seedPreferences() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().apply {
            putStringSet("browse_categories", categories)
            // Prefetch and favourite pre-caching both fire background downloads on login,
            // which would race the request assertions. Tests that need them opt in.
            putString("prefetch_count", "0")
            putBoolean("cache_favourites", false)
            putString("bitrate", "Direct stream")
            putString("cache_size", "100")
        }.commit()
    }

    companion object {
        const val TEST_USER = "e2e-user"
        const val TEST_TOKEN = "e2e-token"
    }
}
