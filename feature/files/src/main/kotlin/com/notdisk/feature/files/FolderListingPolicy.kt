package com.notdisk.feature.files

import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.FileSortField

/**
 * The two listing rules that are pure state-machine logic, extracted so they can be unit-tested
 * without a database, a network, or an Android device.
 *
 * ### Why these are not inline in the ViewModel
 * Both rules guard against a *concrete, user-visible* bug class, and both are easy to get subtly
 * wrong while looking right in a screenshot:
 *
 *  1. **Stale responses.** A directory listing is an asynchronous round trip that can take
 *     hundreds of milliseconds (or seconds on a slow mobile link). A user who taps folder A,
 *     changes their mind and taps folder B expects B. If the ViewModel simply assigns whatever
 *     `listChildren` returns, A's slower response — arriving after B's — *overwrites* B, and the
 *     app shows the contents of a folder the user already left. Nothing crashes; the address bar
 *     and the list simply disagree. That is why every load is tagged with the generation it was
 *     started in and a response whose generation is no longer current is discarded.
 *
 *  2. **Unbounded "load more".** Infinite scroll must append, and must stop. A provider that
 *     echoes its cursor (or a caller that forgets to compare `nextCursor` to `null`) produces a
 *     list that keeps growing with duplicate rows and never ends. The repository already refuses a
 *     non-advancing cursor; this is the client-side half of the same defence.
 *
 * Keeping them here — rather than as private methods on a `ViewModel` that requires a Hilt graph
 * to instantiate — is what makes "a stale response is discarded" a *test* instead of a hope.
 */
internal class RequestGeneration {

    /**
     * The generation of the request that is currently allowed to write to the UI.
     *
     * Starts at 0 and only ever increases; `@Volatile` because [begin] is called from the UI
     * dispatcher (a tap) while [isCurrent] is read from whatever dispatcher the repository resumed
     * on.
     */
    @Volatile
    var current: Long = 0L
        private set

    /**
     * Invalidates every in-flight request and returns the ticket for the new one.
     *
     * Called *before* the suspend call is launched, never after: a request that starts while an
     * older one is in flight must win, and the only way to guarantee that is to claim the
     * generation first.
     */
    fun begin(): Long = ++current

    /**
     * Whether a response tagged with [generation] may still be applied.
     *
     * A response from an earlier generation belongs to a folder the user has already navigated
     * away from, so applying it would show the wrong folder's contents.
     */
    fun isCurrent(generation: Long): Boolean = generation == current

    /**
     * Invalidates every in-flight request without starting a new one.
     *
     * Used when the screen detaches (or the account changes) so that a response arriving after the
     * fact cannot repopulate a list that is no longer being displayed.
     */
    fun invalidate() {
        current++
    }
}

/**
 * The pagination state of one folder listing.
 *
 * Holds the cursor, the accumulated items, and the two facts that decide whether another page may
 * be requested at all. A single value rather than three fields on the screen state, because
 * "there is a cursor" and "a load-more is running" must not be able to disagree.
 */
internal data class ListingPaging(
    /** Opaque cursor for the *next* page, or `null` when the provider said there is none. */
    val nextCursor: String? = null,
    /** Whether a load-more request is in flight; guards against double-taps and scroll jitter. */
    val isLoadingMore: Boolean = false,
) {
    /**
     * Whether [loadMore] would do anything.
     *
     * The three conditions are all necessary: a cursor must exist (the provider has more), no
     * request may already be running (or the same page would be fetched twice and appended twice),
     * and the caller must have a folder to load into.
     */
    fun canLoadMore(): Boolean = nextCursor != null && !isLoadingMore
}

/**
 * Decides what a completed listing response does to the already-visible items.
 *
 * ### Why an explicit decision table rather than a boolean flag
 * "Append or replace?" is the difference between paging and a duplicated list, and the answer
 * depends on whether a cursor was *sent* (a continuation) rather than on which button was pressed.
 * Encoding it as a function means [FilesViewModel] has one call site and the rule is testable on
 * its own.
 */
internal object ListingMerge {

    /**
     * Merges a page into the visible list.
     *
     * @param existing items already on screen (folder B's rows when a stale page is (correctly)
     *   dropped, the first page when loading more).
     * @param incoming the page that just arrived.
     * @param continuation true when [incoming] is page N+1 of [existing] rather than a fresh
     *   listing of the same folder. A refresh replaces: the provider is authoritative, and a file
     *   deleted on the server must disappear rather than linger behind the fresh rows.
     */
    fun merge(
        existing: List<FileItem>,
        incoming: List<FileItem>,
        continuation: Boolean,
    ): List<FileItem> = if (continuation) existing + incoming else incoming
}

/**
 * Orders a page locally, for sort fields the provider cannot apply server-side.
 *
 * ### Why this exists rather than "only offer what the provider supports"
 * [com.notdisk.core.model.FileSystemCapabilities.serverSideSortFields] records the orders a
 * vendor's API accepts. A provider that cannot sort by size would otherwise force the sort menu to
 * omit 大小 entirely, which reads as "this app cannot sort by size" rather than as a vendor
 * limitation. Sorting the *page* the app already holds is honest — it orders what is visible — and
 * costs one comparison pass per page.
 *
 * ### Ordering rules, chosen to match every mainstream file browser
 *  - folders first, always, regardless of field or direction: a folder has no size and no file
 *    type, so mixing them into a size sort would scatter directories through the list randomly;
 *  - then the selected field, with a name tie-break so equal sizes keep a stable, readable order;
 *  - then direction, applied to the file rows only (folders stay alphabetical).
 */
internal object FileItemOrdering {

    fun apply(items: List<FileItem>, sort: FileSort): List<FileItem> {
        val byName = compareBy<FileItem> { it.normalizedName }
        val fieldComparator = when (sort.field) {
            FileSortField.NAME -> byName
            // Unknown sizes sort last in ascending order instead of pretending to be zero: a
            // folder's aggregate size is frequently unreported, and "0 B" would claim it is empty.
            FileSortField.SIZE -> compareBy(nullsLast()) { it.size }
            FileSortField.MODIFIED_TIME -> compareBy(nullsLast()) { it.modifiedTime }
            FileSortField.CREATED_TIME -> compareBy(nullsLast()) { it.createdTime }
            FileSortField.TYPE -> compareBy<FileItem> { it.extension.orEmpty() }
        }
        val directed = if (sort.ascending) fieldComparator else fieldComparator.reversed()

        return items.sortedWith(
            compareByDescending<FileItem> { it.isFolder }.then(directed).then(byName),
        )
    }
}
