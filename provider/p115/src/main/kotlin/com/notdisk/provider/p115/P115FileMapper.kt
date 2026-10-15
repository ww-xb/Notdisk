package com.notdisk.provider.p115

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.FileCapabilities
import com.notdisk.core.model.FileCategory
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FilePath
import com.notdisk.core.model.FilePathSegment
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.FileSortField
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ThumbnailRef
import com.notdisk.provider.p115.api.arrayOf
import com.notdisk.provider.p115.api.asBoolOrNull
import com.notdisk.provider.p115.api.asLongOrNull
import com.notdisk.provider.p115.api.asNonBlankStringOrNull
import com.notdisk.provider.p115.api.boolOf
import com.notdisk.provider.p115.api.intOf
import com.notdisk.provider.p115.api.longOf
import com.notdisk.provider.p115.api.objOf
import com.notdisk.provider.p115.api.stringOf
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * Converts 115's file payloads into the unified [FileItem] model.
 *
 * ### The three traps this file exists to contain
 *
 * 1. **Folders and files are distinguished by field absence, not by a flag.** §4 states the rule
 *    explicitly: a folder object has `cid` and no `fid`; a file object has `fid`. There is a `d`
 *    ("is directory") field in the *detail* response, but it is **not** present in list responses,
 *    so it cannot be relied upon. Every conversion therefore goes through [P115FileIdCodec], which
 *    encodes the distinction into the id itself.
 *
 * 2. **Field types change between endpoints.** §4's closing note records that in search results
 *    `aid`/`s`/`sta`/`t` are strings while in list responses they are numbers. All reads therefore
 *    go through the coercing accessors in `P115Json` rather than assuming a type.
 *
 * 3. **There are four different time fields and they do not mean the same thing.** §4 lists `t`
 *    (formatted modified time), `te` (modified, epoch seconds), `tp` (upload/created), `tu` (user
 *    modified) and `to` (opened). The unified model has just `createdTime` and `modifiedTime`, so
 *    the mapping is explicit: `te` wins for modification (it is the only one guaranteed to be
 *    numeric), and `tp` is used for creation. `t` is parsed only as a fallback because it is
 *    formatted in **local time with no zone**, which is a lossy and ambiguous source.
 */
internal object P115FileMapper {

    /**
     * Builds the synthetic root item.
     *
     * §3.1 documents that `cid=0` is the root and that `/files` returns the *children* of a folder,
     * never the folder itself. So the root node does not exist in any response and must be
     * materialised here — with `id = folder:115:cid=0`. Without this, "navigate to root" and
     * "navigate up from a top-level folder" would have no target.
     */
    fun root(accountId: AccountId): FileItem = FileItem(
        id = P115FileIdCodec.forFolder(ROOT_CID),
        providerId = P115ProviderId,
        accountId = accountId,
        name = "根目录",
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
     * Maps one element of a `GET /files` or `GET /files/search` `data` array.
     *
     * @param parentCid the folder that was listed. Needed because a file object carries its parent
     *   in `cid`, while a folder object's `cid` is its *own* id — the same key means different
     *   things depending on the kind, which is exactly the confusion §1.5 warns about. Passing the
     *   parent explicitly removes the ambiguity instead of re-deriving it.
     */
    fun fromListEntry(
        node: JsonObject,
        accountId: AccountId,
        parentCid: String,
        /** Receives the pickcode discovered in this payload so the caller can cache it. */
        onPickcode: (FileId, String?) -> Unit = { _, _ -> },
    ): FileItem? {
        val name = node["n"].asNonBlankStringOrNull()
            // `files/get_info` and some other endpoints spell it `file_name`.
            ?: node["file_name"].asNonBlankStringOrNull()
            ?: return null

        val fid = node.stringOf("fid", "file_id")
        val cid = node.stringOf("cid")
        val pickcode = node.stringOf("pc", "pickcode", "pick_code")

        // §4: `if (item.fid) → file` / `else if (item.cid) → folder`. A node with neither is not
        // addressable by this client, so it is skipped rather than guessed at.
        val isFolder = fid == null
        if (isFolder && cid == null) return null

        val id = if (isFolder) {
            P115FileIdCodec.forFolder(cid = cid!!)
        } else {
            P115FileIdCodec.forFile(fid = fid!!, parentCid = cid ?: parentCid)
        }
        // The pickcode is *derived metadata*, not identity (see P115FileIdCodec), so it is handed to
        // the caller's cache rather than baked into the id.
        if (!isFolder) onPickcode(id, pickcode)

        return FileItem(
            id = id,
            providerId = P115ProviderId,
            accountId = accountId,
            name = name,
            type = if (isFolder) FileType.FOLDER else FileType.FILE,
            mimeType = if (isFolder) null else mimeTypeFor(node, name),
            extension = if (isFolder) null else extensionFor(node, name),
            size = if (isFolder) null else node.longOf("s", "file_size", "size"),
            // A folder's parent is the folder that was listed. A file's `cid` *is* its parent
            // (§1.5: the same key means "own id" for a folder and "parent id" for a file).
            parentId = FileId(
                P115FileIdCodec.forFolder(if (isFolder) parentCid else (cid ?: parentCid)).value,
            ),
            createdTime = createdTimeOf(node),
            modifiedTime = modifiedTimeOf(node),
            thumbnail = if (isFolder) null else thumbnailRefOf(node, name),
            capabilities = if (isFolder) folderCapabilities(node) else fileCapabilities(node),
            extensions = buildMap {
                // Vendor-specific extras that the UI does not model but that help diagnosis and
                // that are genuinely present in list responses.
                node.stringOf("sha", "sha1")?.let { put(P115Ext.SHA1, it.lowercase(Locale.ROOT)) }
                node.stringOf("ico")?.let { put(P115Ext.ICON, it) }
                node.stringOf("class")?.let { put(P115Ext.CLASS, it) }
                node.boolOf("is_top")?.let { put(P115Ext.IS_TOP, it.toString()) }
                node.intOf("fc")?.let { put(P115Ext.FOLDER_COUNT, it.toString()) }
                node.intOf("check_code")?.let { put(P115Ext.CHECK_CODE, it.toString()) }
            },
        )
    }

    /**
     * Maps `GET /category/get` — the *detail* response, which has a different shape again.
     *
     * This endpoint returns a flat object (not a list), exposes the vendor's `pick_code` spelling,
     * and includes a full `paths` chain that becomes the breadcrumb. It is the only endpoint that
     * reports a folder's *own* parent, which is why [getFile] prefers it.
     */
    fun fromCategoryGet(
        payload: JsonObject,
        accountId: AccountId,
        requestedId: FileId,
        onPickcode: (FileId, String?) -> Unit = { _, _ -> },
    ): CloudResult<FileItem> {
        val requested = P115FileIdCodec.decode(requestedId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的 115 文件标识: $requestedId"),
            )

        val name = payload.stringOf("file_name", "n")
            ?: return CloudResult.Failure(
                CloudError.NotFound(message = "115 中不存在该文件或目录: $requestedId"),
            )

        val pickcode = payload.stringOf("pick_code", "pickcode", "pc")
        val fid = payload.stringOf("file_id", "fid") ?: requested.fid
        val isFolder = fid == null || requested.isFolder

        // The parent is derived from `paths`: its last element is this node, the one before it is
        // the parent. Falling back to the requested ref keeps the id stable when `paths` is absent.
        val paths = payload.arrayOf("paths")
        val parentCidFromPaths = paths
            ?.dropLast(1)
            ?.lastOrNull()
            ?.let { it as? JsonObject }
            ?.stringOf("file_id", "cid")

        val cid = if (isFolder) {
            fid ?: requested.cid ?: ROOT_CID
        } else {
            requested.cid
        }

        val id = if (isFolder) {
            P115FileIdCodec.forFolder(cid = cid!!)
        } else {
            P115FileIdCodec.forFile(fid = fid!!, parentCid = cid)
        }
        if (!isFolder) onPickcode(id, pickcode)

        return CloudResult.Success(
            FileItem(
                id = id,
                providerId = P115ProviderId,
                accountId = accountId,
                name = name,
                type = if (isFolder) FileType.FOLDER else FileType.FILE,
                mimeType = if (isFolder) null else mimeTypeFor(payload, name),
                extension = if (isFolder) null else extensionFor(payload, name),
                size = if (isFolder) null else payload.longOf("file_size", "s", "size"),
                parentId = (parentCidFromPaths ?: if (isFolder) requested.cid else cid)
                    ?.let { FileId(P115FileIdCodec.forFolder(it).value) },
                createdTime = payload.longOf("ctime", "tp"),
                modifiedTime = payload.longOf("ptime", "utime", "te"),
                thumbnail = if (isFolder) null else thumbnailRefOf(payload, name),
                capabilities = if (isFolder) folderCapabilities(payload) else fileCapabilities(payload),
                extensions = buildMap {
                    payload.stringOf("sha1", "sha")?.let { put(P115Ext.SHA1, it.lowercase(Locale.ROOT)) }
                    payload.stringOf("file_category")?.let { put(P115Ext.FILE_CATEGORY, it) }
                    payload.intOf("play_long")?.let { put(P115Ext.PLAY_LONG_SECONDS, it.toString()) }
                },
            ),
        )
    }

    /**
     * Builds the breadcrumb from `GET /category/get`'s `paths` array.
     *
     * §3.2's example shows the chain starting with `{"file_id":0,"file_name":"根目录"}`, i.e. the
     * synthetic root is included by the vendor. The root's `file_id` is the number `0`, so it is
     * converted to the canonical `folder:115:cid=0` form; otherwise the breadcrumb's first element
     * would be a `FileId` that no other code path can resolve.
     */
    fun pathFromCategoryGet(payload: JsonObject, accountId: AccountId): FilePath {
        val segments = payload.arrayOf("paths")
            ?.mapNotNull { element ->
                val node = element as? JsonObject ?: return@mapNotNull null
                val name = node.stringOf("file_name", "n") ?: return@mapNotNull null
                val rawId = node.longOf("file_id", "cid")?.toString()
                    ?: node.stringOf("file_id", "cid")
                    ?: ROOT_CID
                FilePathSegment(
                    id = FileId(P115FileIdCodec.forFolder(rawId).value),
                    name = name,
                )
            }
            .orEmpty()

        // A detail response always describes at least the node itself, so an empty chain means the
        // vendor omitted `paths`; the caller then falls back to `getPath`'s parent walk.
        return FilePath(segments)
    }

    /**
     * Builds a [FilePage] from a listing envelope.
     *
     * ### Cursor model
     * 115 paginates by `offset` + `limit` and reports `count` (the *total* number of entries in the
     * folder, per §3.1's example where `count: 2` accompanies a 2-element page). The unified model
     * uses an opaque cursor, so the cursor is simply the next offset as a string. The end of the
     * listing is decided by **what was actually returned**, not by `count`:
     * `count` is documented as present but its exact semantics vary (it is the folder total in the
     * example, and some endpoints omit it), whereas "a short page means the end" is unambiguous and
     * cannot loop. This is the same defensive rule the shared contract test enforces.
     */
    fun pageFromListing(
        envelopeData: List<JsonObject>,
        accountId: AccountId,
        parentCid: String,
        requestedOffset: Int,
        requestedLimit: Int,
        totalCount: Long?,
        path: List<FilePathSegment>,
        onPickcode: (FileId, String?) -> Unit = { _, _ -> },
    ): FilePage {
        val items = envelopeData.mapNotNull { fromListEntry(it, accountId, parentCid, onPickcode) }
        val nextOffset = requestedOffset + envelopeData.size
        val hasMore = envelopeData.size >= requestedLimit && envelopeData.isNotEmpty()

        return FilePage(
            items = items,
            nextCursor = if (hasMore) nextOffset.toString() else null,
            totalCount = totalCount,
            path = path,
        )
    }

    // ------------------------------------------------------------------ field helpers

    /**
     * Derives the MIME type.
     *
     * 115 does not return a MIME type on list endpoints; it returns `ico`, which is the lowercase
     * extension (§4: "图标类型 = 扩展名"). So the MIME type is derived from the extension. When the
     * extension is unknown, the value is `null` — deliberately *not* `application/octet-stream`,
     * because `null` means "undetermined" while `octet-stream` would assert something false and
     * would push the UI into a download-only affordance for a file it might actually preview.
     */
    private fun mimeTypeFor(node: JsonObject, name: String): String? =
        guessMimeType(extensionFor(node, name))

    private fun extensionFor(node: JsonObject, name: String): String? {
        // `ico` is documented as the extension itself.
        node.stringOf("ico")?.let { icon ->
            val cleaned = icon.lowercase(Locale.ROOT).substringAfterLast('.')
            if (cleaned.isNotBlank() && cleaned.length <= 10) return cleaned
        }
        val fromName = name.substringAfterLast('.', missingDelimiterValue = "")
        return fromName.takeIf { it.isNotBlank() && it.length <= 10 && it != name }
            ?.lowercase(Locale.ROOT)
    }

    private fun createdTimeOf(node: JsonObject): Long? =
        // `tp` is the documented upload/creation timestamp (§4).
        epochSecondsToMillis(node.longOf("tp", "ctime"))

    private fun modifiedTimeOf(node: JsonObject): Long? {
        // `te` is the numeric modified time and is preferred; `tu` (user-modified) is the fallback.
        epochSecondsToMillis(node.longOf("te", "tu", "ptime", "utime", "t"))
            ?.let { return it }
        // `t` is `YYYY-MM-DD HH:mm` in *local* time with no zone, so it is only a last resort and
        // is parsed as UTC rather than silently pretending to know the account's timezone.
        return node.stringOf("t")?.let { parseVendorDateTime(it) }
    }

    /**
     * 115 sends Unix **seconds**; the unified model uses **milliseconds**.
     *
     * A naive `* 1000` would turn a millisecond value into a date in the year 50000, and a naive
     * passthrough would show 1970. The magnitude check makes the conversion total instead of
     * dependent on which endpoint produced the value.
     */
    internal fun epochSecondsToMillis(value: Long?): Long? {
        if (value == null) return null
        if (value <= 0) return null
        return if (value < 100_000_000_000L) value * 1_000L else value
    }

    /** Parses `YYYY-MM-DD HH:mm` (and the `HH:mm:ss` variant) as UTC. */
    internal fun parseVendorDateTime(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val normalised = trimmed.replace(' ', 'T')
            val withSeconds = if (normalised.length == 16) "${normalised}:00" else normalised
            java.time.LocalDateTime.parse(withSeconds)
                .toInstant(java.time.ZoneOffset.UTC)
                .toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }

    /**
     * Builds the thumbnail descriptor.
     *
     * Only a *descriptor* is produced — never a URL. §11.2's thumbnail URLs carry `s=` (signature)
     * and `t=` (timestamp) parameters and are therefore short-lived credentials; putting one into a
     * `FileItem` would mean persisting a credential into the metadata cache. The content hash is
     * carried instead, because `thumb.115.com`'s path is derived from the SHA1 (§11.2) and the
     * resolver needs it to be efficient.
     */
    private fun thumbnailRefOf(node: JsonObject, name: String): ThumbnailRef? {
        val extension = extensionFor(node, name) ?: return null
        if (extension !in IMAGE_EXTENSIONS) return null
        val sha = node.stringOf("sha", "sha1")
        return ThumbnailRef(sizeHint = DEFAULT_THUMBNAIL_SIZE, contentHash = sha?.lowercase(Locale.ROOT))
    }

    private fun folderCapabilities(node: JsonObject? = null): FileCapabilities = FileCapabilities(
        // A folder has no bytes to download directly; §7.2 documents a separate packaging endpoint
        // that this build does not implement, so download is explicitly unavailable rather than
        // silently failing later.
        canDownload = false,
        canStream = false,
        canPreview = false,
        canRename = true,
        canMove = true,
        canCopy = true,
        canDelete = true,
        canShare = null,
        hasThumbnail = false,
        isStarred = node?.let { starredOf(it) },
        isTop = node?.boolOf("is_top"),
        isBlocked = node?.let { blockedOf(it) },
    )

    private fun fileCapabilities(node: JsonObject): FileCapabilities {
        val extension = extensionFor(node, node.stringOf("n", "file_name").orEmpty())
        val blocked = blockedOf(node)
        return FileCapabilities(
            canDownload = blocked != true,
            // §10.1's `files/video` is the only streaming entry point and it applies to video files.
            // `file_status` in that response is what finally decides, so this is a *hint* used only
            // to decide whether to show a play affordance in a list.
            canStream = blocked != true && extension in STREAMABLE_EXTENSIONS,
            canPreview = blocked != true && extension in PREVIEWABLE_EXTENSIONS,
            canRename = true,
            canMove = true,
            canCopy = true,
            canDelete = true,
            canShare = null,
            hasThumbnail = extension in IMAGE_EXTENSIONS,
            isStarred = starredOf(node),
            isTop = node.boolOf("is_top"),
            isBlocked = blocked,
        )
    }

    /**
     * §4: `star_time` is `0` when the item is not starred, and a timestamp when it is.
     * Note the polarity: a *missing* field is unknown, an explicit `0` is "not starred".
     */
    private fun starredOf(node: JsonObject): Boolean? {
        val starTime = node.longOf("star_time") ?: return null
        return starTime > 0
    }

    /** §4: `check_code` is the review state, where `0` means normal. */
    private fun blockedOf(node: JsonObject): Boolean? {
        val checkCode = node.intOf("check_code") ?: return null
        return checkCode != 0
    }

    /**
     * Converts the unified [FileSort] into 115's `o` + `asc` parameters.
     *
     * §3.1 enumerates the vendor's sort keys. `FileSortField.TYPE` maps onto `file_type`, which is
     * an 115 notion of type rather than the unified [FileCategory]; it is a deliberate approximation
     * and is documented as such in `capabilities()`'s `serverSideSortFields`.
     */
    fun toVendorSort(sort: FileSort): Pair<String, String> {
        val field = when (sort.field) {
            FileSortField.NAME -> "file_name"
            FileSortField.SIZE -> "file_size"
            FileSortField.CREATED_TIME -> "user_utime"
            FileSortField.TYPE -> "file_type"
            FileSortField.MODIFIED_TIME -> "user_ptime"
        }
        return field to if (sort.ascending) "1" else "0"
    }

    /** §9.1's `type` parameter values. */
    fun toVendorCategory(category: FileCategory?): String? = category?.code?.toString()

    /** Lower-case image extensions that have a thumbnail at `thumb.115.com` (§11.2). */
    internal val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff", "jfif",
    )

    /** Extensions `files/video` can describe; used only as a list-level hint. */
    internal val STREAMABLE_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts", "mpg", "mpeg", "rmvb", "3gp",
    )

    /** Extensions the preview surface can classify without contacting the vendor. */
    val PREVIEWABLE_EXTENSIONS: Set<String> = IMAGE_EXTENSIONS + STREAMABLE_EXTENSIONS + setOf(
        "txt", "md", "log", "json", "xml", "csv", "ini", "conf", "yml", "yaml",
        "pdf",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "wps", "et", "dps",
        "mp3", "flac", "wav", "aac", "m4a", "ogg", "wma", "ape",
    )

    /** Matches §11.2's default (`url` field, 1440px) while leaving room for other sizes. */
    private const val DEFAULT_THUMBNAIL_SIZE = "1440"
}

/**
 * Keys used in [FileItem.extensions].
 *
 * The brief forbids `Map<String, Any>` in shared models, and this is the sanctioned alternative: a
 * small, namespaced, string-valued bag for fields that are genuinely vendor-specific and genuinely
 * needed (diagnostics, and the SHA1 that thumbnail resolution depends on). Anything the UI acts on
 * has a first-class field instead.
 */
internal object P115Ext {
    const val SHA1 = "p115.sha1"
    const val ICON = "p115.ico"
    const val CLASS = "p115.class"
    const val IS_TOP = "p115.isTop"
    const val FOLDER_COUNT = "p115.folderCount"
    const val CHECK_CODE = "p115.checkCode"
    const val FILE_CATEGORY = "p115.fileCategory"
    const val PLAY_LONG_SECONDS = "p115.playLongSeconds"
}

/**
 * Best-effort extension → MIME mapping.
 *
 * Deliberately small and explicit rather than a dependency: it only needs to cover the types this
 * app actually branches on (media, images, text, PDF, office), and an unknown extension returning
 * `null` is more honest than a giant table that guesses.
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
    "ts" -> "video/mp2t"
    "mpg", "mpeg" -> "video/mpeg"
    "3gp" -> "video/3gpp"
    "rmvb" -> "application/vnd.rn-realmedia-vbr"

    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "aac" -> "audio/aac"
    "m4a" -> "audio/mp4"
    "ogg" -> "audio/ogg"
    "wma" -> "audio/x-ms-wma"
    "ape" -> "audio/x-ape"

    "jpg", "jpeg", "jfif" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "webp" -> "image/webp"
    "heic", "heif" -> "image/heic"
    "tif", "tiff" -> "image/tiff"

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
