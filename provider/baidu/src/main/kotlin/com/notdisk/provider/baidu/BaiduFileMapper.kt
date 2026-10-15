package com.notdisk.provider.baidu

import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileCapabilities
import com.notdisk.core.model.FileCategory
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.FileSortField
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ThumbnailRef
import com.notdisk.provider.baidu.api.arrayOf
import com.notdisk.provider.baidu.api.asNonBlankStringOrNull
import com.notdisk.provider.baidu.api.boolOf
import com.notdisk.provider.baidu.api.intOf
import com.notdisk.provider.baidu.api.longOf
import com.notdisk.provider.baidu.api.objOf
import com.notdisk.provider.baidu.api.stringOf
import java.util.Locale
import kotlinx.serialization.json.JsonObject

/**
 * Converts Baidu Netdisk's file payloads into the unified [FileItem] model.
 *
 * ### The traps this file exists to contain
 *
 * 1. **Folders and files are distinguished by `isdir`, and that flag is reliable here** (unlike the
 *    115 API, which omits a directory flag from list responses). §3.2 lists `isdir` as a documented
 *    `0/1` field on every entry, so it is the single source of truth. What it does *not* say is what
 *    a folder's `size` means — the measured response shows `size: 0` for a directory — so a folder's
 *    size is reported as `null` ("not reported") rather than `0` ("empty"), because the UI shows a
 *    different thing for each.
 *
 * 2. **The listing field is `server_filename`, not `name`.** Every mapping below reads it, with
 *    `filename`/`name` as fallbacks for the `filemetas` and share payloads, which use different
 *    spellings.
 *
 * 3. **Timestamps are Unix seconds, and there are three of them.** §3.2 documents
 *    `server_mtime`/`server_ctime`/`server_atime` plus `local_mtime`/`local_ctime`. The unified model
 *    has only `createdTime` and `modifiedTime`, so the mapping is explicit: `server_*` wins (it is
 *    the server's authoritative clock), with `local_*` as a fallback because some share responses
 *    carry only those.
 *
 * 4. **`category` is *not* the same axis as `FileCategory`.** §3.3's category parameter is the
 *    **query** axis (1=video…7=torrent), while an entry's `category` field is a *file type* code
 *    where **`6` means "folder"** for directories in `/api/list`. The material flags this conflict
 *    explicitly. This mapper therefore never converts an entry's `category` into a
 *    [FileCategory]; the extension is used instead, which is unambiguous.
 */
internal object BaiduFileMapper {

    /**
     * Builds the synthetic root item.
     *
     * §3.2's `dir` parameter defaults to `/` and `/api/list` returns the *children* of a directory,
     * never the directory itself — so the root node does not exist in any response and must be
     * materialised here. Without it, "navigate to root" and "go up from a top-level folder" would
     * have no target.
     */
    fun root(accountId: AccountId): FileItem = FileItem(
        id = BaiduFileIdCodec.forFolder(BaiduFileIdCodec.ROOT_PATH),
        providerId = BaiduProviderId,
        accountId = accountId,
        name = "全部文件",
        type = FileType.FOLDER,
        mimeType = null,
        extension = null,
        size = null,
        parentId = null,
        createdTime = null,
        modifiedTime = null,
        thumbnail = null,
        capabilities = folderCapabilities(),
    )

    /**
     * Maps one element of a `/api/list`, `/api/categorylist`, `/api/search` or `/api/filemetas`
     * payload.
     *
     * A node with neither a name nor a path is skipped rather than guessed at: an entry this client
     * cannot address would otherwise become a row that fails only when tapped.
     *
     * @param parentPath the directory that was listed. Needed because Baidu's list entries carry an
     *   absolute `path` but **not always** (the `filemetas` payload omits it for some products), in
     *   which case the name is joined onto the listed parent.
     */
    fun fromEntry(node: JsonObject, accountId: AccountId, parentPath: String): FileItem? {
        val name = node.stringOf("server_filename", "filename", "file_name", "name")
            ?: return null

        val isFolder = isDirectory(node)
        val path = node.stringOf("path")?.takeIf { it.isNotBlank() }
            ?: joinPath(parentPath, name)

        return FileItem(
            id = BaiduFileIdCodec.forPath(path, isFolder),
            providerId = BaiduProviderId,
            accountId = accountId,
            name = name,
            type = if (isFolder) FileType.FOLDER else FileType.FILE,
            mimeType = if (isFolder) null else mimeTypeFor(node, name),
            extension = if (isFolder) null else extensionFor(node, name),
            // A folder's `size` is 0 in every measured response, which means "not applicable", not
            // "empty". Reporting `null` keeps that distinction visible to the UI.
            size = if (isFolder) null else node.longOf("size", "file_size"),
            parentId = FileId(BaiduFileIdCodec.forFolder(parentPath).value),
            createdTime = createdTimeOf(node),
            modifiedTime = modifiedTimeOf(node),
            thumbnail = if (isFolder) null else thumbnailRefOf(node),
            capabilities = if (isFolder) folderCapabilities(node) else fileCapabilities(node),
            extensions = buildMap {
                // Vendor-specific extras that the UI does not model but that help diagnosis, and
                // that are genuinely present in the documented responses.
                node.stringOf("md5")?.let { put(BaiduExt.MD5, it.lowercase(Locale.ROOT)) }
                node.stringOf("fs_id")?.let { put(BaiduExt.FS_ID, it) }
                node.intOf("category")?.let { put(BaiduExt.CATEGORY_CODE, it.toString()) }
                // `dlink` and the preview URLs are signed, short-lived bearer resources. They are
                // deliberately NOT copied into `extensions`: an extension map is persisted with the
                // metadata cache, and persisting a signed URL is exactly what `docs/security.md`
                // forbids. Their presence is recorded as a boolean instead, which is all the UI
                // needs to decide whether a preview affordance is worth offering.
                node.stringOf("dlink")?.let { put(BaiduExt.HAS_DLINK, "true") }
                node.stringOf("docpreview")?.let { put(BaiduExt.HAS_DOC_PREVIEW, "true") }
                node.stringOf("picdocpreview")?.let { put(BaiduExt.HAS_TEXT_PREVIEW, "true") }
            },
        )
    }

    /**
     * Builds a [FilePage] from a listing.
     *
     * ### Cursor model, and the honest end-of-list rule
     * §3.2 documents `page` + `num` pagination and **no** "has more" field — the reference material
     * verified this by grepping the entire capture, and notes that only `/api/search` returns
     * `has_more`. So the end of the listing is decided by **what was actually returned**: a page
     * shorter than `num` (or empty) means the end. That rule is unambiguous and cannot loop, which is
     * what the shared contract suite asserts.
     *
     * The cursor is the next `page` number rendered as a string. It is opaque to the domain layer,
     * which never parses it.
     *
     * @param requestedPage the 1-based page that was requested.
     * @param requestedNum the page size that was requested.
     * @param hasMoreHint `/api/search`'s `has_more` field, when the endpoint provides one. It is
     *   preferred over the length heuristic because it is authoritative, but it is only a *hint*: a
     *   `1` never overrides an empty page, because that would produce an endless loop.
     */
    fun pageFromListing(
        entries: List<JsonObject>,
        accountId: AccountId,
        parentPath: String,
        requestedPage: Int,
        requestedNum: Int,
        totalCount: Long? = null,
        hasMoreHint: Boolean? = null,
    ): FilePage {
        val items = entries.mapNotNull { fromEntry(it, accountId, parentPath) }
        val isLastPage = when {
            entries.isEmpty() -> true
            hasMoreHint != null -> !hasMoreHint
            else -> entries.size < requestedNum
        }

        return FilePage(
            items = items,
            nextCursor = if (isLastPage) null else (requestedPage + 1).toString(),
            totalCount = totalCount,
            // The vendor's own `path` breadcrumb is absent from `/api/list` responses (unlike 115's),
            // so no path is claimed here. `getPath` reconstructs one from the path string, which is
            // exact for this provider because the id *is* the path.
            path = emptyList(),
        )
    }

    // ------------------------------------------------------------------ field helpers

    /**
     * Reads the directory flag.
     *
     * `isdir` is the documented field (§3.2). `isdir` has been observed as both `1` and `true`, so
     * the coercing reader is used. When the flag is entirely absent the entry is treated as a file:
     * that is the safe default because a file-scoped request against a folder returns empty rather
     * than erroring, whereas the reverse would try to *list* a file.
     */
    private fun isDirectory(node: JsonObject): Boolean = node.boolOf("isdir") ?: false

    /**
     * Derives the MIME type.
     *
     * `/api/list` does not return a MIME type. When the extension is unknown the value is `null` —
     * deliberately *not* `application/octet-stream`, because `null` means "undetermined" while
     * `octet-stream` asserts something false and would push the UI into a download-only affordance
     * for a file it might actually preview.
     */
    private fun mimeTypeFor(node: JsonObject, name: String): String? =
        guessMimeType(extensionFor(node, name))

    /**
     * Derives the extension from the file name.
     *
     * The name is the only reliable source: this API has no `ico`-style extension field (115 does).
     * A name with no dot, or whose "extension" is implausibly long, yields `null` rather than a
     * guess.
     */
    private fun extensionFor(node: JsonObject, name: String): String? {
        val fromName = name.substringAfterLast('.', missingDelimiterValue = "")
        return fromName
            .takeIf { it.isNotBlank() && it.length <= MAX_EXTENSION_LENGTH && it != name }
            ?.lowercase(Locale.ROOT)
    }

    private fun createdTimeOf(node: JsonObject): Long? =
        epochSecondsToMillis(node.longOf("server_ctime", "local_ctime"))

    private fun modifiedTimeOf(node: JsonObject): Long? =
        epochSecondsToMillis(node.longOf("server_mtime", "local_mtime"))

    /**
     * Baidu sends Unix **seconds**; the unified model uses **milliseconds**.
     *
     * A naive `* 1000` would turn an already-millisecond value into a date in the year 50000, and a
     * naive passthrough would show 1970. The magnitude check makes the conversion total instead of
     * dependent on which endpoint produced the value. `0` and negative values mean "not recorded"
     * for this API (the measured listing carries `server_atime: 0`), so they map to `null`.
     */
    internal fun epochSecondsToMillis(value: Long?): Long? {
        if (value == null || value <= 0) return null
        return if (value < MILLISECOND_THRESHOLD) value * 1_000L else value
    }

    /**
     * Builds the thumbnail descriptor.
     *
     * Only a *descriptor* is produced — never a URL. §8.2 documents that a `thumbs` URL carries a
     * `sign`, an `expires` and a `time` parameter, i.e. it is a short-lived credential; putting one
     * into a `FileItem` would persist a credential into the metadata cache. The `md5` is carried
     * instead, because the signature covers the MD5 path segment and the resolver needs it.
     */
    private fun thumbnailRefOf(node: JsonObject): ThumbnailRef? {
        val thumbs = node.objOf("thumbs") ?: return null
        // An empty `thumbs: {}` object is the documented shape for "no thumbnail" (§3.4's measured
        // search response), so absence must be expressed through the ref being `null`.
        val hasAnyUrl = thumbs.stringOf("url1", "url2", "url3", "icon") != null
        if (!hasAnyUrl) return null
        return ThumbnailRef(
            sizeHint = DEFAULT_THUMBNAIL_SIZE,
            contentHash = node.stringOf("md5")?.lowercase(Locale.ROOT),
        )
    }

    /**
     * Capabilities for a folder.
     *
     * Every flag is written as an explicit `Boolean?` literal rather than a bare `true`/`false` so
     * that the *type* records the distinction the model depends on: `null` means "not determined by
     * the provider" (the UI hides the entry point), while `false` means "determined to be unavailable"
     * (the UI shows it disabled with a reason). Inferring `Boolean` here would collapse that.
     */
    private fun folderCapabilities(node: JsonObject? = null): FileCapabilities = FileCapabilities(
        // A folder has no bytes to download. `/api/zipfile/copy` packages one, but that endpoint
        // needs a password parameter whose semantics are only partly documented (§8.6), and this
        // build does not drive it — so download is explicitly unavailable rather than failing later.
        canDownload = false as Boolean?,
        canStream = false as Boolean?,
        canPreview = false as Boolean?,
        // `/api/filemanager` with `opera=rename|move|copy|delete` accepts a `path`-shaped filelist,
        // which is exactly the form this provider produces.
        canRename = true as Boolean?,
        canMove = true as Boolean?,
        canCopy = true as Boolean?,
        canDelete = true as Boolean?,
        canShare = null,
        hasThumbnail = false as Boolean?,
        isStarred = null,
        isTop = null,
        // `/api/list` documents `black_tag`; a non-zero value is the vendor's blacklist marker.
        isBlocked = node?.let { blockedOf(it) },
    )

    private fun fileCapabilities(node: JsonObject): FileCapabilities {
        val name = node.stringOf("server_filename", "filename", "file_name", "name").orEmpty()
        val extension = extensionFor(node, name)
        val blocked = blockedOf(node)
        return FileCapabilities(
            canDownload = (blocked != true) as Boolean?,
            // §7.3's `/api/streaming` is the only streaming entry point and applies to video files.
            // This is a *hint* used to decide whether to show a play affordance in a list; the
            // streaming call itself is what finally decides (it can still answer `errno 133`).
            canStream = (blocked != true && extension in STREAMABLE_EXTENSIONS) as Boolean?,
            canPreview = (blocked != true && extension in PREVIEWABLE_EXTENSIONS) as Boolean?,
            canRename = true as Boolean?,
            canMove = true as Boolean?,
            canCopy = true as Boolean?,
            canDelete = true as Boolean?,
            canShare = null,
            hasThumbnail = hasThumbnail(node),
            isStarred = null,
            isTop = null,
            isBlocked = blocked,
        )
    }

    /**
     * Whether the entry carries at least one thumbnail variant.
     *
     * §3.4's measured search response carries `thumbs: {}` for a *search hit*, which is the vendor's
     * way of saying "no thumbnail here" — so an empty object must read as `false`, not as "present".
     */
    private fun hasThumbnail(node: JsonObject): Boolean =
        node.objOf("thumbs")?.stringOf("url1", "url2", "url3", "icon") != null

    /** §3.2: `black_tag` is the blacklist marker, where `0` means normal. */
    private fun blockedOf(node: JsonObject): Boolean? {
        val blackTag = node.intOf("black_tag") ?: return null
        return blackTag != 0
    }

    /**
     * Converts the unified [FileSort] into Baidu's `order` + `desc` parameters.
     *
     * §3.2 documents exactly three sort keys: `name`, `time` and `size`. `createdTime` and `type`
     * therefore have no server-side equivalent and are mapped onto the closest available key rather
     * than being silently dropped — but `capabilities().fileSystem.serverSideSortFields` declares
     * only the three real ones, so the UI knows a local re-sort may be needed.
     */
    fun toVendorSort(sort: FileSort): Pair<String, String> {
        val field = when (sort.field) {
            FileSortField.NAME -> "name"
            FileSortField.SIZE -> "size"
            // `time`, `createdTime` and `type` all fall back to `time`: it is the vendor's default
            // and the only remaining key.
            FileSortField.MODIFIED_TIME, FileSortField.CREATED_TIME, FileSortField.TYPE -> "time"
        }
        // Baidu uses `desc=1` for descending, which is the opposite polarity to `asc=1`.
        return field to if (sort.ascending) "0" else "1"
    }

    /**
     * Maps the unified [FileCategory] onto §3.3's `category` query values.
     *
     * The mapping is **not** the identity, and the numbered values are not sequential: `5` does not
     * exist as a file type at all (it is a permission bit named `CREATE_DIR`), and `6` is "other"
     * rather than "archive". `FileCategory.ARCHIVE` and `FileCategory.SOFTWARE` therefore map onto
     * `6` and `7` respectively, which is an approximation of the vendor's coarser taxonomy and is
     * documented as such in `capabilities()`.
     */
    fun toVendorCategory(category: FileCategory?): String? = when (category) {
        null, FileCategory.ALL -> null
        FileCategory.VIDEO -> "1"
        FileCategory.AUDIO -> "2"
        FileCategory.IMAGE -> "3"
        FileCategory.DOCUMENT -> "4"
        // "其他" (other) is the closest the vendor has to an archive bucket.
        FileCategory.ARCHIVE -> "6"
        FileCategory.BOOK -> "4"
        FileCategory.SOFTWARE -> "7"
    }

    /** Joins a listed parent with a child name, keeping the canonical absolute form. */
    internal fun joinPath(parentPath: String, name: String): String =
        if (parentPath == BaiduFileIdCodec.ROOT_PATH) {
            "/$name"
        } else {
            "$parentPath/$name"
        }

    /**
     * Image extensions for which §8.2's `thumbs` object is populated. Used only as a hint: the
     * authoritative signal is whether the response actually carried a `thumbs` URL.
     */
    internal val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff", "svg", "ico",
        "jpe", "livp", "avci", "arw", "cr2", "cr3", "nef", "dng", "rw2", "raf", "cur", "svgz",
    )

    /**
     * Extensions §8.1's registry routes to the `video-plugin`.
     *
     * The registry lists these as the container whitelist the player accepts, which makes it a better
     * source than a guess: an extension in this set is one the vendor's own player will agree to
     * open.
     */
    internal val STREAMABLE_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts", "mpg", "mpeg", "rmvb", "3gp",
        "vob", "mts", "m2ts", "m3u8", "rm", "asf", "f4v", "ogv", "divx", "mxf",
    )

    /** Extensions §8.1 routes to the `audio-plugin`. */
    internal val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "aac", "m4a", "ogg", "wma", "ape", "opus", "aiff", "alac", "amr",
    )

    /**
     * Extensions §8.1 routes to the `codeReader-plugin`, i.e. text the app can render inline.
     *
     * Transcribed from the registry's first row so the set is the vendor's, not a guess.
     */
    internal val TEXT_EXTENSIONS = setOf(
        "txt", "md", "js", "json", "py", "html", "htm", "xml", "c", "cpp", "h", "cs", "asp", "css",
        "pas", "diff", "patch", "erl", "groovy", "java", "jsp", "pl", "php", "rb", "sass", "scss",
        "scala", "sql", "vb", "xhtml", "less", "lua", "go", "bat", "wml", "cc", "ejs", "vue", "sh",
        "as", "kt", "kts", "gradle", "toml", "yml", "yaml", "ini", "conf", "log", "properties",
    )

    /**
     * Extensions §8.1 routes to the `pdf-plugin` (the default reader for Office and PDF).
     *
     * The registry gives `pdf-plugin` `pluginType: 2` priority for `doc/docx/ppt/pptx/xls/xlsx/pdf`,
     * and records that the `office-plugin` (WPS) set is a *different*, broader list. Since this build
     * hands the file to the platform's own viewer rather than to WPS, the `pdf-plugin` set is the
     * honest one to claim.
     */
    internal val DOCUMENT_EXTENSIONS = setOf(
        "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
    )

    /** Extensions §8.1 routes to the `unzip-plugin`. */
    internal val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")

    /** Everything this build can classify without contacting the vendor. */
    val PREVIEWABLE_EXTENSIONS: Set<String> =
        IMAGE_EXTENSIONS + STREAMABLE_EXTENSIONS + AUDIO_EXTENSIONS +
            TEXT_EXTENSIONS + DOCUMENT_EXTENSIONS

    /** §8.2: the `url3`/`url2`/`url1` variants are `c850_u580`/`c360_u270`/`c140_u90`. */
    private const val DEFAULT_THUMBNAIL_SIZE = "850"

    /** A longer "extension" is part of the name, not a type indicator. */
    private const val MAX_EXTENSION_LENGTH = 10

    /** 1e11 seconds is the year 5138; anything larger is already milliseconds. */
    private const val MILLISECOND_THRESHOLD = 100_000_000_000L
}

/**
 * Keys used in [FileItem.extensions].
 *
 * The shared model forbids `Map<String, Any>`, and this is the sanctioned alternative: a small,
 * namespaced, string-valued bag for fields that are genuinely vendor-specific and genuinely needed
 * (diagnostics, and the MD5 that thumbnail resolution is keyed on). Anything the UI acts on has a
 * first-class field instead.
 *
 * Note what is **absent**: no signed URL is ever stored here. Only booleans recording that a URL was
 * present, because the presence of a preview URL is what decides whether an affordance is offered,
 * while the URL itself is a short-lived secret that must be re-resolved on demand.
 */
internal object BaiduExt {
    const val MD5 = "baidu.md5"
    const val FS_ID = "baidu.fsId"
    const val CATEGORY_CODE = "baidu.categoryCode"
    const val HAS_DLINK = "baidu.hasDlink"
    const val HAS_DOC_PREVIEW = "baidu.hasDocPreview"
    const val HAS_TEXT_PREVIEW = "baidu.hasTextPreview"
}

/**
 * Best-effort extension → MIME mapping.
 *
 * Deliberately small and explicit rather than a dependency: it only needs to cover the types this
 * app actually branches on (media, images, text, PDF, office), and an unknown extension returning
 * `null` is more honest than a large table that guesses.
 */
internal fun guessMimeType(extension: String?): String? = when (extension?.lowercase(Locale.ROOT)) {
    "mp4" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "flv" -> "video/x-flv"
    "wmv" -> "video/x-ms-wmv"
    "m4v" -> "video/x-m4v"
    "ts", "mts", "m2ts" -> "video/mp2t"
    "mpg", "mpeg" -> "video/mpeg"
    "3gp" -> "video/3gpp"
    "rmvb", "rm" -> "application/vnd.rn-realmedia-vbr"
    "m3u8" -> "application/vnd.apple.mpegurl"

    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "aac" -> "audio/aac"
    "m4a" -> "audio/mp4"
    "ogg" -> "audio/ogg"
    "wma" -> "audio/x-ms-wma"
    "ape" -> "audio/x-ape"
    "opus" -> "audio/opus"

    "jpg", "jpeg", "jpe" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "webp" -> "image/webp"
    "heic", "heif" -> "image/heic"
    "tif", "tiff" -> "image/tiff"
    "svg", "svgz" -> "image/svg+xml"
    "ico" -> "image/x-icon"

    "txt" -> "text/plain"
    "md" -> "text/markdown"
    "log" -> "text/plain"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "csv" -> "text/csv"
    "yml", "yaml" -> "application/yaml"
    "html", "htm" -> "text/html"

    "pdf" -> "application/pdf"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    "zip" -> "application/zip"
    "rar" -> "application/vnd.rar"
    "7z" -> "application/x-7z-compressed"
    "tar" -> "application/x-tar"
    "gz" -> "application/gzip"

    else -> null
}
