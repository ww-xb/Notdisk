package com.notdisk.provider.mock

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.CollisionPolicy
import com.notdisk.core.model.DownloadRequest
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.TransferDirection
import com.notdisk.core.model.TransferProgress
import com.notdisk.core.model.TransferStatus
import com.notdisk.core.model.TransferTask
import com.notdisk.core.model.UploadRequest
import com.notdisk.core.provider.BatchItemFailure
import com.notdisk.core.provider.BatchOperationResult
import com.notdisk.core.provider.DownloadSource
import com.notdisk.core.provider.FileOperationProvider
import com.notdisk.core.provider.ShareResult
import com.notdisk.core.provider.TrashItem
import com.notdisk.core.provider.TrashPage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Offline [FileOperationProvider].
 *
 * Implements the **shape** of a real mutation surface, including the parts that are easy to get
 * wrong and therefore worth validating offline:
 *  - per-item outcomes from batch calls (a partial success is reported as such);
 *  - a trash that is separate from the tree, with restore;
 *  - permanent purge requiring a "safe key", mirroring 115's `rb/clean`;
 *  - upload/download tasks whose progress is a real, *terminating* [Flow];
 *  - mutations immediately visible through [MockFileSystemProvider], because both read the same
 *    [MockSessionState] — so "create then list" and cache invalidation can be tested for real
 *    instead of against a provider that never changes.
 */
internal class MockFileOperationProvider(
    private val accounts: MockAccountStore,
    private val state: MockSessionState,
    private val latencyMs: Long = 0,
    private val clock: () -> Long = System::currentTimeMillis,
) : FileOperationProvider {

    /** Items moved to the trash, keyed by trash id. */
    private val trash = linkedMapOf<String, TrashEntry>()

    private val transfers = ConcurrentHashMap<String, MutableStateFlow<TransferProgress>>()
    private val transferRecords = ConcurrentHashMap<String, TransferTask>()
    private val transferLock = Mutex()

    private data class TrashEntry(
        val trashId: String,
        val nodeId: String,
        val name: String,
        val isFolder: Boolean,
        val size: Long?,
        val deletedAt: Long,
        val originalParentId: String?,
    )

    override suspend fun createFolder(
        accountId: AccountId,
        parentId: FileId,
        name: String,
    ): CloudResult<FileItem> {
        accounts.requireAuthorized(accountId)?.let { return it }
        if (name.isBlank()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "文件夹名不能为空"))
        }
        if (name.contains('/') || name.contains('\\')) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "文件夹名不能包含路径分隔符"),
            )
        }
        await()

        val parent = state.find(parentId.value) ?: return notFound(parentId.value)
        if (!parent.isFolder) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "目标不是文件夹"))
        }
        if (!parent.readable) {
            return CloudResult.Failure(
                CloudError.PermissionDenied(message = "没有权限在「${parent.name}」中创建文件夹"),
            )
        }
        // Checks the tree *and* folders created earlier this session; otherwise a repeated create
        // would silently succeed and produce two same-named siblings.
        if (state.childrenOf(parent.id).any { it.name == name }) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "同名文件夹已存在: $name"),
            )
        }

        val node = MockNode(
            id = "mock:dir-${UUID.randomUUID()}",
            name = name,
            isFolder = true,
            parentId = parent.id,
        )
        state.addFolder(node)
        return CloudResult.Success(node.toItem(accountId))
    }

    override suspend fun rename(
        accountId: AccountId,
        fileId: FileId,
        newName: String,
    ): CloudResult<FileItem> {
        accounts.requireAuthorized(accountId)?.let { return it }
        // Validate locally first: never send an obviously-bad mutation upstream.
        if (newName.isBlank()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "文件名不能为空"))
        }
        if (newName.contains('/') || newName.contains('\\')) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "文件名不能包含路径分隔符"))
        }
        await()

        val node = state.find(fileId.value) ?: return notFound(fileId.value)
        if (!node.readable) {
            return CloudResult.Failure(
                CloudError.PermissionDenied(message = "没有权限重命名「${node.name}」"),
            )
        }

        state.rename(node.id, newName)
        return CloudResult.Success(node.copy(name = newName).toItem(accountId))
    }

    override suspend fun move(
        accountId: AccountId,
        fileIds: List<FileId>,
        targetParentId: FileId,
    ): CloudResult<BatchOperationResult> = batch(accountId, fileIds, "移动") { node ->
        if (!node.readable) {
            throw MockBatchFailure(CloudError.PermissionDenied(message = "没有权限移动「${node.name}」"))
        }
        if (targetParentId.value == node.parentId) {
            throw MockBatchFailure(CloudError.InvalidArgument(message = "已在目标目录中"))
        }
    }

    override suspend fun copy(
        accountId: AccountId,
        fileIds: List<FileId>,
        targetParentId: FileId,
    ): CloudResult<BatchOperationResult> = batch(accountId, fileIds, "复制") { node ->
        if (!node.readable) {
            throw MockBatchFailure(CloudError.PermissionDenied(message = "没有权限复制「${node.name}」"))
        }
    }

    override suspend fun delete(
        accountId: AccountId,
        fileIds: List<FileId>,
        permanent: Boolean,
    ): CloudResult<BatchOperationResult> {
        accounts.requireAuthorized(accountId)?.let { return it }
        if (permanent) {
            // The Mock mirrors 115: the ordinary delete path is a move to the recycle bin, and
            // permanent deletion is a different, separately-guarded operation.
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "Mock Provider 的删除为移入回收站；彻底删除请使用 purgeFromTrash",
                    capability = "operations.delete.permanent",
                ),
            )
        }
        return batch(accountId, fileIds, "删除") { node ->
            if (!node.readable) {
                throw MockBatchFailure(CloudError.PermissionDenied(message = "没有权限删除「${node.name}」"))
            }
        }.also { result ->
            if (result is CloudResult.Success) {
                result.data.succeeded.forEach { key ->
                    val node = state.find(key.fileId.value) ?: return@forEach
                    val trashId = "mock-rid-${node.id.removePrefix("mock:")}"
                    synchronized(trash) {
                        trash[trashId] = TrashEntry(
                            trashId = trashId,
                            nodeId = node.id,
                            name = node.name,
                            isFolder = node.isFolder,
                            size = node.size,
                            deletedAt = clock(),
                            originalParentId = node.parentId,
                        )
                    }
                    // Removed from the visible tree, so a listing after a delete is truthful.
                    state.markDeleted(node.id)
                }
            }
        }
    }

    override suspend fun upload(
        accountId: AccountId,
        request: UploadRequest,
    ): CloudResult<TransferTask> {
        accounts.requireAuthorized(accountId)?.let { return it }
        if (request.fileName.isBlank()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "文件名不能为空"))
        }
        await()

        val id = "mock-upload-${UUID.randomUUID()}"
        val task = TransferTask(
            id = id,
            key = CloudFileKey(MOCK_PROVIDER_ID, accountId, request.parentId),
            direction = TransferDirection.UPLOAD,
            fileName = request.fileName,
            totalBytes = request.totalBytes,
            transferredBytes = 0,
            status = TransferStatus.QUEUED,
            sourceUri = request.sourceUri,
            createdAt = clock(),
            updatedAt = clock(),
            // Never auto-retry an upload: the vendor de-duplicates by name, so a blind retry can
            // create a duplicate file. This mirrors the honest declaration for 115.
            autoRetryable = false,
        )
        transferLock.withLock {
            transferRecords[id] = task
            transfers[id] = MutableStateFlow(task.progress)
        }
        return CloudResult.Success(task)
    }

    override suspend fun prepareDownload(
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<DownloadSource> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()

        val node = state.find(fileId.value) ?: return notFound(fileId.value)
        if (node.isFolder) {
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "Mock Provider 不支持文件夹打包下载",
                    capability = "operations.download.folderArchive",
                ),
            )
        }

        val expiresAt = clock() + DIRECT_LINK_TTL_MS
        return CloudResult.Success(
            DownloadSource(
                // Not a real network location; the Mock download path never leaves the process.
                // The expiry stamp makes a *refreshed* link observably different from a stale one.
                url = "mock://download/${node.id}?t=$expiresAt",
                fileName = node.name,
                sizeBytes = node.size,
                mimeType = node.mimeType,
                expiresAt = expiresAt,
                supportsRangeRequests = true,
                refreshable = true,
            ),
        )
    }

    override suspend fun download(
        accountId: AccountId,
        request: DownloadRequest,
        source: DownloadSource,
    ): CloudResult<TransferTask> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()

        val id = "mock-download-${UUID.randomUUID()}"
        val task = TransferTask(
            id = id,
            key = CloudFileKey(MOCK_PROVIDER_ID, accountId, FileId(request.fileName)),
            direction = TransferDirection.DOWNLOAD,
            fileName = source.fileName,
            totalBytes = source.sizeBytes,
            transferredBytes = request.startOffset,
            status = TransferStatus.QUEUED,
            destinationPath = request.destinationPath,
            createdAt = clock(),
            updatedAt = clock(),
            // Downloads are idempotent: re-fetching a byte range is always safe.
            autoRetryable = true,
        )
        transferLock.withLock {
            transferRecords[id] = task
            transfers[id] = MutableStateFlow(task.progress)
        }
        return CloudResult.Success(task)
    }

    /**
     * Emits the current progress, then every update, and **completes once the transfer reaches a
     * terminal state**.
     *
     * The completion is the important part: a transfer stream that never terminates makes any
     * `collect` on it hang forever — which is exactly the bug this implementation originally had,
     * and which the behaviour test (by collecting the flow) caught. The interface documents this
     * contract and the real provider must honour it identically.
     */
    override fun observeTransfer(transferId: String): Flow<TransferProgress> {
        val stateFlow = transfers[transferId]
            ?: return flowOf(TransferProgress(0, null, TransferStatus.FAILED))
        return stateFlow.asStateFlow().transformWhile { progress ->
            // Emit the terminal value too, then stop: the last emission is the one the UI needs.
            emit(progress)
            !progress.status.isTerminal
        }
    }

    override suspend fun cancelTransfer(transferId: String): CloudResult<Unit> {
        transfers[transferId]?.let { flow ->
            flow.value = flow.value.copy(status = TransferStatus.CANCELLED)
        }
        return CloudResult.Success(Unit)
    }

    override suspend fun retryTransfer(transferId: String): CloudResult<TransferTask> {
        val task = transferRecords[transferId]
            ?: return CloudResult.Failure(CloudError.NotFound(message = "任务不存在: $transferId"))

        // An upload must never be retried blindly: the vendor de-duplicates by name, so a retry
        // after a timeout can create a duplicate file. The user has to start a new upload.
        if (task.direction == TransferDirection.UPLOAD) {
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "上传任务不支持重试，请重新发起上传",
                    capability = "transfer.upload.retry",
                ),
            )
        }

        val updated = task.copy(status = TransferStatus.QUEUED, errorMessage = null, updatedAt = clock())
        transferRecords[transferId] = updated
        transfers[transferId]?.value = updated.progress
        return CloudResult.Success(updated)
    }

    override suspend fun share(
        accountId: AccountId,
        fileIds: List<FileId>,
        enable: Boolean,
        password: String?,
    ): CloudResult<ShareResult> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()
        return if (enable) {
            CloudResult.Success(
                ShareResult(
                    shareUrl = "mock://share/${fileIds.firstOrNull()?.value ?: "none"}",
                    password = password,
                    expiresAt = clock() + 7 * 24 * 60 * 60 * 1000,
                ),
            )
        } else {
            CloudResult.Success(ShareResult(shareUrl = null))
        }
    }

    override suspend fun setStarred(
        accountId: AccountId,
        fileIds: List<FileId>,
        starred: Boolean,
    ): CloudResult<Unit> {
        accounts.requireAuthorized(accountId)?.let { return it }
        fileIds.forEach { state.setStarred(it.value, starred) }
        return CloudResult.Success(Unit)
    }

    override suspend fun listTrash(
        accountId: AccountId,
        cursor: String?,
        pageSize: Int,
    ): CloudResult<TrashPage> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()

        val all = synchronized(trash) { trash.values.toList() }
        val offset = cursor?.toIntOrNull() ?: 0
        val page = all.drop(offset).take(pageSize)
        val nextOffset = offset + page.size

        return CloudResult.Success(
            TrashPage(
                items = page.map {
                    TrashItem(
                        trashId = it.trashId,
                        originalName = it.name,
                        isFolder = it.isFolder,
                        sizeBytes = it.size,
                        deletedAt = it.deletedAt,
                        originalParentId = it.originalParentId?.let { id -> FileId(id) },
                    )
                },
                nextCursor = if (nextOffset < all.size) nextOffset.toString() else null,
                totalCount = all.size.toLong(),
                protectedBySecret = false,
            ),
        )
    }

    override suspend fun restoreFromTrash(
        accountId: AccountId,
        trashIds: List<String>,
    ): CloudResult<BatchOperationResult> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()

        val succeeded = mutableListOf<CloudFileKey>()
        val failed = mutableListOf<BatchItemFailure>()
        trashIds.forEach { trashId ->
            val entry = synchronized(trash) { trash.remove(trashId) }
            if (entry == null) {
                failed += BatchItemFailure(
                    CloudFileKey(MOCK_PROVIDER_ID, accountId, FileId(trashId)),
                    CloudError.NotFound(message = "回收站条目不存在: $trashId"),
                )
            } else {
                state.unmarkDeleted(entry.nodeId)
                succeeded += CloudFileKey(MOCK_PROVIDER_ID, accountId, FileId(entry.nodeId))
            }
        }
        return CloudResult.Success(BatchOperationResult(succeeded = succeeded, failed = failed))
    }

    override suspend fun purgeFromTrash(
        accountId: AccountId,
        trashIds: List<String>,
        safeKey: String?,
    ): CloudResult<BatchOperationResult> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()

        // Mirrors 115's `rb/clean`: the Mock account has no safe key configured, so *supplying* one
        // is an error and omitting it works. That keeps both branches of the real UI reachable
        // during offline testing instead of leaving the safe-key dialog untested.
        if (!safeKey.isNullOrEmpty()) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(
                    message = "该账号未设置安全密钥，无需提供",
                    vendorCode = "MOCK_SAFE_KEY_NOT_SET",
                ),
            )
        }

        val succeeded = mutableListOf<CloudFileKey>()
        val failed = mutableListOf<BatchItemFailure>()
        trashIds.forEach { trashId ->
            val removed = synchronized(trash) { trash.remove(trashId) }
            if (removed == null) {
                failed += BatchItemFailure(
                    CloudFileKey(MOCK_PROVIDER_ID, accountId, FileId(trashId)),
                    CloudError.NotFound(message = "回收站条目不存在: $trashId"),
                )
            } else {
                succeeded += CloudFileKey(MOCK_PROVIDER_ID, accountId, FileId(removed.nodeId))
            }
        }
        return CloudResult.Success(BatchOperationResult(succeeded = succeeded, failed = failed))
    }

    // ------------------------------------------------------------------ helpers

    /** Thrown inside [batch] to signal a per-item, non-fatal failure. */
    private class MockBatchFailure(val error: CloudError) : Exception(error.message)

    /**
     * Runs [check] for every id and collects per-item outcomes instead of failing the whole call.
     * This is the behaviour the real provider must reproduce for `fid[i]`-style batch endpoints.
     */
    private suspend fun batch(
        accountId: AccountId,
        fileIds: List<FileId>,
        operationName: String,
        check: (MockNode) -> Unit,
    ): CloudResult<BatchOperationResult> {
        accounts.requireAuthorized(accountId)?.let { return it }
        if (fileIds.isEmpty()) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "$operationName 操作未选择任何文件"),
            )
        }
        await()

        val succeeded = mutableListOf<CloudFileKey>()
        val failed = mutableListOf<BatchItemFailure>()

        fileIds.forEach { fileId ->
            val node = state.find(fileId.value)
            if (node == null) {
                failed += BatchItemFailure(
                    CloudFileKey(MOCK_PROVIDER_ID, accountId, fileId),
                    CloudError.NotFound(message = "文件不存在: ${fileId.value}"),
                )
                return@forEach
            }
            try {
                check(node)
                succeeded += CloudFileKey(MOCK_PROVIDER_ID, accountId, fileId)
            } catch (failure: MockBatchFailure) {
                failed += BatchItemFailure(CloudFileKey(MOCK_PROVIDER_ID, accountId, fileId), failure.error)
            }
        }

        return CloudResult.Success(
            BatchOperationResult(
                succeeded = succeeded,
                failed = failed,
                // Mirrors 115's `move_proid`: the vendor may process asynchronously.
                vendorTaskId = if (succeeded.isNotEmpty()) "mock-task-${operationName.hashCode()}" else null,
            ),
        )
    }

    private suspend fun await() {
        if (latencyMs > 0) delay(latencyMs)
    }

    /** Test hook: how many entries are currently in the Mock recycle bin. */
    fun trashSize(): Int = synchronized(trash) { trash.size }

    /** Test hook: drives a transfer to completion so a terminating progress flow can be asserted. */
    suspend fun completeTransfer(transferId: String, steps: Int = 4) {
        val flow = transfers[transferId] ?: return
        val total = transferRecords[transferId]?.totalBytes
        flow.value = flow.value.copy(status = TransferStatus.RUNNING)
        repeat(steps) { step ->
            val transferred = total?.let { it * (step + 1) / steps } ?: ((step + 1) * 1024L)
            flow.value = flow.value.copy(
                transferredBytes = transferred,
                status = if (step == steps - 1) TransferStatus.COMPLETED else TransferStatus.RUNNING,
            )
            delay(1)
        }
    }

    /** Exposed so [MockCloudProvider] can report an accurate capability declaration. */
    fun collisionPolicySupported(policy: CollisionPolicy): Boolean = policy != CollisionPolicy.FAIL

    private companion object {
        /** Short on purpose, so a test can advance a virtual clock past it. */
        const val DIRECT_LINK_TTL_MS = 5 * 60 * 1000L
    }
}
