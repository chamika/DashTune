package com.chamika.dashtune

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.preference.PreferenceManager
import com.chamika.dashtune.Constants.LOG_TAG
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_INDEX_PREF
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_TRACK_POSITON_MS_PREF
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.serializer.toUUID
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
@OptIn(UnstableApi::class)
class DashTuneMusicService : MediaLibraryService() {

    @Inject
    lateinit var jellyfin: Jellyfin

    @Inject
    lateinit var mediaCacheDao: MediaCacheDao

    private lateinit var accountManager: com.chamika.dashtune.auth.JellyfinAccountManager
    private lateinit var jellyfinApi: ApiClient
    private lateinit var mediaSourceFactory: DefaultMediaSourceFactory
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var callback: DashTuneSessionCallback

    private val handler: Handler = Handler(Looper.getMainLooper())
    private var currentPlaybackTime: Long = 0
    private var currentTrack: MediaItem? = null

    private lateinit var playbackPoll: Runnable
    private lateinit var playerListener: Player.Listener

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Offline cache
    private lateinit var downloadCache: SimpleCache
    private lateinit var downloadManager: DownloadManager
    private lateinit var cacheDataSourceFactory: CacheDataSource.Factory
    private lateinit var httpDataSourceFactory: DefaultHttpDataSource.Factory

    override fun onCreate() {
        super.onCreate()

        Log.i(LOG_TAG, "onCreate")

        accountManager = com.chamika.dashtune.auth.JellyfinAccountManager(
            AccountManager.get(applicationContext)
        )
        jellyfinApi = jellyfin.createApi()

        // Setup offline cache
        val cacheSizeStr = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("cache_size", "200") ?: "200"
        val cacheSizeBytes = cacheSizeStr.toLong() * 1024L * 1024L

        val cacheDir = File(cacheDir, "exoplayer_cache")
        val databaseProvider = StandaloneDatabaseProvider(this)
        downloadCache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(cacheSizeBytes),
            databaseProvider
        )

        httpDataSourceFactory = DefaultHttpDataSource.Factory()
        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

        // Setup download manager for prefetching
        val downloadExecutor = Executors.newFixedThreadPool(6)
        downloadManager = DownloadManager(
            this,
            databaseProvider,
            downloadCache,
            httpDataSourceFactory,
            downloadExecutor
        ).apply {
            maxParallelDownloads = 3
            addListener(downloadListener)
        }

        playerListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    PreferenceManager.getDefaultSharedPreferences(this@DashTuneMusicService).edit {
                        putInt(PLAYLIST_INDEX_PREF, player.currentMediaItemIndex)
                    }

                    SuspendToFutureAdapter.launchFuture { reportPlayback(player) }

                    // Prefetch next tracks
                    val prefetchCount = PreferenceManager
                        .getDefaultSharedPreferences(this@DashTuneMusicService)
                        .getString("prefetch_count", "5")?.toIntOrNull() ?: 5
                    if (prefetchCount > 0) {
                        prefetchNextTracks(player, prefetchCount)
                    }
                }

                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    if (player.isPlaying) {
                        startPlaybackPoll()
                    } else {
                        stopPlaybackPoll()
                    }
                }

                if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) {
                    PreferenceManager.getDefaultSharedPreferences(this@DashTuneMusicService).edit {
                        putInt("repeat_mode", player.repeatMode)
                    }
                }

                if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                    PreferenceManager.getDefaultSharedPreferences(this@DashTuneMusicService).edit {
                        putBoolean("shuffle_enabled", player.shuffleModeEnabled)
                    }
                }
            }
        }

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        player.addListener(playerListener)

        // Restore saved shuffle and repeat states
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        player.repeatMode = prefs.getInt("repeat_mode", Player.REPEAT_MODE_ALL)
        player.shuffleModeEnabled = prefs.getBoolean("shuffle_enabled", false)

        playbackPoll = Runnable {
            val p = mediaLibrarySession.player
            if (p.isPlaying) {
                currentPlaybackTime = p.currentPosition
                currentTrack = p.currentMediaItem

                PreferenceManager.getDefaultSharedPreferences(this@DashTuneMusicService).edit {
                    putLong(PLAYLIST_TRACK_POSITON_MS_PREF, currentPlaybackTime)
                }
                handler.postDelayed(playbackPoll, 1000)
            }
        }

        callback = DashTuneSessionCallback(this, accountManager, jellyfinApi, mediaCacheDao)

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
            .setMediaButtonPreferences(CommandButtons.createButtons(player))
            .build()

        if (accountManager.isAuthenticated) {
            onLogin()
        }

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(LOG_TAG, "Network available")
                if (!accountManager.isAuthenticated) return

                val prefs = PreferenceManager.getDefaultSharedPreferences(this@DashTuneMusicService)
                val lastSync = prefs.getLong("last_sync_timestamp", 0L)
                val sixHoursMs = 6 * 60 * 60 * 1000L

                if (System.currentTimeMillis() - lastSync > sixHoursMs) {
                    serviceScope.launch {
                        val success = callback.sync()
                        if (success) {
                            prefs.edit { putLong("last_sync_timestamp", System.currentTimeMillis()) }
                            mediaLibrarySession.notifyChildrenChanged(ROOT_ID, 4, null)
                            Toast.makeText(
                                this@DashTuneMusicService,
                                R.string.library_synced,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    private fun startPlaybackPoll() {
        handler.removeCallbacks(playbackPoll)
        handler.post(playbackPoll)
    }

    private fun stopPlaybackPoll() {
        handler.removeCallbacks(playbackPoll)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        Log.i(LOG_TAG, "onDestroy")

        mediaLibrarySession.release()
        mediaLibrarySession.player.removeListener(playerListener)
        mediaLibrarySession.player.release()
        handler.removeCallbacks(playbackPoll)

        try {
            downloadManager.removeListener(downloadListener)
            downloadManager.release()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error releasing download manager", e)
        }

        try {
            downloadCache.release()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Error releasing cache", e)
        }

        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }

        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession.player
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            Log.i(LOG_TAG, "onTaskRemoved: keeping service alive (actively playing)")
        } else {
            Log.i(LOG_TAG, "onTaskRemoved: stopping service")
            player.pause()
            stopSelf()
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    fun onLogin() {
        jellyfinApi.update(
            baseUrl = accountManager.server,
            accessToken = accountManager.token
        )

        val headers = mapOf(
            "Authorization" to "MediaBrowser Client=\"${jellyfinApi.clientInfo.name}\", " +
                    "Device=\"${jellyfinApi.deviceInfo.name}\", " +
                    "DeviceId=\"${jellyfinApi.deviceInfo.id}\", " +
                    "Version=\"${jellyfinApi.clientInfo.version}\", " +
                    "Token=\"${jellyfinApi.accessToken}\""
        )

        httpDataSourceFactory.setDefaultRequestProperties(headers)

        mediaLibrarySession.notifyChildrenChanged(ROOT_ID, 4, null)
    }

    private fun prefetchNextTracks(player: Player, count: Int = 5) {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return

        var remaining = count
        val indicesToPrefetch = mutableListOf<Int>()
        var nextWindowIndex = timeline.getNextWindowIndex(
            player.currentMediaItemIndex,
            player.repeatMode,
            player.shuffleModeEnabled
        )

        while (nextWindowIndex != C.INDEX_UNSET && remaining > 0) {
            indicesToPrefetch.add(nextWindowIndex)
            remaining--
            nextWindowIndex = timeline.getNextWindowIndex(
                nextWindowIndex,
                player.repeatMode,
                player.shuffleModeEnabled
            )
            if (nextWindowIndex == player.currentMediaItemIndex) break
        }

        for (windowIndex in indicesToPrefetch) {
            val mediaItem = player.getMediaItemAt(windowIndex)
            val uri = mediaItem.localConfiguration?.uri ?: continue
            val id = mediaItem.mediaId

            try {
                val downloadRequest = DownloadRequest.Builder(id, uri).build()
                downloadManager.addDownload(downloadRequest)
                downloadManager.resumeDownloads()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to prefetch: $id", e)
                FirebaseCrashlytics.getInstance().recordException(e)
            }
        }
    }

    private suspend fun reportPlayback(player: Player) {
        if (currentTrack != null) {
            Log.i(LOG_TAG, "Reporting playback stopped: $currentPlaybackTime")
            jellyfinApi.playStateApi.reportPlaybackStopped(
                PlaybackStopInfo(
                    itemId = currentTrack!!.mediaId.toUUID(),
                    positionTicks = 10000 * currentPlaybackTime,
                    failed = false
                )
            )
        }

        if (player.currentMediaItem != null) {
            val exoPlayer = player as ExoPlayer
            val format = exoPlayer.audioFormat
            val formatString = "${format?.containerMimeType} at ${format?.averageBitrate} bps"

            Log.i(
                LOG_TAG,
                "Playing $formatString: ${exoPlayer.currentMediaItem?.localConfiguration?.uri}"
            )
            jellyfinApi.playStateApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = player.currentMediaItem!!.mediaId.toUUID(),
                    canSeek = true,
                    isPaused = false,
                    isMuted = false,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT
                )
            )
        }
    }

    private val downloadListener = object : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?
        ) {
            when (download.state) {
                Download.STATE_COMPLETED ->
                    Log.d(LOG_TAG, "Prefetch completed: ${download.request.id}")
                Download.STATE_FAILED ->
                    Log.w(LOG_TAG, "Prefetch failed: ${download.request.id}", finalException)
                Download.STATE_DOWNLOADING ->
                    Log.d(LOG_TAG, "Prefetching: ${download.request.id} (${download.percentDownloaded}%)")
                else -> {}
            }
        }
    }
}
