package com.chamika.dashtune.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaCacheDaoTest {

    private lateinit var database: DashTuneDatabase
    private lateinit var dao: MediaCacheDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DashTuneDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.mediaCacheDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // --- helpers ---

    private fun item(
        mediaId: String,
        parentId: String,
        title: String = "Title",
        sortOrder: Int = 0
    ) = CachedMediaItemEntity(
        mediaId = mediaId,
        parentId = parentId,
        title = title,
        subtitle = null,
        artUri = null,
        mediaType = 1,
        isPlayable = true,
        isBrowsable = false,
        sortOrder = sortOrder,
        durationMs = null,
        isFavorite = false,
        extras = null
    )

    // --- hasData ---

    @Test
    fun `hasData returns false when database is empty`() = runTest {
        assertFalse(dao.hasData())
    }

    @Test
    fun `hasData returns true after inserting an item`() = runTest {
        dao.insertAll(listOf(item("id1", "parent1")))

        assertTrue(dao.hasData())
    }

    // --- insertAll & getItem ---

    @Test
    fun `getItem returns null for non-existent mediaId`() = runTest {
        assertNull(dao.getItem("nonexistent"))
    }

    @Test
    fun `getItem returns item after insertion`() = runTest {
        val entity = item("media1", "parent1", "My Song")
        dao.insertAll(listOf(entity))

        val result = dao.getItem("media1")

        assertNotNull(result)
        assertEquals("media1", result?.mediaId)
        assertEquals("My Song", result?.title)
    }

    @Test
    fun `insertAll replaces existing item with same primary key`() = runTest {
        val original = item("id1", "parent1", "Original")
        val updated = item("id1", "parent1", "Updated")
        dao.insertAll(listOf(original))
        dao.insertAll(listOf(updated))

        val result = dao.getItem("id1")

        assertEquals("Updated", result?.title)
    }

    @Test
    fun `insertAll stores multiple items`() = runTest {
        dao.insertAll(listOf(
            item("id1", "parent1"),
            item("id2", "parent1"),
            item("id3", "parent2")
        ))

        assertNotNull(dao.getItem("id1"))
        assertNotNull(dao.getItem("id2"))
        assertNotNull(dao.getItem("id3"))
    }

    // --- getChildrenByParent ---

    @Test
    fun `getChildrenByParent returns empty list when no items for parent`() = runTest {
        dao.insertAll(listOf(item("id1", "other-parent")))

        val result = dao.getChildrenByParent("nonexistent-parent")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getChildrenByParent returns only items matching parentId`() = runTest {
        dao.insertAll(listOf(
            item("id1", "parent-a"),
            item("id2", "parent-a"),
            item("id3", "parent-b")
        ))

        val result = dao.getChildrenByParent("parent-a")

        assertEquals(2, result.size)
        assertTrue(result.all { it.parentId == "parent-a" })
    }

    @Test
    fun `getChildrenByParent returns items sorted by sortOrder ascending`() = runTest {
        dao.insertAll(listOf(
            item("id-c", "parent1", sortOrder = 3),
            item("id-a", "parent1", sortOrder = 1),
            item("id-b", "parent1", sortOrder = 2)
        ))

        val result = dao.getChildrenByParent("parent1")

        assertEquals(listOf(1, 2, 3), result.map { it.sortOrder })
    }

    // --- getParentIds ---

    @Test
    fun `getParentIds returns empty list for unknown mediaId`() = runTest {
        val result = dao.getParentIds("nonexistent")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getParentIds returns all parent IDs for a mediaId`() = runTest {
        dao.insertAll(listOf(
            item("track1", "album-a"),
            item("track1", "album-b")
        ))

        val result = dao.getParentIds("track1")

        assertEquals(2, result.size)
        assertTrue(result.containsAll(listOf("album-a", "album-b")))
    }

    // --- deleteByParent ---

    @Test
    fun `deleteByParent removes only items with matching parentId`() = runTest {
        dao.insertAll(listOf(
            item("id1", "parent-a"),
            item("id2", "parent-a"),
            item("id3", "parent-b")
        ))

        dao.deleteByParent("parent-a")

        assertNull(dao.getItem("id1"))
        assertNull(dao.getItem("id2"))
        assertNotNull(dao.getItem("id3"))
    }

    @Test
    fun `deleteByParent is no-op when no items match`() = runTest {
        dao.insertAll(listOf(item("id1", "parent-x")))

        dao.deleteByParent("nonexistent-parent")

        assertNotNull(dao.getItem("id1"))
    }

    // --- deleteAll ---

    @Test
    fun `deleteAll removes all items`() = runTest {
        dao.insertAll(listOf(
            item("id1", "parent-a"),
            item("id2", "parent-b"),
            item("id3", "parent-c")
        ))

        dao.deleteAll()

        assertFalse(dao.hasData())
        assertNull(dao.getItem("id1"))
        assertNull(dao.getItem("id2"))
    }

    @Test
    fun `deleteAll on empty database does not throw`() = runTest {
        dao.deleteAll()

        assertFalse(dao.hasData())
    }

    // --- entity field validation ---

    @Test
    fun `stored entity preserves all fields correctly`() = runTest {
        val entity = CachedMediaItemEntity(
            mediaId = "book-chapter-1",
            parentId = "book-1",
            title = "Chapter 1",
            subtitle = "Part One",
            artUri = "content://com.chamika.dashtune/art/book-1",
            mediaType = 2,
            isPlayable = true,
            isBrowsable = false,
            sortOrder = 5,
            durationMs = 180_000L,
            isFavorite = true,
            extras = """{"IS_AUDIOBOOK":true}"""
        )
        dao.insertAll(listOf(entity))

        val result = dao.getItem("book-chapter-1")!!

        assertEquals("book-chapter-1", result.mediaId)
        assertEquals("book-1", result.parentId)
        assertEquals("Chapter 1", result.title)
        assertEquals("Part One", result.subtitle)
        assertEquals("content://com.chamika.dashtune/art/book-1", result.artUri)
        assertEquals(2, result.mediaType)
        assertTrue(result.isPlayable)
        assertFalse(result.isBrowsable)
        assertEquals(5, result.sortOrder)
        assertEquals(180_000L, result.durationMs)
        assertTrue(result.isFavorite)
        assertEquals("""{"IS_AUDIOBOOK":true}""", result.extras)
    }
}
