package com.chamika.dashtune

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.preference.PreferenceManager
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_INDEX_PREF
import com.chamika.dashtune.DashTuneSessionCallback.Companion.PLAYLIST_TRACK_POSITON_MS_PREF
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.universalAudioApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.serializer.toUUID
import kotlin.time.Duration.Companion.seconds
import kotlin.system.exitProcess
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
@OptIn(UnstableApi::class)
class DashTuneMusicService : MediaLibraryService() {

    companion object {
        const val ACTION_STOP_PLAYBACK = "com.chamika.dashtune.ACTION_STOP_PLAYBACK"
        const val ACTION_REFRESH_LIBRARY = "com.chamika.dashtune.ACTION_REFRESH_LIBRARY"
        const val ACTION_FORCE_EXIT = "com.chamika.dashtune.ACTION_FORCE_EXIT"

        internal const val AUDIOBOOK_POSITION_REPORT_INTERVAL_MS = 30_000L
        internal const val MILLISECONDS_TO_TICKS = 10_000L

        /**
         * Delay before killing the process after [ACTION_FORCE_EXIT] calls stopSelf(). onDestroy()
         * runs as a main-looper message, so this must be long enough for it to release the media
         * session, player and caches first - otherwise the SimpleCache lock on the ExoPlayer disk
         * cache can be left held, and a stale foreground notification can linger.
         */
        internal const val FORCE_EXIT_DELAY_MS = 500L

        /**
         * How long playback may stay suppressed by a transient audio-focus loss before we treat
         * it as stuck and re-request focus. Normal transient losses (nav prompts, chimes) resolve
         * with an AUDIOFOCUS_GAIN well within this window; a value this large only fires when focus
         * was never returned, which is the freeze users hit (issue: player detaches until another
         * app grabs focus).
         */
        internal const val FOCUS_RECOVERY_DELAY_MS = 8_000L

        /**
         * Delay between retries of the current item after a transient network playback error.
         * Failures with no connectivity return quickly, so this mostly paces how often we poke
         * the player while waiting for the car's data connection to come up.
         */
        internal const val NETWORK_ERROR_RETRY_DELAY_MS = 5_000L

        /**
         * Max consecutive network-error retries before falling back to skipping the item.
         * 24 × 5s ≈ 2 minutes, which covers the head unit bringing up connectivity after a
         * cold boot; beyond that the server is likely genuinely unreachable.
         */
        internal const val MAX_NETWORK_ERROR_RETRIES = 24

        /**
         * Returns true when [errorCode] (a [PlaybackException.ErrorCode]
         * [androidx.media3.common.PlaybackException]) indicates a transient network failure
         * worth retrying in place. Skip-chaining on these would walk through and then clear the
         * whole queue during a cold boot, before the car has connectivity.
         */
        internal fun isTransientNetworkError(errorCode: Int): Boolean =
            errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

        /**
         * One more try, after [AUDIO_TRACK_INIT_RETRY_DELAY_MS], when the audio output could not
         * be opened. Some head units refuse an AudioTrack while their audio stack is busy (seen
         * on an LG unit, Android 12), and skipping on would fail the same way on every track.
         */
        internal const val MAX_AUDIO_TRACK_INIT_RETRIES = 1
        internal const val AUDIO_TRACK_INIT_RETRY_DELAY_MS = 2_000L

        /** Audio modes in which a call (or its ringing) owns the audio, not a stuck focus stack. */
        internal fun isCallInProgress(audioMode: Int): Boolean = audioMode in setOf(
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT,
        )

        internal fun isAudioTrackInitError(errorCode: Int): Boolean =
            errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED

        /** When it was last seen playing, so a restart can tell a killed listen from an old one. */
        internal const val LAST_PLAYING_AT_PREF = "last_playing_at"

        /** How recently it must have been playing for a restart to put the queue back. */
        internal const val RESTORE_AFTER_EXIT_WINDOW_MS = 5 * 60 * 1000L

        /** A clock that went backwards (a car's RTC) counts as long ago, not as recent. */
        private fun within(now: Long, then: Long, windowMs: Long): Boolean =
            then > 0 && now - then in 0..windowMs

        /**
         * Whether a fresh process should put the saved queue back on the player (paused): only
         * when the last one was killed, not stopped on purpose, and died while it was playing
         * or moments after. An exit from before the last listen belongs to an older process.
         */
        internal fun shouldRestoreQueueAfterExit(exit: ExitReasonLogger.LastExit?, lastPlayingAt: Long, now: Long): Boolean =
            exit != null && ExitReasonLogger.isUnexpected(exit.reason) &&
                    exit.timestamp >= lastPlayingAt &&
                    within(now, lastPlayingAt, RESTORE_AFTER_EXIT_WINDOW_MS)

        /**
         * The exit record describes the previous process, and stays the same for this one's
         * whole life — but the service can be destroyed and created again within it. Only the
         * first creation may act on the record.
         */
        @Volatile
        private var lastExitHandled = false

        /**
         * Favourites share the LRU cache with streaming, so a download the index still calls
         * COMPLETED may have been evicted since. It only counts if its bytes are still there,
         * under the track-id key playback reads (older downloads were keyed by URL).
         */
        internal fun isStillQueuedOrCached(download: Download, cache: Cache): Boolean =
            when (download.state) {
                Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> true
                Download.STATE_COMPLETED -> {
                    val key = download.request.customCacheKey
                    val length = download.contentLength.takeIf { it != C.LENGTH_UNSET.toLong() }
                        ?: download.bytesDownloaded
                    key == download.request.id && length > 0 && cache.isCached(key, 0, length)
                }
                else -> false
            }

        /** Converts a playback position in milliseconds to Jellyfin's 100-ns tick units. */
        internal fun msToTicks(positionMs: Long): Long = positionMs * MILLISECONDS_TO_TICKS

        /** Returns true when [track] has the audiobook metadata flag set. */
        internal fun isAudiobookTrack(track: MediaItem?): Boolean =
            track?.mediaMetadata?.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true

        /**
         * Returns true when the periodic audiobook position reporter should be (re)scheduled.
         * Both conditions must hold: the player is actively playing AND the current content is
         * an audiobook.
         */
        internal fun shouldScheduleReporter(isPlaying: Boolean, isPlayingAudiobook: Boolean): Boolean =
            isPlaying && isPlayingAudiobook

        /**
         * Returns the appropriate [CacheEvictor][androidx.media3.datasource.cache.CacheEvictor]
         * based on the stored preference value.
         *
         * @param cacheSizeMb MB value from the "cache_size" preference.
         *   A value of -1 means unlimited (no eviction).
         */
        fun createCacheEvictor(cacheSizeMb: Long): androidx.media3.datasource.cache.CacheEvictor {
            return if (cacheSizeMb < 0) {
                NoOpCacheEvictor()
            } else {
                LeastRecentlyUsedCacheEvictor(cacheSizeMb * 1024L * 1024L)
            }
        }
    }

    @Inject
    lateinit var jellyfin: Jellyfin

    @Inject
    lateinit var mediaCacheDao: MediaCacheDao

    @Inject
    lateinit var okHttpClient: okhttp3.OkHttpClient

    private lateinit var accountManager: com.chamika.dashtune.auth.JellyfinAccountManager
    private lateinit var jellyfinApi: ApiClient
    private lateinit var mediaSourceFactory: DefaultMediaSourceFactory
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var callback: DashTuneSessionCallback

    private val handler: Handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var currentPlaybackTime: Long = 0
    private var currentTrack: MediaItem? = null
    private var isPlayingAudiobook: Boolean = false

    private lateinit var playbackPoll: Runnable
    private lateinit var audiobookPositionReporter: Runnable
    private lateinit var progressReport: Runnable
    private lateinit var reporter: PlaybackReporter
    private lateinit var playerListener: Player.Listener
    private lateinit var focusRecovery: Runnable
    private lateinit var networkErrorRetry: Runnable
    private lateinit var audioTrackRetry: Runnable
    private var networkErrorRetries = 0
    private var audioTrackInitRetries = 0
    private var consecutiveErrorSkips = 0
    private lateinit var focusDenialRetry: FocusDenialRetry

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var networkSync: NetworkSyncScheduler
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Reports to the server outlive the service on purpose: the save sent as playback pauses is
     * often launched moments before onDestroy (swiping the app away pauses, then stops it), and
     * cancelling it there lost the last position. Each call is bounded by the SDK timeouts.
     */
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "browse_categories") {
            mediaLibrarySession.notifyChildrenChanged(ROOT_ID, 4, null)
        }
        if (key == "cache_favourites") {
            cacheFavouriteTracks()
        }
    }

    // Offline cache
    private lateinit var downloadCache: SimpleCache
    private lateinit var downloadManager: DownloadManager
    private lateinit var cacheDataSourceFactory: CacheDataSource.Factory
    private lateinit var httpDataSourceFactory: OkHttpDataSource.Factory

    override fun onCreate() {
        super.onCreate()

        Log.i(LOG_TAG, "onCreate")
        FirebaseUtils.safeLog("Service created")

        accountManager = com.chamika.dashtune.auth.JellyfinAccountManager(
            AccountManager.get(applicationContext)
        )
        // Explicit HTTP timeouts so no single Jellyfin API call can outlive the
        // session callback timeout guards (issue #31).
        jellyfinApi = jellyfin.createApi(
            httpClientOptions = HttpClientOptions(
                connectTimeout = 5.seconds,
                requestTimeout = 7.seconds,
                socketTimeout = 7.seconds,
            )
        )

        reporter = PlaybackReporter(
            api = jellyfinApi,
            prefs = PreferenceManager.getDefaultSharedPreferences(this),
            scope = reportScope,
            isTranscoding = {
                PreferenceManager.getDefaultSharedPreferences(this)
                    .getString("bitrate", "Direct stream") != "Direct stream"
            },
        )

        // Setup offline cache
        val cacheSizeStr = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("cache_size", "200") ?: "200"
        val cacheSizeMb = cacheSizeStr.toLongOrNull() ?: 200L
        val cacheEvictor = createCacheEvictor(cacheSizeMb)

        val cacheDir = File(cacheDir, "exoplayer_cache")
        val databaseProvider = StandaloneDatabaseProvider(this)
        downloadCache = SimpleCache(
            cacheDir,
            cacheEvictor,
            databaseProvider
        )

        // OkHttp rather than the default HttpURLConnection stack so streaming, buffering and
        // prefetch go through the same TLS configuration as the API calls — including any
        // certificate the user approved at sign-in.
        httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Disable ID3 metadata parsing on the transcoded MP3 streams. The app fully owns
        // now-playing metadata via MediaItem.mediaMetadata, so the in-band ID3 pass only adds
        // a redundant, late onMediaMetadataChanged event per transition. That extra publish
        // races the AAOS Media Center's two now-playing surfaces and can leave them showing
        // different tracks (issue #24).
        val extractorsFactory = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA)
        mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory, extractorsFactory)

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
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                // Verifies issue #24: each transition should produce exactly one metadata
                // publish, and the title must match the current item id (no stale event).
                Log.d(
                    LOG_TAG,
                    "onMediaMetadataChanged: title=${mediaMetadata.title} " +
                            "currentId=${mediaLibrarySession.player.currentMediaItem?.mediaId}"
                )
            }

            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                val p = mediaLibrarySession.player
                Log.w(LOG_TAG, "suppressionReason=$reason playWhenReady=${p.playWhenReady}")
                FirebaseUtils.safeSetCustomKey("playback_suppression_reason", reason)

                handler.removeCallbacks(focusRecovery)
                if (reason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) {
                    // Arm the watchdog: if focus is never returned, focusRecovery re-requests it.
                    handler.postDelayed(focusRecovery, FOCUS_RECOVERY_DELAY_MS)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val p = mediaLibrarySession.player
                val failedId = p.currentMediaItem?.mediaId
                Log.e(LOG_TAG, "Player error on $failedId: ${error.errorCodeName}", error)
                FirebaseUtils.safeSetCustomKey("player_error_code", error.errorCodeName)
                FirebaseUtils.safeSetCustomKey("failed_track_id", failedId ?: "")
                FirebaseUtils.safeRecordException(error)

                if (ApiErrors.isUnauthorizedStream(error)) {
                    // Every other track would be refused the same way, so skipping on would
                    // walk the queue and stop with no sign-in offered. Leave the error showing
                    // and the queue in place, and let the callback check the token.
                    Log.w(LOG_TAG, "Stream refused with 401; checking the token")
                    FirebaseUtils.safeLog("Stream refused with 401; checking the token")
                    callback.onStreamUnauthorized(mediaLibrarySession)
                    return
                }

                if (isAudioTrackInitError(error.errorCode) &&
                    audioTrackInitRetries < MAX_AUDIO_TRACK_INIT_RETRIES
                ) {
                    audioTrackInitRetries++
                    Log.w(LOG_TAG, "Audio output could not be opened; retrying current item")
                    FirebaseUtils.safeLog("AudioTrack init failed; retrying current item")
                    handler.removeCallbacks(audioTrackRetry)
                    handler.postDelayed(audioTrackRetry, AUDIO_TRACK_INIT_RETRY_DELAY_MS)
                    return
                }
                // Each item gets its own retry: when this one is skipped, the next starts fresh.
                // consecutiveErrorSkips still bounds a queue in which nothing can play.
                audioTrackInitRetries = 0

                if (isTransientNetworkError(error.errorCode) &&
                    networkErrorRetries < MAX_NETWORK_ERROR_RETRIES
                ) {
                    networkErrorRetries++
                    Log.w(
                        LOG_TAG,
                        "Network error; retrying current item " +
                                "($networkErrorRetries/$MAX_NETWORK_ERROR_RETRIES)"
                    )
                    handler.removeCallbacks(networkErrorRetry)
                    handler.postDelayed(networkErrorRetry, NETWORK_ERROR_RETRY_DELAY_MS)
                    return
                }
                networkErrorRetries = 0

                // With repeat-all on, hasNextMediaItem() never runs out, so a queue in which
                // nothing can play (a server answering 5xx, say) was skipped round forever.
                // Each item gets one chance; then stop, keeping the queue for the next play.
                if (p.hasNextMediaItem() && consecutiveErrorSkips < p.mediaItemCount) {
                    consecutiveErrorSkips++
                    p.seekToNextMediaItem()
                    p.prepare()
                } else if (p.hasNextMediaItem()) {
                    Log.w(LOG_TAG, "Every queued item failed to play; stopping")
                    FirebaseUtils.safeLog("Every queued item failed to play; stopping")
                    consecutiveErrorSkips = 0
                    p.stop()
                } else {
                    p.stop()
                    p.clearMediaItems()
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    // Save audiobook position for the OLD track before transition
                    reportAudiobookStopped(player)

                    PreferenceManager.getDefaultSharedPreferences(this@DashTuneMusicService).edit {
                        putInt(PLAYLIST_INDEX_PREF, player.currentMediaItemIndex)
                    }

                    // The old track's session ends where the poll last saw it; the new one
                    // starts now if it is meant to play (a queue restored paused waits).
                    reporter.stopped(currentPlaybackTime)
                    val started = player.currentMediaItem?.mediaId
                    if (started != null && player.playWhenReady) reporter.started(started)
                    logFormat(player)

                    // Prefetch next tracks
                    val prefetchCount = PreferenceManager
                        .getDefaultSharedPreferences(this@DashTuneMusicService)
                        .getString("prefetch_count", "5")?.toIntOrNull() ?: 5
                    if (prefetchCount > 0) {
                        prefetchNextTracks(player, prefetchCount)
                    }

                    FirebaseUtils.safeSetCustomKey("current_track_id", player.currentMediaItem?.mediaId ?: "")
                    FirebaseUtils.safeSetCustomKey("playlist_size", player.mediaItemCount)

                    isPlayingAudiobook = player.currentMediaItem
                        ?.mediaMetadata?.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true
                }

                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    FirebaseUtils.safeSetCustomKey("is_playing", player.isPlaying)
                    if (player.isPlaying) {
                        networkErrorRetries = 0
                        audioTrackInitRetries = 0
                        consecutiveErrorSkips = 0
                        player.currentMediaItem?.mediaId?.let {
                            if (reporter.openItemId != it) reporter.started(it)
                        }
                        startPlaybackPoll()
                    } else {
                        stopPlaybackPoll()
                        // Save audiobook position — reportAudiobookStopped uses currentTrack
                        // (the old item) so it's safe during transitions
                        reportAudiobookStopped(player)
                        // Four of Home's five sections are derived from listening history,
                        // and the play we just reported is part of it now — rebuild on the
                        // next browse rather than serving the pre-drive Home. Only when the
                        // listen actually stopped: rebuffering in a dead zone (playWhenReady
                        // still set) would just rebuild against a server that can't be reached.
                        val listenStopped = !player.playWhenReady ||
                                player.playbackState == Player.STATE_ENDED
                        if (listenStopped && ::callback.isInitialized) callback.invalidateHome()
                        // A pause keeps the session open (Stopped would end it); the end of the
                        // queue closes it.
                        if (player.playbackState == Player.STATE_ENDED) {
                            reporter.stopped(player.currentPosition)
                        } else if (!player.playWhenReady) {
                            player.currentMediaItem?.mediaId?.let {
                                reporter.progress(it, player.currentPosition, isPaused = true)
                            }
                        }
                    }
                }

                if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) {
                    FirebaseUtils.safeSetCustomKey("repeat_mode", player.repeatMode)
                    PreferenceManager.getDefaultSharedPreferences(this@DashTuneMusicService).edit {
                        putInt("repeat_mode", player.repeatMode)
                    }
                }

                if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                    FirebaseUtils.safeSetCustomKey("shuffle_enabled", player.shuffleModeEnabled)
                    PreferenceManager.getDefaultSharedPreferences(this@DashTuneMusicService).edit {
                        putBoolean("shuffle_enabled", player.shuffleModeEnabled)
                    }
                }
            }
        }

        // Explicit media usage (not AudioAttributes.DEFAULT, whose usage is UNKNOWN) so AAOS's
        // strict audio-focus policy grants/returns focus reliably.
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        player.addListener(playerListener)
        focusDenialRetry = FocusDenialRetry(player, handler)
        player.addListener(focusDenialRetry)

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
                    putLong(LAST_PLAYING_AT_PREF, System.currentTimeMillis())
                }
                handler.postDelayed(playbackPoll, 1000)
            }
        }

        progressReport = Runnable {
            val p = mediaLibrarySession.player
            val id = p.currentMediaItem?.mediaId
            if (p.isPlaying && id != null) {
                reporter.progress(id, p.currentPosition, isPaused = false)
                handler.postDelayed(progressReport, PlaybackReporter.PROGRESS_INTERVAL_MS)
            }
        }

        audiobookPositionReporter = Runnable {
            val p = mediaLibrarySession.player
            if (shouldScheduleReporter(p.isPlaying, isPlayingAudiobook)) {
                reportAudiobookProgress()
                handler.postDelayed(audiobookPositionReporter, AUDIOBOOK_POSITION_REPORT_INTERVAL_MS)
            }
        }

        focusRecovery = Runnable {
            val p = mediaLibrarySession.player
            // Only act if we're still meant to be playing but remain stuck in transient focus
            // loss. Toggling playWhenReady forces ExoPlayer's AudioFocusManager to abandon and
            // re-request focus. If another app legitimately holds focus, the re-request is denied
            // and we stay suppressed (no double audio); if the state was stale, playback resumes.
            if (p.playWhenReady &&
                p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
            ) {
                // A call holds focus legitimately, and asking during it gives our focus up for
                // a refusal: no GAIN would follow when it ends. Look again later instead.
                if (isCallInProgress(audioManager.mode)) {
                    Log.i(LOG_TAG, "Transient focus loss during a call; checking again later")
                    handler.postDelayed(focusRecovery, FOCUS_RECOVERY_DELAY_MS)
                    return@Runnable
                }
                Log.w(LOG_TAG, "Audio focus stuck in transient loss; re-requesting focus")
                FirebaseUtils.safeLog("Audio focus recovery: re-requesting focus")
                focusDenialRetry.recoverFocus()
            }
        }

        networkErrorRetry = Runnable {
            val p = mediaLibrarySession.player
            // prepare() clears the error and resumes at the failed item/position; playWhenReady
            // is retained, so playback continues automatically once the source is reachable.
            if (p.playbackState == Player.STATE_IDLE && p.playerError != null) {
                Log.i(LOG_TAG, "Retrying playback after network error (attempt $networkErrorRetries)")
                p.prepare()
            }
        }

        audioTrackRetry = Runnable {
            val p = mediaLibrarySession.player
            // prepare() clears the error and reopens the audio output at the failed position.
            if (p.playbackState == Player.STATE_IDLE && p.playerError != null) {
                Log.i(LOG_TAG, "Retrying playback after the audio output failed to open")
                p.prepare()
            }
        }

        callback = DashTuneSessionCallback(this, accountManager, jellyfinApi, mediaCacheDao)

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
            .setMediaButtonPreferences(CommandButtons.createButtons(player))
            .build()

        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefsListener)

        if (accountManager.isAuthenticated) {
            onLogin()
        }

        if (!lastExitHandled) {
            lastExitHandled = true
            restoreQueueAfterUnexpectedExit(ExitReasonLogger.readAndLog(this))
        }

        networkSync = NetworkSyncScheduler(
            scope = serviceScope,
            prefs = PreferenceManager.getDefaultSharedPreferences(this),
            isSignedIn = { accountManager.isAuthenticated },
            isLibraryReady = { callback.isLibraryReady },
            sync = { callback.sync() },
            onSynced = {
                mediaLibrarySession.notifyChildrenChanged(ROOT_ID, 4, null)
                // A successful sync proves the server is reachable again.
                callback.invalidateHome()
                Toast.makeText(this, R.string.library_synced, Toast.LENGTH_SHORT).show()
            },
            log = { message ->
                // Crashlytics is off in debug builds, so logcat too.
                Log.i(LOG_TAG, message)
                FirebaseUtils.safeLog(message)
            },
        )

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(LOG_TAG, "Network available")
                FirebaseUtils.safeLog("Network available")

                // A pending network-error retry can fire right away now that connectivity is
                // back; the runnable no-ops unless the player is idle with an error.
                handler.removeCallbacks(networkErrorRetry)
                handler.post(networkErrorRetry)

                if (!accountManager.isAuthenticated) return

                // Home may have been built while the server was unreachable. This callback
                // runs on a ConnectivityManager thread; the session must be told on main.
                handler.post { if (::callback.isInitialized) callback.invalidateHome() }
                handler.post { networkSync.onAvailable(network) }
                // Stopped reports kept while offline: the plays still count.
                handler.post { reporter.flushPending() }
            }

            // Sync waits for a validated network; see NetworkSyncScheduler.
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    handler.post { networkSync.onValidated(network) }
                }
            }

            override fun onLost(network: Network) {
                handler.post { networkSync.onLost(network) }
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    /**
     * A process killed mid-listen came back with an empty player, so the head unit had nothing
     * to resume and the queue had to be found again on Home. Put it back, paused at the
     * saved position: one press of Play carries on, and nothing starts playing by itself.
     */
    private fun restoreQueueAfterUnexpectedExit(lastExit: ExitReasonLogger.LastExit?) {
        if (!accountManager.isAuthenticated) return
        val lastPlayingAt = PreferenceManager.getDefaultSharedPreferences(this)
            .getLong(LAST_PLAYING_AT_PREF, 0L)
        if (!shouldRestoreQueueAfterExit(lastExit, lastPlayingAt, System.currentTimeMillis())) return

        serviceScope.launch {
            try {
                if (callback.restoreSavedQueuePaused(mediaLibrarySession)) {
                    Log.i(LOG_TAG, "Restored queue after unexpected exit (${lastExit?.reasonName})")
                    FirebaseUtils.safeLog("Restored queue after unexpected exit (${lastExit?.reasonName})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not restore the queue after an unexpected exit", e)
                FirebaseUtils.safeRecordException(e)
            }
        }
    }

    private fun startPlaybackPoll() {
        handler.removeCallbacks(playbackPoll)
        handler.post(playbackPoll)
        handler.removeCallbacks(progressReport)
        handler.postDelayed(progressReport, PlaybackReporter.PROGRESS_INTERVAL_MS)
        if (isPlayingAudiobook) {
            handler.removeCallbacks(audiobookPositionReporter)
            handler.postDelayed(audiobookPositionReporter, AUDIOBOOK_POSITION_REPORT_INTERVAL_MS)
        }
    }

    private fun stopPlaybackPoll() {
        handler.removeCallbacks(playbackPoll)
        handler.removeCallbacks(progressReport)
        handler.removeCallbacks(audiobookPositionReporter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_PLAYBACK) {
            if (::mediaLibrarySession.isInitialized) {
                mediaLibrarySession.player.stop()
                mediaLibrarySession.player.clearMediaItems()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH_LIBRARY) {
            Log.i(LOG_TAG, "Refresh library requested")
            if (::callback.isInitialized) {
                callback.invalidateCache()
            }
            if (::mediaLibrarySession.isInitialized) {
                mediaLibrarySession.notifyChildrenChanged(ROOT_ID, 4, null)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_FORCE_EXIT) {
            Log.i(LOG_TAG, "Force exit requested")
            FirebaseUtils.safeLog("Force exit requested")
            if (::mediaLibrarySession.isInitialized) {
                mediaLibrarySession.player.stop()
                mediaLibrarySession.player.clearMediaItems()
            }
            stopSelf()
            handler.postDelayed({ exitProcess(0) }, FORCE_EXIT_DELAY_MS)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        Log.i(LOG_TAG, "onDestroy")
        FirebaseUtils.safeLog("Service destroyed")

        // Destroyed mid-book without a pause first: save where it got to before the player goes.
        if (mediaLibrarySession.player.isPlaying) reportAudiobookStopped(mediaLibrarySession.player)
        // Stopping the service ends whatever session is open; reporter's scope survives this.
        reporter.stopped(
            if (mediaLibrarySession.player.isPlaying) mediaLibrarySession.player.currentPosition else currentPlaybackTime
        )
        mediaLibrarySession.release()
        mediaLibrarySession.player.removeListener(playerListener)
        mediaLibrarySession.player.removeListener(focusDenialRetry)
        focusDenialRetry.release()
        mediaLibrarySession.player.release()
        handler.removeCallbacks(playbackPoll)
        handler.removeCallbacks(audiobookPositionReporter)
        handler.removeCallbacks(progressReport)
        handler.removeCallbacks(focusRecovery)
        handler.removeCallbacks(networkErrorRetry)
        handler.removeCallbacks(audioTrackRetry)

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
        if (::networkSync.isInitialized) networkSync.release()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        serviceScope.cancel()

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
        FirebaseUtils.safeLog("Login applied to service")
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

        if (::callback.isInitialized) {
            callback.invalidateCache()
        }
        mediaLibrarySession.notifyChildrenChanged(ROOT_ID, 4, null)
        cacheFavouriteTracks()
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
                val downloadRequest = DownloadRequest.Builder(id, uri)
                    .setCustomCacheKey(mediaItem.localConfiguration?.customCacheKey ?: id)
                    .build()
                downloadManager.addDownload(downloadRequest)
                downloadManager.resumeDownloads()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to prefetch: $id", e)
                FirebaseUtils.safeSetCustomKey("prefetch_track_id", id)
                FirebaseUtils.safeRecordException(e)
            }
        }
    }

    private fun cacheFavouriteTracks() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean("cache_favourites", false)) return
        if (!accountManager.isAuthenticated) return

        serviceScope.launch(Dispatchers.IO) {
            try {
                val items = jellyfinApi.itemsApi.getItems(
                    recursive = true,
                    filters = listOf(ItemFilter.IS_FAVORITE),
                    includeItemTypes = listOf(BaseItemKind.AUDIO)
                ).content.items

                val preferenceBitrate = prefs.getString("bitrate", "Direct stream")!!
                val bitrate = if (preferenceBitrate == "Direct stream") null else preferenceBitrate.toIntOrNull()
                val allowedContainers = listOf("flac", "mp3", "m4a", "aac", "ogg")

                var queued = 0
                for (item in items) {
                    try {
                        val id = item.id.toString()
                        val existing = downloadManager.downloadIndex.getDownload(id)
                        if (existing != null && isStillQueuedOrCached(existing, downloadCache)) continue
                        val streamUrl = jellyfinApi.universalAudioApi.getUniversalAudioStreamUrl(
                            item.id,
                            container = allowedContainers,
                            audioBitRate = bitrate,
                            maxStreamingBitrate = bitrate,
                            transcodingContainer = "mp3",
                            audioCodec = "mp3"
                        )
                        val downloadRequest = DownloadRequest.Builder(
                            item.id.toString(),
                            streamUrl.toUri()
                        ).setCustomCacheKey(id).build()
                        downloadManager.addDownload(downloadRequest)
                        queued++
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Failed to queue favourite for cache: ${item.id}", e)
                    }
                }
                if (queued > 0) {
                    downloadManager.resumeDownloads()
                    Log.i(LOG_TAG, "Queued $queued favourite tracks for caching")
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to cache favourites", e)
                FirebaseUtils.safeRecordException(e)
            }
        }
    }

    private fun reportAudiobookStopped(player: Player) {
        // Use tracked state (currentTrack/currentPlaybackTime) which reflect the item
        // that was actually playing, not player.currentMediaItem which may already
        // point to the next item during transitions.
        val track = currentTrack ?: player.currentMediaItem ?: return
        val mediaId = track.mediaId
        if (!isAudiobookTrack(track)) return

        val positionMs = if (currentTrack != null) currentPlaybackTime else player.currentPosition
        val ticks = msToTicks(positionMs)
        Log.i(LOG_TAG, "Audiobook stopped: $mediaId at ${positionMs}ms")
        reportScope.launch {
            try {
                jellyfinApi.itemsApi.updateItemUserData(
                    itemId = mediaId.toUUID(),
                    data = UpdateUserItemDataDto(playbackPositionTicks = ticks)
                )
                Log.i(LOG_TAG, "Audiobook position saved: $mediaId at ${positionMs}ms")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to save audiobook position", e)
            }
        }
    }

    private fun reportAudiobookProgress() {
        val track = currentTrack ?: return
        if (!isAudiobookTrack(track)) return

        val positionMs = currentPlaybackTime
        val ticks = msToTicks(positionMs)
        Log.d(LOG_TAG, "Audiobook progress: ${track.mediaId} at ${positionMs}ms")
        reportScope.launch {
            try {
                jellyfinApi.itemsApi.updateItemUserData(
                    itemId = track.mediaId.toUUID(),
                    data = UpdateUserItemDataDto(playbackPositionTicks = ticks)
                )
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to report audiobook progress", e)
            }
        }
    }

    private fun logFormat(player: Player) {
        val format = (player as ExoPlayer).audioFormat
        Log.i(LOG_TAG, "Playing ${format?.containerMimeType} at ${format?.averageBitrate} bps")
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
