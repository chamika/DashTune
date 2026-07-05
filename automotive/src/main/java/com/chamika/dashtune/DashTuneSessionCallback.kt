package com.chamika.dashtune

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.core.content.edit
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Rating
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT
import androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT
import androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_USING_CAR_APP_LIBRARY_INTENT_COMPAT
import androidx.media3.session.MediaConstants.EXTRAS_KEY_MEDIA_ART_SIZE_PIXELS
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import com.chamika.dashtune.Constants.LOG_TAG
import com.chamika.dashtune.auth.JellyfinAccountManager
import com.chamika.dashtune.data.MediaRepository
import com.chamika.dashtune.data.db.MediaCacheDao
import com.chamika.dashtune.media.JellyfinMediaTree
import com.chamika.dashtune.media.MediaItemFactory
import com.chamika.dashtune.media.MediaItemFactory.Companion.IS_AUDIOBOOK_KEY
import com.chamika.dashtune.media.MediaItemFactory.Companion.ROOT_ID
import com.chamika.dashtune.media.MediaItemResolver
import com.chamika.dashtune.signin.SignInActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.serializer.toUUID
import java.util.concurrent.TimeoutException

@OptIn(UnstableApi::class)
class DashTuneSessionCallback(
    private val service: DashTuneMusicService,
    private val accountManager: JellyfinAccountManager,
    private val jellyfinApi: ApiClient,
    private val mediaCacheDao: MediaCacheDao
) : MediaLibraryService.MediaLibrarySession.Callback {

    companion object {
        const val LOGIN_COMMAND = "com.chamika.dashtune.COMMAND.LOGIN"
        const val REPEAT_COMMAND = "com.chamika.dashtune.COMMAND.REPEAT"
        const val SHUFFLE_COMMAND = "com.chamika.dashtune.COMMAND.SHUFFLE"
        const val SYNC_COMMAND = "com.chamika.dashtune.COMMAND.SYNC"

        const val PLAYLIST_IDS_PREF = "playlistIds"
        const val PLAYLIST_INDEX_PREF = "playlistIndex"
        const val PLAYLIST_TRACK_POSITON_MS_PREF = "playlistTrackPositionMs"

        private const val BROWSE_TIMEOUT_MS = 8_000L

        // Bound for queue-mutating callbacks (onAddMediaItems/onSetMediaItems/
        // onPlaybackResumption). Media3 serializes controller commands behind each
        // callback's future — a future that never completes wedges the controller's
        // command queue permanently (issue #31), so every future must complete.
        private const val COMMAND_TIMEOUT_MS = 10_000L
    }

    private lateinit var repository: MediaRepository
    private lateinit var resolver: MediaItemResolver
    private val initLock = Any()

    fun invalidateCache() {
        synchronized(initLock) {
            if (::repository.isInitialized) {
                repository.invalidateCache()
            }
        }
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ConnectionResult {
        Log.i(LOG_TAG, "onConnect")

        val connectionResult = super.onConnect(session, controller)

        val sessionCommands = connectionResult.availableSessionCommands
            .buildUpon()
            .add(SessionCommand(LOGIN_COMMAND, Bundle()))
            .add(SessionCommand(REPEAT_COMMAND, Bundle()))
            .add(SessionCommand(SHUFFLE_COMMAND, Bundle()))
            .add(SessionCommand(SYNC_COMMAND, Bundle()))
            .build()

        return ConnectionResult.accept(
            sessionCommands,
            connectionResult.availablePlayerCommands
        )
    }

    private fun ensureTreeInitialized(artSizeHint: Int? = null) {
        synchronized(initLock) {
            if (!::repository.isInitialized) {
                val artSize = artSizeHint ?: 1024
                Log.d(LOG_TAG, "Initializing media tree with art size: $artSize")

                val itemFactory = MediaItemFactory(service, jellyfinApi, artSize)
                val tree = JellyfinMediaTree(service, jellyfinApi, itemFactory)
                repository = MediaRepository(mediaCacheDao, tree, itemFactory)
                resolver = MediaItemResolver(repository)
            }
        }
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Log.i(LOG_TAG, "onGetRoot")

        val artSize = params?.extras?.getInt(EXTRAS_KEY_MEDIA_ART_SIZE_PIXELS)
        ensureTreeInitialized(artSize)

        // Always return a valid root MediaItem. Returning LibraryResult.ofError here
        // causes the Media3 -> legacy MediaBrowserService bridge to deliver a null root
        // to the AAOS Media Center, which then fails with onConnectFailed and shows
        // a blank screen instead of the sign-in button. The unauthenticated case is
        // surfaced via the auth extras on the root params and the auth check in
        // onGetChildren.
        return SuspendToFutureAdapter.launchFuture {
            try {
                val rootItem = repository.getItem(ROOT_ID)
                val outParams = if (!accountManager.isAuthenticated) {
                    MediaLibraryService.LibraryParams.Builder()
                        .setExtras(authenticationExtras()).build()
                } else {
                    params
                }
                LibraryResult.ofItem(rootItem, outParams)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to get library root", e)
                FirebaseUtils.safeSetCustomKey("failed_operation", "get_library_root")
                FirebaseUtils.safeRecordException(e)
                LibraryResult.ofError(
                    SessionError(
                        SessionError.ERROR_UNKNOWN,
                        service.getString(R.string.library_unavailable)
                    ),
                    retryErrorParams()
                )
            }
        }
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        Log.i(LOG_TAG, "onGetChildren $parentId")
        if (!accountManager.isAuthenticated) {
            return Futures.immediateFuture(
                LibraryResult.ofError(
                    SessionError(
                        SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
                        service.getString(R.string.sign_in_to_your_jellyfin_server)
                    ),
                    MediaLibraryService.LibraryParams.Builder()
                        .setExtras(authenticationExtras()).build()
                )
            )
        }

        ensureTreeInitialized()

        return SuspendToFutureAdapter.launchFuture {
            try {
                val children = withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                    repository.getChildren(parentId)
                } ?: throw java.util.concurrent.TimeoutException("Browse timed out after ${BROWSE_TIMEOUT_MS}ms")
                LibraryResult.ofItemList(children, params)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to get children for $parentId", e)
                FirebaseUtils.safeSetCustomKey("failed_operation", "get_children")
                FirebaseUtils.safeSetCustomKey("parent_id", parentId)
                FirebaseUtils.safeRecordException(e)
                LibraryResult.ofError(
                    SessionError(
                        SessionError.ERROR_UNKNOWN,
                        service.getString(R.string.library_unavailable)
                    ),
                    retryErrorParams()
                )
            }
        }
    }

    private fun authenticationExtras(): Bundle {
        return Bundle().also {
            it.putString(
                EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT,
                service.getString(R.string.sign_in_to_your_jellyfin_server)
            )

            val signInIntent = Intent(service, SignInActivity::class.java)

            val flags = PendingIntent.FLAG_IMMUTABLE
            it.putParcelable(
                EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
                PendingIntent.getActivity(service, 0, signInIntent, flags)
            )

            it.putParcelable(
                EXTRAS_KEY_ERROR_RESOLUTION_USING_CAR_APP_LIBRARY_INTENT_COMPAT,
                PendingIntent.getActivity(service, 0, signInIntent, flags)
            )
        }
    }

    private fun retryExtras(): Bundle {
        return Bundle().also {
            it.putString(
                EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT,
                service.getString(R.string.retry)
            )

            val refreshIntent = Intent(service, DashTuneMusicService::class.java).apply {
                action = DashTuneMusicService.ACTION_REFRESH_LIBRARY
            }
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pending = PendingIntent.getService(service, 0, refreshIntent, flags)

            it.putParcelable(EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, pending)
            it.putParcelable(EXTRAS_KEY_ERROR_RESOLUTION_USING_CAR_APP_LIBRARY_INTENT_COMPAT, pending)
        }
    }

    private fun retryErrorParams(): MediaLibraryService.LibraryParams {
        return MediaLibraryService.LibraryParams.Builder()
            .setExtras(retryExtras())
            .build()
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        Log.i(LOG_TAG, "onGetItem $mediaId")
        ensureTreeInitialized()
        return SuspendToFutureAdapter.launchFuture {
            try {
                val item = withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                    repository.getItem(mediaId)
                } ?: throw TimeoutException("getItem timed out after ${BROWSE_TIMEOUT_MS}ms")
                LibraryResult.ofItem(item, null)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to get item $mediaId", e)
                FirebaseUtils.safeSetCustomKey("failed_operation", "get_item")
                FirebaseUtils.safeRecordException(e)
                LibraryResult.ofError(
                    SessionError(
                        SessionError.ERROR_UNKNOWN,
                        service.getString(R.string.library_unavailable)
                    ),
                    retryErrorParams()
                )
            }
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        Log.i(LOG_TAG, "onAddMediaItems $mediaItems")
        return SuspendToFutureAdapter.launchFuture {
            try {
                withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                    resolver.resolveMediaItems(mediaItems)
                } ?: throw TimeoutException(
                    "resolveMediaItems timed out after ${COMMAND_TIMEOUT_MS}ms"
                )
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to add media items", e)
                FirebaseUtils.safeSetCustomKey("failed_operation", "add_media_items")
                FirebaseUtils.safeRecordException(e)
                // Rethrow to complete the future exceptionally: Media3 drops this one
                // command and keeps processing the queue, keeping the session usable.
                throw e
            }
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        browser: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.i(LOG_TAG, "onSetMediaItems ${mediaItems.size} items")
        return SuspendToFutureAdapter.launchFuture {
            try {
                withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                    resolveSetMediaItems(mediaSession, mediaItems, startIndex, startPositionMs)
                } ?: throw TimeoutException(
                    "onSetMediaItems timed out after ${COMMAND_TIMEOUT_MS}ms"
                )
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to set media items", e)
                FirebaseUtils.safeSetCustomKey("failed_operation", "set_media_items")
                FirebaseUtils.safeRecordException(e)
                // Rethrow to complete the future exceptionally: the current queue keeps
                // playing and the session stays responsive to later commands.
                throw e
            }
        }
    }

    private suspend fun resolveSetMediaItems(
        mediaSession: MediaSession,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaSession.MediaItemsWithStartPosition {
        if (resolver.isSingleItemWithParent(mediaItems)) {
            val singleItem = mediaItems[0]
            val resolvedItems = resolver.expandSingleItem(singleItem)

            val isAudiobookContent = resolvedItems.any {
                it.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true
            }
            handleAudiobookShuffle(mediaSession, isAudiobookContent)

            if (isAudiobookContent) {
                val selectedId = singleItem.mediaId
                val selectedIndex = resolvedItems.indexOfFirst { it.mediaId == selectedId }.coerceAtLeast(0)
                try {
                    val positionMs = withTimeoutOrNull(3000) {
                        getChapterResumePosition(selectedId)
                    } ?: 0L
                    Log.i(LOG_TAG, "Audiobook chapter resume: index=$selectedIndex, position=$positionMs ms")
                    val mediaItemsWithStartPosition = MediaSession.MediaItemsWithStartPosition(
                        resolvedItems,
                        selectedIndex,
                        positionMs
                    )
                    savePlaylist(resolvedItems)
                    return mediaItemsWithStartPosition
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Failed to get chapter resume position", e)
                }
            }

            val mediaItemsWithStartPosition = MediaSession.MediaItemsWithStartPosition(
                resolvedItems,
                // indexOfFirst returns -1 when the selected item isn't among the resolved
                // children (e.g. stale cache); fall back to the first item.
                resolvedItems.indexOfFirst { it.mediaId == singleItem.mediaId }.coerceAtLeast(0),
                startPositionMs
            )
            savePlaylist(resolvedItems)
            return mediaItemsWithStartPosition
        }

        val resolvedItems = resolver.resolveMediaItems(mediaItems)

        val isAudiobookContent = resolvedItems.any {
            it.mediaMetadata.extras?.getBoolean(IS_AUDIOBOOK_KEY) == true
        }
        handleAudiobookShuffle(mediaSession, isAudiobookContent)

        var finalStartIndex = startIndex
        var finalStartPositionMs = startPositionMs

        if (isAudiobookContent && mediaItems.size == 1) {
            val bookId = mediaItems[0].mediaId
            try {
                val resumeInfo = withTimeoutOrNull(3000) {
                    getAudiobookResumePosition(bookId, resolvedItems)
                }
                if (resumeInfo != null) {
                    finalStartIndex = resumeInfo.first
                    finalStartPositionMs = resumeInfo.second
                    Log.i(LOG_TAG, "Audiobook resume: chapter=$finalStartIndex, position=$finalStartPositionMs ms")
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to get audiobook resume position, using local fallback", e)
            }
        }

        val mediaItemsWithStartPosition = MediaSession.MediaItemsWithStartPosition(
            resolvedItems,
            finalStartIndex,
            finalStartPositionMs
        )
        savePlaylist(resolvedItems)
        return mediaItemsWithStartPosition
    }

    private fun savePlaylist(resolvedItems: List<MediaItem>) {
        val playlistIDs = resolvedItems.map { it.mediaId }.joinToString(",")
        Log.d(LOG_TAG, "Saving playlist $playlistIDs")

        PreferenceManager.getDefaultSharedPreferences(service).edit {
            putString(PLAYLIST_IDS_PREF, playlistIDs)
        }
    }

    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        ensureTreeInitialized()
        return SuspendToFutureAdapter.launchFuture {
            try {
                val results = withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                    repository.search(query).size
                } ?: throw java.util.concurrent.TimeoutException("Search timed out after ${BROWSE_TIMEOUT_MS}ms")
                session.notifySearchResultChanged(browser, query, results, params)
                LibraryResult.ofVoid(params)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to search for '$query'", e)
                FirebaseUtils.safeSetCustomKey("failed_operation", "search")
                FirebaseUtils.safeSetCustomKey("search_query", query)
                FirebaseUtils.safeRecordException(e)
                LibraryResult.ofError(
                    SessionError(
                        SessionError.ERROR_UNKNOWN,
                        service.getString(R.string.library_unavailable)
                    ),
                    retryErrorParams()
                )
            }
        }
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        ensureTreeInitialized()
        return SuspendToFutureAdapter.launchFuture {
            try {
                val results = withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                    repository.search(query)
                } ?: throw java.util.concurrent.TimeoutException("Search timed out after ${BROWSE_TIMEOUT_MS}ms")
                LibraryResult.ofItemList(results, params)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to get search results for '$query'", e)
                FirebaseUtils.safeSetCustomKey("failed_operation", "get_search_result")
                FirebaseUtils.safeSetCustomKey("search_query", query)
                FirebaseUtils.safeRecordException(e)
                LibraryResult.ofError(
                    SessionError(
                        SessionError.ERROR_UNKNOWN,
                        service.getString(R.string.library_unavailable)
                    ),
                    retryErrorParams()
                )
            }
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.i(LOG_TAG, "onPlaybackResumption")
        ensureTreeInitialized()

        return SuspendToFutureAdapter.launchFuture {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(service)

                val savedIds = prefs
                    .getString(PLAYLIST_IDS_PREF, "")
                    ?.split(",")
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()

                val playableIds = savedIds.filter { service.isTrackCachedOrOnline(it) }
                if (playableIds.size < savedIds.size) {
                    Log.i(LOG_TAG, "Skipping ${savedIds.size - playableIds.size} unplayable tracks (offline + uncached)")
                }

                val mediaItemsToRestore = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                    playableIds
                        .map { async { repository.getItem(it) } }
                        .awaitAll()
                } ?: run {
                    Log.w(LOG_TAG, "Playback resumption timed out after ${COMMAND_TIMEOUT_MS}ms")
                    emptyList()
                }

                Log.d(LOG_TAG, "Resuming playback with $mediaItemsToRestore")
                FirebaseUtils.safeLog("Restoring playback: index=${prefs.getInt(PLAYLIST_INDEX_PREF, 0)}, trackCount=${mediaItemsToRestore.size}")

                if (mediaItemsToRestore.isEmpty()) {
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
                } else {
                    val savedIndex = prefs.getInt(PLAYLIST_INDEX_PREF, 0)
                        .coerceIn(0, mediaItemsToRestore.lastIndex)
                    MediaSession.MediaItemsWithStartPosition(
                        mediaItemsToRestore,
                        savedIndex,
                        prefs.getLong(PLAYLIST_TRACK_POSITON_MS_PREF, 0),
                    )
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to resume playback", e)
                FirebaseUtils.safeRecordException(e)
                MediaSession.MediaItemsWithStartPosition(
                    emptyList(),
                    0,
                    0L
                )
            }
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        Log.i(LOG_TAG, "CustomCommand: ${customCommand.customAction}")
        when (customCommand.customAction) {
            LOGIN_COMMAND -> {
                service.onLogin()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            REPEAT_COMMAND -> {
                val currentMode = session.player.repeatMode
                session.player.repeatMode = (currentMode + 1) % 3
                session.setMediaButtonPreferences(CommandButtons.createButtons(session.player))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            SHUFFLE_COMMAND -> {
                session.player.shuffleModeEnabled = !session.player.shuffleModeEnabled
                session.setMediaButtonPreferences(CommandButtons.createButtons(session.player))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            SYNC_COMMAND -> {
                return SuspendToFutureAdapter.launchFuture {
                    val success = withContext(Dispatchers.IO) { repository.sync() }
                    if (success) {
                        PreferenceManager.getDefaultSharedPreferences(service).edit {
                            putLong("last_sync_timestamp", System.currentTimeMillis())
                        }
                        (session as MediaLibraryService.MediaLibrarySession).notifyChildrenChanged(ROOT_ID, 4, null)
                        android.widget.Toast.makeText(service, R.string.library_synced, android.widget.Toast.LENGTH_SHORT).show()
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    } else {
                        android.widget.Toast.makeText(service, R.string.sync_failed, android.widget.Toast.LENGTH_SHORT).show()
                        SessionResult(SessionError.ERROR_UNKNOWN)
                    }
                }
            }
        }

        return super.onCustomCommand(session, controller, customCommand, args)
    }

    override fun onSetRating(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaId: String,
        rating: Rating
    ): ListenableFuture<SessionResult> {
        // Controllers can send any Rating subtype; we only support HeartRating (favourites).
        if (rating !is HeartRating) {
            Log.w(LOG_TAG, "onSetRating: unsupported rating type ${rating.javaClass.simpleName}")
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        Log.i(LOG_TAG, "onSetRating ${rating.isHeart}")

        val item = session.player.currentMediaItem
        item?.let {
            val metadata = it.mediaMetadata.buildUpon().setUserRating(rating).build()
            val mediaItem = it.buildUpon().setMediaMetadata(metadata).build()
            session.player.replaceMediaItem(session.player.currentMediaItemIndex, mediaItem)
        }

        return SuspendToFutureAdapter.launchFuture {
            try {
                applyRating(mediaId, rating)
                SessionResult(SessionResult.RESULT_SUCCESS)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to set rating for $mediaId", e)
                FirebaseUtils.safeSetCustomKey("failed_operation", "set_rating")
                FirebaseUtils.safeRecordException(e)
                SessionResult(SessionError.ERROR_UNKNOWN)
            }
        }
    }

    suspend fun sync(): Boolean {
        if (!::repository.isInitialized) return false
        FirebaseUtils.safeLog("Sync started")
        val result = repository.sync()
        FirebaseUtils.safeLog(if (result) "Sync completed" else "Sync failed")
        return result
    }

    private suspend fun applyRating(currentMediaItem: String, newRating: Rating) {
        val id = currentMediaItem.toUUID()

        if (newRating == HeartRating(true)) {
            Log.i(LOG_TAG, "Marking as favorite")
            jellyfinApi.userLibraryApi.markFavoriteItem(id)
        } else {
            Log.i(LOG_TAG, "Unmarking as favorite")
            jellyfinApi.userLibraryApi.unmarkFavoriteItem(id)
        }
    }

    private suspend fun getAudiobookResumePosition(
        bookId: String,
        chapters: List<MediaItem>
    ): Pair<Int, Long>? {
        val response = jellyfinApi.itemsApi.getItems(
            parentId = bookId.toUUID(),
            sortBy = listOf(
                ItemSortBy.PARENT_INDEX_NUMBER,
                ItemSortBy.INDEX_NUMBER,
                ItemSortBy.SORT_NAME
            )
        )

        val chaptersData = response.content.items

        // Multi-chapter book: find last-played chapter that matches resolved items
        if (chaptersData.isNotEmpty()) {
            val lastInProgress = chaptersData
                .filter { (it.userData?.playbackPositionTicks ?: 0) > 0 }
                .filter { it.userData?.lastPlayedDate != null }
                .maxByOrNull { it.userData?.lastPlayedDate!! }

            if (lastInProgress != null) {
                val chapterIndex = chapters.indexOfFirst { it.mediaId == lastInProgress.id.toString() }
                if (chapterIndex >= 0) {
                    val positionMs = (lastInProgress.userData?.playbackPositionTicks ?: 0) / 10_000
                    return Pair(chapterIndex, positionMs)
                }
            }
            // Fall through: children didn't match resolved items (e.g. folder/library items)
        }

        // Single-file audiobook: check the item's own userData
        val itemResponse = jellyfinApi.userLibraryApi.getItem(bookId.toUUID())
        val item = itemResponse.content
        val positionTicks = item.userData?.playbackPositionTicks ?: 0
        if (positionTicks > 0) {
            val positionMs = positionTicks / 10_000
            return Pair(0, positionMs)
        }

        return null
    }

    private suspend fun getChapterResumePosition(chapterId: String): Long {
        val itemResponse = jellyfinApi.userLibraryApi.getItem(chapterId.toUUID())
        val userData = itemResponse.content.userData
        Log.i(LOG_TAG, "Server userData for $chapterId: positionTicks=${userData?.playbackPositionTicks}, played=${userData?.played}, playCount=${userData?.playCount}")
        val positionTicks = userData?.playbackPositionTicks ?: 0
        return positionTicks / 10_000
    }

    private fun handleAudiobookShuffle(
        mediaSession: MediaSession,
        isAudiobookContent: Boolean
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(service)
        val player = mediaSession.player

        if (isAudiobookContent) {
            if (player.shuffleModeEnabled) {
                prefs.edit { putBoolean("shuffle_before_audiobook", true) }
                player.shuffleModeEnabled = false
                mediaSession.setMediaButtonPreferences(CommandButtons.createButtons(player))
            }
        } else {
            if (prefs.getBoolean("shuffle_before_audiobook", false)) {
                prefs.edit { putBoolean("shuffle_before_audiobook", false) }
                player.shuffleModeEnabled = true
                mediaSession.setMediaButtonPreferences(CommandButtons.createButtons(player))
            }
        }
    }
}
