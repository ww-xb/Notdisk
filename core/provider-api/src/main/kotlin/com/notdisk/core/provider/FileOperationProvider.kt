package com.notdisk.core.provider

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.CollisionPolicy
import com.notdisk.core.model.DownloadRequest
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.TransferProgress
import com.notdisk.core.model.TransferTask
import com.notdisk.core.model.UploadRequest
import kotlinx.coroutines.flow.Flow

/**
 * Mutating file operations, batch operations, sharing and trash.
 *
 * ### Idempotency and retry safety — read this before implementing
 * Mutations on a cloud drive are **not** idempotent in general:
 *  - `POST /files/move` retried after a timeout moves the same file twice (harmless) but
 *    `POST /files/copy` retried creates two copies (harmful);
 *  - a retried upload can create a duplicate file when the vendor de-duplicates by name rather
 *    than by content hash.
 *
 * Therefore:
 *  - providers must declare [com.notdisk.core.model.FileOperationCapabilities.idempotentWrites]
 *    honestly;
 *  - the transport layer must only auto-retry when that flag is `true` **or** the caller has
 *    explicitly opted in with a reconciliation step;
 *  - [move]/[copy]/[delete] report per-item outcomes so a partially applied batch is visible
 *    instead of being reported as a blanket failure.
 */
interface FileOperationProvider {

    /** Creates a folder under [parentId]. */
    suspend fun createFolder(
        accountId: AccountId,
        parentId: FileId,
        name: String,
    ): CloudResult<FileItem>

    /**
     * Renames one item.
     *
     * 115 note: the *only* working endpoint is `POST /files/batch_rename` with the new name in
     * `files_new_name[<id>]`; the widely-circulated `/files/edit` returns `errno 1001`.
     */
    suspend fun rename(
        accountId: AccountId,
        fileId: FileId,
        newName: String,
    ): CloudResult<FileItem>

    /**
     * Moves items into [targetParentId].
     *
     * Returns one outcome per requested item plus the raw task id when the vendor processes the
     * move asynchronously (115 returns `move_proid`).
     */
    suspend fun move(
        accountId: AccountId,
        fileIds: List<FileId>,
        targetParentId: FileId,
    ): CloudResult<BatchOperationResult>

    /** Copies items into [targetParentId]. See [move] for the batch result contract. */
    suspend fun copy(
        accountId: AccountId,
        fileIds: List<FileId>,
        targetParentId: FileId,
    ): CloudResult<BatchOperationResult>

    /**
     * Deletes items.
     *
     * For 115 this is a *move to the recycle bin* (`POST /rb/delete`), not a permanent delete;
     * [permanent] is therefore reported as `UNSUPPORTED` for 115 unless a safe key is supplied.
     */
    suspend fun delete(
        accountId: AccountId,
        fileIds: List<FileId>,
        permanent: Boolean = false,
    ): CloudResult<BatchOperationResult>

    /**
     * Starts an upload.
     *
     * The returned [TransferTask] is a *record*; progress is observed through [observeTransfer].
     * Implementations that cannot upload must fail with `CloudError.Unsupported` rather than
     * pretending to succeed.
     */
    suspend fun upload(
        accountId: AccountId,
        request: UploadRequest,
    ): CloudResult<TransferTask>

    /**
     * Resolves a fresh, short-lived download location and returns it as a transferable resource.
     *
     * Kept separate from the actual byte transfer so that the (platform) download worker can
     * re-resolve on expiry without the provider knowing anything about Android work scheduling.
     */
    suspend fun prepareDownload(
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<DownloadSource>

    /** Starts a download using an already-prepared [DownloadSource]. */
    suspend fun download(
        accountId: AccountId,
        request: DownloadRequest,
        source: DownloadSource,
    ): CloudResult<TransferTask>

    /** Live progress for one transfer. Completes when the transfer reaches a terminal state. */
    fun observeTransfer(transferId: String): Flow<TransferProgress>

    /** Requests cancellation. Must be idempotent. */
    suspend fun cancelTransfer(transferId: String): CloudResult<Unit>

    /**
     * Retries a failed transfer.
     *
     * Must only be called by the user (or by a reconciling worker that has verified the remote
     * state), never automatically for non-idempotent uploads.
     */
    suspend fun retryTransfer(transferId: String): CloudResult<TransferTask>

    /**
     * Creates or revokes a share link.
     *
     * 115 note: `POST /usershare/share` with `share_opt=1|0` — this is documented in the vendor's
     * front-end bundle but **not** verified in this build, so the capability is
     * [com.notdisk.core.model.CapabilitySupport.UNVERIFIED].
     */
    suspend fun share(
        accountId: AccountId,
        fileIds: List<FileId>,
        enable: Boolean = true,
        password: String? = null,
    ): CloudResult<ShareResult>

    /** Star / unstar items. */
    suspend fun setStarred(
        accountId: AccountId,
        fileIds: List<FileId>,
        starred: Boolean,
    ): CloudResult<Unit>

    /** Trash listing, when the provider has one. */
    suspend fun listTrash(
        accountId: AccountId,
        cursor: String? = null,
        pageSize: Int = 30,
    ): CloudResult<TrashPage>

    /** Restores items from the trash by their *trash* ids (115's `rid`, which is not the file id). */
    suspend fun restoreFromTrash(
        accountId: AccountId,
        trashIds: List<String>,
    ): CloudResult<BatchOperationResult>

    /**
     * Permanently deletes trash entries.
     *
     * 115 requires the account's **safe key** (`password`) for `POST /rb/clean`; callers must
     * obtain it from the user. It must never be stored.
     */
    suspend fun purgeFromTrash(
        accountId: AccountId,
        trashIds: List<String>,
        safeKey: String? = null,
    ): CloudResult<BatchOperationResult>
}

/**
 * Per-item outcome of a batch operation.
 *
 * A vendor can partially succeed (e.g. move 3 of 5 items, refuse one due to a name collision and
 * one due to permissions). Reporting that faithfully is required; collapsing it into a single
 * boolean hides data loss from the user.
 */
data class BatchOperationResult(
    val succeeded: List<CloudFileKey> = emptyList(),
    val failed: List<BatchItemFailure> = emptyList(),
    /** Vendor-side asynchronous job id, when the operation is processed in the background. */
    val vendorTaskId: String? = null,
) {
    val allSucceeded: Boolean get() = failed.isEmpty() && succeeded.isNotEmpty()
    val partiallySucceeded: Boolean get() = succeeded.isNotEmpty() && failed.isNotEmpty()
}

data class BatchItemFailure(
    val key: CloudFileKey,
    val error: com.notdisk.core.common.error.CloudError,
)

/**
 * A freshly resolved download location.
 *
 * [url] is a short-lived signed link — a secret with a lifetime measured in minutes. It is
 * deliberately **not** part of [TransferTask] so it can never be persisted by accident.
 */
data class DownloadSource(
    val url: String,
    val fileName: String,
    val sizeBytes: Long?,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Long? = null,
    val supportsRangeRequests: Boolean? = null,
    val refreshable: Boolean = true,
) {
    /**
     * True when the link is at (or past) its expiry. [nowMillis] is injectable so the download
     * worker can be tested against a virtual clock instead of waiting for real time to pass.
     */
    fun isExpired(skewMillis: Long = 0L, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val expiry = expiresAt ?: return false
        return nowMillis + skewMillis >= expiry
    }
}

data class ShareResult(
    val shareUrl: String?,
    val password: String? = null,
    val expiresAt: Long? = null,
)

data class TrashPage(
    val items: List<TrashItem>,
    val nextCursor: String? = null,
    val totalCount: Long? = null,
    /** True when the account has a recycle-bin password set (115's `rb_pass`). */
    val protectedBySecret: Boolean = false,
) {
    val hasMore: Boolean get() = nextCursor != null
}

/**
 * A recycle-bin entry.
 *
 * [trashId] is the vendor's *trash* identifier, which for 115 (`rid`) is different from the
 * original file id — conflating them is a documented upstream bug source.
 */
data class TrashItem(
    val trashId: String,
    val originalName: String,
    val isFolder: Boolean,
    val sizeBytes: Long? = null,
    val deletedAt: Long? = null,
    val originalParentId: FileId? = null,
    val originalParentName: String? = null,
)

/** Upload-name collision handling helper shared by providers. */
fun CollisionPolicy.requiresPreflight(): Boolean = this == CollisionPolicy.FAIL
