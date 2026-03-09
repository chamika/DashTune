package com.chamika.dashtune.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.chamika.dashtune.DashTuneMusicService
import androidx.preference.PreferenceManager
import com.chamika.dashtune.AlbumArtContentProvider
import com.chamika.dashtune.auth.JellyfinAccountManager
import com.chamika.dashtune.data.db.MediaCacheDao
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
    @ApplicationContext private val context: Context
) : ViewModel() {

    @Inject
    lateinit var jellyfin: Jellyfin

    fun versionString(): CharSequence =
        "DashTune: ${jellyfin.clientInfo?.version}, Jellyfin API: ${Jellyfin.apiVersion}"

    suspend fun logout() {
        context.startService(Intent(context, DashTuneMusicService::class.java).apply {
            action = DashTuneMusicService.ACTION_STOP_PLAYBACK
        })
        accountManager.logout()

        withContext(Dispatchers.IO) {
            mediaCacheDao.deleteAll()
            AlbumArtContentProvider.clearCache(context.cacheDir)
            File(context.cacheDir, "exoplayer_cache").deleteRecursively()
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
