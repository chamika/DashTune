package com.chamika.dashtune.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.chamika.dashtune.DashTuneMusicService
import androidx.preference.PreferenceManager
import com.chamika.dashtune.AlbumArtContentProvider
import com.chamika.dashtune.auth.JellyfinAccountManager
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.data.db.PinnedDownloadDao
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountManager: JellyfinAccountManager,
    private val mediaCacheDao: MediaCacheDao,
    private val pinnedDownloadDao: PinnedDownloadDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    @Inject
    lateinit var jellyfin: Jellyfin

    fun versionString(): CharSequence =
        "DashTune: ${jellyfin.clientInfo?.version}, Jellyfin API: ${Jellyfin.apiVersion}"

    /** Human-readable size of the pinned (deliberately downloaded) content on disk. */
    suspend fun downloadsStorageString(): String = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "pinned_downloads")
        val bytes = if (dir.exists()) {
            dir.walkBottomUp().filter { it.isFile }.map { it.length() }.sum()
        } else {
            0L
        }
        formatBytes(bytes)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    fun forceExit() {
        context.startService(Intent(context, DashTuneMusicService::class.java).apply {
            action = DashTuneMusicService.ACTION_FORCE_EXIT
        })
    }

    suspend fun clearCache() {
        withContext(Dispatchers.IO) {
            mediaCacheDao.deleteAll()
            AlbumArtContentProvider.clearCache(context.cacheDir)
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit().apply {
            remove("last_sync_timestamp")
            apply()
        }
        context.startService(Intent(context, DashTuneMusicService::class.java).apply {
            action = DashTuneMusicService.ACTION_REFRESH_LIBRARY
        })
    }

    suspend fun logout() {
        context.startService(Intent(context, DashTuneMusicService::class.java).apply {
            action = DashTuneMusicService.ACTION_STOP_PLAYBACK
        })
        accountManager.logout()

        withContext(Dispatchers.IO) {
            mediaCacheDao.deleteAll()
            pinnedDownloadDao.deleteAll()
            AlbumArtContentProvider.clearCache(context.cacheDir)
            File(context.cacheDir, "exoplayer_cache").deleteRecursively()
            File(context.cacheDir, "pinned_downloads").deleteRecursively()
        }

        PreferenceManager.getDefaultSharedPreferences(context).edit().apply {
            remove("playlistIds")
            remove("playlistIndex")
            remove("playlistTrackPositionMs")
            remove("last_sync_timestamp")
            remove("repeat_mode")
            remove("shuffle_enabled")
            apply()
        }
    }
}
