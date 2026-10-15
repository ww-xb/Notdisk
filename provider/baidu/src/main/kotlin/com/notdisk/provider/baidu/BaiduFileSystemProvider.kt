package com.notdisk.provider.baidu

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FilePath
import com.notdisk.core.model.FilePathSegment
import com.notdisk.core.model.FileSearchQuery
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.model.FileSort
import com.notdisk.core.provider.FileSystemProvider
import com.notdisk.provider.baidu.api.BaiduEndpoints
import com.notdisk.provider.baidu.api.BaiduErrorFamily
import com.notdisk.provider.baidu.api.BaiduHttpClient
import com.notdisk.provider.baidu.api.intOf
import com.notdisk.provider.baidu.api.stringOf
import com.notdisk.provider.baidu.auth.BaiduSession

/**
 * Baidu Netdisk's file-system surface.
 *
 * ### Endpoint choices, and why
 * | Operation       | Endpoint                | Reason (from the reference material)               |
 * |-----------------|-------------------------|----------------------------------------------------|
 * | list            | `GET /api/list`         | §3.2 — the measured core listing, path-addressed    |
 * | category list   | `GET /api/categorylist` | §3.3 — returns `info[]`, normalized by the envelope |
 * | search          | `GET /api/search`       | §3.4 — `recursion=1`; `dir` must be *deleted*       |
 * | detail          | `POST /api/filemetas`   | §3.5 — the only endpoint giving `md5` and `dlink`   |
 * | path → id       | *(none needed)*         | the id **is** the path — see [BaiduFileIdCodec]     |
 *
 * ### Pagination, and the honest end-of-list rule
 * §3.2 documents `page` + `num` and **no** "has more" field — the reference material verified this by
 * grepping the whole capture, and notes that only `/api/search` returns `has_more`. So the end of a
 * listing is decided by what was actually returned: a page shorter than `num` is the last page. That
 * rule cannot loop, which is what the shared contract suite asserts.
 *
 * ### Why this provider is path-addressed, and what that costs
 * Every file-scoped endpoint in §3.5, §3.6, §7.3 and §5.3 keys on an absolute **path**, so the
 * unified [FileId] encodes the path (see [BaiduFileIdCodec] for the full reasoning). The cost is that
 * a rename or a move invalidates the id of the renamed item and of everything beneath it. The
 * benefit is that `getFile`, `listChildren` and `search` all agree on a file's identity by
 * construction, and that `getPath` is exact and free rather than reconstructed by walking parents.
 *
 * A rename therefore invalidates the affected cache rows, which is the correct outcome: the old id
 * no longer addresses anything, and pretending otherwise would leave the UI showing a row that fails
 * when tapped.
 */
internal class BaiduFileSystemProvider(
    private val http: BaiduHttpClient,
    private val sessionProvider: suspend (AccountId) -> CloudResult<BaiduSession>,
    private val configuration: BaiduConfiguration,
    private val logger: CloudLogger,
) : FileSystemProvider {

    override suspend fun getRoot(accountId: AccountId): CloudResult<FileItem> {
        // The root is synthetic, but the account must still have a usable credential: returning a
        // root for a logged-out account would let the UI navigate into a tree it cannot read.
        when (val session = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(session.error)
            is CloudResult.Success -> Unit
        }
        // §3.2: `/api/list?dir=/` returns the *children* of the root, never the root itself, so the
        // node is materialised locally. There is nothing to fetch.
        return CloudResult.Success(BaiduFileMapper.root(accountId))
    }

    override suspend fun listChildren(
        accountId: AccountId,
        request: ListChildrenRequest,
    ): CloudResult<FilePage> {
        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val ref = BaiduFileIdCodec.decode(request.parentId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的百度网盘目录标识: ${request.parentId}"),
            )
        if (!ref.isFolder) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "目标不是文件夹: ${request.parentId}"),
            )
        }

        val page = parsePageCursor(request.cursor)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "分页游标无效: ${request.cursor}"),
            )
        val num = request.pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val (order, desc) = BaiduFileMapper.toVendorSort(request.sort)

        val httpRequest = http.get(
            url = http.resolve(BaiduEndpoints.LIST),
            query = mapOf(
                // §3.2: `dir` is an absolute path. The vendor's own client omits it for the root,
                // which is why the measured capture shows no `dir` at all — sending it explicitly is
                // equivalent and makes the request self-describing.
                "dir" to ref.path,
                "order" to order,
                "desc" to desc,
                "num" to num.toString(),
                "page" to page.toString(),
                // §3.2 documents `showempty`; `0` hides empty directories, which is the vendor's own
                // default in its second measured parameter set.
                "showempty" to "0",
            ),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(httpRequest, tag = "list")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val envelope = result.data
                CloudResult.Success(
                    BaiduFileMapper.pageFromListing(
                        entries = envelope.entries,
                        accountId = accountId,
                        parentPath = ref.path,
                        requestedPage = page,
                        requestedNum = num,
                        totalCount = envelope.long("count"),
                    ),
                )
            }
        }
    }

    /**
     * File/folder detail via `POST /api/filemetas` (§3.5).
     *
     * `filemetas` is chosen over re-listing the parent because it is the only endpoint that returns
     * `md5` (which thumbnail resolution is keyed on, §8.2) and because it accepts a `target` list
     * directly. It is documented as accepting GET too; POST is used because the vendor's own client
     * posts the `target` array in the body for anything larger than a single path, and a path
     * containing non-ASCII characters is large.
     */
    override suspend fun getFile(accountId: AccountId, fileId: FileId): CloudResult<FileItem> {
        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val ref = BaiduFileIdCodec.decode(fileId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的百度网盘文件标识: $fileId"),
            )

        // The synthetic root has no server-side representation to fetch.
        if (ref.isFolder && ref.path == BaiduFileIdCodec.ROOT_PATH) {
            return CloudResult.Success(BaiduFileMapper.root(accountId))
        }

        val parentPath = ref.parentPath ?: BaiduFileIdCodec.ROOT_PATH
        val request = http.postForm(
            url = http.resolve(BaiduEndpoints.FILEMETAS),
            form = mapOf("target" to targetJson(ref.path)),
            query = mapOf("dlink" to "0", "thumb" to "1", "version" to System.currentTimeMillis().toString()),
            cookies = session.credential.cookieHeader(),
        )

        // `/api/filemetas` is multi-target, and §3.5 measures it answering `errno 12` ("partial
        // failure") with per-element codes inside a *successful* HTTP response. Declaring that code
        // here is what lets the element-level check below be reached instead of the whole call being
        // rejected outright.
        return when (
            val result = http.execute(
                request,
                tag = "filemetas",
                partialFailureCodes = setOf(PARTIAL_FAILURE_CODE),
            )
        ) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val entry = result.data.entries.firstOrNull()
                    ?: return CloudResult.Failure(
                        CloudError.NotFound(message = "百度网盘中不存在该文件: ${ref.path}"),
                    )

                // §3.5: **each element carries its own `errno`**, and the outer envelope can be
                // `errno: 12` ("partial failure") while this specific element is fine. A per-element
                // code therefore has to be checked before trusting the element — and a per-element
                // `-9` ("文件不存在") is the correct signal for a stale id, not a parse failure.
                val elementCode = entry.intOf("errno")
                if (elementCode != null && elementCode != 0) {
                    return CloudResult.Failure(
                        com.notdisk.provider.baidu.api.mapBaiduError(
                            code = elementCode,
                            message = null,
                            family = BaiduErrorFamily.GENERAL,
                        ),
                    )
                }

                val item = BaiduFileMapper.fromEntry(entry, accountId, parentPath)
                    ?: return CloudResult.Failure(
                        CloudError.ParseError(message = "百度网盘文件详情解析失败: ${ref.path}"),
                    )
                CloudResult.Success(item)
            }
        }
    }

    /**
     * The full root→file path.
     *
     * Exact and free for this provider: the id *is* the absolute path ([BaiduFileIdCodec]), so the
     * chain is derived by splitting it. No request is made, which also means the method cannot fail
     * for a file that exists but is momentarily unreachable.
     *
     * The leading synthetic root segment is included so the chain always starts at the root, matching
     * the breadcrumb contract the UI relies on.
     */
    override suspend fun getPath(accountId: AccountId, fileId: FileId): CloudResult<FilePath> {
        val ref = BaiduFileIdCodec.decode(fileId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的百度网盘文件标识: $fileId"),
            )

        val segments = mutableListOf(
            FilePathSegment(
                id = FileId(BaiduFileIdCodec.forFolder(BaiduFileIdCodec.ROOT_PATH).value),
                name = "全部文件",
            ),
        )

        if (ref.path != BaiduFileIdCodec.ROOT_PATH) {
            var accumulated = ""
            ref.path.trim('/').split('/').forEachIndexed { index, segment ->
                if (segment.isBlank()) return@forEachIndexed
                accumulated = "$accumulated/$segment"
                val isLast = index == ref.path.trim('/').split('/').lastIndex
                // Intermediate segments are always folders; the last one may be either, which the
                // caller's id already told us.
                val isFolder = !isLast || ref.isFolder
                segments += FilePathSegment(
                    id = FileId(BaiduFileIdCodec.forPath(accumulated, isFolder).value),
                    name = segment,
                )
            }
        }

        return CloudResult.Success(FilePath(segments))
    }

    /**
     * Recursive search (§3.4).
     *
     * Two documented details are honored:
     *  - `dir` must be **deleted** rather than set: the vendor's own client does `delete n.dir`
     *    because search is always recursive from the account root;
     *  - `recursion=1` is what makes it search subdirectories at all.
     *
     * The `parentId` argument is therefore *not* forwarded: the endpoint has no documented way to
     * scope a search to a subtree. Ignoring it silently would be wrong, so a caller that asks for a
     * scoped search gets the account-wide result plus a logged note — the alternative (returning
     * `Unsupported`) would hide a working feature behind a parameter it merely cannot honor.
     */
    override suspend fun search(
        accountId: AccountId,
        query: FileSearchQuery,
    ): CloudResult<FilePage> {
        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        if (query.parentId != null) {
            logger.i(
                TAG,
                "百度网盘搜索不支持限定子树（/api/search 显式删除 dir 参数），已按全盘递归搜索",
            )
        }

        val num = configuration.pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val (order, desc) = BaiduFileMapper.toVendorSort(query.sort)

        val request = http.get(
            url = http.resolve(BaiduEndpoints.SEARCH),
            query = buildMap {
                put("key", query.keyword)
                put("recursion", "1")
                put("order", order)
                put("desc", desc)
                put("num", num.toString())
                put("page", "1")
                // `/api/filemetas`-style category scoping is not part of §3.4's documented search
                // parameters, so `query.category` is not forwarded. Filtering happens client-side in
                // the caller when it needs a category, which keeps the request honest.
            },
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "search")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val envelope = result.data
                val entries = envelope.entries
                // §3.4 documents `has_more` on this endpoint only, so it is used when present and the
                // length heuristic is the fallback.
                val hasMore = envelope.int("has_more")?.let { it != 0 }

                // Search results carry absolute `path`s, so the joined fallback path is irrelevant;
                // the root is passed as a neutral value rather than inventing a parent.
                CloudResult.Success(
                    BaiduFileMapper.pageFromListing(
                        entries = entries,
                        accountId = accountId,
                        parentPath = BaiduFileIdCodec.ROOT_PATH,
                        requestedPage = 1,
                        requestedNum = num,
                        totalCount = envelope.long("count", "display_count"),
                        hasMoreHint = hasMore,
                    ),
                )
            }
        }
    }

    /**
     * Resolves a human path such as `我的文件/电视剧` into a [FileId].
     *
     * Nearly free for this provider: because the id **is** the absolute path, this is a
     * normalization rather than a lookup, and it cannot disagree with what `listChildren` returns.
     *
     * The one asymmetry worth naming: the caller cannot tell from a path string whether it names a
     * file or a folder, and the two are addressed differently by the vendor. A path is therefore
     * resolved as a **folder**, which is the only useful interpretation for a navigation entry point
     * — and a caller that genuinely holds a file path has that path already and can encode it with
     * [BaiduFileIdCodec.forFile].
     */
    override suspend fun resolvePath(accountId: AccountId, path: String): CloudResult<FileId> {
        when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> Unit
        }

        val normalised = BaiduFileIdCodec.normalise(path)
        return CloudResult.Success(BaiduFileIdCodec.forFolder(normalised))
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Parses the unified opaque cursor into Baidu's 1-based page number.
     *
     * `null` cursor means page 1. A cursor that is not a positive integer is rejected rather than
     * coerced: silently treating garbage as page 1 would re-fetch the first page forever, which is
     * the exact "cursor does not advance" failure the shared contract suite forbids.
     */
    private fun parsePageCursor(cursor: String?): Int? {
        if (cursor == null) return 1
        val parsed = cursor.toIntOrNull() ?: return null
        return parsed.takeIf { it >= 1 }
    }

    /** §3.5: `target` is a JSON **array string**, e.g. `["/a.txt"]`. */
    private fun targetJson(path: String): String = buildString {
        append('[')
        append('"')
        // Escape the two characters JSON requires inside a string. A path is arbitrary user data, so
        // an unescaped quote or backslash would produce a malformed array — which the vendor reports
        // as `errno 2` ("参数错误"), a message that points nowhere near the real cause.
        path.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(character)
            }
        }
        append('"')
        append(']')
    }

    private companion object {
        const val TAG = "BaiduFiles"

        /**
         * §3.2 documents `num` defaulting to 100 and the capture shows values up to 1000. The shared
         * model caps `pageSize` at 200, which is inside the range the vendor is known to accept.
         */
        const val MAX_PAGE_SIZE = 200

        /**
         * §3.5 — the outer code `/api/filemetas` returns when some targets failed.
         *
         * Named rather than inlined so the element-level error check below is not mistaken for dead
         * code by a future reader.
         */
        const val PARTIAL_FAILURE_CODE = 12
    }
}
