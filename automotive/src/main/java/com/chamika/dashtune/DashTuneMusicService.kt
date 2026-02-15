package com.chamika.dashtune

import android.accounts.AccountManager
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_INDEX_PREF
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_TRACK_POSITON_MS_PREF
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import dagger.hilt.android.AndroidEntryPoint
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.serializer.toUUID
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
@OptIn(UnstableApi::class)
class DashTuneMusicService : MediaLibraryService() {

    @Inject
    lateinit var jellyfin: Jellyfin

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
                    prefetchNextTracks(player)
                }
            }
        }

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        player.addListener(playerListener)

        player.repeatMode = Player.REPEAT_MODE_ALL
        player.shuffleModeEnabled = false

        playbackPoll = Runnable {
            if (player.isPlaying) {
                currentPlaybackTime = player.currentPosition
                currentTrack = player.currentMediaItem

                PreferenceManager.getDefaultSharedPreferences(this@DashTuneMusicService).edit {
                    putLong(PLAYLIST_TRACK_POSITON_MS_PREF, currentPlaybackTime)
                }
            }

            handler.postDelayed(playbackPoll, 1000)
        }
        handler.postDelayed(playbackPoll, 1000)

        callback = DashTuneSessionCallback(this, accountManager, jellyfinApi)

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
            .setMediaButtonPreferences(CommandButtons.createButtons(player))
            .build()

        if (accountManager.isAuthenticated) {
            onLogin()
        }
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

        super.onDestroy()
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
            }
        }
    }

    private suspend fun reportPlayback(player: Player) {
        if (currentTrack != null) {
            Log.i(LOG_TAG, "Reporting playback stopped: $currentPlaybackTime")
            jellyfinApi.playStateApi.onPlaybackStopped(
                currentTrack!!.mediaId.toUUID(),
                positionTicks = 10000 * currentPlaybackTime
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
            jellyfinApi.playStateApi.onPlaybackStart(
                player.currentMediaItem!!.mediaId.toUUID(),
                canSeek = true
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
