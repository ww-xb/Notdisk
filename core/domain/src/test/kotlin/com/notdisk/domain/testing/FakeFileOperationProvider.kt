package com.notdisk.domain.testing

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.CollisionPolicy
import com.notdisk.core.model.DownloadRequest
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FileType
import com.notdisk.core.model.TransferProgress
import com.notdisk.core.model.TransferStatus
import com.notdisk.core.model.TransferTask
import com.notdisk.core.model.UploadRequest
import com.notdisk.core.provider.BatchOperationResult
import com.notdisk.core.provider.DownloadSource
import com.notdisk.core.provider.FileOperationProvider
import com.notdisk.core.provider.ShareResult
import com.notdisk.core.provider.TrashItem
import com.notdisk.core.provider.TrashPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Configurable [FileOperationProvider] fake.
 *
 * ### Why the batch methods record the *whole selection*
 * The domain layer's batch use cases do real work before delegating — they refuse an empty
 * selection and de-duplicate while preserving order. Both of those are observable *only* in the
 * arguments that reach the provider, so the fake records the selection as a list rather than a
 * count. A test asserting "the duplicate was removed and the order survived" then reads directly
 * off the call log, which is the only place that property is visible from outside the use case.
 *
 * ### Why the mutating methods default to a *non-empty* success
 * [BatchOperationResult.allSucceeded] requires `succeeded.isNotEmpty()`, so a fake that returned a
 * default empty result would make every "the batch succeeded" assertion subtly wrong and would
 * mask a use case that forgot to forward the provider's result. The default echoes the requested
 * keys back as succeeded, which is what a well-behaved provider does.
 */
class FakeFileOperationProvider(private val owner: FakeCloudProvider) : FileOperationProvider {

    var createFolderFailure: CloudError? = null
    var renameFailure: CloudError? = null

    /** Per-method failure hooks for the batch surface. `null` = succeed. */
    var moveFailure: CloudError? = null
    var copyFailure: CloudError? = null
    var deleteFailure: CloudError? = null
    var starFailure: CloudError? = null
    var shareFailure: CloudError? = null
    var trashListFailure: CloudError? = null
    var restoreFailure: CloudError? = null
    var purgeFailure: CloudError? = null
    var prepareDownloadFailure: CloudError? = null

    /** Explicit overrides that win over the failure hooks. */
    var moveResult: CloudResult<BatchOperationResult>? = null
    var copyResult: CloudResult<BatchOperationResult>? = null
    var deleteResult: CloudResult<BatchOperationResult>? = null
    var shareResult: CloudResult<ShareResult>? = null

    /** What [listTrash] returns. Defaults to an empty, unprotected bin. */
    var trashPage: TrashPage = TrashPage(items = emptyList(), nextCursor = null)

    /** What [prepareDownload] returns. */
    var downloadSource: DownloadSource = DownloadSource(
        url = "https://cdn.invalid/secret-signed-url",
        fileName = "file.bin",
        sizeBytes = 1024L,
        expiresAt = null,
    )

    override suspend fun createFolder(
        accountId: AccountId,
        parentId: FileId,
        name: String,
    ): CloudResult<FileItem> {
        owner.record(
            ProviderSurface.OPERATIONS,
            "createFolder",
            "accountId" to accountId,
            "parentId" to parentId,
            "name" to name,
        )
        createFolderFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(
            FileItem(
                id = FileId("created-$name"),
                providerId = owner.id,
                accountId = accountId,
                name = name,
                type = FileType.FOLDER,
                parentId = parentId,
            ),
        )
    }

    override suspend fun rename(
        accountId: AccountId,
        fileId: FileId,
        newName: String,
    ): CloudResult<FileItem> {
        owner.record(
            ProviderSurface.OPERATIONS,
            "rename",
            "accountId" to accountId,
            "fileId" to fileId,
            "newName" to newName,
        )
        renameFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(
            FileItem(
                id = fileId,
                providerId = owner.id,
                accountId = accountId,
                name = newName,
                type = FileType.FILE,
            ),
        )
    }

    override suspend fun move(
        accountId: AccountId,
        fileIds: List<FileId>,
        targetParentId: FileId,
    ): CloudResult<BatchOperationResult> {
        owner.record(
            ProviderSurface.OPERATIONS,
            "move",
            "accountId" to accountId,
            "fileIds" to fileIds,
            "targetParentId" to targetParentId,
        )
        moveResult?.let { return it }
        moveFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(batchOf(accountId, fileIds))
    }

    override suspend fun copy(
        accountId: AccountId,
        fileIds: List<FileId>,
        targetParentId: FileId,
    ): CloudResult<BatchOperationResult> {
        owner.record(
            ProviderSurface.OPERATIONS,
            "copy",
            "accountId" to accountId,
            "fileIds" to fileIds,
            "targetParentId" to targetParentId,
        )
        copyResult?.let { return it }
        copyFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(batchOf(accountId, fileIds))
    }

    override suspend fun delete(
        accountId: AccountId,
        fileIds: List<FileId>,
        permanent: Boolean,
    ): CloudResult<BatchOperationResult> {
        owner.record(
            ProviderSurface.OPERATIONS,
            "delete",
            "accountId" to accountId,
            "fileIds" to fileIds,
            "permanent" to permanent,
        )
        deleteResult?.let { return it }
        deleteFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(batchOf(accountId, fileIds))
    }

    override suspend fun upload(
        accountId: AccountId,
        request: UploadRequest,
    ): CloudResult<TransferTask> {
        owner.record(
            ProviderSurface.OPERATIONS,
            "upload",
            "accountId" to accountId,
            "fileName" to request.fileName,
        )
        return CloudResult.Success(
            TransferTask(
                id = "upload-1",
                key = CloudFileKey(owner.id, accountId, request.parentId),
                direction = com.notdisk.core.model.TransferDirection.UPLOAD,
                fileName = request.fileName,
                totalBytes = request.totalBytes,
                transferredBytes = 0L,
                status = TransferStatus.QUEUED,
            ),
        )
    }

    override suspend fun prepareDownload(
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<DownloadSource> {
        owner.record(
            ProviderSurface.OPERATIONS,
            "prepareDownload",
            "accountId" to accountId,
            "fileId" to fileId,
        )
        prepareDownloadFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(downloadSource)
    }

    override suspend fun download(
        accountId: AccountId,
        request: DownloadRequest,
        source: DownloadSource,
    ): CloudResult<TransferTask> {
        owner.record(ProviderSurface.OPERATIONS, "download", "accountId" to accountId)
        return CloudResult.Success(
            TransferTask(
                id = "download-1",
                key = request.key,
                direction = com.notdisk.core.model.TransferDirection.DOWNLOAD,
                fileName = request.fileName,
                totalBytes = source.sizeBytes,
                transferredBytes = request.startOffset,
                status = TransferStatus.QUEUED,
            ),
        )
    }

    override fun observeTransfer(transferId: String): Flow<TransferProgress> =
        flowOf(TransferProgress(0L, null, TransferStatus.QUEUED))

    override suspend fun cancelTransfer(transferId: String): CloudResult<Unit> {
        owner.record(ProviderSurface.OPERATIONS, "cancelTransfer", "transferId" to transferId)
        return CloudResult.Success(Unit)
    }

    override suspend fun retryTransfer(transferId: String): CloudResult<TransferTask> {
        owner.record(ProviderSurface.OPERATIONS, "retryTransfer", "transferId" to transferId)
        return unsupported("operations.retryTransfer")
    }

    override suspend fun share(
        accountId: AccountId,
        fileIds: List<FileId>,
        enable: Boolean,
        password: String?,
    ): CloudResult<ShareResult> {
        owner.record(
            ProviderSurface.OPERATIONS,
            "share",
            "accountId" to accountId,
            "fileIds" to fileIds,
            "enable" to enable,
            "password" to password,
        )
        shareResult?.let { return it }
        shareFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(ShareResult(shareUrl = "https://share.invalid/x"))
    }

    override suspend fun setStarred(
        accountId: AccountId,
        fileIds: List<FileId>,
        starred: Boolean,
    ): CloudResult<Unit> {
        owner.record(
            ProviderSurface.OPERATIONS,
            "setStarred",
            "accountId" to accountId,
            "fileIds" to fileIds,
            "starred" to starred,
        )
        starFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(Unit)
    }

    override suspend fun listTrash(
        accountId: AccountId,
        cursor: String?,
        pageSize: Int,
    ): CloudResult<TrashPage> {
        owner.record(
            ProviderSurface.OPERATIONS,
            "listTrash",
            "accountId" to accountId,
            "cursor" to cursor,
            "pageSize" to pageSize,
        )
        trashListFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(trashPage)
    }

    override suspend fun restoreFromTrash(
        accountId: AccountId,
        trashIds: List<String>,
    ): CloudResult<BatchOperationResult> {
        owner.record(
            ProviderSurface.OPERATIONS,
            "restoreFromTrash",
            "accountId" to accountId,
            "trashIds" to trashIds,
        )
        restoreFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(
            BatchOperationResult(
                succeeded = trashIds.map { CloudFileKey(owner.id, accountId, FileId(it)) },
            ),
        )
    }

    override suspend fun purgeFromTrash(
        accountId: AccountId,
        trashIds: List<String>,
        safeKey: String?,
    ): CloudResult<BatchOperationResult> {
        owner.record(
            ProviderSurface.OPERATIONS,
            "purgeFromTrash",
            "accountId" to accountId,
            "trashIds" to trashIds,
            // Recorded so a test can prove the safe key reached the provider, and — more
            // importantly — that a *null* one is passed through rather than invented.
            "safeKey" to safeKey,
        )
        purgeFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(
            BatchOperationResult(
                succeeded = trashIds.map { CloudFileKey(owner.id, accountId, FileId(it)) },
            ),
        )
    }

    /** Echoes the requested keys back as succeeded, which is what a healthy provider does. */
    private fun batchOf(accountId: AccountId, fileIds: List<FileId>) = BatchOperationResult(
        succeeded = fileIds.map { CloudFileKey(owner.id, accountId, it) },
        failed = emptyList(),
    )
}

/** A trash item helper for tests that need a populated bin. */
internal fun trashItem(id: String, name: String = "deleted-$id.txt") = TrashItem(
    trashId = id,
    originalName = name,
    isFolder = false,
    sizeBytes = 1024L,
)
