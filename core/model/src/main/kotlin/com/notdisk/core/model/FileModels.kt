package com.notdisk.core.model

import java.util.Locale

/** Whether a remote entry is a file or a folder. */
enum class FileType {
    FILE,
    FOLDER,
    ;

    companion object {
        fun fromIsDirectory(isDirectory: Boolean, isKnown: Boolean = true): FileType? = when {
            !isKnown -> null
            isDirectory -> FOLDER
            else -> FILE
        }
    }
}

/**
 * Per-item capabilities.
 *
 * A vendor may return the same `FileItem` shape for items that behave differently (a video that
 * can be streamed vs. one that is still transcoding; a file the account may rename vs. a
 * read-only shared item). Capabilities are attached to the *item* so the UI can decide which
 * affordances to show without asking the provider again.
 *
 * `null` means "not determined by the provider", which the UI must treat as "hide the entry
 * point" rather than "allowed".
 */
data class FileCapabilities(
    val canDownload: Boolean? = null,
    val canStream: Boolean? = null,
    val canPreview: Boolean? = null,
    val canRename: Boolean? = null,
    val canMove: Boolean? = null,
    val canCopy: Boolean? = null,
    val canDelete: Boolean? = null,
    val canShare: Boolean? = null,
    val hasThumbnail: Boolean? = null,
    val isStarred: Boolean? = null,
    val isTop: Boolean? = null,
    /** Vendor-side review/moderation state, normalised. `null` = not reported. */
    val isBlocked: Boolean? = null,
) {
    companion object {
        val Unknown = FileCapabilities()
    }
}

/**
 * The unified file/folder model returned by every provider.
 *
 * Deliberately narrow: only fields that at least one real provider can populate *and* that the
 * UI actually consumes. Vendor-only fields live behind [extensions] with an explicit namespace,
 * never as a free-form map in the shared model.
 *
 * ### Timestamps
 * All times are Unix epoch **milliseconds**, UTC. Providers convert from whatever the vendor
 * returns (115 returns both `"YYYY-MM-DD HH:mm"` strings and epoch seconds). `null` means the
 * vendor did not report the value; it never means "0".
 */
data class FileItem(
    /** Provider-scoped opaque handle. See [FileId]. */
    val id: FileId,
    val providerId: ProviderId,
    val accountId: AccountId,
    /** Display name exactly as the remote side reports it (never path-joined). */
    val name: String,
    val type: FileType,
    /** Best-effort MIME type. `null` = undetermined (not "unknown/binary"). */
    val mimeType: String? = null,
    /** Lower-case extension without the leading dot, e.g. `"mp4"`. `null` for folders. */
    val extension: String? = null,
    /** Size in bytes. `null` for folders unless the vendor reports an aggregate. */
    val size: Long? = null,
    /** Parent folder id. `null` when the vendor did not report it or for the root itself. */
    val parentId: FileId? = null,
    /** Creation/upload time, epoch millis. */
    val createdTime: Long? = null,
    /** Last modification time, epoch millis. */
    val modifiedTime: Long? = null,
    /**
     * Thumbnail descriptor. This is a *descriptor*, not a URL: thumbnail URLs are short-lived
     * signed resources and must be resolved on demand (see [ThumbnailRef]).
     */
    val thumbnail: ThumbnailRef? = null,
    val capabilities: FileCapabilities = FileCapabilities.Unknown,
    /** Vendor-specific extras, namespaced by provider. Empty for providers that expose none. */
    val extensions: Map<String, String> = emptyMap(),
) {
    val isFolder: Boolean get() = type == FileType.FOLDER
    val isFile: Boolean get() = type == FileType.FILE

    /** Identity that is safe to persist and to cross provider/account boundaries. */
    val key: CloudFileKey get() = CloudFileKey(providerId, accountId, id)

    /** Name lower-cased for locale-independent sorting/matching. */
    val normalizedName: String get() = name.lowercase(Locale.ROOT)
}

/**
 * Points at a thumbnail the provider can render, *without* embedding a signed URL.
 *
 * Rationale: signed thumbnail URLs expire in minutes. Caching one in Room or in a
 * `FileItem` that is kept in a ViewModel would produce broken images plus a place where a
 * credential-bearing URL leaks into persistence. Instead we keep the identity and re-resolve.
 */
data class ThumbnailRef(
    /** e.g. `1440`, `480`, `0` (original). Provider-specific but comparable within a provider. */
    val sizeHint: String? = null,
    /** Stable content hash the provider groups thumbnails by, when it exposes one. */
    val contentHash: String? = null,
) {
    override fun toString(): String = "ThumbnailRef(size=$sizeHint, hash=${contentHash?.take(8)})"
}

/**
 * One page of a directory listing or search result.
 *
 * Providers translate their own paging model into this shape:
 *  - 115 uses `offset` + `limit` with a `count` total;
 *  - cursor-based vendors return an opaque `nextCursor`.
 *
 * [nextCursor] is opaque to the domain layer. `null` means "no further pages", which is a
 * definitive statement: a provider that simply does not know must not return a cursor that
 * re-fetches page 1 (the shared contract test asserts cursor progress).
 */
data class FilePage(
    val items: List<FileItem>,
    val nextCursor: String? = null,
    /** Total number of entries when the vendor reports it; informational only. */
    val totalCount: Long? = null,
    /** Breadcrumb chain from the root down to the listed folder, when the vendor reports it. */
    val path: List<FilePathSegment> = emptyList(),
) {
    val hasMore: Boolean get() = nextCursor != null

    companion object {
        fun empty(): FilePage = FilePage(items = emptyList(), nextCursor = null)
    }
}

/** One breadcrumb element. [id] is `null` for the synthetic root segment. */
data class FilePathSegment(
    val id: FileId?,
    val name: String,
)

/**
 * A resolved full path (used by "file details" screens).
 * Ordered root → file.
 */
data class FilePath(val segments: List<FilePathSegment>) {
    val displayPath: String get() = segments.joinToString("/") { it.name }
}

/** How a folder listing should be ordered. Providers map this onto their own sort keys. */
enum class FileSortField {
    NAME,
    MODIFIED_TIME,
    CREATED_TIME,
    SIZE,
    TYPE,
}

data class FileSort(
    val field: FileSortField = FileSortField.MODIFIED_TIME,
    val ascending: Boolean = false,
    /** Natural ordering (`file2` before `file10`). Providers that cannot do it ignore it. */
    val naturalOrder: Boolean = true,
) {
    companion object {
        val Default = FileSort()
    }
}

/** A unified search request. */
data class FileSearchQuery(
    val keyword: String,
    /** Restrict to a subtree. `null` = search the whole reachable tree. */
    val parentId: FileId? = null,
    val sort: FileSort = FileSort.Default,
    /**
     * Restrict by unified category. Providers that have a native category filter map it
     * directly (115's `type` parameter); others filter client-side or return
     * [com.notdisk.core.common.error.CloudError.Unsupported].
     */
    val category: FileCategory? = null,
    /** Restrict by extensions, lower-case without dot, e.g. `["mp4", "mkv"]`. */
    val suffixes: Set<String> = emptySet(),
) {
    init {
        require(keyword.isNotBlank()) { "search keyword must not be blank" }
    }
}

/** Unified file categories (115's `type` parameter maps onto these 1:1). */
enum class FileCategory(val code: Int) {
    ALL(0),
    DOCUMENT(1),
    IMAGE(2),
    AUDIO(3),
    VIDEO(4),
    ARCHIVE(5),
    SOFTWARE(6),
    BOOK(7),
}

/** A request to list one folder. */
data class ListChildrenRequest(
    val parentId: FileId,
    val cursor: String? = null,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val sort: FileSort = FileSort.Default,
    val includeFolders: Boolean = true,
) {
    init {
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be in 1..$MAX_PAGE_SIZE" }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 40
        const val MAX_PAGE_SIZE = 200
    }
}
