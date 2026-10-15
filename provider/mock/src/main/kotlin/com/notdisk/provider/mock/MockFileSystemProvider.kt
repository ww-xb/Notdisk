package com.notdisk.provider.mock

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.FileCapabilities
import com.notdisk.core.model.FileCategory
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FilePath
import com.notdisk.core.model.FilePathSegment
import com.notdisk.core.model.FileSearchQuery
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.FileSortField
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.model.ThumbnailRef
import com.notdisk.core.provider.FileSystemProvider
import kotlinx.coroutines.delay

/**
 * Offline [FileSystemProvider] over [MockTree] plus the session's own mutations.
 *
 * The point of this class is not to be a toy: it implements the *same* paging, sorting, filtering
 * and error semantics the real provider must implement, so that the shared contract suite
 * validates something meaningful and the UI can be developed against realistic behaviour:
 *  - offset/limit paging with a terminating cursor (mirrors 115's `offset`+`limit`+`count`);
 *  - server-side-style sorting and category/suffix filtering;
 *  - a `无权限目录` that returns `PermissionDenied` rather than an empty list, because "empty" and
 *    "forbidden" are different states the UI must render differently;
 *  - a `空文件夹` that returns an empty *successful* page;
 *  - reads that immediately reflect writes made through [MockFileOperationProvider], so cache
 *    invalidation can be exercised for real instead of against a provider that never changes.
 */
internal class MockFileSystemProvider(
    private val accounts: MockAccountStore,
    private val state: MockSessionState,
    private val latencyMs: Long = 0,
) : FileSystemProvider {

    override suspend fun getRoot(accountId: AccountId): CloudResult<FileItem> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()
        return CloudResult.Success(MockTree.root.toItem(accountId))
    }

    override suspend fun listChildren(
        accountId: AccountId,
        request: ListChildrenRequest,
    ): CloudResult<FilePage> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()

        val node = state.find(request.parentId.value) ?: return notFound(request.parentId.value)
        if (!node.isFolder) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "${node.name} 不是文件夹"),
            )
        }
        if (!node.readable) {
            return CloudResult.Failure(
                CloudError.PermissionDenied(
                    message = "没有权限访问「${node.name}」",
                    vendorCode = "MOCK_FORBIDDEN",
                ),
            )
        }

        val all = state.childrenOf(node.id)
            .filter { request.includeFolders || !it.isFolder }
            .let { sortAndFilter(it, request.sort, category = null, suffixes = emptySet()) }

        val offset = request.cursor?.toIntOrNull() ?: 0
        if (offset < 0) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "分页游标无效: ${request.cursor}"),
            )
        }

        val page = all.drop(offset).take(request.pageSize)
        val nextOffset = offset + page.size
        val nextCursor = if (nextOffset < all.size) nextOffset.toString() else null

        return CloudResult.Success(
            FilePage(
                items = page.map { it.toItem(accountId) },
                nextCursor = nextCursor,
                totalCount = all.size.toLong(),
                path = state.pathTo(node.id).map { FilePathSegment(FileId(it.id), it.name) },
            ),
        )
    }

    override suspend fun getFile(accountId: AccountId, fileId: FileId): CloudResult<FileItem> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()
        val node = state.find(fileId.value) ?: return notFound(fileId.value)
        return CloudResult.Success(node.toItem(accountId))
    }

    override suspend fun getPath(accountId: AccountId, fileId: FileId): CloudResult<FilePath> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()
        val chain = state.pathTo(fileId.value)
        if (chain.isEmpty()) return notFound(fileId.value)
        return CloudResult.Success(FilePath(chain.map { FilePathSegment(FileId(it.id), it.name) }))
    }

    override suspend fun search(
        accountId: AccountId,
        query: FileSearchQuery,
    ): CloudResult<FilePage> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()

        val scope = query.parentId?.value ?: MockTree.ROOT_ID
        val inScope = state.allVisible().filter { isDescendantOf(it, scope) }

        val matched = inScope
            .filter { it.name.contains(query.keyword, ignoreCase = true) }
            .let { sortAndFilter(it, query.sort, query.category, query.suffixes) }

        return CloudResult.Success(
            FilePage(
                items = matched.map { it.toItem(accountId) },
                nextCursor = null,
                totalCount = matched.size.toLong(),
            ),
        )
    }

    override suspend fun resolvePath(accountId: AccountId, path: String): CloudResult<FileId> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()

        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return CloudResult.Success(FileId(MockTree.ROOT_ID))

        var currentId = MockTree.ROOT_ID
        for (segment in segments) {
            // The synthetic root's name is also accepted as the first segment.
            if (currentId == MockTree.ROOT_ID && segment == MockTree.root.name) continue
            val child = state.childrenOf(currentId)
                .firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return CloudResult.Failure(
                    CloudError.NotFound(message = "路径不存在: $path（在「$segment」处中断）"),
                )
            currentId = child.id
        }
        return CloudResult.Success(FileId(currentId))
    }

    // ------------------------------------------------------------------ helpers

    private fun isDescendantOf(node: MockNode, ancestorId: String): Boolean {
        if (ancestorId == MockTree.ROOT_ID) return true
        var current = node
        var guard = 0
        while (guard++ < 64) {
            val parentId = current.parentId ?: return false
            if (parentId == ancestorId) return true
            current = state.find(parentId) ?: return false
        }
        return false
    }

    private fun sortAndFilter(
        nodes: List<MockNode>,
        sort: FileSort,
        category: FileCategory?,
        suffixes: Set<String>,
    ): List<MockNode> {
        val normalizedSuffixes = suffixes.map { it.lowercase() }.toSet()

        val filtered = nodes.filter { node ->
            if (node.isFolder) {
                // Folders survive category filters: 115's `type` filter narrows files only.
                true
            } else {
                val categoryOk = category == null ||
                    category == FileCategory.ALL ||
                    categoryOf(node.extension) == category
                val suffixOk = normalizedSuffixes.isEmpty() ||
                    node.extension?.lowercase() in normalizedSuffixes
                categoryOk && suffixOk
            }
        }

        // Folders always lead, matching every mainstream cloud-drive UI (and 115's behaviour).
        val comparator = when (sort.field) {
            FileSortField.NAME -> compareBy<MockNode> { it.name.lowercase() }
            FileSortField.SIZE -> compareBy { it.size ?: -1L }
            FileSortField.CREATED_TIME -> compareBy { it.createdTime }
            FileSortField.TYPE -> compareBy { it.extension ?: "" }
            FileSortField.MODIFIED_TIME -> compareBy { it.modifiedTime }
        }
        val directed = if (sort.ascending) comparator else comparator.reversed()

        return filtered.sortedWith(
            compareByDescending<MockNode> { it.isFolder }.then(directed).thenBy { it.name },
        )
    }

    private suspend fun await() {
        if (latencyMs > 0) delay(latencyMs)
    }
}

/**
 * Account guard shared by the Mock sub-providers.
 *
 * Returns a typed failure when the account is unknown or its session is invalid, so every method
 * can `?.let { return it }` without repeating the error construction.
 */
internal fun MockAccountStore.requireAuthorized(accountId: AccountId): CloudResult<Nothing>? {
    val record = get(accountId)
        ?: return CloudResult.Failure(
            CloudError.NotAuthenticated(message = "Mock 账号不存在: $accountId"),
        )
    return when (record.authState) {
        AuthState.EXPIRED, AuthState.REVOKED -> CloudResult.Failure(
            CloudError.TokenExpired(message = "Mock 账号授权已失效，请重新登录", canRefresh = false),
        )

        else -> null
    }
}

/** Converts a tree node into the unified model, including per-item capabilities. */
internal fun MockNode.toItem(accountId: AccountId): FileItem = FileItem(
    id = FileId(id),
    providerId = MOCK_PROVIDER_ID,
    accountId = accountId,
    name = name,
    type = if (isFolder) FileType.FOLDER else FileType.FILE,
    mimeType = mimeType,
    extension = extension,
    size = size,
    parentId = parentId?.let { FileId(it) },
    createdTime = createdTime,
    modifiedTime = modifiedTime,
    thumbnail = if (isImage) ThumbnailRef(sizeHint = "480", contentHash = contentHash) else null,
    capabilities = FileCapabilities(
        canDownload = true,
        canStream = isVideo || isAudio,
        canPreview = previewable,
        canRename = readable,
        canMove = readable,
        canCopy = readable,
        canDelete = readable,
        canShare = readable,
        hasThumbnail = isImage,
        isStarred = starred,
        isTop = false,
        isBlocked = !readable,
    ),
    extensions = emptyMap(),
)
