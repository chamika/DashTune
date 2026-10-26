package com.chamika.dashtune.media

import org.junit.Assert.assertTrue
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import kotlinx.coroutines.runBlocking
import android.os.Looper
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.UUID

/**
 * A container's children are fetched in pages: the SDK's request timeout covers reading the
 * whole body, and a playlist of several hundred tracks in one response never finished
 * downloading on a weak car connection. Also pins which failures are worth a retry.
 */
@RunWith(RobolectricTestRunner::class)
class JellyfinMediaTreePagingTest {

    private lateinit var context: Context
    private lateinit var itemsApi: ItemsApi
    private lateinit var userLibraryApi: UserLibraryApi
    private lateinit var tree: JellyfinMediaTree

    private val playlistId: UUID = UUID.randomUUID()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()

        val api = mockk<ApiClient>(relaxed = true)
        itemsApi = mockk(relaxed = true)
        userLibraryApi = mockk(relaxed = true)
        every { api.itemsApi } returns itemsApi
        every { api.userLibraryApi } returns userLibraryApi

        coEvery { userLibraryApi.getItem(playlistId) } returns Response(
            content = BaseItemDto(id = playlistId, type = BaseItemKind.PLAYLIST, name = "Long drive"),
            status = 200,
            headers = emptyMap()
        )

        val itemFactory = mockk<MediaItemFactory>(relaxed = true)
        every { itemFactory.create(any(), any(), any(), any(), any(), any(), any()) } answers {
            val dto = firstArg<BaseItemDto>()
            val type = if (dto.type == BaseItemKind.PLAYLIST) {
                MediaMetadata.MEDIA_TYPE_PLAYLIST
            } else {
                MediaMetadata.MEDIA_TYPE_MUSIC
            }
            MediaItem.Builder()
                .setMediaId(dto.id.toString())
                .setMediaMetadata(MediaMetadata.Builder().setMediaType(type).build())
                .build()
        }

        tree = JellyfinMediaTree(context, api, itemFactory)
    }

    @Test
    fun `a long playlist is fetched page by page until a short page`() = runTest {
        val tracks = tracks(CHILDREN_PAGE_SIZE * 2 + 50)
        givenPage(0, tracks, total = tracks.size)
        givenPage(CHILDREN_PAGE_SIZE, tracks, total = tracks.size)
        givenPage(CHILDREN_PAGE_SIZE * 2, tracks, total = tracks.size)

        val children = tree.getChildren(playlistId.toString())

        assertEquals(tracks.map { it.id.toString() }, children.map { it.mediaId })
        coVerify(exactly = 3) { itemsApi.getItems(sortBy = any(), parentId = playlistId, startIndex = any(), limit = CHILDREN_PAGE_SIZE) }
    }

    @Test
    fun `paging stops at the reported total even when the last page is full`() = runTest {
        val tracks = tracks(CHILDREN_PAGE_SIZE)
        givenPage(0, tracks, total = tracks.size)

        assertEquals(CHILDREN_PAGE_SIZE, tree.getChildren(playlistId.toString()).size)
        coVerify(exactly = 1) { itemsApi.getItems(sortBy = any(), parentId = playlistId, startIndex = any(), limit = any()) }
    }

    @Test
    fun `a server that ignores the limit is read in one go`() = runTest {
        val tracks = tracks(CHILDREN_PAGE_SIZE + 17)
        coEvery {
            itemsApi.getItems(sortBy = any(), parentId = playlistId, startIndex = 0, limit = CHILDREN_PAGE_SIZE)
        } returns page(tracks, startIndex = 0, total = tracks.size)

        assertEquals(tracks.size, tree.getChildren(playlistId.toString()).size)
        coVerify(exactly = 1) { itemsApi.getItems(sortBy = any(), parentId = playlistId, startIndex = any(), limit = any()) }
    }

    @Test
    fun `a server that ignores the start index does not duplicate the first page`() = runTest {
        val tracks = tracks(CHILDREN_PAGE_SIZE)
        coEvery {
            itemsApi.getItems(sortBy = any(), parentId = playlistId, startIndex = any(), limit = CHILDREN_PAGE_SIZE)
        } returns page(tracks, startIndex = 0, total = CHILDREN_PAGE_SIZE * 3)

        assertEquals(CHILDREN_PAGE_SIZE, tree.getChildren(playlistId.toString()).size)
    }

    @Test
    fun `a DNS failure wrapped by the SDK is retried`() = runTest {
        val tracks = tracks(3)
        var calls = 0
        coEvery {
            itemsApi.getItems(sortBy = any(), parentId = playlistId, startIndex = 0, limit = CHILDREN_PAGE_SIZE)
        } answers {
            if (calls++ == 0) throw TimeoutException("HTTP host unreachable", UnknownHostException("server"))
            page(tracks, startIndex = 0, total = tracks.size)
        }

        assertEquals(3, tree.getChildren(playlistId.toString()).size)
        assertEquals(2, calls)
    }

    @Test
    fun `a request that already used up the read timeout is not retried`() = runTest {
        coEvery {
            itemsApi.getItems(sortBy = any(), parentId = playlistId, startIndex = 0, limit = CHILDREN_PAGE_SIZE)
        } throws TimeoutException("Connection timed out or was interrupted", InterruptedIOException("timeout"))

        try {
            tree.getChildren(playlistId.toString())
            fail("expected the timeout to propagate")
        } catch (e: TimeoutException) {
            // expected
        }
        coVerify(exactly = 1) { itemsApi.getItems(sortBy = any(), parentId = playlistId, startIndex = any(), limit = any()) }
    }

    // --- helpers ---

    private fun tracks(count: Int) = List(count) {
        BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.AUDIO, name = "Track $it")
    }

    private fun givenPage(startIndex: Int, all: List<BaseItemDto>, total: Int) {
        coEvery {
            itemsApi.getItems(sortBy = any(), parentId = playlistId, startIndex = startIndex, limit = CHILDREN_PAGE_SIZE)
        } returns page(all.drop(startIndex).take(CHILDREN_PAGE_SIZE), startIndex, total)
    }

    private fun page(items: List<BaseItemDto>, startIndex: Int, total: Int) = Response(
        content = BaseItemDtoQueryResult(items = items, totalRecordCount = total, startIndex = startIndex),
        status = 200,
        headers = emptyMap()
    )

    // --- Threading and a rejected token ---

    @Test
    fun `a request made from the main thread runs off it`() {
        val main = Looper.getMainLooper()
        assertEquals("Robolectric runs tests on the main looper", main, Looper.myLooper())
        var requestedOnMain: Boolean? = null
        coEvery { userLibraryApi.getItem(playlistId) } answers {
            requestedOnMain = Looper.myLooper() == main
            Response(
                content = BaseItemDto(id = playlistId, type = BaseItemKind.PLAYLIST, name = "Long drive"),
                status = 200,
                headers = emptyMap()
            )
        }

        runBlocking { tree.getItem(playlistId.toString()) }

        assertEquals(false, requestedOnMain)
    }

    @Test
    fun `a 401 is reported and not retried`() {
        var rejected = 0
        val api = mockk<ApiClient>(relaxed = true)
        val failingLibrary = mockk<UserLibraryApi>()
        every { api.userLibraryApi } returns failingLibrary
        coEvery { failingLibrary.getItem(playlistId) } throws InvalidStatusException(401)
        val rejectingTree = JellyfinMediaTree(context, api, mockk(relaxed = true)) { rejected++ }

        val failure = runCatching { runBlocking { rejectingTree.getItem(playlistId.toString()) } }

        assertTrue(failure.exceptionOrNull() is InvalidStatusException)
        assertEquals(1, rejected)
        coVerify(exactly = 1) { failingLibrary.getItem(playlistId) }
    }
}
