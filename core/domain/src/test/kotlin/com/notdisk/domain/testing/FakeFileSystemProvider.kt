package com.notdisk.domain.testing

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileCategory
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FilePath
import com.notdisk.core.model.FilePathSegment
import com.notdisk.core.model.FileSearchQuery
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.provider.FileSystemProvider

/**
 * Configurable [FileSystemProvider] fake.
 *
 * ### The cursor behaviour is a first-class knob, not a hardcoded value
 * [cursorPolicy] exists because the single most important behaviour this fake must be able to
 * produce is the *bad* one: a provider that echoes the cursor it was given. That is the bug
 * [com.notdisk.domain.files.ListChildrenUseCase] was written to catch, and a fake that could
 * only return well-formed pages would make the test for it impossible to write. Modelling the
 * policy as an enum rather than as a `Boolean` keeps the *good* behaviours explicit too —
 * [CursorPolicy.Advance] and [CursorPolicy.End] are different real behaviours (a middle page versus
 * the last page), and a test asserting "the last page reports no more pages" needs the second.
 */
class FakeFileSystemProvider(private val owner: FakeCloudProvider) : FileSystemProvider {

    /** How [listChildren] derives the cursor it returns. */
    var cursorPolicy: CursorPolicy = CursorPolicy.End

    /** Explicit override; when set, it wins over [cursorPolicy]. */
    var listChildrenResult: CloudResult<FilePage>? = null

    var listChildrenFailure: CloudError? = null
    var listChildrenThrow: Boolean = false

    var root: FileItem = defaultRoot()
    var rootFailure: CloudError? = null

    var file: FileItem? = null
    var fileFailure: CloudError? = null

    var path: FilePath = FilePath(listOf(FilePathSegment(id = null, name = "根目录")))
    var pathFailure: CloudError? = null

    var searchResult: CloudResult<FilePage>? = null
    var searchFailure: CloudError? = null

    var resolvedFileId: FileId = FileId("resolved-id")
    var resolveFailure: CloudError? = null

    override suspend fun getRoot(accountId: AccountId): CloudResult<FileItem> {
        owner.record(ProviderSurface.FILES, "getRoot", "accountId" to accountId)
        rootFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(root)
    }

    override suspend fun listChildren(
        accountId: AccountId,
        request: ListChildrenRequest,
    ): CloudResult<FilePage> {
        owner.record(
            ProviderSurface.FILES,
            "listChildren",
            "accountId" to accountId,
            "parentId" to request.parentId,
            "cursor" to request.cursor,
            "pageSize" to request.pageSize,
        )
        if (listChildrenThrow) throw FakeProviderException("fake ${owner.id}: listChildren threw")
        listChildrenResult?.let { return it }
        listChildrenFailure?.let { return CloudResult.Failure(it) }

        val next = when (cursorPolicy) {
            // The contract violation under test: hand back exactly what was passed in. Combined
            // with the caller's `cursor = next` loop this would re-fetch the same page forever.
            CursorPolicy.EchoInput -> request.cursor
            CursorPolicy.Advance -> "${request.cursor ?: "0"}-next"
            CursorPolicy.End -> null
        }
        return CloudResult.Success(
            FilePage(
                items = listOf(childItem(request.parentId, request.cursor)),
                nextCursor = next,
            ),
        )
    }

    override suspend fun getFile(accountId: AccountId, fileId: FileId): CloudResult<FileItem> {
        owner.record(
            ProviderSurface.FILES,
            "getFile",
            "accountId" to accountId,
            "fileId" to fileId,
        )
        fileFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(file ?: childItem(fileId, cursor = null))
    }

    override suspend fun getPath(accountId: AccountId, fileId: FileId): CloudResult<FilePath> {
        owner.record(
            ProviderSurface.FILES,
            "getPath",
            "accountId" to accountId,
            "fileId" to fileId,
        )
        pathFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(path)
    }

    override suspend fun search(
        accountId: AccountId,
        query: FileSearchQuery,
    ): CloudResult<FilePage> {
        owner.record(
            ProviderSurface.FILES,
            "search",
            "accountId" to accountId,
            "keyword" to query.keyword,
            "parentId" to query.parentId,
            "category" to query.category,
            "suffixes" to query.suffixes,
        )
        searchResult?.let { return it }
        searchFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(FilePage(items = emptyList(), nextCursor = null))
    }

    override suspend fun resolvePath(accountId: AccountId, path: String): CloudResult<FileId> {
        owner.record(ProviderSurface.FILES, "resolvePath", "accountId" to accountId, "path" to path)
        resolveFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(resolvedFileId)
    }

    private fun defaultRoot() = FileItem(
        id = FileId("root"),
        providerId = owner.id,
        accountId = AccountId("unused"),
        name = "根目录",
        type = FileType.FOLDER,
        parentId = null,
    )

    private fun childItem(parentId: FileId, cursor: String?) = FileItem(
        id = FileId("child-${parentId.value}-${cursor ?: "first"}"),
        providerId = owner.id,
        accountId = AccountId("unused"),
        name = "child-${cursor ?: "first"}.mp4",
        type = FileType.FILE,
        parentId = parentId,
        extension = "mp4",
    )
}

/**
 * How the fake derives the `nextCursor` it returns, named after the *behaviour* rather than the
 * value so a test reads as a statement about a provider.
 */
enum class CursorPolicy {
    /** Return `null`: "this was the last page". The correct behaviour for a final page. */
    End,

    /** Return a genuinely new cursor: "there is more". The correct behaviour for a middle page. */
    Advance,

    /**
     * Return the cursor that was passed in. **A contract violation** — the caller's
     * `cursor = page.nextCursor` loop would re-fetch the same page indefinitely.
     */
    EchoInput,
}

/** A search-filter helper used by tests that need a query with a category. */
internal fun queryOf(
    keyword: String,
    parentId: FileId? = null,
    category: FileCategory? = null,
): FileSearchQuery = FileSearchQuery(keyword = keyword, parentId = parentId, category = category)
