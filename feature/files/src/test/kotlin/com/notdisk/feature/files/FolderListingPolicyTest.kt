package com.notdisk.feature.files

import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.FileSortField
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileCapabilities
import com.notdisk.core.model.FileItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure listing rules that [FilesViewModel] relies on.
 *
 * ### Why these are tested here rather than through the ViewModel
 * [RequestGeneration] and [ListingPaging] encode the two bugs the file browser is most likely to
 * ship with, and both are *timing* bugs: whether a slow response is still allowed to paint, and
 * whether a cursor permits another page. Testing them directly makes each assertion name the exact
 * rule it protects, and keeps the test free of a dispatcher, a database and a network — the whole
 * class runs in microseconds and cannot hang.
 *
 * The end-to-end behaviour (a real slow listing for folder A losing to a fast one for folder B) is
 * covered separately in [FilesViewModelTest].
 */
class FolderListingPolicyTest {

    // ------------------------------------------------------------------ RequestGeneration

    @Test
    fun `a response from the current generation is applied`() {
        val generation = RequestGeneration()

        val ticket = generation.begin()

        assertTrue(generation.isCurrent(ticket))
    }

    @Test
    fun `a response for a folder the user already left is discarded`() {
        val generation = RequestGeneration()

        // The user taps folder A …
        val folderA = generation.begin()
        // … then immediately taps folder B before A's listing comes back.
        val folderB = generation.begin()

        // A's response has arrived last. Applying it would repaint B's screen with A's contents.
        assertFalse(
            generation.isCurrent(folderA),
            "a response for a folder the user left must not be applied",
        )
        assertTrue(generation.isCurrent(folderB))
    }

    @Test
    fun `generations are strictly increasing and never repeat`() {
        val generation = RequestGeneration()

        val tickets = List(5) { generation.begin() }

        assertEquals(tickets.size, tickets.toSet().size, "a ticket must identify exactly one request")
        assertEquals(tickets, tickets.sorted(), "generations must increase monotonically")
    }

    @Test
    fun `invalidate discards everything in flight without starting a request`() {
        val generation = RequestGeneration()
        val ticket = generation.begin()

        generation.invalidate()

        assertFalse(generation.isCurrent(ticket))
        // The next request still receives a fresh, unused ticket.
        assertTrue(generation.isCurrent(generation.begin()))
    }

    // ------------------------------------------------------------------ ListingPaging

    @Test
    fun `load more is possible only while a cursor exists`() {
        assertTrue(ListingPaging(nextCursor = "40").canLoadMore())
    }

    @Test
    fun `a null cursor is a definitive end and forbids another request`() {
        // This is the rule that stops an infinite scroll from re-requesting page 1 forever.
        assertFalse(ListingPaging(nextCursor = null).canLoadMore())
    }

    @Test
    fun `an in-flight page is not requested twice`() {
        // A LazyColumn fires its end-of-list callback on every scroll frame; without this guard the
        // same page would be appended many times over.
        assertFalse(ListingPaging(nextCursor = "40", isLoadingMore = true).canLoadMore())
    }

    // ------------------------------------------------------------------ ListingMerge

    @Test
    fun `a continuation page appends`() {
        val first = listOf(item("a"), item("b"))
        val second = listOf(item("c"))

        val merged = ListingMerge.merge(existing = first, incoming = second, continuation = true)

        assertEquals(listOf("a", "b", "c"), merged.map { it.name })
    }

    @Test
    fun `a fresh listing replaces so deletions disappear`() {
        val stale = listOf(item("a"), item("b"), item("deleted-on-the-server"))

        val merged = ListingMerge.merge(existing = stale, incoming = listOf(item("a")), continuation = false)

        assertEquals(listOf("a"), merged.map { it.name })
    }

    // ------------------------------------------------------------------ FileItemOrdering

    @Test
    fun `folders lead regardless of field and direction`() {
        val folders = listOf(folder("z-folder"), folder("a-folder"))
        val files = listOf(item("b-file", size = 10), item("a-file", size = 5))

        val sorted = FileItemOrdering.apply(folders + files, FileSort(FileSortField.SIZE, ascending = false))

        assertTrue(sorted.take(2).all { it.isFolder }, "folders must lead: $sorted")
    }

    @Test
    fun `unknown sizes sort last in ascending order rather than pretending to be zero`() {
        val items = listOf(
            item("unknown", size = null),
            item("small", size = 5),
            item("large", size = 10_000),
        )

        val sorted = FileItemOrdering.apply(items, FileSort(FileSortField.SIZE, ascending = true))

        assertEquals(listOf("small", "large", "unknown"), sorted.map { it.name })
    }
}

// ---------------------------------------------------------------------------- fixtures

private val TEST_PROVIDER = ProviderId("test")
private val TEST_ACCOUNT = AccountId("account")

private fun item(name: String, size: Long? = null) = FileItem(
    id = FileId("file-$name"),
    providerId = TEST_PROVIDER,
    accountId = TEST_ACCOUNT,
    name = name,
    type = FileType.FILE,
    size = size,
    capabilities = FileCapabilities(canDelete = true),
)

private fun folder(name: String) = FileItem(
    id = FileId("dir-$name"),
    providerId = TEST_PROVIDER,
    accountId = TEST_ACCOUNT,
    name = name,
    type = FileType.FOLDER,
    capabilities = FileCapabilities(canDelete = true),
)
