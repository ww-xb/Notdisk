package com.notdisk.provider.p115

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.common.result.map
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FilePath
import com.notdisk.core.model.FilePathSegment
import com.notdisk.core.model.FileSearchQuery
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.provider.FileSystemProvider
import com.notdisk.provider.p115.api.P115Endpoints
import com.notdisk.provider.p115.api.P115Envelope
import com.notdisk.provider.p115.api.P115Hosts
import com.notdisk.provider.p115.api.P115HttpClient
import com.notdisk.provider.p115.api.arrayOf
import com.notdisk.provider.p115.api.asLongOrNull
import com.notdisk.provider.p115.api.asNonBlankStringOrNull
import com.notdisk.provider.p115.api.intOf
import com.notdisk.provider.p115.api.longOf
import com.notdisk.provider.p115.api.objOf
import com.notdisk.provider.p115.api.stringOf
import com.notdisk.provider.p115.auth.P115Session
import kotlinx.serialization.json.JsonObject

/**
 * 115's file-system surface.
 *
 * ### Endpoint choices, and why
 * | Operation        | Endpoint                       | Reason (from `API_REFERENCE.md`)       |
 * |------------------|--------------------------------|-----------------------------------------|
 * | list             | `GET /files`                   | §3.1, the measured core listing          |
 * | detail           | `GET /category/get`            | §3.2, the only source of the `paths` chain |
 * | fallback detail  | `GET /files/get_info`          | §3.3, complete attributes, returns an array |
 * | search           | `GET /files/search`            | §3.4                                     |
 * | path → id        | `GET /files/getid`             | §3.5                                     |
 *
 * ### Pagination
 * 115 uses `offset` + `limit` (§3.1). The unified cursor is the next offset rendered as a string;
 * end-of-list is decided by the *actual* page length rather than by `count`, because §3.1's example
 * shows `count` behaving as a folder total and some endpoints omit it. A short page therefore
 * terminates the listing unambiguously, which is what the shared contract test asserts.
 */
internal class P115FileSystemProvider(
    private val http: P115HttpClient,
    private val sessionProvider: suspend (AccountId) -> CloudResult<P115Session>,
    private val configuration: P115Configuration,
    private val logger: CloudLogger,
    /**
     * Pickcodes are not part of a file's identity, so they are cached here and resolved on demand.
     * Every listing and detail call populates it, which is what makes playback a single request in
     * the common case while keeping `FileId` stable.
     */
    private val pickcodes: P115PickcodeCache = P115PickcodeCache(),
) : FileSystemProvider {

    /** Root directory id in 115's model (§1.5 / §3.1: `cid=0`). */
    private val rootCid = ROOT_CID

    override suspend fun getRoot(accountId: AccountId): CloudResult<FileItem> {
        // The root is synthetic, but the account must still have a usable session: returning a root
        // for a logged-out account would let the UI navigate into a tree it cannot actually read.
        when (val session = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(session.error)
            is CloudResult.Success -> Unit
        }
        // `GET /files?cid=0` returns the *children* of the root, never the root itself, so the root
        // node is materialised locally. There is nothing to fetch.
        return CloudResult.Success(P115FileMapper.root(accountId))
    }

    override suspend fun listChildren(
        accountId: AccountId,
        request: ListChildrenRequest,
    ): CloudResult<FilePage> {
        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val ref = P115FileIdCodec.decode(request.parentId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的 115 目录标识: ${request.parentId}"),
            )
        if (!ref.isFolder) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "目标不是文件夹: ${request.parentId}"),
            )
        }

        val cid = ref.cid ?: rootCid
        val offset = parseCursor(request.cursor)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "分页游标无效: ${request.cursor}"),
            )
        val limit = request.pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val (orderField, asc) = P115FileMapper.toVendorSort(request.sort)

        val httpRequest = http.get(
            url = http.resolve(P115Endpoints.FILES),
            query = mapOf(
                "aid" to PERSONAL_SPACE_AID,
                "cid" to cid,
                "offset" to offset.toString(),
                "limit" to limit.toString(),
                "show_dir" to if (request.includeFolders) "1" else "0",
                "o" to orderField,
                "asc" to asc,
                "natsort" to if (request.sort.naturalOrder) "1" else "0",
                "format" to "json",
            ),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(httpRequest, tag = "files.list")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val envelope = result.data
                val entries = envelope.dataAsArray.mapNotNull { it as? JsonObject }

                // §3.1: the response carries its own `path` breadcrumb. It is preferred over
                // reconstructing one, because the vendor's chain is authoritative and free.
                val breadcrumb = parseBreadcrumb(envelope, cid)

                CloudResult.Success(
                    P115FileMapper.pageFromListing(
                        envelopeData = entries,
                        accountId = accountId,
                        parentCid = cid,
                        requestedOffset = offset,
                        requestedLimit = limit,
                        totalCount = envelope.long("count"),
                        path = breadcrumb,
                        onPickcode = { id, pc -> pickcodes.put(accountId, id, pc) },
                    ),
                )
            }
        }
    }

    /**
     * File/folder detail.
     *
     * `GET /category/get?cid=<id>` is used first because it is the only endpoint that returns the
     * full `paths` chain (§3.2) and it works for both files and folders. If it fails with a
     * server-side error, `GET /files/get_info?file_id=<fid>` is tried as a fallback because it
     * returns the most complete attribute set (§3.3) — but it returns an *array*, so it is only
     * usable for file ids.
     */
    override suspend fun getFile(accountId: AccountId, fileId: FileId): CloudResult<FileItem> {
        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val ref = P115FileIdCodec.decode(fileId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的 115 文件标识: $fileId"),
            )

        // The synthetic root has no server-side representation to fetch.
        if (ref.isFolder && ref.cid == rootCid) {
            return CloudResult.Success(P115FileMapper.root(accountId))
        }

        // `category/get` takes the *id*: for a folder that is its own cid, for a file its fid.
        val detailId = ref.fid ?: ref.cid
            ?: return CloudResult.Failure(CloudError.InvalidArgument(message = "文件标识缺少 ID"))

        val primary = http.get(
            url = http.resolve(P115Endpoints.CATEGORY_GET),
            query = mapOf("cid" to detailId),
            cookies = session.credential.cookieHeader(),
        )

        when (val result = http.execute(primary, tag = "category.get")) {
            is CloudResult.Success -> {
                // `category/get` returns the fields at the TOP level (not under `data`), per §3.2.
                val payload = result.data.dataObject ?: result.data.root
                val mapped = P115FileMapper.fromCategoryGet(
                    payload = payload,
                    accountId = accountId,
                    requestedId = fileId,
                    onPickcode = { id, pc -> pickcodes.put(accountId, id, pc) },
                )
                if (mapped is CloudResult.Success) return mapped
                logger.w(TAG, "category.get mapping failed, falling back to get_info")
            }

            is CloudResult.Failure -> {
                if (result.error is CloudError.NetworkUnavailable || result.error is CloudError.Timeout) {
                    return CloudResult.Failure(result.error)
                }
                logger.w(
                    TAG,
                    "category.get failed (${result.error::class.simpleName}), trying get_info",
                )
            }
        }

        return getInfoFallback(accountId, fileId, session, ref)
    }

    /** §3.3 — `GET /files/get_info?file_id=<fid>&this_path=1`, which returns an array. */
    private suspend fun getInfoFallback(
        accountId: AccountId,
        fileId: FileId,
        session: P115Session,
        ref: P115Ref,
    ): CloudResult<FileItem> {
        val fid = ref.fid
            ?: return CloudResult.Failure(
                CloudError.NotFound(message = "115 目录详情接口未返回该目录"),
            )

        val request = http.get(
            url = http.resolve(P115Endpoints.FILES_GET_INFO),
            query = mapOf("file_id" to fid, "this_path" to "1"),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "files.get_info")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                // The response is a bare JSON array (or an envelope wrapping one).
                val entry = result.data.dataAsArray.firstOrNull() as? JsonObject
                    ?: result.data.root.arrayOf("data")?.firstOrNull() as? JsonObject
                    ?: return CloudResult.Failure(
                        CloudError.NotFound(message = "115 未返回文件详情: $fileId"),
                    )
                P115FileMapper.fromListEntry(
                    node = entry,
                    accountId = accountId,
                    parentCid = ref.cid ?: rootCid,
                    onPickcode = { id, pc -> pickcodes.put(accountId, id, pc) },
                )?.let { CloudResult.Success(it) }
                    ?: CloudResult.Failure(
                        CloudError.ParseError(message = "115 文件详情解析失败: $fileId"),
                    )
            }
        }
    }

    override suspend fun getPath(accountId: AccountId, fileId: FileId): CloudResult<FilePath> {
        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val ref = P115FileIdCodec.decode(fileId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的 115 文件标识: $fileId"),
            )

        val detailId = ref.fid ?: ref.cid ?: rootCid
        val request = http.get(
            url = http.resolve(P115Endpoints.CATEGORY_GET),
            query = mapOf("cid" to detailId),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "category.get.path")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val payload = result.data.dataObject ?: result.data.root
                val path = P115FileMapper.pathFromCategoryGet(payload, accountId)
                if (path.segments.isNotEmpty()) {
                    CloudResult.Success(path)
                } else {
                    // `paths` absent: fall back to a local parent walk, which reports `NotFound`
                    // when the id does not describe a readable node.
                    walkPath(accountId, session, ref)
                }
            }
        }
    }

    /**
     * Reconstructs a path by walking parent links.
     *
     * Used only when the vendor omits `paths`. Bounded by [MAX_PATH_DEPTH] so a malformed response
     * cannot produce an infinite loop — a real risk when walking a tree whose parent pointers come
     * from an untrusted server.
     */
    private suspend fun walkPath(
        accountId: AccountId,
        session: P115Session,
        start: P115Ref,
    ): CloudResult<FilePath> {
        val chain = ArrayDeque<FilePathSegment>()
        var current: P115Ref? = start
        var depth = 0

        while (current != null && depth++ < MAX_PATH_DEPTH) {
            val detailId = current.fid ?: current.cid ?: break
            val request = http.get(
                url = http.resolve(P115Endpoints.CATEGORY_GET),
                query = mapOf("cid" to detailId),
                cookies = session.credential.cookieHeader(),
            )
            val payload = when (val result = http.execute(request, tag = "category.get.walk")) {
                is CloudResult.Failure -> return CloudResult.Failure(result.error)
                is CloudResult.Success -> result.data.dataObject ?: result.data.root
            }

            val name = payload.stringOf("file_name", "n") ?: break
            val parentId = payload.arrayOf("paths")
                ?.dropLast(1)?.lastOrNull()
                ?.let { it as? JsonObject }
                ?.stringOf("file_id", "cid")

            chain.addFirst(
                FilePathSegment(
                    id = FileId(
                        P115FileIdCodec.forFolder(if (current.isFolder) current.cid ?: ROOT_CID else parentId ?: ROOT_CID).value,
                    ),
                    name = name,
                ),
            )

            current = parentId
                ?.takeIf { it != ROOT_CID && it.isNotBlank() }
                ?.let { P115Ref(cid = it, fid = null) }
        }

        if (chain.isEmpty()) {
            // Not a parse failure: the server answered, it just does not describe a node this client
            // can address. Reporting `NotFound` lets the UI say "该文件不存在或已被删除" instead of
            // implying the app could not understand a well-formed response.
            return CloudResult.Failure(
                CloudError.NotFound(
                    message = "115 中不存在该文件或目录: ${start.fid ?: start.cid}",
                ),
            )
        }
        // Prepend the synthetic root so the chain always starts at the root, matching the
        // breadcrumb contract the UI relies on.
        chain.addFirst(FilePathSegment(id = FileId(P115FileIdCodec.forFolder(rootCid).value), name = "根目录"))
        return CloudResult.Success(FilePath(chain.toList()))
    }

    override suspend fun search(
        accountId: AccountId,
        query: FileSearchQuery,
    ): CloudResult<FilePage> {
        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val parentCid = query.parentId
            ?.let { P115FileIdCodec.decode(it)?.cid }
            ?: rootCid
        val (orderField, asc) = P115FileMapper.toVendorSort(query.sort)

        val request = http.get(
            url = http.resolve(P115Endpoints.FILES_SEARCH),
            query = buildMap {
                put("aid", PERSONAL_SPACE_AID)
                put("cid", parentCid)
                put("search_value", query.keyword)
                put("offset", "0")
                put("limit", configuration.pageSize.coerceIn(1, MAX_PAGE_SIZE).toString())
                put("format", "json")
                put("order", orderField)
                put("asc", asc)
                P115FileMapper.toVendorCategory(query.category)?.let { put("type", it) }
                if (query.suffixes.isNotEmpty()) {
                    put("suffix", query.suffixes.joinToString(",") { it.lowercase() })
                }
            },
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "files.search")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val envelope = result.data
                val entries = envelope.dataAsArray.mapNotNull { it as? JsonObject }

                // §3.4's warning: `ns` is an HTML-highlighted name. `P115FileMapper.fromListEntry`
                // reads `n`, never `ns`, so no HTML can reach the UI. When a result carries only
                // `ns`, the tag is stripped here rather than shown raw.
                val sanitised = entries.map { node ->
                    if (node.stringOf("n") == null) {
                        node.stringOf("ns")?.let { highlighted ->
                            JsonObject(node + ("n" to kotlinx.serialization.json.JsonPrimitive(stripHtml(highlighted))))
                        } ?: node
                    } else {
                        node
                    }
                }

                CloudResult.Success(
                    FilePage(
                        items = sanitised.mapNotNull {
                            P115FileMapper.fromListEntry(
                                node = it,
                                accountId = accountId,
                                parentCid = parentCid,
                                onPickcode = { id, pc -> pickcodes.put(accountId, id, pc) },
                            )
                        },
                        // §3.4's response has no cursor field; a single page is returned and the
                        // caller is expected to narrow the query. Reporting `null` is honest:
                        // claiming more pages exist would produce an endless loop.
                        nextCursor = null,
                        totalCount = envelope.long("count"),
                        // §3.4 returns the searched folder in `folder`, which is a partial breadcrumb
                        // (id + name only). It is not a full chain, so no path is claimed here.
                        path = emptyList(),
                    ),
                )
            }
        }
    }

    override suspend fun resolvePath(accountId: AccountId, path: String): CloudResult<FileId> {
        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val trimmed = path.trim().trim('/')
        if (trimmed.isEmpty()) return CloudResult.Success(FileId(P115FileIdCodec.forFolder(rootCid).value))

        val request = http.get(
            url = http.resolve(P115Endpoints.FILES_GETID),
            query = mapOf("path" to trimmed),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "files.getid")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                // §3.5: the id is at the top level as a string, and `0`/absent means "not found".
                val id = result.data.string("id", "cid", "file_id")
                    ?: result.data.dataString("id", "cid")
                when {
                    id == null || id == "0" || id.isBlank() -> CloudResult.Failure(
                        CloudError.NotFound(message = "115 中不存在路径: $path"),
                    )

                    else -> CloudResult.Success(
                        FileId(P115FileIdCodec.forFolder(id).value),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Parses the unified opaque cursor into 115's offset. `null` cursor means page 0. */
    private fun parseCursor(cursor: String?): Int? {
        if (cursor == null) return 0
        val parsed = cursor.toIntOrNull() ?: return null
        return parsed.takeIf { it >= 0 }
    }

    /**
     * §3.1: the listing response carries `path: [{cid, name}, …]` — the vendor's own breadcrumb.
     *
     * Note that it uses `cid` as the key even for the root entry (`{"cid":0,"name":"根目录"}`), so
     * the segment ids are built with the *folder* encoding, which is what makes them resolvable by
     * `listChildren` later.
     */
    private fun parseBreadcrumb(
        envelope: P115Envelope,
        listedCid: String,
    ): List<FilePathSegment> {
        val vendorPath = envelope.array("path") ?: return emptyList()
        val segments = vendorPath.mapNotNull { element ->
            val node = element as? JsonObject ?: return@mapNotNull null
            val name = node.stringOf("name", "file_name") ?: return@mapNotNull null
            val id = node.stringOf("cid", "file_id") ?: rootCid
            FilePathSegment(id = FileId(P115FileIdCodec.forFolder(id).value), name = name)
        }
        // The vendor's chain describes the folder being listed, which is what the UI wants. When it
        // is absent the caller falls back to `getPath`.
        return segments.ifEmpty { emptyList() }
    }

    /** §3.4: `ns` carries `<b style='color:#FF0000'>…</b>` around the matched substring. */
    private fun stripHtml(input: String): String =
        input.replace(HTML_TAG_REGEX, "").trim()

    private companion object {
        const val TAG = "P115Files"

        /** §1.5 / §3.1: `aid=1` is the personal space. */
        const val PERSONAL_SPACE_AID = "1"

        /** §7.4 and general practice: 115 accepts larger limits, but stay near the web client. */
        const val MAX_PAGE_SIZE = 200

        /** Guards the local parent walk against a malformed/cyclic server response. */
        const val MAX_PATH_DEPTH = 32

        val HTML_TAG_REGEX = Regex("<[^>]*>")
    }
}
