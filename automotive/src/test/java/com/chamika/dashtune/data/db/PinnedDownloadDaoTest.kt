package com.chamika.dashtune.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PinnedDownloadDaoTest {

    private lateinit var database: DashTuneDatabase
    private lateinit var dao: PinnedDownloadDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DashTuneDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.pinnedDownloadDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun pinned(
        containerId: String,
        title: String = "Album",
        createdAt: Long = 0L,
        trackIds: String = """["t1","t2"]"""
    ) = PinnedDownloadEntity(
        containerId = containerId,
        title = title,
        subtitle = "Artist",
        artUri = null,
        mediaType = 3,
        trackIds = trackIds,
        totalTracks = 2,
        createdAt = createdAt
    )

    @Test
    fun `getAll returns empty when nothing pinned`() = runTest {
        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun `upsert then getById returns the entity`() = runTest {
        dao.upsert(pinned("album-1", "My Album"))

        val result = dao.getById("album-1")

        assertNotNull(result)
        assertEquals("My Album", result?.title)
        assertEquals(2, result?.totalTracks)
    }

    @Test
    fun `upsert replaces an existing container`() = runTest {
        dao.upsert(pinned("album-1", "Original"))
        dao.upsert(pinned("album-1", "Updated"))

        assertEquals("Updated", dao.getById("album-1")?.title)
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun `getAll is ordered by createdAt ascending`() = runTest {
        dao.upsert(pinned("c", createdAt = 300L))
        dao.upsert(pinned("a", createdAt = 100L))
        dao.upsert(pinned("b", createdAt = 200L))

        assertEquals(listOf("a", "b", "c"), dao.getAll().map { it.containerId })
    }

    @Test
    fun `deleteById removes only that container`() = runTest {
        dao.upsert(pinned("album-1"))
        dao.upsert(pinned("album-2"))

        dao.deleteById("album-1")

        assertNull(dao.getById("album-1"))
        assertNotNull(dao.getById("album-2"))
    }

    @Test
    fun `deleteAll clears everything`() = runTest {
        dao.upsert(pinned("album-1"))
        dao.upsert(pinned("album-2"))

        dao.deleteAll()

        assertTrue(dao.getAll().isEmpty())
    }

    @Test
    fun `getAllContainerIds returns every pinned id`() = runTest {
        dao.upsert(pinned("album-1"))
        dao.upsert(pinned("album-2"))

        val ids = dao.getAllContainerIds()

        assertEquals(2, ids.size)
        assertTrue(ids.containsAll(listOf("album-1", "album-2")))
    }

    @Test
    fun `stored entity preserves all fields`() = runTest {
        val entity = PinnedDownloadEntity(
            containerId = "album-x",
            title = "Title",
            subtitle = "Sub",
            artUri = "http://server/art",
            mediaType = 3,
            trackIds = """["a","b","c"]""",
            totalTracks = 3,
            createdAt = 42L
        )
        dao.upsert(entity)

        val result = dao.getById("album-x")!!

        assertEquals("album-x", result.containerId)
        assertEquals("Title", result.title)
        assertEquals("Sub", result.subtitle)
        assertEquals("http://server/art", result.artUri)
        assertEquals(3, result.mediaType)
        assertEquals("""["a","b","c"]""", result.trackIds)
        assertEquals(3, result.totalTracks)
        assertEquals(42L, result.createdAt)
    }
}
