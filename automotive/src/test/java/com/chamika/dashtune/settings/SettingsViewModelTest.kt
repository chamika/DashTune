package com.chamika.dashtune.settings

import android.app.Application
import android.content.Intent
import androidx.preference.PreferenceManager
import com.chamika.dashtune.DashTuneMusicService
import com.chamika.dashtune.auth.JellyfinAccountManager
import com.chamika.dashtune.data.db.MediaCacheDao
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private lateinit var context: Application
    private lateinit var accountManager: JellyfinAccountManager
    private lateinit var mediaCacheDao: MediaCacheDao
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        // Spy on the real Application to stub startService (DashTuneMusicService requires Hilt
        // which cannot be initialised in unit tests) while keeping real SharedPreferences.
        context = spyk(RuntimeEnvironment.getApplication())
        every { context.startService(any()) } returns null

        accountManager = mockk(relaxed = true)
        mediaCacheDao = mockk(relaxed = true)
        viewModel = SettingsViewModel(accountManager, mediaCacheDao, context)
    }

    // --- logout ---

    @Test
    fun `logout calls accountManager logout`() = runTest {
        viewModel.logout()

        verify { accountManager.logout() }
    }

    @Test
    fun `logout calls mediaCacheDao deleteAll`() = runTest {
        viewModel.logout()

        coVerify { mediaCacheDao.deleteAll() }
    }

    @Test
    fun `logout sends ACTION_STOP_PLAYBACK intent to DashTuneMusicService`() = runTest {
        val intentSlot = slot<Intent>()
        every { context.startService(capture(intentSlot)) } returns null

        viewModel.logout()

        assertEquals(DashTuneMusicService.ACTION_STOP_PLAYBACK, intentSlot.captured.action)
        assertEquals(
            DashTuneMusicService::class.java.name,
            intentSlot.captured.component?.className
        )
    }

    @Test
    fun `logout removes playlistIds from shared preferences`() = runTest {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString("playlistIds", "id1,id2").apply()

        viewModel.logout()

        assertNull(prefs.getString("playlistIds", null))
    }

    @Test
    fun `logout removes playlistIndex from shared preferences`() = runTest {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt("playlistIndex", 3).apply()

        viewModel.logout()

        assertEquals(-1, prefs.getInt("playlistIndex", -1))
    }

    @Test
    fun `logout removes playlistTrackPositionMs from shared preferences`() = runTest {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putLong("playlistTrackPositionMs", 12345L).apply()

        viewModel.logout()

        assertEquals(-1L, prefs.getLong("playlistTrackPositionMs", -1L))
    }

    @Test
    fun `logout removes last_sync_timestamp from shared preferences`() = runTest {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putLong("last_sync_timestamp", 999L).apply()

        viewModel.logout()

        assertEquals(-1L, prefs.getLong("last_sync_timestamp", -1L))
    }

    @Test
    fun `logout removes repeat_mode from shared preferences`() = runTest {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt("repeat_mode", 1).apply()

        viewModel.logout()

        assertEquals(-1, prefs.getInt("repeat_mode", -1))
    }

    @Test
    fun `logout removes shuffle_enabled from shared preferences`() = runTest {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean("shuffle_enabled", true).apply()

        viewModel.logout()

        assertTrue(!prefs.contains("shuffle_enabled"))
    }

    @Test
    fun `logout completes without exception when preferences are already empty`() = runTest {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().apply()

        viewModel.logout()

        verify { accountManager.logout() }
    }
}
